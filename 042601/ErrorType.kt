package com.ruide.service.camera.session

/**
 * Session 错误类型。
 *
 * @property isRecoverable true = 下次 open() 可自动恢复；false = 需人工干预
 */
sealed class ErrorType(val isRecoverable: Boolean) {

    /** USB 设备暂时未找到，下次可能恢复 */
    object DeviceNotFound : ErrorType(true)

    /** 打开超时，下次可能恢复 */
    object OpenTimeout : ErrorType(true)

    /** 线程中断，下次可能恢复 */
    object Interrupted : ErrorType(true)

    /** 权限被拒绝，下次请求可能成功 */
    object PermissionDenied : ErrorType(true)

    /** 打开过程中设备断开 */
    object DeviceDisconnectedDuringOpen : ErrorType(true)

    /** 运行时设备断开 */
    object DeviceDisconnected : ErrorType(true)

    /** 打开失败（通用） */
    object OpenFailed : ErrorType(true)

    /** 硬件驱动初始化失败，不可自动恢复 */
    object HardwareInitFailed : ErrorType(true)

    /** USB 协议错误，不可自动恢复 */
    object UsbProtocolError : ErrorType(false)

    /** 设备已被物理销毁 */
    object DeviceDestroyed : ErrorType(false)

    /** 未知错误，保守处理为不可恢复 */
    object Unknown : ErrorType(false)

    /** 自定义错误消息 */
    class Custom(val message: String, isRecoverable: Boolean = true) : ErrorType(isRecoverable)

    override fun toString(): String {
        return when (this) {
            is DeviceNotFound -> "DeviceNotFound"
            is OpenTimeout -> "OpenTimeout"
            is Interrupted -> "Interrupted"
            is PermissionDenied -> "PermissionDenied"
            is DeviceDisconnectedDuringOpen -> "DeviceDisconnectedDuringOpen"
            is DeviceDisconnected -> "DeviceDisconnected"
            is OpenFailed -> "OpenFailed"
            is HardwareInitFailed -> "HardwareInitFailed"
            is UsbProtocolError -> "UsbProtocolError"
            is DeviceDestroyed -> "DeviceDestroyed"
            is Unknown -> "Unknown"
            is Custom -> "Custom(message=$message)"
        }
    }
}