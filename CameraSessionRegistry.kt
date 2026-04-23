package com.ruide.service

import android.view.Surface
import android.util.Log
import com.ruide.aidl.para.CAM

/**
 * 管理 AIDL 客户端与相机/预览/推流的归属关系。
 *
 * 职责：
 * - 记录哪个客户端(cid)打开了哪些相机
 * - 记录哪个客户端绑定了哪个预览 Surface
 * - 记录哪个客户端发起了推流会话
 *
 * 不感知任何硬件操作，所有状态变更均通过显式方法完成。
 * 内部使用单一锁保护所有状态，外部无需关心并发细节。
 */
class CameraSessionRegistry {

    companion object {
        private const val TAG = "CameraSessionRegistry"
    }

    /**
     * 预览的key
     */
    data class PreviewKey(val cid: Long, val cameraType: CAM.CAM_ID_TYPE)

    /** [unregisterCamera] 的返回结果，调用方据此决定是否需要额外的资源回收 */
    enum class UnregisterResult {
        /** 正常注销，无额外操作 */
        OK,
        /** 该客户端未打开此相机，无需处理 */
        NOT_FOUND,
        /** 注销时发现该客户端持有推流会话，调用方需同步停止推流并释放 media 引用 */
        NEED_STOP_MEDIA,
    }

    private val lock = Any()

    // cid → 该客户端已打开的相机集合
    private val clientCameras = HashMap<Long, MutableSet<CAM.CAM_ID_TYPE>>()

    // PreviewKey → Surface
    private val clientPreviews = HashMap<PreviewKey, Surface>()

    // cameraType → 发起推流的 clientId
    private val mediaOwners = HashMap<CAM.CAM_ID_TYPE, Long>()

    // ========================= 相机归属 =========================

    /**
     * 注册相机归属（openCamera 占位）。
     * @return true 表示新增成功；false 表示该客户端已持有此相机（幂等）
     */
    fun registerCamera(cid: Long, cameraType: CAM.CAM_ID_TYPE): Boolean = synchronized(lock) {
        clientCameras.getOrPut(cid) { mutableSetOf() }.add(cameraType)
    }

    /**
     * 回滚相机归属占位（openCamera 硬件操作失败时调用）。
     */
    fun rollbackCamera(cid: Long, cameraType: CAM.CAM_ID_TYPE) {
        synchronized(lock) {
            val set = clientCameras[cid] ?: return@synchronized false
            set.remove(cameraType)
            if (set.isEmpty()) clientCameras.remove(cid)
            true
        }
    }

    /**
     * 注销相机归属（closeCamera 时调用）。
     * @return [UnregisterResult] 告知调用方是否需要额外停止推流
     */
    fun unregisterCamera(cid: Long, cameraType: CAM.CAM_ID_TYPE): UnregisterResult =
        synchronized(lock) {
            val set = clientCameras[cid]
            if (set == null || !set.remove(cameraType)) {
                return UnregisterResult.NOT_FOUND
            }
            if (set.isEmpty()) {
                clientCameras.remove(cid)
                Log.d(TAG, "unregisterCamera: cid=$cid 已无打开的相机，移除客户端记录")
            }
            val hadMedia = mediaOwners[cameraType] == cid && mediaOwners.remove(cameraType) != null
            if (hadMedia) {
                Log.i(TAG, "unregisterCamera: cid=$cid 注销时移除推流会话 $cameraType")
                UnregisterResult.NEED_STOP_MEDIA
            } else {
                UnregisterResult.OK
            }
        }

    /**
     * 查询客户端是否持有指定相机。
     */
    fun isOwner(cid: Long, cameraType: CAM.CAM_ID_TYPE): Boolean = synchronized(lock) {
        clientCameras[cid]?.contains(cameraType) == true
    }

    // ========================= 预览归属 =========================

    /**
     * 注册预览 Surface 绑定。
     */
    fun registerPreview(cid: Long, cameraType: CAM.CAM_ID_TYPE, surface: Surface) =
        synchronized(lock) {
            clientPreviews[PreviewKey(cid, cameraType)] = surface
        }

    /**
     * 注销预览 Surface 绑定。
     * @return 被移除的 Surface，不存在时返回 null
     */
    fun unregisterPreview(cid: Long, cameraType: CAM.CAM_ID_TYPE): Surface? = synchronized(lock) {
        clientPreviews.remove(PreviewKey(cid, cameraType))
    }

    /**
     * 查询客户端当前绑定的预览 Surface。
     */
    fun getPreview(cid: Long, cameraType: CAM.CAM_ID_TYPE): Surface? = synchronized(lock) {
        clientPreviews[PreviewKey(cid, cameraType)]
    }

    // ========================= 推流归属 =========================

    /**
     * 注册推流会话归属（startRemoteVideo 时调用）。
     */
    fun registerMedia(cid: Long, cameraType: CAM.CAM_ID_TYPE) = synchronized(lock) {
        mediaOwners[cameraType] = cid
    }

    /**
     * 注销推流会话归属（stopRemoteVideo / stopAllMedia 时调用）。
     * @return true 表示确实存在会话被移除；false 表示原本不存在
     */
    fun unregisterMedia(cameraType: CAM.CAM_ID_TYPE): Boolean = synchronized(lock) {
        mediaOwners.remove(cameraType) != null
    }

    /**
     * 批量注销推流会话，返回被注销的所有 cameraType。
     */
    fun unregisterAllMedia(): List<CAM.CAM_ID_TYPE> = synchronized(lock) {
        mediaOwners.keys.toList().also { mediaOwners.clear() }
    }

    // ========================= 批量清理 =========================

    /**
     * 强制释放某相机的所有客户端归属（断电前调用）。
     *
     * 操作：
     * - 注销推流会话
     * - 清理所有该相机的预览绑定
     * - 从所有客户端的相机集合中移除该相机
     *
     * @return 被清理的 [PreviewKey] 列表，调用方据此通知 Manager 移除对应 Surface
     */
    fun releaseAllForCamera(cameraType: CAM.CAM_ID_TYPE): List<PreviewKey> = synchronized(lock) {
        mediaOwners.remove(cameraType)
        val keys = clientPreviews.keys.filter { it.cameraType == cameraType }
        keys.forEach { clientPreviews.remove(it) }
        clientCameras.values.forEach { it.remove(cameraType) }
        clientCameras.entries.removeIf { it.value.isEmpty() }
        Log.i(TAG, "releaseAllForCamera: $cameraType 已清理 ${keys.size} 个预览绑定")
        keys
    }
}