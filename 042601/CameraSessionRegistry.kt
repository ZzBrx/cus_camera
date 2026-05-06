package com.ruide.camera.session

import android.util.Log
import android.view.Surface
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

    data class PreviewKey(val cid: Long, val cameraId: Int)

    enum class UnregisterResult {
        OK,
        NOT_FOUND,
        NEED_STOP_MEDIA,
    }

    private val lock = Any()

    // cid → 该客户端已打开的相机集合
    private val clientCameras = HashMap<Long, MutableSet<Int>>()

    // cameraId → 当前持有该相机的客户端数量（增量维护，O(1) 查询）
    private val clientCountByCamera = HashMap<Int, Int>()

    // PreviewKey → Surface
    private val clientPreviews = HashMap<PreviewKey, Surface>()

    // cameraId → 发起推流的 clientId
    private val mediaOwners = HashMap<Int, Long>()

    // ========================= 相机归属 =========================

    fun registerCamera(cid: Long, cameraId: Int): Boolean = synchronized(lock) {
        val added = clientCameras.getOrPut(cid) { mutableSetOf() }.add(cameraId)
        if (added) {
            clientCountByCamera[cameraId] = (clientCountByCamera[cameraId] ?: 0) + 1
            Log.d(TAG, "registerCamera: cid=$cid, cameraType=$cameraId, count=${clientCountByCamera[cameraId]}")
        }
        added
    }

    fun rollbackCamera(cid: Long, cameraId: Int) = synchronized(lock) {
        val set = clientCameras[cid] ?: return@synchronized
        if (set.remove(cameraId)) {
            val count = (clientCountByCamera[cameraId] ?: 1) - 1
            if (count <= 0) {
                clientCountByCamera.remove(cameraId)
            } else {
                clientCountByCamera[cameraId] = count
            }
        }
        if (set.isEmpty()) clientCameras.remove(cid)
    }

    fun unregisterCamera(cid: Long, cameraId: Int): UnregisterResult = synchronized(lock) {
        val set = clientCameras[cid]
        if (set == null || !set.remove(cameraId)) {
            return UnregisterResult.NOT_FOUND
        }
        if (set.isEmpty()) {
            clientCameras.remove(cid)
            Log.d(TAG, "unregisterCamera: cid=$cid 已无打开的相机，移除客户端记录")
        }

        // 同步更新计数
        val count = (clientCountByCamera[cameraId] ?: 1) - 1
        if (count <= 0) {
            clientCountByCamera.remove(cameraId)
        } else {
            clientCountByCamera[cameraId] = count
        }

        val hadMedia = mediaOwners[cameraId] == cid && mediaOwners.remove(cameraId) != null
        if (hadMedia) {
            Log.i(TAG, "unregisterCamera: cid=$cid 注销时移除推流会话 $cameraId")
            UnregisterResult.NEED_STOP_MEDIA
        } else {
            UnregisterResult.OK
        }
    }

    fun isOwner(cid: Long, cameraId: Int): Boolean = synchronized(lock) {
        clientCameras[cid]?.contains(cameraId) == true
    }

    /**
     * O(1) 查询指定 cameraType 的引用计数。
     * SSOT：此值直接从归属关系派生，无独立存储。
     */
    fun getClientCount(cameraId: Int): Int = synchronized(lock) {
        clientCountByCamera[cameraId] ?: 0
    }

    // ========================= 预览归属 =========================

    fun registerPreview(cid: Long, cameraId: Int, surface: Surface) = synchronized(lock) {
        clientPreviews[PreviewKey(cid, cameraId)] = surface
    }

    fun unregisterPreview(cid: Long, cameraId: Int): Surface? = synchronized(lock) {
        clientPreviews.remove(PreviewKey(cid, cameraId))
    }

    fun getPreview(cid: Long, cameraId: Int): Surface? = synchronized(lock) {
        clientPreviews[PreviewKey(cid, cameraId)]
    }

    // ========================= 推流归属 =========================

    fun registerMedia(cid: Long, cameraId: Int) = synchronized(lock) {
        mediaOwners[cameraId] = cid
    }

    fun unregisterMedia(cameraId: Int): Boolean = synchronized(lock) {
        mediaOwners.remove(cameraId) != null
    }

    fun unregisterAllMedia(): List<Int> = synchronized(lock) {
        mediaOwners.keys.toList().also { mediaOwners.clear() }
    }

    // ========================= 批量清理 =========================

    fun releaseAllForCamera(cameraId: Int): List<PreviewKey> = synchronized(lock) {
        mediaOwners.remove(cameraId)
        val keys = clientPreviews.keys.filter { it.cameraId == cameraId }
        keys.forEach { clientPreviews.remove(it) }
        clientCameras.values.forEach { it.remove(cameraId) }
        clientCameras.entries.removeIf { it.value.isEmpty() }
        clientCountByCamera.remove(cameraId) // 强制清零
        Log.i(TAG, "releaseAllForCamera: $cameraId 已清理 ${keys.size} 个预览绑定")
        keys
    }
}