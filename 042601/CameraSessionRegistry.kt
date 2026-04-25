package com.ruide.service

import android.util.Log
import android.view.Surface
import com.ruide.aidl.para.CAM

/**
 * 管理 AIDL 客户端与相机/预览/推流的归属关系。
 *
 * ## 变更
 * - 增加 O(1) 的 clientCountByCamera 缓存
 * - 所有对外方法以返回值为准，不允许调用方猜测成功
 *
 * ## 外部不可直接访问
 * 仅由 CameraSessionManager 在 stateLock 内操作。
 */
class CameraSessionRegistry {

    companion object {
        private const val TAG = "CameraSessionRegistry"
    }

    data class PreviewKey(val cid: Long, val cameraType: CAM.CAM_ID_TYPE)

    enum class UnregisterResult {
        OK,
        NOT_FOUND,
        NEED_STOP_MEDIA,
    }

    private val lock = Any()

    // cid → 该客户端已打开的相机集合
    private val clientCameras = HashMap<Long, MutableSet<CAM.CAM_ID_TYPE>>()

    // cameraType → 当前持有该相机的客户端数量（增量维护，O(1) 查询）
    private val clientCountByCamera = HashMap<CAM.CAM_ID_TYPE, Int>()

    // PreviewKey → Surface
    private val clientPreviews = HashMap<PreviewKey, Surface>()

    // cameraType → 发起推流的 clientId
    private val mediaOwners = HashMap<CAM.CAM_ID_TYPE, Long>()

    // ========================= 相机归属 =========================

    fun registerCamera(cid: Long, cameraType: CAM.CAM_ID_TYPE): Boolean = synchronized(lock) {
        val added = clientCameras.getOrPut(cid) { mutableSetOf() }.add(cameraType)
        if (added) {
            clientCountByCamera[cameraType] = (clientCountByCamera[cameraType] ?: 0) + 1
            Log.d(TAG, "registerCamera: cid=$cid, cameraType=$cameraType, count=${clientCountByCamera[cameraType]}")
        }
        added
    }

    fun rollbackCamera(cid: Long, cameraType: CAM.CAM_ID_TYPE) = synchronized(lock) {
        val set = clientCameras[cid] ?: return@synchronized
        if (set.remove(cameraType)) {
            val count = (clientCountByCamera[cameraType] ?: 1) - 1
            if (count <= 0) {
                clientCountByCamera.remove(cameraType)
            } else {
                clientCountByCamera[cameraType] = count
            }
        }
        if (set.isEmpty()) clientCameras.remove(cid)
    }

    fun unregisterCamera(cid: Long, cameraType: CAM.CAM_ID_TYPE): UnregisterResult = synchronized(lock) {
        val set = clientCameras[cid]
        if (set == null || !set.remove(cameraType)) {
            return UnregisterResult.NOT_FOUND
        }
        if (set.isEmpty()) {
            clientCameras.remove(cid)
            Log.d(TAG, "unregisterCamera: cid=$cid 已无打开的相机，移除客户端记录")
        }

        // 同步更新计数
        val count = (clientCountByCamera[cameraType] ?: 1) - 1
        if (count <= 0) {
            clientCountByCamera.remove(cameraType)
        } else {
            clientCountByCamera[cameraType] = count
        }

        val hadMedia = mediaOwners[cameraType] == cid && mediaOwners.remove(cameraType) != null
        if (hadMedia) {
            Log.i(TAG, "unregisterCamera: cid=$cid 注销时移除推流会话 $cameraType")
            UnregisterResult.NEED_STOP_MEDIA
        } else {
            UnregisterResult.OK
        }
    }

    fun isOwner(cid: Long, cameraType: CAM.CAM_ID_TYPE): Boolean = synchronized(lock) {
        clientCameras[cid]?.contains(cameraType) == true
    }

    /**
     * O(1) 查询指定 cameraType 的引用计数。
     * SSOT：此值直接从归属关系派生，无独立存储。
     */
    fun getClientCount(cameraType: CAM.CAM_ID_TYPE): Int = synchronized(lock) {
        clientCountByCamera[cameraType] ?: 0
    }

    // ========================= 预览归属 =========================

    fun registerPreview(cid: Long, cameraType: CAM.CAM_ID_TYPE, surface: Surface) = synchronized(lock) {
        clientPreviews[PreviewKey(cid, cameraType)] = surface
    }

    fun unregisterPreview(cid: Long, cameraType: CAM.CAM_ID_TYPE): Surface? = synchronized(lock) {
        clientPreviews.remove(PreviewKey(cid, cameraType))
    }

    fun getPreview(cid: Long, cameraType: CAM.CAM_ID_TYPE): Surface? = synchronized(lock) {
        clientPreviews[PreviewKey(cid, cameraType)]
    }

    // ========================= 推流归属 =========================

    fun registerMedia(cid: Long, cameraType: CAM.CAM_ID_TYPE) = synchronized(lock) {
        mediaOwners[cameraType] = cid
    }

    fun unregisterMedia(cameraType: CAM.CAM_ID_TYPE): Boolean = synchronized(lock) {
        mediaOwners.remove(cameraType) != null
    }

    fun unregisterAllMedia(): List<CAM.CAM_ID_TYPE> = synchronized(lock) {
        mediaOwners.keys.toList().also { mediaOwners.clear() }
    }

    // ========================= 批量清理 =========================

    fun releaseAllForCamera(cameraType: CAM.CAM_ID_TYPE): List<PreviewKey> = synchronized(lock) {
        mediaOwners.remove(cameraType)
        val keys = clientPreviews.keys.filter { it.cameraType == cameraType }
        keys.forEach { clientPreviews.remove(it) }
        clientCameras.values.forEach { it.remove(cameraType) }
        clientCameras.entries.removeIf { it.value.isEmpty() }
        clientCountByCamera.remove(cameraType) // 强制清零
        Log.i(TAG, "releaseAllForCamera: $cameraType 已清理 ${keys.size} 个预览绑定")
        keys
    }
}