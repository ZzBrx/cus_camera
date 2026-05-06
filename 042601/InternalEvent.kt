package com.ruide.service.camera.event

import com.ruide.camera.event.DisconnectReason

/**
 * 内部系统事件：用于 CameraSessionManager 内部模块间通信。
 * 不暴露给 ImpCAMService。
 */
sealed class InternalEvent {
    abstract fun productId(): Int

    /** 帧数据（高频，30fps）—— 注意：实际帧分发不经过事件总线，走专用管道 */
    data class FrameData(
        val productId: Int,
        val data: ByteArray,
        val width: Int,
        val height: Int
    ) : InternalEvent() {
        override fun productId(): Int = productId
    }

    /** 设备被移除 */
    data class DeviceRemoved(
        val productId: Int,
        val reason: DisconnectReason
    ) : InternalEvent() {
        override fun productId(): Int = productId
    }

    /** 硬件错误 */
    data class HardwareError(
        val productId: Int,
        val error: Throwable
    ) : InternalEvent() {
        override fun productId(): Int = productId
    }
}