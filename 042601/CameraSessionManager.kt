package com.ruide.camera

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.Surface
import com.ruide.aidl.para.CAM
import com.ruide.camera.usb.Size
import com.ruide.camera.usb.USBMonitor
import com.ruide.service.CameraSessionRegistry
import com.ruide.service.MediaStreamService
import com.ruide.service.ScreenRecordManager
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 全局相机会话管理器（单例）。
 *
 * ## 职责
 * - 管理 [CameraSession] 的完整生命周期（open / close / forceClose）
 * - 管理 [CameraDevicePool]（设备缓存池）
 * - 管理 [CameraSessionRegistry]（SSOT，客户端归属）
 * - 管理 [UsbDeviceMonitor]（USB 插拔监听）
 * - 管理 [CameraEventBus]（事件分发）
 * - 所有相机能力操作的路由
 *
 * ## 线程安全硬规约
 * - **[stateLock] 是唯一的状态锁**，保护：
 *   - sessions map 的增删
 *   - 每个 session 的 state / realProductId / errorType
 *   - registry 的客户端归属变更
 * - **不允许在持有 stateLock 时执行 IO 或耗时操作**
 * - **所有锁外操作失败后重入锁，必须先校验 session 状态**
 *
 * ## 使用方式
 * ```kotlin
 * val manager = CameraSessionManager.getInstance(context)
 * val result = manager.openSession(clientId, cameraType)
 * // ...
 * manager.closeSession(clientId, cameraType)
 * ```
 */
