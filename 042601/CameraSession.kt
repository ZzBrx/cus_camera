package com.ruide.camera

/**
 * 单个逻辑相机的会话数据。
 *
 * ## 线程安全硬规约
 * - 所有字段的**读取和修改**必须在持有 [CameraSessionManager.stateLock] 时进行
 * - [@Volatile] 仅用于防止 JVM 重排序，不替代锁的原子性保证
 * - 任何跨方法的状态判断，调用方必须先获取锁，然后调用锁内方法
 *
 * @param cameraType 逻辑相机类型
 */
class CameraSession(
    val cameraType: com.ruide.aidl.para.CAM.CAM_ID_TYPE
) {
    @Volatile
    private var _state: SessionState = SessionState.IDLE

    @Volatile
    private var _realProductId: Int = -1

    @Volatile
    private var _errorType: ErrorType? = null

    // ========================= 内部访问器（仅 CameraSessionManager 使用） =========================

    internal fun getState(): SessionState = _state
    internal fun getRealProductId(): Int = _realProductId
    internal fun getErrorType(): ErrorType? = _errorType

    internal fun setState(state: SessionState) { _state = state }
    internal fun setRealProductId(id: Int) { _realProductId = id }
    internal fun setErrorType(type: ErrorType?) { _errorType = type }

    // ========================= 便捷查询（锁内调用） =========================

    internal fun isOpen(): Boolean = _state == SessionState.OPEN
    internal fun isOpening(): Boolean = _state == SessionState.OPENING
    internal fun isIdle(): Boolean = _state == SessionState.IDLE
    internal fun isError(): Boolean = _state == SessionState.ERROR

    override fun toString(): String {
        return "CameraSession(type=$cameraType, state=$_state, productId=$_realProductId, error=$_errorType)"
    }
}