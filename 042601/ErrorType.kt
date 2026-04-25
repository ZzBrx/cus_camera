package com.ruide.camera

/**
 * Session 错误类型。
 *
 * @property isRecoverable true = 下次 open() 可自动恢复；false = 需人工干预
 */
enum class ErrorType(val isRecoverable: Boolean) {
    /** USB 设备暂时未找到，下次可能恢复 */
    DeviceNotFound(isRecoverable = true),

    /** 打开超时，下次可能恢复 */
    OpenTimeout(isRecoverable = true),

    /** 线程中断，下次可能恢复 */
    Interrupted(isRecoverable = true),

    /** 权限被拒绝，下次请求可能成功 */
    PermissionDenied(isRecoverable = true),

    /** 打开过程中设备断开 */
    DeviceDisconnectedDuringOpen(isRecoverable = true),

    /** 运行时设备断开 */
    DeviceDisconnected(isRecoverable = true),

    /** 打开失败（通用） */
    OpenFailed(isRecoverable = true),

    /** 硬件驱动初始化失败，不可自动恢复 */
    HardwareInitFailed(isRecoverable = false),

    /** USB 协议错误，不可自动恢复 */
    UsbProtocolError(isRecoverable = false),

    /** 设备已被物理销毁 */
    DeviceDestroyed(isRecoverable = false),

    /** 未知错误，保守处理为不可恢复 */
    Unknown(isRecoverable = false),

    /** 自定义错误消息 */
    Custom(val message: String, isRecoverable: Boolean = true);

    constructor(isRecoverable: Boolean) : this()
}