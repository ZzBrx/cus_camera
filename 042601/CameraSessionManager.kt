package com.ruide.camera.session

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.Surface
import com.ruide.camera.device.CameraDevice
import com.ruide.camera.device.CameraDevicePool
import com.ruide.camera.event.CameraEvent
import com.ruide.camera.event.DisconnectReason
import com.ruide.camera.usb.ScreenRecordManager
import com.ruide.camera.usb.Size
import com.ruide.camera.usb.USBMonitor
import com.ruide.camera.usb.UsbDeviceMonitor
import com.ruide.service.camera.event.CameraEventBus
import com.ruide.service.camera.session.ErrorType
import com.ruide.service.camera.session.SessionState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    var screenRecordManager: ScreenRecordManager? = null
        private set

    // ========================= 状态 =========================

    private val stateLock = Any()
    private val sessions = ConcurrentHashMap<Int, CameraSession>()
    private val productIdToCameraType = ConcurrentHashMap<Int, Int>()
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val eventExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ========================= 结果类型 =========================

    sealed class SessionResult {
        object Success : SessionResult()
        object AlreadyOpen : SessionResult()
        object DeviceNotFound : SessionResult()
        object Timeout : SessionResult()
        data class Error(val message: String) : SessionResult()
        object Ignored : SessionResult()
    }

    sealed class CloseResult {
        object FullyReleased : CloseResult()
        object PartialReleased : CloseResult()
        object NotOpenedByClient : CloseResult()
    }

    // ========================= 初始化 =========================

    fun initScreenRecordManager(recordDir: (Int) -> File) {
        screenRecordManager = ScreenRecordManager(this, recordDir)
    }

    /**
     * 生成预览 Surface 的唯一 key。
     */
    private fun previewKey(clientId: Long, cameraId: Int): String {
        return "preview_${cameraId}_$clientId"
    }

    // ========================= Session 生命周期 =========================

    fun openSession(clientId: Long, cameraId: Int): SessionResult {
        val session: CameraSession

        synchronized(stateLock) {
            session = getOrCreateSession(cameraId)
            if (session.getState() == SessionState.OPEN) {
                registry.registerCamera(clientId, cameraId)
                return SessionResult.AlreadyOpen
            }
            if (!tryEnterOpening(session)) {
                return when (session.getState()) {
                    SessionState.ERROR -> SessionResult.Error("不可恢复错误: ${session.getErrorType()}")
                    else -> SessionResult.Error("状态: ${session.getState()}")
                }
            }
        }

        // 第一次查找设备
        var usbDevice = usbMonitor.findDeviceForCameraType(cameraId)
        if (usbDevice == null) {
            synchronized(stateLock) {
                if (session.getState() == SessionState.OPENING) {
                    onOpenFailure(session, ErrorType.DeviceNotFound)
                }
            }
            return SessionResult.DeviceNotFound
        }

        // 权限检查
        if (!usbMonitor.hasPermission(usbDevice)) {
            usbMonitor.requestPermission(usbDevice)
            synchronized(stateLock) {
                if (session.getState() == SessionState.OPENING) {
                    onOpenFailure(session, ErrorType.PermissionDenied)
                }
            }
            return SessionResult.Error("需要 USB 权限，请重试")
        }

        // 打开设备（带重试）
        var ctrlBlock = openDeviceWithRetry(usbDevice, maxRetry = 3, delayMs = 300)

        // 如果打开失败，重新查找设备再试一次
        if (ctrlBlock == null) {
            Log.w(TAG, "openSession: 第一次打开失败，重新查找设备")
            Thread.sleep(500)  // 等待设备重新枚举

            usbDevice = usbMonitor.findDeviceForCameraType(cameraId)
            if (usbDevice == null) {
                synchronized(stateLock) {
                    if (session.getState() == SessionState.OPENING) {
                        onOpenFailure(session, ErrorType.DeviceNotFound)
                    }
                }
                return SessionResult.DeviceNotFound
            }

            if (!usbMonitor.hasPermission(usbDevice)) {
                usbMonitor.requestPermission(usbDevice)
                synchronized(stateLock) {
                    if (session.getState() == SessionState.OPENING) {
                        onOpenFailure(session, ErrorType.PermissionDenied)
                    }
                }
                return SessionResult.Error("需要 USB 权限，请重试")
            }

            ctrlBlock = openDeviceWithRetry(usbDevice, maxRetry = 3, delayMs = 300)
        }

        if (ctrlBlock == null) {
            synchronized(stateLock) {
                if (session.getState() == SessionState.OPENING) {
                    onOpenFailure(session, ErrorType.HardwareInitFailed)
                }
            }
            return SessionResult.Error("openDevice 失败")
        }

        // 使用最新的 productId
        val realProductId = usbDevice.productId
        Log.d(TAG, "openSession: cameraId=$cameraId, realProductId=$realProductId")

        val cameraDevice = devicePool.acquire(realProductId, usbDevice, ctrlBlock, 1280, 720)
        if (cameraDevice == null) {
            synchronized(stateLock) {
                if (session.getState() == SessionState.OPENING) {
                    onOpenFailure(session, ErrorType.HardwareInitFailed)
                }
            }
            return SessionResult.Error("无法创建 CameraDevice")
        }

        return processOpenResult(session, cameraDevice, usbDevice, clientId, cameraId)
    }

    /**
     * 打开 USB 设备，带重试机制。
     * 解决 USB 设备节点暂时不可用的问题。
     */
    private fun openDeviceWithRetry(
        usbDevice: UsbDevice,
        maxRetry: Int = 3,
        delayMs: Long = 200
    ): USBMonitor.UsbControlBlock? {
        var lastException: Exception? = null

        for (i in 0 until maxRetry) {
            try {
                val ctrlBlock = usbMonitor.openDevice(usbDevice)
                if (ctrlBlock != null) {
                    if (i > 0) {
                        Log.i(TAG, "openDevice 重试成功: retry=$i, productId=${usbDevice.productId}")
                    }
                    return ctrlBlock
                }
            } catch (e: IllegalArgumentException) {
                // USB 设备节点不存在或受限
                lastException = e
                Log.w(TAG, "openDevice 失败 (retry $i/${maxRetry}): ${e.message}")
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "openDevice 异常 (retry $i/${maxRetry}): ${e.message}")
            }

            if (i < maxRetry - 1) {
                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        Log.e(TAG, "openDevice 重试耗尽: productId=${usbDevice.productId}", lastException)
        return null
    }

    fun closeSession(clientId: Long, cameraId: Int): CloseResult {
        synchronized(stateLock) {
            val session = sessions[cameraId] ?: return CloseResult.NotOpenedByClient

            // 如果是 ERROR 状态，且客户端没有持有（open 失败了的 session）
            if (session.getState() == SessionState.ERROR) {
                if (!registry.isOwner(clientId, cameraId)) {
                    // 清理这个失败的 session
                    Log.i(TAG, "closeSession: 清理失败的 session, cameraId=$cameraId")
                    session.setState(SessionState.IDLE)
                    session.setErrorType(null)
                    session.setRealProductId(-1)
                    sessions.remove(cameraId)
                    return CloseResult.FullyReleased
                }
            }

            if (!registry.isOwner(clientId, cameraId)) {
                Log.w(TAG, "closeSession: 客户端未持有此相机, cameraId=$cameraId")
                return CloseResult.NotOpenedByClient
            }

            val unregisterResult = registry.unregisterCamera(clientId, cameraId)
            val remaining = registry.getClientCount(cameraId)

            Log.d(TAG, "closeSession: cameraId=$cameraId, unregisterResult=$unregisterResult, remaining=$remaining")

            if (remaining <= 0) {
                session.setState(SessionState.CLOSING)
                registry.releaseAllForCamera(cameraId)

                val productId = session.getRealProductId()
                scheduleAfterUnlock {
                    closeHardware(productId)
                }

                session.setState(SessionState.CLOSED)
                session.setState(SessionState.IDLE)
                session.setRealProductId(-1)
                session.setErrorType(null)

                productIdToCameraType.remove(productId)

                Log.i(TAG, "closeSession: 完全释放 cameraId=$cameraId")
                return CloseResult.FullyReleased
            }

            return CloseResult.PartialReleased
        }
    }

    fun forceCloseSession(cameraId: Int) {
        synchronized(stateLock) {
            val session = sessions[cameraId] ?: return
            val productId = session.getRealProductId()

            registry.releaseAllForCamera(cameraId)
            productIdToCameraType.remove(productId)

            session.setState(SessionState.CLOSING)

            scheduleAfterUnlock {
                closeHardware(productId)
            }

            session.setState(SessionState.CLOSED)
            session.setState(SessionState.IDLE)
            session.setRealProductId(-1)
            session.setErrorType(null)

            Log.i(TAG, "forceCloseSession: cameraId=$cameraId")
        }
    }

    fun isSessionOpen(cameraId: Int): Boolean {
        synchronized(stateLock) {
            return sessions[cameraId]?.isOpen() == true
        }
    }

    fun isCameraReady(cameraId: Int): Boolean {
        if (!isDevicePresent(cameraId)) return false
        if (isSessionOpen(cameraId)) return true

        val usbDevice = usbMonitor.findDeviceForCameraType(cameraId) ?: return false
        return usbMonitor.hasPermission(usbDevice)
    }

    // ========================= 预览 Surface =========================


    fun bindPreview(
        cameraId: Int,
        clientId: Long,
        surface: Surface
    ): Boolean {
        val productId: Int
        synchronized(stateLock) {
            val session = sessions[cameraId]
            if (session == null || !session.isOpen()) {
                Log.w(TAG, "bindPreview: Session 未打开, cameraId=$cameraId")
                return false
            }
            productId = session.getRealProductId()
        }

        val device = devicePool.get(productId) ?: return false
        val key = previewKey(clientId, cameraId)

        // 先清理旧 Surface
        device.removeClientSurface(key)

        // 放入新 Surface
        device.addClientSurface(key, surface)
        registry.registerPreview(clientId, cameraId, surface)

        Log.i(TAG, "bindPreview 成功: cameraId=$cameraId, productId=$productId, key=$key")
        return true
    }

    /**
     * 移除指定相机的客户端预览 Surface。
     */
    fun unbindPreview(cameraId: Int, clientId: Long) {
        val productId: Int
        synchronized(stateLock) {
            val session = sessions[cameraId]
            if (session == null) {
                productId = productIdToCameraType.entries
                    .firstOrNull { it.value == cameraId }
                    ?.key ?: return
            } else {
                productId = session.getRealProductId()
            }
        }

        val key = previewKey(clientId, cameraId)
        val device = devicePool.get(productId)
        device?.removeClientSurface(key)
        registry.unregisterPreview(clientId, cameraId)

        Log.i(TAG, "unbindPreview: cameraId=$cameraId, productId=$productId, key=$key")
    }

    /**
     * 批量移除某相机下所有指定 clientKey 的预览 Surface。
     */
    fun unbindAllPreviews(cameraId: Int, previewKeys: List<String>) {
        val productId: Int
        synchronized(stateLock) {
            val session = sessions[cameraId] ?: return
            productId = session.getRealProductId()
        }

        val device = devicePool.get(productId) ?: return
        previewKeys.forEach { device.removeClientSurface(it) }
    }

    // ========================= 查询接口 =========================

    /**
     * 查询客户端当前绑定的预览 Surface。
     */
    fun getPreview(cameraId: Int, clientId: Long): Surface? {
        return registry.getPreview(clientId, cameraId)
    }

    /**
     * 检查客户端是否持有指定相机。
     */
    fun isOwner(clientId: Long, cameraId: Int): Boolean {
        return registry.isOwner(clientId, cameraId)
    }

    // ========================= 推流归属 =========================

    /**
     * 注册推流归属（startRemoteVideo 时调用）。
     */
    fun registerMedia(clientId: Long, cameraId: Int) {
        registry.registerMedia(clientId, cameraId)
    }

    /**
     * 注销推流归属（stopRemoteVideo 时调用）。
     * @return true 表示确实存在会话被移除
     */
    fun unregisterMedia(cameraId: Int): Boolean {
        return registry.unregisterMedia(cameraId)
    }

    /**
     * 批量注销推流会话，返回被注销的所有 cameraType。
     */
    fun unregisterAllMedia(): List<Int> {
        return registry.unregisterAllMedia()
    }

    // ========================= 能力操作 =========================

    private inline fun <T> requireOpenDevice(
        cameraId: Int,
        operation: String,
        block: (CameraDevice) -> T
    ): T? {
        val productId: Int

        synchronized(stateLock) {
            val session = sessions[cameraId]
            if (session == null) {
                Log.w(TAG, "$operation: session 不存在, cameraId=$cameraId")
                return null
            }
            if (!session.isOpen()) {
                Log.w(TAG, "$operation: session 状态非法, state=${session.getState()}, cameraId=$cameraId")
                return null
            }
            productId = session.getRealProductId()
        }

        val device = devicePool.get(productId)
        if (device == null) {
            Log.w(TAG, "$operation: CameraDevice 不存在, productId=$productId")
            synchronized(stateLock) {
                val session = sessions[cameraId]
                if (session != null && session.getState() == SessionState.OPEN) {
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

    fun captureFrame(cameraId: Int,): CameraDevice.CapturedFrame? =
        requireOpenDevice(cameraId, "captureFrame") { it.captureNextFrame() }

    fun setZoom(cameraId: Int, zoom: Int): Boolean =
        requireOpenDevice(cameraId, "setZoom") {
            it.setZoomScale(zoom)
            true
        } ?: false

    fun getZoom(cameraId: Int): Int? =
        requireOpenDevice(cameraId, "getZoom") { it.getZoomScale() }

    fun setWhiteBalance(cameraId: Int, mode: Int): Boolean =
        requireOpenDevice(cameraId, "setWhiteBalance") {
            it.setWhiteBalanceMode(mode)
        } ?: false

    fun getSupportedResolutions(cameraId: Int): List<Size> =
        requireOpenDevice(cameraId, "getSupportedResolutions") {
            it.getSupportedSizes()
        } ?: emptyList()

    fun setResolution(cameraId: Int, width: Int, height: Int): Boolean =
        requireOpenDevice(cameraId, "setResolution") {
            it.resetPreview(width, height)
        } ?: false

    fun getCurrentResolutionAndZoom(cameraId: Int): IntArray? =
        requireOpenDevice(cameraId, "getCurrentResolutionAndZoom") {
            it.getCurrentResolutionAndZoom()
        }

    fun setCommonOrder(cameraId: Int, pages: Int, value: Int): Boolean =
        requireOpenDevice(cameraId, "setCommonOrder") {
            it.setCommonOrder(pages, value)
        } ?: false

    fun getCommonOrder(cameraId: Int, pages: Int): Int =
        requireOpenDevice(cameraId, "getCommonOrder") {
            it.getCommonOrder(pages)
        } ?: -1

    // ========================= 录像 Surface =========================

    /**
     * 添加录像 Surface（供 ScreenRecordManager 调用）。
     */
    fun addRecordSurface(cameraId: Int, surfaceKey: String, surface: Surface) {
        val productId: Int
        synchronized(stateLock) {
            val session = sessions[cameraId]
            if (session == null || !session.isOpen()) {
                Log.w(TAG, "addRecordSurface: Session 未打开, cameraId=$cameraId")
                return
            }
            productId = session.getRealProductId()
        }

        val device = devicePool.get(productId)
        device?.addClientSurface(surfaceKey, surface)
    }

    /**
     * 移除录像 Surface（供 ScreenRecordManager 调用）。
     */
    fun removeRecordSurface(cameraId: Int, surfaceKey: String) {
        val productId: Int
        synchronized(stateLock) {
            val session = sessions[cameraId]
            if (session == null) {
                productId = productIdToCameraType.entries
                    .firstOrNull { it.value == cameraId }
                    ?.key ?: return
            } else {
                productId = session.getRealProductId()
            }
        }

        val device = devicePool.get(productId)
        device?.removeClientSurface(surfaceKey)
    }

    // ========================= 录像管理 =========================

    /**
     * 开始录像。
     */
    fun startRecording(cameraId: Int): Boolean {
        val productId: Int
        synchronized(stateLock) {
            val session = sessions[cameraId]
            if (session == null || !session.isOpen()) return false
            productId = session.getRealProductId()
        }
        val device = devicePool.get(productId) ?: return false
        val resolution = device.getCurrentResolutionAndZoom()
        val w = resolution[0]
        val h = resolution[1]
        return screenRecordManager?.start(productId, w, h, cameraId) ?: false
    }

    /**
     * 停止录像。
     */
    fun stopRecording(cameraId: Int) {
        screenRecordManager?.stop(cameraId)
    }

    /**
     * 强制释放录像（断电时调用）。
     */
    fun releaseRecording(cameraId: Int) {
        screenRecordManager?.releaseForCamera(cameraId)
    }


    /**
     * 设置画面翻转模式。
     */
    fun setFlipMode(cameraId: Int, mode: CameraDevice.FlipMode): Boolean =
        requireOpenDevice(cameraId, "setFlipMode") {
            it.setFlipMode(mode)
            true
        } ?: false

    /**
     * 获取当前翻转模式。
     */
    fun getFlipMode(cameraId: Int): CameraDevice.FlipMode? =
        requireOpenDevice(cameraId, "getFlipMode") {
            it.getFlipMode()
        }

    // ========================= 设备检测 =========================

    fun isDevicePresent(cameraId: Int): Boolean {
        return usbMonitor.findDeviceForCameraType(cameraId) != null
    }

    // ========================= 事件订阅 =========================

    /**
     * 订阅指定相机的业务事件。
     */
    fun subscribeEvents(
        cameraId: Int,
        observer: (CameraEvent) -> Unit
    ): CameraEventBus.Subscription {
        return eventBus.subscribeExternal(cameraId, observer)
    }

    // ========================= 全局释放 =========================

    fun release() {
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

        synchronized(Companion::class.java) {
            if (instance === this) {
                instance = null
            }
        }
        Log.d(TAG, "CameraSessionManager released")
    }

    // ========================= 内部：状态管理 =========================

    private fun getOrCreateSession(cameraId: Int): CameraSession {
        return sessions.getOrPut(cameraId) { CameraSession(cameraId) }
    }

    private fun tryEnterOpening(session: CameraSession): Boolean {
        return when (session.getState()) {
            SessionState.IDLE -> {
                session.setState(SessionState.OPENING)
                true
            }
            SessionState.ERROR -> {
                if (session.getErrorType()?.isRecoverable == true) {
                    Log.i(TAG, "自动恢复: cameraId=${session.cameraId}, errorType=${session.getErrorType()}")
                    session.setState(SessionState.IDLE)
                    session.setErrorType(null)
                    session.setState(SessionState.OPENING)
                    true
                } else {
                    Log.w(TAG, "不可恢复错误，拒绝自动恢复: cameraId=${session.cameraId}, errorType=${session.getErrorType()}")
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

    // ========================= 内部：打开处理 =========================

    private fun processOpenResult(
        session: CameraSession,
        cameraDevice: CameraDevice,
        usbDevice: UsbDevice,
        clientId: Long,
        cameraId: Int
    ): SessionResult {
        synchronized(stateLock) {
            // === 关键：重入锁后立即校验状态 ===
            if (session.getState() != SessionState.OPENING) {
                Log.w(TAG, "processOpenResult: session 状态已变更 (${session.getState()})，忽略")
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

            // 成功：使用真实 productId
            val realProductId = usbDevice.productId
            onOpenSuccess(session, realProductId)

            // 原子操作：registry + 反向映射
            registry.registerCamera(clientId, cameraId)
            productIdToCameraType[realProductId] = cameraId

            Log.i(
                TAG, "openSession 成功: cameraId=$cameraId, realProductId=$realProductId, " +
                    "count=${registry.getClientCount(cameraId)}")

            // 锁外发布事件
            scheduleExternalEvent {
                eventBus.publishExternal(
                    cameraId,
                    CameraEvent.Connected(cameraId, realProductId)
                )
            }

            return SessionResult.Success
        }
    }

    /**
     * 检查设备是否真正可用（上电且可通信）。
     *
     * @return true = 设备上电且可正常通信
     */
    fun isDeviceReallyAlive(cameraId: Int): Boolean {
        return usbMonitor.isDeviceCommunicable(cameraId)
    }

    // ========================= 内部：硬件管理 =========================

    private fun closeHardware(productId: Int) {
        if (productId <= 0) return
        devicePool.remove(productId)
        eventBus.removeInternalObservers(productId)
    }

    // ========================= 内部：USB 事件处理 =========================

    private fun handleDeviceDisconnected(productId: Int) {
        val cameraId: Int?

        synchronized(stateLock) {
            cameraId = productIdToCameraType[productId]
            val session = cameraId?.let { sessions[it] }

            if (session != null) {
                when (session.getState()) {
                    SessionState.OPENING -> {
                        Log.w(TAG, "handleDeviceDisconnected: 设备在 OPENING 过程中被拔掉, cameraId=$cameraId")
                    }
                    SessionState.OPEN -> {
                        session.setState(SessionState.ERROR)
                        session.setErrorType(ErrorType.DeviceDisconnected)
                    }
                    else -> { /* 忽略 */ }
                }
            }
        }

        devicePool.onUsbDisconnected(productId)

        cameraId?.let { ct ->
            scheduleExternalEvent {
                eventBus.publishExternal(
                    ct,
                    CameraEvent.Disconnected(ct, DisconnectReason.PHYSICAL_DETACH)
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
            eventBus.publishExternal(
                cameraType,
                CameraEvent.Error(cameraType, message)
            )
        }
    }

    // ========================= 内部：工具方法 =========================

    private fun scheduleAfterUnlock(action: () -> Unit) {
        ioExecutor.execute(action)
    }

    private fun scheduleExternalEvent(action: () -> Unit) {
        eventExecutor.execute(action)
    }
}