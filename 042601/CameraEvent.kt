package com.ruide.camera

import com.ruide.aidl.para.CAM

/**
 * 对外业务事件：低频，供 ImpCAMService 订阅。
 * 只包含业务层关心的事件。
 */
sealed class CameraEvent {
    /** 相机已连接 */
    data class Connected(
        val cameraType: CAM.CAM_ID_TYPE,
        val productId: Int
    ) : CameraEvent()

    /** 相机已断开 */
    data class Disconnected(
        val cameraType: CAM.CAM_ID_TYPE,
        val reason: DisconnectReason
    ) : CameraEvent()

    /** 相机出错 */
    data class Error(
        val cameraType: CAM.CAM_ID_TYPE,
        val message: String
    ) : CameraEvent()
}

enum class DisconnectReason {
    PHYSICAL_DETACH,
    PERMISSION_DENIED,
    RELEASED,
    UNKNOWN
}