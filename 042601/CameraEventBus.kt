package com.ruide.camera

import com.ruide.aidl.para.CAM
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 双层事件总线。
 *
 * - 内部通道：模块间通信（DeviceRemoved、HardwareError），用 ArrayList+锁，低频
 * - 外部通道：业务事件（Connected、Disconnected、Error），用 CopyOnWriteArrayList，避免锁竞争
 *
 * 帧数据不经过事件总线，走专用管道。
 */
class CameraEventBus {

    // ========================= 内部观察者 =========================

    private val internalObservers = ConcurrentHashMap<Int, MutableList<InternalObserver>>()

    // ========================= 外部观察者 =========================

    private val externalObservers = ConcurrentHashMap<CAM.CAM_ID_TYPE, MutableList<ExternalObserver>>()

    // ========================= 订阅接口 =========================

    fun subscribeInternal(productId: Int, observer: (InternalEvent) -> Unit): Subscription {
        val sub = InternalSubscription(productId, observer)
        internalObservers.getOrPut(productId) {
            Collections.synchronizedList(ArrayList())
        }.add(sub)
        return sub
    }

    fun subscribeExternal(cameraType: CAM.CAM_ID_TYPE, observer: (CameraEvent) -> Unit): Subscription {
        val sub = ExternalSubscription(cameraType, observer)
        externalObservers.getOrPut(cameraType) { CopyOnWriteArrayList() }.add(sub)
        return sub
    }

    // ========================= 发布接口 =========================

    fun publishInternal(event: InternalEvent) {
        val productId = event.productId()
        val list = internalObservers[productId] ?: return
        // toList() 快照，防止 remove 时的并发修改
        list.toList().forEach { it.onEvent(event) }
    }

    fun publishExternal(cameraType: CAM.CAM_ID_TYPE, event: CameraEvent) {
        externalObservers[cameraType]?.forEach { it.onEvent(event) }
    }

    // ========================= 清理接口 =========================

    /**
     * 设备移除时调用，清理该 productId 的所有内部观察者。
     * 直接从 map 中移除，不复用旧 list。
     */
    fun removeInternalObservers(productId: Int) {
        internalObservers.remove(productId)?.clear()
    }

    /**
     * Session 关闭时调用，清理该 cameraType 的所有外部观察者。
     */
    fun removeExternalObservers(cameraType: CAM.CAM_ID_TYPE) {
        externalObservers.remove(cameraType)?.clear()
    }

    /**
     * 全局清理。
     */
    fun clearAll() {
        internalObservers.values.forEach { it.clear() }
        internalObservers.clear()
        externalObservers.values.forEach { it.clear() }
        externalObservers.clear()
    }

    // ========================= 订阅句柄 =========================

    interface Subscription {
        fun dispose()
    }

    private inner class InternalSubscription(
        private val productId: Int,
        private val observer: (InternalEvent) -> Unit
    ) : Subscription {
        override fun dispose() {
            internalObservers[productId]?.remove(this)
        }
        fun onEvent(event: InternalEvent) = observer(event)
    }

    private inner class ExternalSubscription(
        private val cameraType: CAM.CAM_ID_TYPE,
        private val observer: (CameraEvent) -> Unit
    ) : Subscription {
        override fun dispose() {
            externalObservers[cameraType]?.remove(this)
        }
        fun onEvent(event: CameraEvent) = observer(event)
    }
}