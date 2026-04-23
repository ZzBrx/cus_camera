package com.ruide.camera;

import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.util.Log;
import android.view.Surface;

import com.ruide.camera.usb.CameraListener;
import com.ruide.camera.usb.IFrameCallback;
import com.ruide.camera.usb.Size;
import com.ruide.camera.usb.USBMonitor;
import com.ruide.camera.usb.UVCCamera;

import org.opencv.android.Utils;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 单个 USB 相机设备的生命周期管理。
 *
 * <p>职责：
 * <ul>
 *   <li>管理 {@link UVCCamera} 的打开 / 关闭 / 重置</li>
 *   <li>向多个 Surface（按 clientKey 区分）分发 YUV 帧</li>
 *   <li>支持数字变焦、白平衡、分辨率切换</li>
 *   <li>通知注册的 {@link CameraListener}</li>
 * </ul>
 *
 * <p>线程安全说明：
 * <ul>
 *   <li>相机控制路径（open / close / reset）使用 {@code synchronized} 保证串行</li>
 *   <li>帧分发路径不持锁，通过 {@link AtomicBoolean} 标志避免访问已销毁的 Surface</li>
 *   <li>截帧请求使用 {@link AtomicReference} 保证并发安全，避免 latch 被覆盖</li>
 * </ul>
 */
public class CameraDevice {

    private static final String TAG = "CameraDevice";

    /**
     * 每个 Surface 对应的帧渲染 Executor 队列容量，超出则丢弃最旧帧
     */
    private static final int SURFACE_QUEUE_CAPACITY = 2;

    final int productId;
    private final GlobalCameraManager mManager;

    private UVCCamera mCamera;
    private USBMonitor.UsbControlBlock mCtrlBlock;
    private SurfaceTexture mDummyTexture;
    private Surface mDummySurface;

    private volatile boolean isConnected = false;
    private volatile int mWidth = 1280;
    private volatile int mHeight = 720;
    private volatile int mZoomFactor = 1;

    private volatile boolean mIsClosing = false; // 主动关闭标志

    // ---- 截帧：使用 AtomicReference 避免并发调用时 latch 被覆盖 ----
    private final AtomicReference<CaptureRequest> mPendingCapture = new AtomicReference<>(null);

    // ---- FPS 统计----
    // FPS 统计 —— 仅由 dispatchFrame 调用，受 UVC 单线程回调约束，无需加锁
    // 若未来需要跨线程统计，改为 LongAdder + volatile long mLastFpsTime
    private long mLastFpsTime = 0;
    private int mFrameCount = 0;

    // ---- 监听器 & Surface 管理 ----
    private final List<CameraListener> mListeners = new CopyOnWriteArrayList<>();

    private final Set<String> mPendingRemovals = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final ConcurrentHashMap<String, SurfaceSlot> mSurfaceSlots = new ConcurrentHashMap<>();


    private final IFrameCallback mFrameCallback = this::dispatchFrame;

    private final Object mCameraLock = new Object();  // 仅保护 mCamera / mCtrlBlock / mDummySurface
    private final Object mResizeLock = new Object();   // getSupportedSizes 独占

    private volatile UsbDevice mConnectedDevice = null; // 新增成员

    // ========================= 构造 =========================

    CameraDevice(int productId, GlobalCameraManager manager) {
        this.productId = productId;
        this.mManager = manager;
    }

    // ========================= 公开数据类 =========================

    /**
     * 截帧结果，包含 NV21 数据及宽高。
     */
    public static class CapturedFrame {
        public final byte[] data;
        public final int width;
        public final int height;

