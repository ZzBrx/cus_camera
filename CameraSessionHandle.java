package com.ruide.camera;

import android.app.Application;
import android.hardware.usb.UsbDevice;
import android.util.Log;
import com.ruide.camera.usb.CameraListener;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单个逻辑相机的会话句柄，仅供 {@link GlobalCameraManager} 使用。
 *
 * <p>职责：
 * <ul>
 *   <li>引用计数管理，支持多客户端共享同一相机</li>
 *   <li>持有 {@link GlobalCameraManager.CameraReference} 防 GC</li>
 *   <li>记录 {@code realProductId}，供 {@link GlobalCameraManager} 路由到 {@link CameraDevice}</li>
 * </ul>
 *
 * <p>能力操作（变焦、截帧、白平衡等）由 {@link GlobalCameraManager} 直接通过
 * {@code realProductId} 操作 {@link CameraDevice}，本类不再透传转发。
 *
 * <p>可见性：包级（default），上层服务仅通过 {@link GlobalCameraManager} 间接操作。
 */
class CameraSessionHandle {

    private static final String TAG_PREFIX = "CameraSession[";
    private static final long OPEN_TIMEOUT_SECONDS = 10L;

    private final String TAG;
    private final int pid;
    private final Application application;
    private final GlobalCameraManager cameraManager;
    private final int initWidth;
    private final int initHeight;

    /** 引用计数，0 表示未打开或已完全释放 */
    private final AtomicInteger refCount = new AtomicInteger(0);

    /** 持有相机引用，防止 GC 回收 CameraDevice */
    private GlobalCameraManager.CameraReference cameraReference;

    /** 相机真实 USB productId，open 成功后由连接回调赋值，之前为 -1 */
    private volatile int realProductId = -1;

    // ========================= 构造 =========================

    CameraSessionHandle(int pid, Application application, GlobalCameraManager cameraManager) {
        this(pid, application, cameraManager, 1280, 720);
    }

    CameraSessionHandle(int pid,
                        Application application,
                        GlobalCameraManager cameraManager,
                        int width,
                        int height) {
        this.pid = pid;
        this.application = application;
        this.cameraManager = cameraManager;
        this.initWidth = width;
        this.initHeight = height;
        this.TAG = TAG_PREFIX + pid + "]";
    }

    // ========================= 生命周期 =========================

    /**
     * 打开相机，引用计数 +1。
     *
     * <p>若已打开则直接复用（引用 +1）；首次打开会阻塞等待 USB 连接回调，
     * 超过 {@value OPEN_TIMEOUT_SECONDS} 秒视为超时并清理资源。
     *
     * @return true = 成功；false = 设备未找到或超时
     */
    synchronized boolean open() {
        if (refCount.get() > 0) {
            refCount.incrementAndGet();
            Log.d(TAG, "已打开，引用计数: " + refCount.get());
            return true;
        }

        // 释放上一次的残留引用（异常退出场景）
        releaseReference();

        CountDownLatch latch = new CountDownLatch(1);
        CameraListener listener = buildCameraListener(latch);
        GlobalCameraManager.CameraReference reference =
                cameraManager.openCameraByPid(pid, listener, initWidth, initHeight);
        if (reference == null) {
            Log.e(TAG, "open: openCameraByPid 返回 null");
            return false;
        }
        cameraReference = reference;

        boolean connected;
        try {
            connected = latch.await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "open 被中断", e);
            Thread.currentThread().interrupt();
            connected = false;
        }

        if (connected && realProductId >= 0) {
            refCount.set(1);
            Log.d(TAG, "open 成功, realProductId=" + realProductId);
            return true;
        } else {
            Log.e(TAG, "open 超时或连接失败，清理资源");
            releaseReference();
            return false;
        }
    }

    /**
     * 关闭相机，引用计数 -1。
     *
     * @return true = 引用归零，资源已完全释放；false = 仍有其他引用持有
     */
    boolean close() {
        int remaining = refCount.decrementAndGet();
        Log.d(TAG, "close, 剩余引用: " + remaining);
        if (remaining <= 0) {
            releaseReference();
            refCount.set(0);
            return true;
        }
        return false;
    }

    boolean isOpen() {
        return refCount.get() > 0;
    }

    // ========================= 查询 =========================

    /**
     * 返回相机真实 USB productId。
     * 仅在 {@link #open()} 成功后有效，否则返回 -1。
     */
    int getRealProductId() {
        return realProductId;
    }

    // ========================= 私有工具 =========================

    private void releaseReference() {
        if (cameraReference != null) {
            cameraReference.release();
            cameraReference = null;
        }
        realProductId = -1;
    }

    private CameraListener buildCameraListener(CountDownLatch latch) {
        return new CameraListener() {
            @Override
            public void onCameraConnected(UsbDevice device) {
                realProductId = (device != null) ? device.getProductId() : -1;
                Log.d(TAG, "已连接: " + (device != null ? device.getDeviceName() : "null")
                        + ", realProductId=" + realProductId);
                latch.countDown();
            }

            @Override
            public void onCameraDisconnected(UsbDevice device) {
                Log.d(TAG, "已断开: " + (device != null ? device.getDeviceName() : "null"));
                refCount.set(0);
                latch.countDown();
            }

            @Override
            public void onCameraError(UsbDevice device, String error) {
                Log.e(TAG, "错误: " + error);
            }

            @Override
            public void onFrameReceived(ByteBuffer frame, int width, int height) {
                // 帧数据由 CameraDevice 直接分发到 Surface，Session 层无需处理
            }
        };
    }
}