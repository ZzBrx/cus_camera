package com.ruide.camera.device

import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.Surface
import com.ruide.camera.usb.IFrameCallback
import com.ruide.camera.usb.Size
import com.ruide.camera.usb.USBMonitor
import com.ruide.camera.usb.UVCCamera
import com.ruide.service.camera.event.CameraHardwareCallback
import org.opencv.android.Utils
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Collectors

/**
 * 单个 USB 相机设备的生命周期管理（精简版）。
 *
 * ## 硬约束
 * - 只负责硬件操作 + 帧分发，不持有任何 session / client / listener 语义
 * - 所有事件通过 [CameraHardwareCallback] 上抛
 * - close() 和 destroy() 必须幂等
 * - initialize() 失败时内部自行完全清理
 *
 * @param productId USB productId
 * @param hardwareCallback 硬件事件回调
 */
class CameraDevice(
    val productId: Int,
    private val hardwareCallback: CameraHardwareCallback
) {
    companion object {
        private const val TAG = "CameraDevice"
        private const val SURFACE_QUEUE_CAPACITY = 2
    }

    // ========================= 状态 =========================

    private enum class DeviceState {
        CREATED, INITIALIZED, OPENED, CLOSED, DESTROYED
    }

    private val deviceState = AtomicReference(DeviceState.CREATED)
    private val closeGuard = AtomicBoolean(false)
    private val destroyGuard = AtomicBoolean(false)

    // ========================= 相机硬件 =========================

    private var mCamera: UVCCamera? = null
    private var mCtrlBlock: USBMonitor.UsbControlBlock? = null
    private var mDummyTexture: SurfaceTexture? = null
    private var mDummySurface: Surface? = null

    @Volatile
    private var mWidth: Int = 1280

    @Volatile
    private var mHeight: Int = 720

    @Volatile
    private var mZoomFactor: Int = 1

    /** 画面翻转模式 */
    enum class FlipMode {
        NONE,           // 不翻转
        HORIZONTAL,     // 水平翻转（镜像）
        VERTICAL,       // 垂直翻转
        BOTH            // 水平+垂直（旋转180度）
    }

    @Volatile
    private var flipMode: FlipMode = FlipMode.NONE

    /**
     * 设置画面翻转模式。
     */
    fun setFlipMode(mode: FlipMode) {
        flipMode = mode
        Log.d(TAG, "setFlipMode: $mode")
    }

    /**
     * 获取当前翻转模式。
     */
    fun getFlipMode(): FlipMode = flipMode

    // ========================= 帧分发 =========================

    private val surfaceSlots = ConcurrentHashMap<String, SurfaceSlot>()
    private val pendingRemovals = ConcurrentHashMap.newKeySet<String>()

    // ---- 截帧 ----
    private val pendingCapture = AtomicReference<CaptureRequest>(null)

    // ---- FPS 统计 ----
    @Volatile
    private var lastFpsTime: Long = 0
    private val frameCount = AtomicInteger(0)

    // ---- 帧回调 ----
    private val frameCallback = IFrameCallback { dispatchFrame(it) }

    // ---- 尺寸协商锁 ----
    private val resizeLock = Any()

    // ---- 截帧数据结构 ----
    data class CapturedFrame(val data: ByteArray, val width: Int, val height: Int)

    private class CaptureRequest {
        @Volatile
        var result: ByteArray? = null
        val latch = java.util.concurrent.CountDownLatch(1)
    }

    // ========================= 初始化 =========================

    /**
     * 初始化设备，分配资源但不打开。
     *
     * ## 契约
     * - 失败时内部完全自行清理，调用方无需额外调用 close() 或 destroy()
     * - 成功则状态变为 INITIALIZED
     *
     * @throws IllegalStateException 如果设备已初始化或已销毁
     */
    @Throws(IllegalStateException::class)
    fun initialize(usbDevice: UsbDevice, width: Int, height: Int) {
        if (!deviceState.compareAndSet(DeviceState.CREATED, DeviceState.INITIALIZED)) {
            throw IllegalStateException("CameraDevice 状态不正确: ${deviceState.get()}")
        }
        try {
            mWidth = width
            mHeight = height
            Log.d(TAG, "initialize: productId=$productId, ${width}x${height}")
        } catch (e: Exception) {
            // 失败回滚
            deviceState.set(DeviceState.CREATED)
            throw e
        }
    }

    // ========================= 打开 =========================

    /**
     * 异步打开设备。
     *
     * ## 契约
     * - 失败时内部自行完全清理，退回 INITIALIZED 状态
     * - 成功则状态变为 OPENED
     */
    fun openAsync(
        usbDevice: UsbDevice,
        width: Int,
        height: Int,
        callback: (result: OpenResult) -> Unit
    ) {
        if (!deviceState.compareAndSet(DeviceState.INITIALIZED, DeviceState.OPENED)) {
            callback(OpenResult.Error("设备状态不正确: ${deviceState.get()}"))
            return
        }

        mWidth = width
        mHeight = height

        try {
            doOpen(usbDevice)
            Log.i(TAG, "openAsync 成功: productId=$productId, ${width}x${height}")
            callback(OpenResult.Success)
        } catch (e: Exception) {
            Log.e(TAG, "openAsync 失败: productId=$productId", e)
            // 失败自行清理
            try { mCamera?.close() } catch (_: Exception) {}
            mCamera = null
            try { mCtrlBlock?.close() } catch (_: Exception) {}
            mCtrlBlock = null
            deviceState.set(DeviceState.INITIALIZED)
            callback(OpenResult.Error(e.message ?: "打开失败"))
        }
    }

    /**
     * 使用已有 ctrlBlock 打开（USB 重连场景）。
     *
     * ## 契约
     * - 失败时内部自行完全清理
     */
    fun openWithCtrlBlock(
        ctrlBlock: USBMonitor.UsbControlBlock,
        width: Int = mWidth,
        height: Int = mHeight
    ) {
        if (!deviceState.compareAndSet(DeviceState.INITIALIZED, DeviceState.OPENED)) {
            throw IllegalStateException("CameraDevice 状态不正确: ${deviceState.get()}")
        }

        mWidth = width
        mHeight = height
        mCtrlBlock = ctrlBlock

        try {
            mCamera = UVCCamera().apply {
                open(ctrlBlock)
                setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG)
                setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP)
                updateCameraParams()
            }
            ensurePreviewRunning()
            Log.i(TAG, "openWithCtrlBlock 成功: productId=$productId")
        } catch (e: Exception) {
            Log.e(TAG, "openWithCtrlBlock 失败", e)
            try { mCamera?.close() } catch (_: Exception) {}
            mCamera = null
            deviceState.set(DeviceState.INITIALIZED)
            throw e
        }
    }

    private fun doOpen(usbDevice: UsbDevice) {
        // 需要外部传入 ctrlBlock，这里假设调用方已通过 UsbDeviceMonitor 获取
        // 实际使用时由 CameraDevicePool 在 acquire 中传入
        throw UnsupportedOperationException("请使用 openWithCtrlBlock 或通过 CameraDevicePool 打开")
    }

    // ========================= 关闭 =========================

    /**
     * 关闭相机硬件连接。幂等。
     */
    fun close() {
        if (!closeGuard.compareAndSet(false, true)) return
        if (deviceState.get() == DeviceState.DESTROYED) return
        deviceState.set(DeviceState.CLOSED)

        try {
            mCamera?.stopPreview()
            mCamera?.close()
        } catch (_: Exception) {
        }
        mCamera = null
    }

    /**
     * 销毁设备，释放所有资源。幂等。
     */
    fun destroy() {
        close()
        if (!destroyGuard.compareAndSet(false, true)) return
        deviceState.set(DeviceState.DESTROYED)

        try { mCamera?.destroy() } catch (_: Exception) {}
        mCamera = null
        try { mCtrlBlock?.close() } catch (_: Exception) {}
        mCtrlBlock = null
        releaseDummySurface()

        // 清理 Surface slots
        surfaceSlots.values.forEach { it.deactivateAndShutdown() }
        surfaceSlots.clear()

        Log.d(TAG, "destroy: productId=$productId")
    }

    fun isClosed(): Boolean = closeGuard.get()
    fun isDestroyed(): Boolean = destroyGuard.get()
    fun isOpened(): Boolean = deviceState.get() == DeviceState.OPENED

    // ========================= Surface 管理 =========================

    fun addClientSurface(clientKey: String, surface: Surface) {
        if (!surface.isValid) return
        surfaceSlots.put(clientKey, SurfaceSlot(surface))
        if (surfaceSlots.size == 1 && isOpened()) {
            ensurePreviewRunning()
        }
    }

    fun removeClientSurface(clientKey: String) {
        val slot = surfaceSlots.remove(clientKey)
        if (slot != null) {
            slot.deactivateAndShutdown()
            if (slot.surface.isValid) slot.surface.release()
        }
        if (surfaceSlots.isEmpty() && isOpened()) {
            try {
                mCamera?.stopPreview()
                mCamera?.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_YUV420SP)
            } catch (e: Exception) {
                Log.e(TAG, "停止预览失败", e)
            }
        }
    }

    // ========================= 帧分发 =========================

    private fun dispatchFrame(frame: ByteBuffer) {
        if (surfaceSlots.isEmpty()) return

        flushPendingRemovals()

        val rawData = toByteArray(frame)
        val zoom = mZoomFactor
        val w = mWidth
        val h = mHeight

        // 1. 缩放裁剪
        var frameData = if (zoom > 1) {
            Utils.cropAndScaleNV21(rawData, w, h, zoom)
        } else {
            rawData
        }

        // 2. 翻转
        frameData = applyFlip(frameData, w, h)

        // 3. 截帧
        fulfillCaptureRequest(frameData)

        // 4. 渲染
        for ((key, slot) in surfaceSlots) {
            scheduleRender(key, slot, frameData, w, h)
        }

        // 5. 硬件回调
        hardwareCallback.onFrameData(frameData, w, h)
    }

    private fun flushPendingRemovals() {
        val iter = pendingRemovals.iterator()
        while (iter.hasNext()) {
            val key = iter.next()
            iter.remove()

            // 直接移除，不调用 surface.release()（避免异常）
            val slot = surfaceSlots.remove(key)
            if (slot != null) {
                slot.active.set(false)
                slot.executor.shutdownNow()
            }
        }

        // 如果所有 Surface 都移除了，停止预览
        if (surfaceSlots.isEmpty() && isOpened()) {
            try {
                mCamera?.stopPreview()
                mCamera?.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_YUV420SP)
            } catch (e: Exception) {
                Log.e(TAG, "停止预览失败", e)
            }
        }
    }

    private fun toByteArray(buffer: ByteBuffer): ByteArray {
        val arr = ByteArray(buffer.remaining())
        buffer.get(arr)
        return arr
    }

    private fun fulfillCaptureRequest(frameData: ByteArray) {
        val request = pendingCapture.get() ?: return
        if (request.latch.count > 0) {
            request.result = frameData
            request.latch.countDown()
        }
    }

    private fun scheduleRender(
        clientKey: String,
        slot: SurfaceSlot,
        frameData: ByteArray,
        w: Int,
        h: Int
    ) {
        if (!slot.active.get() || slot.executor.isShutdown) return

        // 快速检查 Surface 有效性，无效直接标记移除
        if (!slot.surface.isValid) {
            pendingRemovals.add(clientKey)
            return
        }

        val dataCopy = frameData.copyOf()

        try {
            slot.executor.execute {
                if (!slot.active.get()) return@execute
                if (!slot.surface.isValid) {
                    pendingRemovals.add(clientKey)
                    return@execute
                }
                try {
                    Utils.renderYuvToSurface(ByteBuffer.wrap(dataCopy), slot.surface, w, h)
                } catch (e: Exception) {
                    Log.e(TAG, "渲染到 Surface 失败: $clientKey, ${e.message}")
                    // 渲染失败，标记移除
                    pendingRemovals.add(clientKey)
                }
            }
        } catch (e: Exception) {
            // executor 已关闭
            if (e is java.util.concurrent.RejectedExecutionException) {
                pendingRemovals.add(clientKey)
            }
        }
    }

    // ========================= 预览控制 =========================

    private fun initDummySurface() {
        mDummyTexture = SurfaceTexture(false)
        mDummySurface = Surface(mDummyTexture)
    }

    private fun ensurePreviewRunning() {
        try {
            if (mDummySurface == null) initDummySurface()
            mCamera?.setPreviewDisplay(mDummySurface)
            mCamera?.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP)
            if (surfaceSlots.isNotEmpty()) {
                mCamera?.startPreview()
                Log.d(TAG, "ensurePreviewRunning: 预览已启动")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensurePreviewRunning 失败", e)
        }
    }

    private fun releaseDummySurface() {
        try { mDummySurface?.release() } catch (_: Exception) {}
        mDummySurface = null
        try { mDummyTexture?.release() } catch (_: Exception) {}
        mDummyTexture = null
    }

    // ========================= 能力操作 =========================

    fun setSize(width: Int, height: Int) {
        mWidth = width
        mHeight = height
    }

    fun getSupportedSizes(): List<Size> {
        synchronized(resizeLock) {
            val camera = mCamera ?: return emptyList()
            val raw: List<Size> = camera.supportedSizeList
            try {
                camera.stopPreview()
            } catch (ignored: java.lang.Exception) {
            }

            val verified: List<Size> = negotiateSizes(raw)
            try {
                camera.setPreviewSize(mWidth, mHeight, UVCCamera.FRAME_FORMAT_MJPEG)
                if (!surfaceSlots.isEmpty()) {
                    camera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP)
                    camera.startPreview()
                }
            } catch (e: java.lang.Exception) {
                Log.e(TAG, "getSupportedSizes: 恢复预览失败", e)
            }

            Log.i(TAG, "getSupportedSizes: 原始=" + raw.size + "个, 实际可用=" + verified.size + "个 "
                    + verified.stream().map { s: Size -> s.width.toString() + "x" + s.height }
                .collect(Collectors.joining(", ", "[", "]"))
            )
            return verified
        }
    }

    private fun negotiateSizes(raw: List<Size>): List<Size> {
        val verified: MutableList<Size> = ArrayList()
        val seen: MutableSet<String> = HashSet()
        for (size in raw) {
            val key = size.width.toString() + "x" + size.height
            if (!seen.add(key)) {
                Log.d(TAG, "negotiateSizes: 跳过重复分辨率 $key")
                continue
            }
            try {
                mCamera!!.setPreviewSize(size.width, size.height, UVCCamera.FRAME_FORMAT_MJPEG)
                verified.add(size)
                Log.d(TAG, "negotiateSizes: 验证通过 $key")
            } catch (e: java.lang.Exception) {
                Log.d(TAG, "negotiateSizes: 跳过不可用分辨率 " + key + " (" + e.message + ")")
            }
        }
        return verified
    }

    fun resetPreview(width: Int, height: Int): Boolean {
        val camera = mCamera ?: return false
        if (!isOpened()) return false
        return try {
            camera.stopPreview()
            camera.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG)
            mWidth = width
            mHeight = height
            if (surfaceSlots.isNotEmpty()) {
                camera.setPreviewDisplay(mDummySurface)
                camera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP)
                camera.startPreview()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "resetPreview 失败", e)
            false
        }
    }

    fun setZoomScale(scale: Int) {
        mZoomFactor = scale
    }

    fun getZoomScale(): Int = mZoomFactor

    fun getCurrentResolutionAndZoom(): IntArray {
        return intArrayOf(mWidth, mHeight, mZoomFactor)
    }

    fun setWhiteBalanceMode(mode: Int): Boolean {
        val camera = mCamera ?: return false
        return try {
            when (mode) {
                0 -> {
                    camera.setAutoWhiteBlance(true)
                    Thread.sleep(100)
                }
                1, 2 -> {
                    camera.setAutoWhiteBlance(false)
                    Thread.sleep(200)
                    if (camera.autoWhiteBlance) {
                        camera.setAutoWhiteBlance(false)
                        Thread.sleep(200)
                    }
                    camera.setWhiteBlance(if (mode == 1) 30 else 60)
                }
                else -> return false
            }
            true
        } catch (e: Exception) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun captureNextFrame(): CapturedFrame? {
        val request = CaptureRequest()
        pendingCapture.set(request)
        return try {
            request.latch.await(3, TimeUnit.SECONDS)
            request.result?.let { CapturedFrame(it, mWidth, mHeight) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            pendingCapture.compareAndSet(request, null)
        }
    }

    fun setCommonOrder(pages: Int, value: Int): Boolean {
        val camera = mCamera ?: return false
        return try {
            camera.setCommonOrder(pages, value)
            true
        } catch (e: Exception) {
            Log.e(TAG, "setCommonOrder 失败", e)
            false
        }
    }

    fun getCommonOrder(pages: Int): Int {
        val camera = mCamera ?: return -1
        return camera.getCommonOrder(pages)
    }

    private fun applyFlip(data: ByteArray, width: Int, height: Int): ByteArray {
        if (flipMode == FlipMode.NONE) {
            return data
        }

        Log.d(TAG, "applyFlip: mode=$flipMode, size=${data.size}, ${width}x${height}, expectedSize=${width * height * 3 / 2}")

        val result = when (flipMode) {
            FlipMode.HORIZONTAL -> flipHorizontalNV21(data, width, height)
            FlipMode.VERTICAL -> flipVerticalNV21(data, width, height)
            FlipMode.BOTH -> flipBothNV21(data, width, height)
            else -> data
        }

        Log.d(TAG, "applyFlip: result size=${result.size}")
        return result
    }

    /**
     * NV21 水平翻转（左右镜像）。
     *
     * NV21 格式：
     * - Y 平面: w * h 字节，逐行存储
     * - UV 平面: w * h / 2 字节，UVUVUV... 交错，每 2 字节对应 2 个水平相邻像素
     */
    private fun flipHorizontalNV21(data: ByteArray, width: Int, height: Int): ByteArray {
        val result = ByteArray(data.size)
        val halfWidth = width / 2
        val uvOffset = width * height

        // 1. 翻转 Y 分量：每一行左右颠倒
        for (y in 0 until height) {
            val srcRowStart = y * width
            val dstRowStart = y * width
            for (x in 0 until width) {
                result[dstRowStart + x] = data[srcRowStart + (width - 1 - x)]
            }
        }

        // 2. 翻转 UV 分量
        // UV 数据排列：每行有 halfWidth 对 UV，每对 2 字节
        // 对于 height/2 行 UV 数据，每行宽度 = width（字节数 = halfWidth * 2 = width）
        for (y in 0 until height / 2) {
            val srcRowStart = uvOffset + y * width
            val dstRowStart = uvOffset + y * width
            for (x in 0 until halfWidth) {
                val srcIdx = srcRowStart + x * 2
                val dstIdx = dstRowStart + (halfWidth - 1 - x) * 2
                // 复制一对 UV（2 字节）
                result[dstIdx] = data[srcIdx]
                result[dstIdx + 1] = data[srcIdx + 1]
            }
        }

        return result
    }

    /**
     * NV21 垂直翻转（上下颠倒）。
     */
    private fun flipVerticalNV21(data: ByteArray, width: Int, height: Int): ByteArray {
        val result = ByteArray(data.size)
        val uvOffset = width * height

        // 1. 翻转 Y 分量：整行复制
        for (y in 0 until height) {
            val srcRowStart = y * width
            val dstRowStart = (height - 1 - y) * width
            System.arraycopy(data, srcRowStart, result, dstRowStart, width)
        }

        // 2. 翻转 UV 分量
        for (y in 0 until height / 2) {
            val srcRowStart = uvOffset + y * width
            val dstRowStart = uvOffset + (height / 2 - 1 - y) * width
            System.arraycopy(data, srcRowStart, result, dstRowStart, width)
        }

        return result
    }

    /**
     * NV21 水平+垂直翻转（旋转 180 度 = 两次翻转）。
     */
    private fun flipBothNV21(data: ByteArray, width: Int, height: Int): ByteArray {
        val horizontal = flipHorizontalNV21(data, width, height)
        return flipVerticalNV21(horizontal, width, height)
    }

    // ========================= SurfaceSlot =========================

    private class SurfaceSlot(val surface: Surface) {
        val active = AtomicBoolean(true)
        val executor: ExecutorService = ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS,
            LinkedBlockingQueue(SURFACE_QUEUE_CAPACITY),
            ThreadPoolExecutor.DiscardOldestPolicy()
        )

        fun deactivateAndShutdown() {
            active.set(false)
            executor.shutdown()
            try {
                if (!executor.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                executor.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
    }
}

/** 异步打开结果 */
sealed class OpenResult {
    object Success : OpenResult()
    data class Error(val message: String) : OpenResult()
}