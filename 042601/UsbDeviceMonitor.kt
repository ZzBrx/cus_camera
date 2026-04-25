package com.ruide.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.ruide.camera.usb.USBDeviceSelector
import com.ruide.camera.usb.USBMonitor

/**
 * USB 设备监听封装。
 *
 * 职责：
 * - 监听 USB 设备插拔
 * - 提供设备查找和存活校验
 * - 事件回调给外部监听器
 *
 * 注意：OAC productId 固定为 0x7584
 */
class UsbDeviceMonitor(context: Context) {

    companion object {
        private const val TAG = "UsbDeviceMonitor"
        const val OAC_PRODUCT_ID = 0x7584
    }

    private val usbMonitor: USBMonitor = USBMonitor(context, onDeviceConnectListener)

    /** 设备连接监听器（由 CameraSessionManager 设置） */
    var onDeviceConnectListener: OnDeviceConnectListener? = null

    // ========================= 设备查询 =========================

    /**
     * 查找指定 productId 的设备，不重试。
     */
    fun findDevice(productId: Int): UsbDevice? {
        return selectDevice(productId)
    }

    /**
     * 带重试的设备查找。
     */
    fun findDeviceWithRetry(productId: Int, maxRetry: Int = 10, delayMs: Long = 100): UsbDevice? {
        for (i in 0 until maxRetry) {
            val device = selectDevice(productId)
            if (device != null) {
                Log.d(TAG, "findDevice: 找到设备 productId=$productId, 重试次数=$i")
                return device
            }
            Log.d(TAG, "findDevice: 第${i + 1}次未找到 productId=$productId, 等待重试...")
            try {
                Thread.sleep(delayMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return null
    }

    /**
     * 快速检查设备是否仍在 USB 总线上。
     * 不阻塞，仅遍历当前设备列表。
     */
    fun isDeviceAlive(productId: Int): Boolean {
        return selectDevice(productId) != null
    }

    /**
     * 检查是否有 USB 权限。
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbMonitor.hasPermission(device)
    }

    /**
     * 打开设备，返回 UsbControlBlock。
     */
    fun openDevice(device: UsbDevice): USBMonitor.UsbControlBlock? {
        return usbMonitor.openDevice(device)
    }

    /**
     * 请求 USB 权限。
     */
    fun requestPermission(device: UsbDevice) {
        usbMonitor.requestPermission(device)
    }

    // ========================= 生命周期 =========================

    fun register() {
        usbMonitor.register()
    }

    fun unregister() {
        usbMonitor.unregister()
    }

    fun destroy() {
        usbMonitor.destroy()
    }

    // ========================= 私有 =========================

    /**
     * OAC 精确匹配 productId；其他类型排除 OAC 后取第一个设备。
     */
    private fun selectDevice(productId: Int): UsbDevice? {
        return if (productId == OAC_PRODUCT_ID) {
            USBDeviceSelector.selectByProductId(usbMonitor, productId)
        } else {
            USBDeviceSelector.selectOtherExcluding(usbMonitor, OAC_PRODUCT_ID)
        }
    }

    // ========================= 回调接口 =========================

    interface OnDeviceConnectListener {
        fun onAttach(device: UsbDevice)
        fun onConnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock, createNew: Boolean)
        fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock?)
        fun onDettach(device: UsbDevice)
        fun onCancel(device: UsbDevice)
    }

    // USBMonitor 的实际回调，转发给 onDeviceConnectListener
    private val internalListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {
            Log.d(TAG, "onAttach: ${device.deviceName}")
            onDeviceConnectListener?.onAttach(device)
        }

        override fun onConnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock, createNew: Boolean) {
            Log.d(TAG, "onConnect: ${device.deviceName}")
            onDeviceConnectListener?.onConnect(device, ctrlBlock, createNew)
        }

        override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock?) {
            Log.d(TAG, "onDisconnect: ${device.deviceName}")
            onDeviceConnectListener?.onDisconnect(device, ctrlBlock)
        }

        override fun onDettach(device: UsbDevice) {
            Log.d(TAG, "onDettach: ${device.deviceName}")
            onDeviceConnectListener?.onDettach(device)
        }

        override fun onCancel(device: UsbDevice) {
            Log.d(TAG, "onCancel: ${device.deviceName}")
            onDeviceConnectListener?.onCancel(device)
        }
    }.also { usbMonitor.register() } // 初始化时注册
}