        public CapturedFrame(byte[] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * 截帧请求：封装 latch + 结果，通过 AtomicReference 原子替换保证并发安全。
     */
    private static class CaptureRequest {
        volatile byte[] result = null;
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
    }

    // ========================= 尺寸 =========================

    void setSize(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    // ========================= Listener 管理 =========================

    void addListener(CameraListener l) {
        if (l == null || mListeners.contains(l)) return;
        mListeners.add(l);
        if (isConnected && mConnectedDevice != null) {
            l.onCameraConnected(mConnectedDevice);
        }
        Log.d(TAG, "addListener, 当前数量: " + mListeners.size());
    }

    void removeListener(CameraListener l) {
        mListeners.remove(l);
        Log.d(TAG, "removeListener, 剩余数量: " + mListeners.size());
        if (mListeners.isEmpty()) {
            mManager.releaseCameraDevice(this);
        }
    }

    /**
     * 调用 UVCCamera.setCommonOrder 发送自定义命令。
     *
     * @param pages 页码
     * @param value 值
     * @return true = 成功；false = 相机未打开
     */
    synchronized boolean setCommonOrder(int pages, int value) {
        if (mCamera == null) {
            Log.e(TAG, "setCommonOrder: 相机未打开, productId=" + productId);
            return false;
        }
        try {
            mCamera.setCommonOrder(pages, value);
            Log.d(TAG, "setCommonOrder 成功: pages=" + pages + ", value=" + value);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "setCommonOrder 失败", e);
            return false;
        }
    }

    synchronized boolean getCommonOrder(int pages) {
        if (mCamera == null) {
            Log.d(TAG, "getCommonOrder: 相机未打开, productId=" + productId);
            return false;
        }
        try {
            mCamera.getCommonOrder(pages);
            Log.d(TAG, "getCommonOrder 成功: pages=" + pages);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "getCommonOrder 失败", e);
            return false;
        }
    }

    int getListenerCount() {
        return mListeners.size();
    }

    // ========================= 相机开关 =========================

    /**
     * 使用已记录的分辨率打开相机。
     */
    void openCamera(USBMonitor.UsbControlBlock ctrlBlock) {
        openCamera(ctrlBlock, mWidth, mHeight);
    }

    synchronized void openCamera(USBMonitor.UsbControlBlock ctrlBlock, int width, int height) {
        if (mCamera != null) {
            Log.d(TAG, "openCamera: 相机已打开，忽略重复调用");
            notifyConnected(ctrlBlock.getDevice());
            return;
        }
        mWidth = width;
        mHeight = height;
        mCtrlBlock = ctrlBlock;
        try {
            mCamera = new UVCCamera();
            mCamera.open(ctrlBlock);
            mCamera.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG);
            mCamera.setFrameCallback(mFrameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP);
            mCamera.updateCameraParams();
            ensurePreviewRunning();
            isConnected = true;
            Log.i(TAG, "openCamera 成功: productId=" + productId);
            mConnectedDevice = ctrlBlock.getDevice();
            notifyConnected(ctrlBlock.getDevice());
        } catch (Exception e) {
            Log.e(TAG, "openCamera 失败", e);
            cleanupCamera();
        }
    }

    /**
     * 完整销毁设备，包含所有 Executor 和 Surface。
     */
    void destroy() {
        cleanupCamera();
        mSurfaceSlots.values().forEach(a -> {
            a.active.set(false);
            a.executor.shutdown();
        });
        mSurfaceSlots.clear();
        mListeners.clear();
        Log.i(TAG, "CameraDevice destroyed: productId=" + productId);
    }

    // ========================= 分辨率 =========================

