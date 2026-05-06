package com.ruide.camera.device

import android.hardware.usb.UsbDevice
import android.util.Log
import com.ruide.service.camera.event.CameraEventBus
import com.ruide.service.camera.event.InternalEvent
import com.ruide.camera.usb.USBMonitor
import com.ruide.service.camera.event.CameraHardwareCallback
import com.ruide.camera.event.DisconnectReason
import com.ruide.camera.usb.UsbDeviceMonitor
import java.util.concurrent.ConcurrentHashMap

/**
 * CameraDevice 缓存池。
 *
 * ## 硬约束
 * - 设备只在 USB 断开时销毁（productId 维度）
 * - acquire() 两阶段：创建→初始化成功→入池
 * - 入池前校验设备存活
 * - remove() 幂等
 */
class CameraDevicePool(
    private val usbMonitor: UsbDeviceMonitor,
    private val eventBus: CameraEventBus
) {
    companion object {
        private const val TAG = "CameraDevicePool"
    }

    private val devices = ConcurrentHashMap<Int, CameraDevice>()

    // ========================= 获取 =========================

    /**
     * 获取或创建 CameraDevice。
     * 初始化成功后放入池，失败返回 null（不会留下脏对象）。
     */
    fun acquire(
        productId: Int,
        usbDevice: UsbDevice,
        ctrlBlock: USBMonitor.UsbControlBlock,
        width: Int,
        height: Int
    ): CameraDevice? {
        // 先查池
        devices[productId]?.let { return it }

        // 创建新实例
        val device = CameraDevice(productId, createCallback(productId))

        return try {
            device.initialize(usbDevice, width, height)

            // 入池前校验设备存活
            if (!usbMonitor.isDeviceAlive(productId)) {
                Log.w(TAG, "acquire: 设备在初始化后断开，丢弃 productId=$productId")
                device.close()
                return null
            }

            // 打开设备
            device.openWithCtrlBlock(ctrlBlock, width, height)

            // 入池
            val existing = devices.putIfAbsent(productId, device)
            if (existing != null) {
                // 另一个线程已经放入，关闭当前多余的实例
                device.destroy()
                existing
            } else {
                device
            }
        } catch (e: Exception) {
            Log.e(TAG, "CameraDevice 初始化失败: productId=$productId", e)
            device.destroy()
            null
        }
    }

    /**
     * 仅获取，不创建。
     */
    fun get(productId: Int): CameraDevice? {
        val device = devices[productId]
        if (device == null) {
            Log.w(TAG, "get: 设备不在池中, productId=$productId, 池中keys=${devices.keys}")
        }
        return device
    }

    /**
     * 强制放入（用于已打开的 device）。
     */
    fun putIfAbsent(productId: Int, device: CameraDevice) {
        devices.putIfAbsent(productId, device)
    }

    // ========================= 移除 =========================

    /**
     * 移除并销毁指定 productId 的 CameraDevice。幂等。
     */
    fun remove(productId: Int) {
        eventBus.removeInternalObservers(productId)
        val device = devices.remove(productId)
        if (device != null && !device.isDestroyed()) {
            device.destroy()
        }
    }

    // ========================= USB 断开 =========================

    /**
     * USB 断开时调用，销毁对应 productId 的 CameraDevice。
     */
    fun onUsbDisconnected(productId: Int) {
        Log.i(TAG, "onUsbDisconnected: productId=$productId")
        // 先发布内部事件
        eventBus.publishInternal(
            InternalEvent.DeviceRemoved(productId, DisconnectReason.PHYSICAL_DETACH)
        )
        // 再销毁设备
        remove(productId)
    }

    // ========================= 全局清理 =========================

    fun destroyAll() {
        devices.keys.forEach { productId ->
            eventBus.removeInternalObservers(productId)
        }
        devices.values.forEach { device ->
            try { device.destroy() } catch (_: Exception) {}
        }
        devices.clear()
    }

    fun hasDevice(productId: Int): Boolean = devices.containsKey(productId)

    // ========================= 私有 =========================

    private fun createCallback(productId: Int) = object : CameraHardwareCallback {
        override fun onFrameData(data: ByteArray, width: Int, height: Int) {
            eventBus.publishInternal(
                InternalEvent.FrameData(productId, data, width, height)
            )
        }

        override fun onHardwareError(error: Throwable) {
            eventBus.publishInternal(
                InternalEvent.HardwareError(productId, error)
            )
        }
    }
}