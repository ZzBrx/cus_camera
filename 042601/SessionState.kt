package com.ruide.camera

/**
 * CameraSession 的严格状态机。
 *
 * ```
 * IDLE ──open()──▶ OPENING ──onConnected──▶ OPEN ──close(refCount>0)──▶ CLOSING ──refCount==0──▶ CLOSED ────▶ IDLE
 *   ▲                  │                      │                          │
 *   │                  ├──onError/超时──▶ ERROR │                          │
 *   │                  │    (可恢复: 下次open自动reset)                   │
 *   │                  │    (不可恢复: 拒绝所有open)                       │
 *   │                  │                      │                          │
 *   │                  │                      ├──USB断开──▶ ERROR         │
 *   │                  │                      │                          │
 *   └──────────────────┴──────────────────────┴──────────────────────────┘
 * ```
 */
enum class SessionState {
    /** 未打开，可进入 OPENING */
    IDLE,

    /** 正在异步打开，等待 USB 连接回调 */
    OPENING,

    /** 已打开，帧正在分发 */
    OPEN,

    /** 正在关闭（refCount > 0，仅减引用，不销毁资源） */
    CLOSING,

    /** 已完全关闭，资源已释放（自动转 IDLE） */
    CLOSED,

    /** 出错（设备断开、打开超时等） */
    ERROR
}