    /**
     * 获取设备实际支持的分辨率列表（会逐一协商验证，耗时操作，勿在主线程调用）。
     */
    List<Size> getSupportedSizes() {
        synchronized (mResizeLock){
            final UVCCamera camera;
            synchronized (mCameraLock) {
                if (mCamera == null) return Collections.emptyList();
                camera = mCamera;
            }

            if (camera == null) {
                Log.d(TAG, "getSupportedSizes: 相机未打开");
                return Collections.emptyList();
            }
            List<Size> raw;
            try {
                raw = camera.getSupportedSizeList();
            } catch (Exception e) {
                Log.e(TAG, "getSupportedSizeList 失败", e);
                return Collections.emptyList();
            }
            if (raw == null || raw.isEmpty()) return Collections.emptyList();

            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }

            List<Size> verified = negotiateSizes(raw);

            // 恢复原分辨率并重启预览
            try {
                camera.setPreviewSize(mWidth, mHeight, UVCCamera.FRAME_FORMAT_MJPEG);
                if (!mSurfaceSlots.isEmpty()) {
                    camera.setFrameCallback(mFrameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP);
                    camera.startPreview();
                }
            } catch (Exception e) {
                Log.e(TAG, "getSupportedSizes: 恢复预览失败", e);
            }

            Log.i(TAG, "getSupportedSizes: 原始=" + raw.size() + "个, 实际可用=" + verified.size() + "个 "
                    + verified.stream().map(s -> s.width + "x" + s.height)
                    .collect(Collectors.joining(", ", "[", "]")));
            return verified;
        }
    }

    /**
     * 遍历原始列表，逐一向相机协商，返回真正可用的分辨率（已去重）。
     */
    private List<Size> negotiateSizes(List<Size> raw) {
        List<Size> verified = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Size size : raw) {
            String key = size.width + "x" + size.height;
            if (!seen.add(key)) {
                Log.d(TAG, "negotiateSizes: 跳过重复分辨率 " + key);
                continue;
            }
            try {
                mCamera.setPreviewSize(size.width, size.height, UVCCamera.FRAME_FORMAT_MJPEG);
                verified.add(size);
                Log.d(TAG, "negotiateSizes: 验证通过 " + key);
            } catch (Exception e) {
                Log.d(TAG, "negotiateSizes: 跳过不可用分辨率 " + key + " (" + e.getMessage() + ")");
            }
        }
        return verified;
    }

    synchronized boolean resetPreview(int width, int height) {
        if (mCamera == null || !isConnected) {
            Log.d(TAG, "resetPreview: 相机未打开，仅更新尺寸记录");
            mWidth = width;
            mHeight = height;
            return false;
        }
        try {
            mCamera.stopPreview();
            mCamera.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG);
            mWidth = width;
            mHeight = height;
            if (!mSurfaceSlots.isEmpty()) {
                mCamera.setPreviewDisplay(mDummySurface);
                mCamera.setFrameCallback(mFrameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP);
                mCamera.startPreview();
                Log.i(TAG, "resetPreview 成功: " + width + "x" + height);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "resetPreview 失败", e);
            return false;
        }
    }

    // ========================= 变焦 =========================

    void setZoomScale(int scale) {
        mZoomFactor = scale;
        Log.d(TAG, "setZoomScale: " + scale + "x");
    }

    int getZoomScale() {
        if (mCamera == null) {
            Log.d(TAG, "getZoomScale: 相机未打开");
            return -1;
        }
        return mZoomFactor;
    }

    /**
     * 获取当前预览分辨率和缩放倍数，用于计算芯片窗口尺寸。
     * @return int数组，[0]=width, [1]=height, [2]=zoomFactor
     */
    int[] getCurrentResolutionAndZoom() {
        return new int[]{mWidth, mHeight, mZoomFactor};
    }


    // ========================= 白平衡 =========================

    /**
     * 设置白平衡模式。
     *
     * @param mode 0=自动, 1=室内(暖色温), 2=室外(冷色温)
     * @return true 表示设置成功
     */
    boolean setWhiteBalanceMode(int mode) {
        // 第一阶段：持锁校验相机，拿到本地引用后立即释放
        final UVCCamera camera;
        synchronized (mCameraLock) {
            if (mCamera == null) return false;
            camera = mCamera;
        }
        // 第二阶段：锁外执行含 sleep 的 UVC 操作
        try {
            switch (mode) {
                case 0:
                    mCamera.setAutoWhiteBlance(true);
                    Thread.sleep(100); // 锁外 sleep，不阻塞其他 synchronized 方法
                    break;
                case 1:
                case 2:
                    mCamera.setAutoWhiteBlance(false);
                    Thread.sleep(200);
                    if (camera.getAutoWhiteBlance()) {
                        camera.setAutoWhiteBlance(false);
                        Thread.sleep(200);
                    }
                    camera.setWhiteBlance(mode == 1 ? 30 : 60);
                    break;
                default:
                    return false;
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    // ========================= 截帧 =========================

    /**
     * 阻塞等待下一帧，最多等待 3 秒。
     * 使用 {@link AtomicReference} 保证并发调用时各自拿到独立的 latch，互不干扰。
     *
     * @return 含 NV21 数据的 {@link CapturedFrame}，超时或被中断返回 null
     */
    CapturedFrame captureNextFrame() {
        CaptureRequest request = new CaptureRequest();
        // 若已有其他请求在等待，直接替换（后来者优先）
        mPendingCapture.set(request);
        try {
            request.latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            mPendingCapture.compareAndSet(request, null);
        }
        byte[] result = request.result;
        return (result == null) ? null : new CapturedFrame(result, mWidth, mHeight);
    }

    // ========================= Surface 管理 =========================

    void addClientSurface(String clientKey, Surface surface) {
        if (surface == null || !surface.isValid()) return;
        // 单次 put，帧线程要么看到完整 slot，要么看不到此 key
        mSurfaceSlots.put(clientKey, new SurfaceSlot(surface));
        if (mSurfaceSlots.size() == 1 && isConnected && mCamera != null) {
            ensurePreviewRunning();
        }
    }

    void removeClientSurface(String clientKey) {
        SurfaceSlot slot = mSurfaceSlots.remove(clientKey);
        if (slot != null) {
            slot.deactivateAndShutdown();
            if (slot.surface.isValid()) slot.surface.release();
        }
        if (mSurfaceSlots.isEmpty() && mCamera != null) {
            try {
                mCamera.stopPreview();
                mCamera.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_YUV420SP);
            } catch (Exception e) {
                Log.e(TAG, "停止预览失败", e);
            }
        }
    }

    // ========================= 事件通知 =========================

    /**
     * 被动断开（物理拔出）时才真正通知上层
     *
     * @param device
     */
    void notifyDisconnected(UsbDevice device) {
        if (mIsClosing) {
            Log.d(TAG, "主动关闭中，忽略 onDisconnect 回调");
            return;
        }
        isConnected = false;
        mListeners.forEach(l -> l.onCameraDisconnected(device));
    }

    void notifyError(UsbDevice device, String err) {
        mListeners.forEach(l -> l.onCameraError(device, err));
    }

    // ========================= 私有：相机控制 =========================

    private void initDummySurface() {
        mDummyTexture = new SurfaceTexture(false);
        mDummySurface = new Surface(mDummyTexture);
    }

    /**
     * 确保预览处于运行状态：设置 DummySurface + FrameCallback，若有 Surface 则 startPreview。
     * openCamera 完成后、首个 Surface 接入后，均通过此方法统一启动。
     */
    private void ensurePreviewRunning() {
        try {
            if (mDummySurface == null) initDummySurface();
            mCamera.setPreviewDisplay(mDummySurface);
            mCamera.setFrameCallback(mFrameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP);
            if (!mSurfaceSlots.isEmpty()) {
                mCamera.startPreview();
                Log.d(TAG, "ensurePreviewRunning: 预览已启动");
            } else {
                Log.d(TAG, "ensurePreviewRunning: 无 Surface，仅挂载 FrameCallback");
            }
        } catch (Exception e) {
            Log.e(TAG, "ensurePreviewRunning 失败", e);
        }
    }

    private void cleanupCamera() {
        mIsClosing = true;
        try {
            if (mCamera != null) {
                mCamera.stopPreview();
                mCamera.close();
                mCamera.destroy();
                mCamera = null;
            }
            if (mCtrlBlock != null) {
                mCtrlBlock.close();
                mCtrlBlock = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "cleanupCamera error", e);
        } finally {
            releaseDummySurface();
            isConnected = false;
            mIsClosing = false;
        }
    }

    private void releaseDummySurface() {
        if (mDummySurface != null) {
            mDummySurface.release();
            mDummySurface = null;
        }
        if (mDummyTexture != null) {
            mDummyTexture.release();
            mDummyTexture = null;
        }
    }

    private void notifyConnected(UsbDevice device) {
        mListeners.forEach(l -> l.onCameraConnected(device));
    }

    // ========================= 私有：帧分发 =========================

    /**
     * 每帧回调入口。先处理 Surface 清理，再拷贝数据 + 变焦，最后异步投递到各客户端渲染队列。
     */
    private void dispatchFrame(ByteBuffer frame) {
        logFpsIfDebug();
        flushPendingRemovals();

        if (mListeners.isEmpty() && mSurfaceSlots.isEmpty()) return;

        final byte[] rawData = toByteArray(frame);
        final int zoom = mZoomFactor;
        final int w = mWidth;
        final int h = mHeight;
        final byte[] frameData = (zoom > 1)
                ? Utils.cropAndScaleNV21(rawData, w, h, zoom) : rawData;


        fulfillCaptureRequest(frameData);

        for (Map.Entry<String, SurfaceSlot> entry : mSurfaceSlots.entrySet()) {
            scheduleRender(entry.getKey(), entry.getValue(), frameData, w, h);
        }
    }

    private void logFpsIfDebug() {
        if (!BuildConfig.DEBUG) return;
        long now = System.currentTimeMillis();
        mFrameCount++;
        if (now - mLastFpsTime >= 1000) {
//            Log.d(TAG, "FPS: " + mFrameCount);
            mFrameCount = 0;
            mLastFpsTime = now;
        }
    }

    private void flushPendingRemovals() {
        for (String key : mPendingRemovals) {
            mPendingRemovals.remove(key);
            removeClientSurface(key);
        }
    }

    private static byte[] toByteArray(ByteBuffer buffer) {
        byte[] arr = new byte[buffer.remaining()];
        buffer.get(arr);
        return arr;
    }

    private void fulfillCaptureRequest(byte[] frameData) {
        CaptureRequest request = mPendingCapture.get();
        if (request != null && request.latch.getCount() > 0) {
            request.result = frameData;
            request.latch.countDown();
        }
    }

    private void scheduleRender(String clientKey, SurfaceSlot slot, byte[] frameData, int w, int h) {
        if (!slot.active.get() || slot.executor.isShutdown()) return;

        try {
            slot.executor.execute(() -> {
                if (!slot.active.get()) return;
                if (!slot.surface.isValid()) {
                    mPendingRemovals.add(clientKey);
                    return;
                }
                try {
                    Utils.renderYuvToSurface(ByteBuffer.wrap(frameData), slot.surface, w, h);
                } catch (Exception e) {
                    Log.e(TAG, "渲染到 Surface 失败: " + clientKey, e);
                }
            });
        } catch (Exception e) {
            Log.d(TAG, "scheduleRender: executor 已关闭, key=" + clientKey);
        }
    }

    /**
     * 构建单线程、丢最旧帧策略的渲染 Executor。
     */
    private static ExecutorService buildSurfaceExecutor() {
        return new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(SURFACE_QUEUE_CAPACITY),
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }

    /**
     * 将 Surface、active flag、Executor 打包为一个不可变槽，单次 put 原子可见
     */
    private static final class SurfaceSlot {
        final Surface surface;
        final AtomicBoolean active = new AtomicBoolean(true);
        final ExecutorService executor;

        SurfaceSlot(Surface surface) {
            this.surface = surface;
            this.executor = buildSurfaceExecutor();
        }

        void deactivateAndShutdown() {
            active.set(false);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

}