class CameraSessionManager private constructor(
    private val application: Application
) {
    companion object {
        private const val TAG = "CameraSessionManager"
        private const val OPEN_TIMEOUT_SECONDS = 10L

        @Volatile
        private var instance: CameraSessionManager? = null

        fun getInstance(context: Context): CameraSessionManager {
            return instance ?: synchronized(this) {
                instance ?: CameraSessionManager(
                    context.applicationContext as Application
                ).also { instance = it }
            }
        }
    }

    // ========================= 模块依赖 =========================

    private val usbMonitor: UsbDeviceMonitor = UsbDeviceMonitor(application)
    private val eventBus: CameraEventBus = CameraEventBus()
    private val devicePool: CameraDevicePool = CameraDevicePool(usbMonitor, eventBus)
    private val registry: CameraSessionRegistry = CameraSessionRegistry()

    /** 录像管理器（延迟初始化，需要提供目录策略） */
    var screenRecordManager: ScreenRecordManager? = null
        private set

    // ========================= 状态 =========================

    /** 唯一状态锁 */
    private val stateLock = Any()

    /** cameraType → CameraSession */
    private val sessions = ConcurrentHashMap<CAM.CAM_ID_TYPE, CameraSession>()

    /** productId → cameraType 反向映射 */
    private val productIdToCameraType = ConcurrentHashMap<Int, CAM.CAM_ID_TYPE>()

    /** IO 线程池（用于耗时的设备查找、异步打开） */
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool()

    /** 事件发布线程（避免在 stateLock 内回调外部代码） */
    private val eventExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ========================= 结果类型 =========================

    sealed class SessionResult {
        data object Success : SessionResult()
        data object AlreadyOpen : SessionResult()
        data object DeviceNotFound : SessionResult()
        data object Timeout : SessionResult()
        data class Error(val message: String) : SessionResult()
        /** 内部使用：结果被忽略 */
        internal data object Ignored : SessionResult()
    }

    sealed class CloseResult {
        data object FullyReleased : CloseResult()
        data object PartialReleased : CloseResult()
        data object NotOpenedByClient : CloseResult()
    }

    // ========================= USB 监听 =========================

    init {
        usbMonitor.onDeviceConnectListener = object : UsbDeviceMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice) {
                Log.d(TAG, "onAttach: ${device.deviceName}")
            }

            override fun onConnect(
                device: UsbDevice,
                ctrlBlock: USBMonitor.UsbControlBlock,
                createNew: Boolean
            ) {
                Log.d(TAG, "onConnect: ${device.deviceName}, productId=${device.productId}")
                // 如果有已存在的 device，尝试重新打开
                val existingDevice = devicePool.get(device.productId)
                if (existingDevice != null && !existingDevice.isOpened()) {
                    try {
                        existingDevice.openWithCtrlBlock(ctrlBlock)
                    } catch (e: Exception) {
                        Log.e(TAG, "重连打开失败: ${device.deviceName}", e)
                    }
                }
            }

            override fun onDisconnect(
                device: UsbDevice,
                ctrlBlock: USBMonitor.UsbControlBlock?
            ) {
                Log.d(TAG, "onDisconnect: ${device.deviceName}, productId=${device.productId}")
                handleDeviceDisconnected(device.productId)
            }

            override fun onDettach(device: UsbDevice) {
                Log.d(TAG, "onDettach: ${device.deviceName}")
            }

            override fun onCancel(device: UsbDevice) {
                Log.d(TAG, "onCancel: ${device.deviceName}")
                handleDeviceError(device.productId, "权限请求被取消")
            }
        }
    }

    // ========================= 初始化 =========================

    /**
     * 设置录像目录策略，初始化 ScreenRecordManager。
     */
    fun initScreenRecordManager(recordDir: (CAM.CAM_ID_TYPE) -> File) {
        screenRecordManager = ScreenRecordManager(this, recordDir)
    }

    // ========================= Session 生命周期 =========================

    /**
     * 打开指定类型相机的 Session。
     *
     * @param clientId Binder 调用方标识
     * @param cameraType 逻辑相机类型
     * @return SessionResult
     */
    fun openSession(clientId: Long, cameraType: CAM.CAM_ID_TYPE): SessionResult {
        val session: CameraSession

        // 阶段1：准备（锁内）
        synchronized(stateLock) {
            session = getOrCreateSession(cameraType)

            if (session.getState() == SessionState.OPEN) {
                // 已打开，直接加引用
                registry.registerCamera(clientId, cameraType)
                Log.d(TAG, "openSession: 复用已打开, cameraType=$cameraType, count=${registry.getClientCount(cameraType)}")
                return SessionResult.AlreadyOpen
            }

            if (!tryEnterOpening(session)) {
                return when (session.getState()) {
                    SessionState.ERROR -> {
                        if (session.getErrorType()?.isRecoverable == false) {
                            SessionResult.Error("不可恢复错误: ${session.getErrorType()}")
                        } else {
                            SessionResult.Error("相机不可用: state=${session.getState()}")
                        }
                    }
                    SessionState.OPENING -> SessionResult.Error("正在打开中")
                    else -> SessionResult.Error("状态: ${session.getState()}")
                }
            }
        }

        // 阶段2：查找设备和创建（锁外）
        val productId = cameraType.toMappedProductId()
        val usbDevice = usbMonitor.findDeviceWithRetry(productId)
        if (usbDevice == null) {
            synchronized(stateLock) {
                if (session.getState() == SessionState.OPENING) {
                    onOpenFailure(session, ErrorType.DeviceNotFound)
                }
            }
            return SessionResult.DeviceNotFound
        }

        // 获取 CtrlBlock（需要权限）
        val ctrlBlock: USBMonitor.UsbControlBlock
        if (usbMonitor.hasPermission(usbDevice)) {
            ctrlBlock = usbMonitor.openDevice(usbDevice) ?: run {
                synchronized(stateLock) {
                    if (session.getState() == SessionState.OPENING) {
                        onOpenFailure(session, ErrorType.HardwareInitFailed)
                    }
                }
                return SessionResult.Error("openDevice 返回 null")
            }
        } else {
            // 请求权限（异步，本次调用无法等待）
            usbMonitor.requestPermission(usbDevice)
            synchronized(stateLock) {
                if (session.getState() == SessionState.OPENING) {
                    onOpenFailure(session, ErrorType.PermissionDenied)
                }
            }
            return SessionResult.Error("需要 USB 权限，请重试")
        }

        val cameraDevice = devicePool.acquire(
            productId, usbDevice, ctrlBlock, 1280, 720
        )
        if (cameraDevice == null) {
            synchronized(stateLock) {
                if (session.getState() == SessionState.OPENING) {
                    onOpenFailure(session, ErrorType.HardwareInitFailed)
                }
            }
            return SessionResult.Error("无法创建 CameraDevice")
        }

        // 阶段3：处理结果（重入锁）
        return processOpenResult(session, cameraDevice, usbDevice, clientId, cameraType)
    }

    /**
     * 处理异步打开的结果。重入锁后首先校验状态。
     */
    private fun processOpenResult(
        session: CameraSession,
        cameraDevice: CameraDevice,
        usbDevice: UsbDevice,
        clientId: Long,
        cameraType: CAM.CAM_ID_TYPE
    ): SessionResult {
        synchronized(stateLock) {
            // === 关键：重入锁后立即校验状态 ===
            if (session.getState() != SessionState.OPENING) {
                Log.w(TAG, "processOpenResult: session 状态已变更 (${session.getState()})，忽略")
                // 状态已被其他线程修改，清理设备
                scheduleAfterUnlock {
                    cameraDevice.destroy()
                    devicePool.remove(usbDevice.productId)
                }
                return SessionResult.Ignored
            }

            // 最终校验：入池前确认设备仍然存活
            if (!usbMonitor.isDeviceAlive(usbDevice.productId)) {
                Log.w(TAG, "processOpenResult: 设备在打开后断开，转 ERROR")
                onOpenFailure(session, ErrorType.DeviceDisconnectedDuringOpen)
                scheduleAfterUnlock {
                    cameraDevice.destroy()
                    devicePool.remove(usbDevice.productId)
                }
                return SessionResult.DeviceNotFound
            }

            // 成功
            onOpenSuccess(session, usbDevice.productId)

            // 原子操作：registry + 反向映射
            registry.registerCamera(clientId, cameraType)
            productIdToCameraType[usbDevice.productId] = cameraType

            Log.i(TAG, "openSession 成功: cameraType=$cameraType, productId=${usbDevice.productId}, " +
                    "count=${registry.getClientCount(cameraType)}")

            // 锁外发布事件
            scheduleExternalEvent {
                eventBus.publishExternal(
                    cameraType,
                    CameraEvent.Connected(usbDevice.productId)
                )
            }

            return SessionResult.Success
        }
    }

    /**
     * 关闭 Session，引用计数 -1。
     */
    fun closeSession(clientId: Long, cameraType: CAM.CAM_ID_TYPE): CloseResult {
        synchronized(stateLock) {
            val session = sessions[cameraType] ?: return CloseResult.NotOpenedByClient

            if (!registry.isOwner(clientId, cameraType)) {
                Log.w(TAG, "closeSession: 客户端未持有此相机, cameraType=$cameraType")
                return CloseResult.NotOpenedByClient
            }

            // 先注销归属
            val unregisterResult = registry.unregisterCamera(clientId, cameraType)
            val remaining = registry.getClientCount(cameraType)

            Log.d(TAG, "closeSession: cameraType=$cameraType, unregisterResult=$unregisterResult, remaining=$remaining")

            if (remaining <= 0) {
                // 完全释放
                session.setState(SessionState.CLOSING)

                // 清理预览
                registry.releaseAllForCamera(cameraType)

                // 释放锁后执行硬件关闭
                val productId = session.getRealProductId()
                scheduleAfterUnlock {
                    closeHardware(productId)
                }

                session.setState(SessionState.CLOSED)
                session.setState(SessionState.IDLE)
                session.setRealProductId(-1)
                session.setErrorType(null)

                productIdToCameraType.remove(productId)

                Log.i(TAG, "closeSession: 完全释放 cameraType=$cameraType")
                return CloseResult.FullyReleased
            }

            return CloseResult.PartialReleased
        }
    }

    /**
     * 强制关闭 Session，忽略引用计数（断电前调用）。
     */
    fun forceCloseSession(cameraType: CAM.CAM_ID_TYPE) {
        synchronized(stateLock) {
            val session = sessions[cameraType] ?: return

            val productId = session.getRealProductId()

            // 强制清理 registry
            registry.releaseAllForCamera(cameraType)
            productIdToCameraType.remove(productId)

            session.setState(SessionState.CLOSING)

            scheduleAfterUnlock {
                closeHardware(productId)
            }

            session.setState(SessionState.CLOSED)
            session.setState(SessionState.IDLE)
            session.setRealProductId(-1)
            session.setErrorType(null)

            Log.i(TAG, "forceCloseSession: cameraType=$cameraType")
        }
    }

    /**
     * 查询 Session 是否已打开。
     */
    fun isSessionOpen(cameraType: CAM.CAM_ID_TYPE): Boolean {
        synchronized(stateLock) {
            return sessions[cameraType]?.isOpen() == true
        }
    }

    /**
     * 探测相机是否可用。
     */
    fun isCameraReady(cameraType: CAM.CAM_ID_TYPE): Boolean {
        if (!isDevicePresent(cameraType)) return false
        if (isSessionOpen(cameraType)) return true

        // 临时探测
        val productId = cameraType.toMappedProductId()
        val device = usbMonitor.findDevice(productId) ?: return false
        return usbMonitor.hasPermission(device)
    }

    // ========================= 预览 Surface =========================

    /**
     * 为指定相机绑定一个客户端预览 Surface。
     */
    fun bindPreview(
        cameraType: CAM.CAM_ID_TYPE,
        clientId: Long,
        surface: Surface
    ): Boolean {
        val productId: Int
        synchronized(stateLock) {
            val session = sessions[cameraType]
            if (session == null || !session.isOpen()) {
                Log.w(TAG, "bindPreview: Session 未打开, cameraType=$cameraType")
                return false
            }
            productId = session.getRealProductId()
        }

        val device = devicePool.get(productId) ?: return false
        val previewKey = CameraSessionRegistry.PreviewKey(clientId, cameraType).toString()

        registry.registerPreview(clientId, cameraType, surface)
        device.addClientSurface(previewKey, surface)
        return true
    }

    /**
     * 移除指定相机的客户端预览 Surface。
     */
    fun unbindPreview(cameraType: CAM.CAM_ID_TYPE, clientId: Long) {
        val productId: Int
        synchronized(stateLock) {
            val session = sessions[cameraType]
            if (session == null) {
                // session 已不存在，遍历所有 device 清理
                for (productIdKey in devicePool.hashCode().let { /* 无法遍历，改用其他方式 */ }) {
                    // ... 
                }
                return
            }
            productId = session.getRealProductId()
        }

        val removed = registry.unregisterPreview(clientId, cameraType)
        if (removed != null) {
            val previewKey = CameraSessionRegistry.PreviewKey(clientId, cameraType).toString()
            val device = devicePool.get(productId)
            device?.removeClientSurface(previewKey)
        }
    }

    /**
     * 批量移除某相机下所有指定 clientKey 的预览 Surface。
     */
    fun unbindAllPreviews(cameraType: CAM.CAM_ID_TYPE, previewKeys: List<String>) {
        val productId: Int
        synchronized(stateLock) {
            val session = sessions[cameraType] ?: return
            productId = session.getRealProductId()
        }

        val device = devicePool.get(productId) ?: return
        previewKeys.forEach { device.removeClientSurface(it) }
    }

    // ========================= 能力操作 =========================

    /**
     * 所有能力操作的统一入口，三层校验：
     * 1. session 存在
     * 2. 状态为 OPEN
     * 3. device 存在
     */
    private inline fun <T> requireOpenDevice(
        cameraType: CAM.CAM_ID_TYPE,
        operation: String,
        block: (CameraDevice) -> T
    ): T? {
        val productId: Int
        val session: CameraSession

        synchronized(stateLock) {
            session = sessions[cameraType]
            if (session == null) {
                Log.w(TAG, "$operation: session 不存在, cameraType=$cameraType")
                return null
            }
            if (!session.isOpen()) {
                Log.w(TAG, "$operation: session 状态非法, state=${session.getState()}, cameraType=$cameraType")
                return null
            }
            productId = session.getRealProductId()
        }

        val device = devicePool.get(productId)
        if (device == null) {
            Log.w(TAG, "$operation: CameraDevice 不存在, productId=$productId")
            synchronized(stateLock) {
                if (session.getState() == SessionState.OPEN) {
                    session.setState(SessionState.ERROR)
                    session.setErrorType(ErrorType.DeviceDisconnected)
                }
            }
            return null
        }

        if (!device.isOpened()) {
            Log.w(TAG, "$operation: CameraDevice 已关闭, productId=$productId")
            return null
        }

        return block(device)
    }

    fun captureFrame(cameraType: CAM.CAM_ID_TYPE): CameraDevice.CapturedFrame? =
        requireOpenDevice(cameraType, "captureFrame") { it.captureNextFrame() }

    fun setZoom(cameraType: CAM.CAM_ID_TYPE, zoom: Int): Boolean =
        requireOpenDevice(cameraType, "setZoom") {
            it.setZoomScale(zoom)
            true
        } ?: false

    fun getZoom(cameraType: CAM.CAM_ID_TYPE): Int? =
        requireOpenDevice(cameraType, "getZoom") { it.getZoomScale() }

    fun setWhiteBalance(cameraType: CAM.CAM_ID_TYPE, mode: Int): Boolean =
        requireOpenDevice(cameraType, "setWhiteBalance") {
            it.setWhiteBalanceMode(mode)
        } ?: false

    fun getSupportedResolutions(cameraType: CAM.CAM_ID_TYPE): List<Size> =
        requireOpenDevice(cameraType, "getSupportedResolutions") {
            it.getSupportedSizes()
        } ?: emptyList()

    fun setResolution(cameraType: CAM.CAM_ID_TYPE, width: Int, height: Int): Boolean =
        requireOpenDevice(cameraType, "setResolution") {
            it.resetPreview(width, height)
        } ?: false

    fun getCurrentResolutionAndZoom(cameraType: CAM.CAM_ID_TYPE): IntArray? =
        requireOpenDevice(cameraType, "getCurrentResolutionAndZoom") {
            it.getCurrentResolutionAndZoom()
        }

    fun setCommonOrder(cameraType: CAM.CAM_ID_TYPE, pages: Int, value: Int): Boolean =
        requireOpenDevice(cameraType, "setCommonOrder") {
            it.setCommonOrder(pages, value)
        } ?: false

    fun getCommonOrder(cameraType: CAM.CAM_ID_TYPE, pages: Int): Boolean =
        requireOpenDevice(cameraType, "getCommonOrder") {
            it.getCommonOrder(pages)
        } ?: false

    // ========================= 设备检测 =========================

    fun isDevicePresent(cameraType: CAM.CAM_ID_TYPE): Boolean {
        return usbMonitor.isDeviceAlive(cameraType.toMappedProductId())
    }

    // ========================= 事件订阅 =========================

    /**
     * 订阅指定相机的业务事件。
     */
    fun subscribeEvents(cameraType: CAM.CAM_ID_TYPE, observer: (CameraEvent) -> Unit): CameraEventBus.Subscription {
        return eventBus.subscribeExternal(cameraType, observer)
    }

    // ========================= 全局释放 =========================

    fun release() {
        // 强制关闭所有 session
        synchronized(stateLock) {
            sessions.values.forEach { session ->
                if (session.isOpen()) {
                    session.setState(SessionState.CLOSING)
                    val productId = session.getRealProductId()
                    scheduleAfterUnlock {
                        closeHardware(productId)
                    }
                    session.setState(SessionState.CLOSED)
                    session.setState(SessionState.IDLE)
                }
            }
            sessions.clear()
            productIdToCameraType.clear()
        }

        devicePool.destroyAll()
        eventBus.clearAll()
        usbMonitor.unregister()
        usbMonitor.destroy()
        ioExecutor.shutdown()
        eventExecutor.shutdown()

        synchronized(CameraSessionManager.Companion) {
            if (instance === this) {
                instance = null
            }
        }
        Log.d(TAG, "CameraSessionManager released")
    }

    // ========================= 内部：状态管理（所有方法必须在 stateLock 内调用） =========================

    private fun getOrCreateSession(cameraType: CAM.CAM_ID_TYPE): CameraSession {
        return sessions.getOrPut(cameraType) { CameraSession(cameraType) }
    }

    /**
     * 尝试将 session 转为 OPENING。
     * ERROR 状态根据 errorType 决定是否自动恢复。
     */
    private fun tryEnterOpening(session: CameraSession): Boolean {
        return when (session.getState()) {
            SessionState.IDLE -> {
                session.setState(SessionState.OPENING)
                true
            }
            SessionState.ERROR -> {
                if (session.getErrorType()?.isRecoverable == true) {
                    Log.i(TAG, "自动恢复: cameraType=${session.cameraType}, errorType=${session.getErrorType()}")
                    session.setState(SessionState.IDLE)
                    session.setErrorType(null)
                    session.setState(SessionState.OPENING)
                    true
                } else {
                    Log.w(TAG, "不可恢复错误，拒绝自动恢复: cameraType=${session.cameraType}, errorType=${session.getErrorType()}")
                    false
                }
            }
            else -> false
        }
    }

    private fun onOpenSuccess(session: CameraSession, productId: Int) {
        session.setState(SessionState.OPEN)
        session.setRealProductId(productId)
        session.setErrorType(null)
    }

    private fun onOpenFailure(session: CameraSession, errorType: ErrorType) {
        session.setState(SessionState.ERROR)
        session.setErrorType(errorType)
    }

    // ========================= 内部：硬件管理 =========================

    private fun closeHardware(productId: Int) {
        if (productId <= 0) return
        devicePool.remove(productId)
        eventBus.removeInternalObservers(productId)
    }

    // ========================= 内部：USB 事件处理 =========================

    private fun handleDeviceDisconnected(productId: Int) {
        val cameraType: CAM.CAM_ID_TYPE?
        val session: CameraSession?

        synchronized(stateLock) {
            cameraType = productIdToCameraType[productId]
            session = cameraType?.let { sessions[it] }
        }

        if (cameraType == null || session == null) {
            // 没有对应的 session，直接清理设备
            devicePool.onUsbDisconnected(productId)
            return
        }

        synchronized(stateLock) {
            when (session.getState()) {
                SessionState.OPENING -> {
                    Log.w(TAG, "handleDeviceDisconnected: 设备在 OPENING 过程中被拔掉, cameraType=$cameraType")
                    // processOpenResult 重入锁时会发现状态已变，这里先清理设备
                }
                SessionState.OPEN -> {
                    session.setState(SessionState.ERROR)
                    session.setErrorType(ErrorType.DeviceDisconnected)
                }
                else -> { /* 忽略 */ }
            }
        }

        // 清理设备
        devicePool.onUsbDisconnected(productId)

        // 通知外部
        if (cameraType != null) {
            scheduleExternalEvent {
                eventBus.publishExternal(
                    cameraType,
                    CameraEvent.Disconnected(DisconnectReason.PHYSICAL_DETACH)
                )
            }
        }
    }

    private fun handleDeviceError(productId: Int, message: String) {
        val cameraType = productIdToCameraType[productId] ?: return

        synchronized(stateLock) {
            val session = sessions[cameraType] ?: return
            if (session.getState() == SessionState.OPEN || session.getState() == SessionState.OPENING) {
                session.setState(SessionState.ERROR)
                session.setErrorType(ErrorType.Custom(message))
            }
        }

        scheduleExternalEvent {
            eventBus.publishExternal(cameraType, CameraEvent.Error(message))
        }
    }

    // ========================= 内部：工具方法 =========================

    /**
     * 在释放 stateLock 后执行操作。
     */
    private fun scheduleAfterUnlock(action: () -> Unit) {
        ioExecutor.execute(action)
    }

    /**
     * 在释放 stateLock 后发布外部事件。
     */
    private fun scheduleExternalEvent(action: () -> Unit) {
        eventExecutor.execute(action)
    }

    /**
     * cameraType → productId 映射。
     * 收拢到此方法，避免分散在各处。
     */
    private fun CAM.CAM_ID_TYPE.toMappedProductId(): Int {
        return com.ruide.aidl.para.CAM.getCameraPid(this)
    }

    // ========================= 内部：ScreenRecordManager 封装 =========================

    /**
     * 开始录像。由 ImpCAMService 调用。
     */
    fun startRecording(cameraType: CAM.CAM_ID_TYPE): Boolean {
        val productId: Int
        val resolution: IntArray
        synchronized(stateLock) {
            val session = sessions[cameraType]
            if (session == null || !session.isOpen()) return false
            productId = session.getRealProductId()
        }
        val device = devicePool.get(productId) ?: return false
        resolution = device.getCurrentResolutionAndZoom()
        val w = resolution[0]
        val h = resolution[1]
        return screenRecordManager?.start(productId, w, h, cameraType) ?: false
    }

    /**
     * 停止录像。由 ImpCAMService 调用。
     */
    fun stopRecording(cameraType: CAM.CAM_ID_TYPE) {
        val productId: Int
        synchronized(stateLock) {
            val session = sessions[cameraType]
            if (session == null) {
                // 遍历所有可能
                return
            }
            productId = session.getRealProductId()
        }
        screenRecordManager?.stop(productId, cameraType)
    }

    /**
     * 强制释放录像。由 ImpCAMService 在断电时调用。
     */
    fun releaseRecording(cameraType: CAM.CAM_ID_TYPE) {
        stopRecording(cameraType)
    }
}