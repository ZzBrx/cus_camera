package com.ruide.service.camera.event

/**
 * CameraDevice 唯一可接受的硬件事件回调。
 *
 * ## 硬约束
 * - 禁止在回调中传入任何 session / client / registry 语义
 * - 实现方（[CameraDevicePool]）负责将硬件事件转换为 [InternalEvent]
 * - onFrameData 是高频回调（30fps），实现方应在专用线程中处理，不阻塞 UVC 回调线程
 * - onHardwareError 是低频回调，不应执行耗时操作
 */
interface CameraHardwareCallback {

    /**
     * 帧数据到达。
     *
     * ## 调用线程
     * UVC 回调线程。实现方应尽快返回，将数据投递到其他线程处理。
     *
     * ## 数据生命周期
     * data 数组在回调返回后可能被复用。如果需要异步使用，实现方应复制数据。
     *
     * @param data NV21 格式的完整帧数据
     * @param width 帧宽度
     * @param height 帧高度
     */
    fun onFrameData(data: ByteArray, width: Int, height: Int)

    /**
     * 硬件错误（非断开，如 setPreviewSize 失败、USB 协议错误等）。
     *
     * ## 调用线程
     * 任意线程。
     *
     * ## 语义
     * 这不是设备断开会话级事件，而是硬件操作级别的异常。
     * 设备可能仍然在线，但某个操作失败了。
     * 上层可根据错误类型决定是否降级 session 状态。
     *
     * @param error 异常信息
     */
    fun onHardwareError(error: Throwable)
}