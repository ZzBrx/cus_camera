package com.ruide.service.camera.event

import com.ruide.camera.event.CameraEvent
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 双层事件总线。
 *
 * ## 分层设计
 * - **内部通道**：模块间通信（[InternalEvent]），用 ArrayList + synchronized，低频
 * - **外部通道**：业务事件（[CameraEvent]），用 CopyOnWriteArrayList，避免锁竞争
 *
 * ## 重要
 * - 帧数据不经过事件总线，走专用管道（[CameraDevice.scheduleRender]）
 * - 所有订阅返回 [Subscription] 句柄，调用方应持有并在不需要时 dispose
 * - [removeInternalObservers] 和 [removeExternalObservers] 用于批量清理
 */
class CameraEventBus {

    // ========================= 内部观察者 =========================

    /**
     * productId → 内部事件观察者列表。
     * 使用 ConcurrentHashMap + Collections.synchronizedList(ArrayList)。
     */
    private val internalObservers = ConcurrentHashMap<Int, MutableList<InternalObserver>>()

    // ========================= 外部观察者 =========================

    /**
     * cameraType → 外部事件观察者列表。
     * 使用 CopyOnWriteArrayList，读多写少场景。
     */
    private val externalObservers = ConcurrentHashMap<Int, MutableList<ExternalObserver>>()

    // ========================= 订阅接口 =========================

    /**
     * 订阅内部事件。
     *
     * @param productId USB productId
     * @param observer 事件回调
     * @return [Subscription] 句柄，不再需要时调用 dispose()
     */
    fun subscribeInternal(productId: Int, observer: (InternalEvent) -> Unit): Subscription {
        val sub = InternalObserver(productId, observer)
        internalObservers.getOrPut(productId) {
            Collections.synchronizedList(ArrayList())
        }.add(sub)
        return sub
    }

    /**
     * 订阅外部业务事件。
     *
     * @param cameraType 逻辑相机类型
     * @param observer 事件回调
     * @return [Subscription] 句柄，不再需要时调用 dispose()
     */
    fun subscribeExternal(
        cameraId: Int,
        observer: (CameraEvent) -> Unit
    ): Subscription {
        val sub = ExternalObserver(cameraId, observer)
        externalObservers.getOrPut(cameraId) { CopyOnWriteArrayList() }.add(sub)
        return sub
    }

    // ========================= 发布接口 =========================

    /**
     * 发布内部事件。
     *
     * 使用 toList() 快照遍历，防止 removeInternalObservers 时的并发修改。
     */
    fun publishInternal(event: InternalEvent) {
        val productId = event.productId()
        val list = internalObservers[productId] ?: return
        // toList() 创建快照：remove 从 map 中移除整个 list，不会影响当前遍历
        list.toList().forEach { it.dispatch(event) }
    }

    /**
     * 发布外部业务事件。
     *
     * CopyOnWriteArrayList 迭代器本身是快照，直接遍历即可。
     */
    fun publishExternal(cameraId: Int, event: CameraEvent) {
        externalObservers[cameraId]?.forEach { it.dispatch(event) }
    }

    // ========================= 清理接口 =========================

    /**
     * 移除指定 productId 的所有内部观察者。
     * 设备移除时由 [CameraDevicePool.remove] 调用。
     *
     * 直接从 map 中移除整个 entry，不复用旧 list。
     * publishInternal 中通过 toList() 快照保证遍历安全。
     */
    fun removeInternalObservers(productId: Int) {
        internalObservers.remove(productId)?.clear()
    }

    /**
     * 移除指定 cameraType 的所有外部观察者。
     * Session 关闭时由 [CameraSessionManager] 调用。
     */
    fun removeExternalObservers(cameraId: Int) {
        externalObservers.remove(cameraId)?.clear()
    }

    /**
     * 全局清理。释放时调用。
     */
    fun clearAll() {
        internalObservers.values.forEach { it.clear() }
        internalObservers.clear()
        externalObservers.values.forEach { it.clear() }
        externalObservers.clear()
    }

    // ========================= 观察者数量查询（调试用） =========================

    /**
     * 查询指定 productId 的内部观察者数量。
     */
    fun internalObserverCount(productId: Int): Int {
        return internalObservers[productId]?.size ?: 0
    }

    /**
     * 查询指定 cameraType 的外部观察者数量。
     */
    fun externalObserverCount(cameraId: Int): Int {
        return externalObservers[cameraId]?.size ?: 0
    }

    // ========================= 订阅句柄 =========================

    /**
     * 订阅句柄。
     *
     * 调用方持有此句柄，在不需要时调用 [dispose] 取消订阅。
     * 句柄被 GC 不会自动取消订阅，必须显式调用 dispose。
     */
    interface Subscription {
        /**
         * 取消订阅。
         *
         * 幂等：可重复调用。
         */
        fun dispose()
    }

    // ========================= 内部观察者实现 =========================

    /**
     * 内部事件观察者。
     *
     * 封装 productId + 回调函数，支持 remove 时的等值比较。
     *
     * @param productId USB productId（用于定位所在的观察者列表）
     * @param callback 事件回调函数
     */
    private inner class InternalObserver(
        private val productId: Int,
        private val callback: (InternalEvent) -> Unit
    ) : Subscription {

        /**
         * 分发事件给回调。
         * 仅在 [CameraEventBus.publishInternal] 中调用。
         */
        fun dispatch(event: InternalEvent) {
            callback(event)
        }

        /**
         * 取消订阅。幂等。
         */
        override fun dispose() {
            internalObservers[productId]?.remove(this)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is InternalObserver) return false
            return productId == other.productId && callback == other.callback
        }

        override fun hashCode(): Int {
            return 31 * productId + callback.hashCode()
        }

        override fun toString(): String {
            return "InternalObserver(productId=$productId)"
        }
    }

    // ========================= 外部观察者实现 =========================

    /**
     * 外部业务事件观察者。
     *
     * 封装 cameraType + 回调函数，支持 remove 时的等值比较。
     *
     * @param cameraType 逻辑相机类型（用于定位所在的观察者列表）
     * @param callback 事件回调函数
     */
    private inner class ExternalObserver(
        private val cameraId: Int,
        private val callback: (CameraEvent) -> Unit
    ) : Subscription {

        /**
         * 分发事件给回调。
         * 仅在 [CameraEventBus.publishExternal] 中调用。
         */
        fun dispatch(event: CameraEvent) {
            callback(event)
        }

        /**
         * 取消订阅。幂等。
         */
        override fun dispose() {
            externalObservers[cameraId]?.remove(this)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ExternalObserver) return false
            return cameraId == other.cameraId && callback == other.callback
        }

        override fun hashCode(): Int {
            return 31 * cameraId.hashCode() + callback.hashCode()
        }

        override fun toString(): String {
            return "ExternalObserver(cameraId=$cameraId)"
        }
    }
}