package com.ruide.camera

/**
 * CameraDevice 唯一可接受的硬件事件回调。
 *
 * ## 硬约束
 * - 禁止传入任何 session / client / registry 语义
 * - 实现方（CameraDevicePool）负责将硬件事件转换为系统事件
 */
interface CameraHardwareCallback {
    /**
     * 帧数据到达（高频，30fps）。
     * 实现方应在专用线程中处理，不阻塞 UVC 回调线程。
     */
    fun onFrameData(data: ByteArray, width: Int, height: Int)

    /**
     * 硬件错误（非断开，如 setPreviewSize 失败）。
     */
    fun onHardwareError(error: Throwable)
}