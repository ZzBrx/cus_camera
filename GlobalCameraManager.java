package com.ruide.camera;

import android.app.Application;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.util.Log;
import android.view.Surface;

import com.ruide.camera.usb.CameraListener;
import com.ruide.camera.usb.Size;
import com.ruide.camera.usb.USBDeviceSelector;
import com.ruide.camera.usb.USBMonitor;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全局相机管理器（单例）。
 *
 * <p>职责：
 * <ul>
 *   <li>监听 USB 设备插拔，分发事件到对应 {@link CameraDevice}</li>
 *   <li>管理 {@link CameraDevice} 的创建与销毁</li>
 *   <li>管理 {@link CameraSessionHandle} 的完整生命周期（打开、关闭、强制释放）</li>
 *   <li>直接路由相机能力操作（变焦、截帧、白平衡等）到 {@link CameraDevice}</li>
 * </ul>
 *
 * <p>分层说明：
 * <pre>
 *   ImpCAMService  ──→  GlobalCameraManager  ──→  CameraDevice
 *   (AIDL门面)         (Session+设备管理/能力路由)   (USB驱动/帧分发)
 *                             ↑
 *                    CameraSessionHandle（引用计数句柄）
 * </pre>
 *
 * <p>线程安全：{@code mCameraDevices} 和 {@code mSessions} 均使用
 * {@link ConcurrentHashMap}；Session 的打开/关闭通过 {@code mSessionLock} 串行化。
 */
public class GlobalCameraManager {

    private static final String TAG = "GlobalCameraManager";
    private static final int OAC_PRODUCT_ID = 0x7584;
    private static final int FIND_DEVICE_MAX_RETRY = 10;
    private static final int FIND_DEVICE_RETRY_DELAY_MS = 100;

    private static volatile GlobalCameraManager sInstance;

    private final Application mApplication;
    private final Context mContext;
    private USBMonitor mUSBMonitor;
    private volatile boolean isReleased = false;

    /**
     * productId → CameraDevice
     */
    private final Map<Integer, CameraDevice> mCameraDevices = new ConcurrentHashMap<>();

    /**
     * cameraType → CameraSessionHandle（由本类统一管理生命周期）
     */
    private final Map<Integer, CameraSessionHandle> mSessions = new ConcurrentHashMap<>();

    /**
     * Session 打开/关闭操作的互斥锁，防止并发引起引用计数错乱
     */
    private final Object mSessionLock = new Object();

    // ========================= 单例 =========================

    private GlobalCameraManager(Application application) {
        mApplication = application;
        mContext = application.getApplicationContext();
        initUSBMonitor();
    }

    public static GlobalCameraManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (GlobalCameraManager.class) {
                if (sInstance == null) {
                    sInstance = new GlobalCameraManager(
                            (Application) context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    // ========================= USB 监听 =========================

    private void initUSBMonitor() {
        mUSBMonitor = new USBMonitor(mContext, mDeviceConnectListener);
        mUSBMonitor.register();
    }

    private final USBMonitor.OnDeviceConnectListener mDeviceConnectListener =
            new USBMonitor.OnDeviceConnectListener() {
                @Override
                public void onAttach(UsbDevice device) {
                    Log.d(TAG, "onAttach: " + device.getDeviceName());
                }

                @Override
                public void onConnect(UsbDevice device,
                                      USBMonitor.UsbControlBlock ctrlBlock,
                                      boolean createNew) {
                    CameraDevice cam = mCameraDevices.get(device.getProductId());
                    if (cam != null) cam.openCamera(ctrlBlock);
                }

                @Override
                public void onDisconnect(UsbDevice device,
                                         USBMonitor.UsbControlBlock ctrlBlock) {
                    CameraDevice cam = mCameraDevices.get(device.getProductId());
                    if (cam != null) cam.notifyDisconnected(device);
                }

                @Override
                public void onDettach(UsbDevice device) {
                    Log.d(TAG, "onDettach: " + device.getDeviceName());
                }

                @Override
                public void onCancel(UsbDevice device) {
                    CameraDevice cam = mCameraDevices.get(device.getProductId());
                    if (cam != null) cam.notifyError(device, "权限请求被取消");
                }
            };


    public void addDisconnectObserver(int pid, Runnable onDisconnected) {
        CameraDevice device = requireDevice(pid, "addDisconnectObserver");
        if (device == null) {
            return;
        }

        device.addListener(new CameraListener() {

            @Override
            public void onCameraConnected(UsbDevice device) {

            }

            @Override
            public void onCameraDisconnected(UsbDevice device) {
                onDisconnected.run();
            }

            @Override
            public void onCameraError(UsbDevice device, String error) {

            }

            @Override
            public void onFrameReceived(ByteBuffer data, int width, int height) {

            }
        });
    }

    // ========================= Session 生命周期管理 =========================

    /**
     * 打开指定类型相机的 Session，引用计数 +1。
     *
     * <p>若 Session 已存在且处于打开状态，直接复用（引用 +1）；
     * 若存在僵尸 Session（refCount == 0），先移除再重建。
     *
     * @param cameraType 相机逻辑类型
     * @return true = 成功；false = 设备未找到或打开超时
     */
    public boolean openSession(int pid) {
        if (isReleased) {
            Log.e(TAG, "openSession: GlobalCameraManager 已释放");
            return false;
        }
        synchronized (mSessionLock) {
            CameraSessionHandle existing = mSessions.get(pid);
            // 清理僵尸 Session
            if (existing != null && !existing.isOpen()) {
                Log.d(TAG, "openSession: 发现僵尸 Session，移除重建: " + pid);
                mSessions.remove(pid);
                existing = null;
            }
            if (existing == null) {
                mSessions.put(pid,
                        new CameraSessionHandle(pid, mApplication, this));
            }
            CameraSessionHandle session = mSessions.get(pid);
            if (session == null) return false;

            if (!session.open()) {
                mSessions.remove(pid);
                Log.e(TAG, "openSession: 打开失败 cameraType=" + pid);
                return false;
            }
            Log.i(TAG, "openSession 成功: cameraPid=" + pid
                    + ", realProductId=" + session.getRealProductId());
            return true;
        }
    }

    /**
     * 关闭 Session，引用计数 -1。引用归零时自动移除 Session。
     *
     * @param cameraType 相机逻辑类型
     */
    public void closeSession(int pid) {
        synchronized (mSessionLock) {
            CameraSessionHandle session = mSessions.get(pid);
            if (session == null) return;
            if (session.close()) {
                mSessions.remove(pid);
                Log.i(TAG, "closeSession: cameraPid=" + pid + " 完全释放");
            }
        }
    }

    /**
     * 强制关闭 Session，忽略引用计数，通常在断电前调用。
     *
     * @param cameraType 相机逻辑类型
     */
    public void forceCloseSession(int pid) {
        synchronized (mSessionLock) {
            CameraSessionHandle session = mSessions.remove(pid);
            if (session == null) return;
            while (session.isOpen()) session.close();
            Log.i(TAG, "forceCloseSession: cameraPid=" + pid + " 已强制释放");
        }
    }

    /**
     * 查询指定相机 Session 是否已打开。
     */
    public boolean isSessionOpen(int pid) {
        CameraSessionHandle session = mSessions.get(pid);
        return session != null && session.isOpen();
    }

    /**
     * 探测相机是否可用：USB 设备存在 & 可成功打开（打开后立即关闭）。
     *
     * <p>若 Session 已在运行中，直接返回 true，不重复探测。
     *
     * @param cameraType 相机逻辑类型
     * @return true = 就绪；false = 设备不存在或无法打开
     */
    public boolean isCameraReady(int pid) {
        if (!isDevicePresent(pid)) {
            Log.d(TAG, "isCameraReady: USB 设备不存在, cameraPid=" + pid);
            return false;
        }
        if (isSessionOpen(pid)) {
            Log.d(TAG, "isCameraReady: cameraPid=" + pid + " 已打开");
            return true;
        }
        // 临时探测：open 后立即 close
        CameraSessionHandle probe = new CameraSessionHandle(pid, mApplication, this);
        if (probe.open()) {
            probe.close();
            Log.d(TAG, "isCameraReady: cameraPid=" + pid + " 可以打开");
            return true;
        }
        Log.d(TAG, "isCameraReady: cameraPid=" + pid + " 无法打开");
        return false;
    }

    // ========================= 预览 Surface =========================

    /**
     * 为指定相机绑定一个客户端预览 Surface。
     *
     * @param cameraType 相机逻辑类型
     * @param clientKey  客户端唯一标识（由调用方保证唯一性）
     * @param surface    预览 Surface
     * @return true = 绑定成功；false = Session 未打开
     */
    public boolean addPreviewSurface(int pid, String clientKey, Surface surface) {
        CameraDevice device = requireDevice(pid, "addPreviewSurface");
        if (device == null) return false;
        device.addClientSurface(clientKey, surface);
        return true;
    }

    /**
     * 移除指定相机的客户端预览 Surface。
     *
     * @param cameraType 相机逻辑类型
     * @param clientKey  客户端唯一标识
     */
    public void removePreviewSurface(int pid, String clientKey) {
        CameraDevice device = requireDevice(pid, "removePreviewSurface");
        if (device != null) {
            device.removeClientSurface(clientKey);
        } else {
            // session 已关闭，尝试遍历所有 CameraDevice 清理残留 Surface
            for (CameraDevice d : mCameraDevices.values()) {
                d.removeClientSurface(clientKey);
            }
        }
    }

    /**
     * 批量移除某相机下所有指定 clientKey 的预览 Surface。
     * 用于强制断电前批量清理所有客户端的预览。
     *
     * @param cameraType 相机逻辑类型
     * @param clientKeys 需要清理的 clientKey 集合
     */
    public void removeAllPreviewSurfaces(int cameraType, Iterable<String> clientKeys) {
        CameraDevice device = requireDevice(cameraType, "removeAllPreviewSurfaces");
        if (device == null) return;
        for (String key : clientKeys) {
            device.removeClientSurface(key);
        }
    }

    // ========================= 相机能力操作 =========================

    /**
     * 获取指定相机支持的分辨率列表，Session 未打开时返回空列表。
     */
    public List<Size> getSupportedSizes(int cameraType) {
        CameraDevice device = requireDevice(cameraType, "getSupportedSizes");
        if (device == null) return Collections.emptyList();
        return device.getSupportedSizes();
    }

    /**
     * 切换指定相机的预览分辨率，Session 运行中立即生效。
     */
    public boolean resetPreview(int cameraType, int width, int height) {
        CameraDevice device = requireDevice(cameraType, "resetPreview");
        if (device == null) return false;
        return device.resetPreview(width, height);
    }

    public boolean setZoomScale(int cameraType, int scale) {
        CameraDevice device = requireDevice(cameraType, "setZoomScale");
        if (device == null) return false;
        device.setZoomScale(scale);
        return true;
    }

    public Integer getZoomScale(int cameraType) {
        CameraDevice device = requireDevice(cameraType, "getZoomScale");
        if (device == null) return null;
        return device.getZoomScale();
    }

    /**
     * 获取当前相机的分辨率和缩放倍数。
     * @param cameraType 相机类型
     * @return int数组，[0]=width, [1]=height, [2]=zoomFactor；如果相机未打开返回null
     */
    public int[] getCurrentResolutionAndZoom(int cameraType) {
        CameraDevice device = requireDevice(cameraType, "getCurrentResolutionAndZoom");
        if (device == null) return null;
        return device.getCurrentResolutionAndZoom();
    }


    /**
     * 阻塞截取当前帧，最多等待 3 秒。
     */
    public CameraDevice.CapturedFrame captureFrame(int pid) {
        CameraDevice device = requireDevice(pid, "captureFrame");
        if (device == null) return null;
        return device.captureNextFrame();
    }

    public boolean setWhiteBalanceMode(int cameraType, int mode) {
        CameraDevice device = requireDevice(cameraType, "setWhiteBalanceMode");
        if (device == null) return false;
        return device.setWhiteBalanceMode(mode);
    }

    /**
     * 设置通用命令（用于UVC扩展功能）。
     * @param pages key
     * @param value
     * @return true = 成功；false = Session 未打开或设备不存在
     */
    public boolean setCommonOrder(int pid, int pages, int value) {
        CameraDevice device = requireDevice(pid, "setCommonOrder");
        if (device == null) return false;
        Log.d(TAG, "setCommonOrder: key="+ pages + ", value="+ value);
        boolean isFocus = false;
        try {
            isFocus = device.setCommonOrder(pages, value);
        } catch (Exception e) {
            Log.d(TAG, "setCommonOrder exception="+ e.getMessage());
            e.printStackTrace();
        }
        Log.d(TAG, "isFocus="+ isFocus);
        return isFocus;
    }

    public boolean getCommonOrder(int cameraType, int pages) {
        CameraDevice device = requireDevice(cameraType, "getCommonOrder");
        if (device == null) return false;
        return device.getCommonOrder(pages);
    }

    // ========================= 设备检测 =========================

    /**
     * 检查 USB 设备列表中是否存在指定 cameraType 对应的设备（不重试，立即返回）。
     */
    public boolean isDevicePresent(int pid) {
        if (mUSBMonitor == null) return false;
        return selectDevice(pid) != null;
    }

    // ========================= 全局释放 =========================

    /**
     * 释放全局管理器，应在 Service 销毁时调用。
     */
    public void release() {
        if (isReleased) return;
        isReleased = true;
        mSessions.values().forEach(s -> {
            while (s.isOpen()) s.close();
        });
        mSessions.clear();
        mCameraDevices.values().forEach(CameraDevice::destroy);
        mCameraDevices.clear();
        if (mUSBMonitor != null) {
            mUSBMonitor.unregister();
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        sInstance = null;
        Log.d(TAG, "GlobalCameraManager released");
    }

    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }

    // ========================= 包内回调（由 CameraDevice 调用） =========================

    /**
     * Listener 清空时由 {@link CameraDevice} 回调，触发设备移除并销毁。
     * 包级可见，不对外暴露。
     */
    void releaseCameraDevice(CameraDevice device) {
        if (device == null) return;
        // 用 remove 的返回值判断是否真的移除了，防止并发双重 destroy
        CameraDevice removed = mCameraDevices.remove(device.productId);
        if (removed != null) {
            removed.destroy();
            Log.d(TAG, "releaseCameraDevice: productId=" + device.productId);
        }
    }

    // ========================= 包内：CameraSessionHandle 调用的底层接口 =========================

    /**
     * 异步查找 USB 设备并打开相机，结果通过 {@link CameraListener} 回调。
     * 仅供 {@link CameraSessionHandle} 调用。
     */
    CameraReference openCameraByPid(int pid, CameraListener listener) {
        return openCameraByPid(pid, listener, 1280, 720);
    }

    CameraReference openCameraByPid(int pid, CameraListener listener, int width, int height) {
        CameraReference ref = new CameraReference(listener);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                doOpenCamera(ref, pid, listener, width, height);
            } finally {
                executor.shutdown();
            }
        });
        return ref;
    }

    // ========================= 私有实现 =========================

    private void doOpenCamera(CameraReference ref, int pid,
                              CameraListener listener, int width, int height) {
        UsbDevice device = findDevice(pid);
        if (device == null) {
            Log.e(TAG, "doOpenCamera: 找不到设备, pid=" + pid);
            listener.onCameraError(null, "找不到设备, pid=" + pid);
            return;
        }
        int realPid = device.getProductId();
        Log.d(TAG, "doOpenCamera: 传入 pid=" + pid + ", 真实 productId=" + realPid);

        CameraDevice cameraDevice = mCameraDevices.computeIfAbsent(
                realPid, id -> new CameraDevice(id, this));
        cameraDevice.addListener(listener);
        ref.attach(cameraDevice);

        try {
            openOrRequestPermission(device, cameraDevice, width, height);
        } catch (Exception e) {
            // 权限请求意外失败，清理已注册的 listener
            Log.e(TAG, "openOrRequestPermission 异常，清理 listener", e);
            cameraDevice.removeListener(listener);
            listener.onCameraError(device, "打开失败: " + e.getMessage());
        }
    }

    private UsbDevice findDevice(int pid) {
        for (int i = 0; i < FIND_DEVICE_MAX_RETRY; i++) {
            UsbDevice device = selectDevice(pid);
            if (device != null) {
                Log.d(TAG, "findDevice: 找到设备，重试次数=" + i);
                return device;
            }
            Log.d(TAG, "findDevice: 第" + (i + 1) + "次未找到，等待重试...");
            try {
                Thread.sleep(FIND_DEVICE_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    /**
     * OAC 精确匹配 pid；其他类型排除 OAC 后取第一个设备。
     */
    private UsbDevice selectDevice(int pid) {
        return (pid == OAC_PRODUCT_ID)
                ? USBDeviceSelector.selectByProductId(mUSBMonitor, pid)
                : USBDeviceSelector.selectOtherExcluding(mUSBMonitor, OAC_PRODUCT_ID);
    }

    private void openOrRequestPermission(UsbDevice device,
                                         CameraDevice cameraDevice,
                                         int width, int height) {
        if (mUSBMonitor.hasPermission(device)) {
            try {
                USBMonitor.UsbControlBlock ctrlBlock = mUSBMonitor.openDevice(device);
                if (ctrlBlock == null) {
                    Log.e(TAG, "openDevice 返回 null: " + device.getDeviceName());
                    cameraDevice.notifyError(device, "openDevice 返回 null");
                    return;
                }
                cameraDevice.openCamera(ctrlBlock, width, height);
            } catch (Exception e) {
                // ctrlBlock 异常（CloneNotSupportedException 等）时降级重走权限流程
                Log.e(TAG, "openDevice 失败，降级请求权限: " + e.getMessage());
                cameraDevice.setSize(width, height);
                mUSBMonitor.requestPermission(device); // 让 USBMonitor 重建 ctrlBlock
            }
        } else {
            cameraDevice.setSize(width, height);
            mUSBMonitor.requestPermission(device);
        }
    }

    /**
     * 获取已打开 Session 对应的 {@link CameraDevice}，并打印操作名以便定位日志。
     * Session 未打开或设备不存在时返回 null。
     */
    private CameraDevice requireDevice(int pid, String operation) {
        CameraSessionHandle session = mSessions.get(pid);
        if (session == null || !session.isOpen()) {
            Log.d(TAG, operation + ": Session 未打开, cameraPid=" + pid);
            return null;
        }
        int realPid = session.getRealProductId();
        CameraDevice device = mCameraDevices.get(realPid);
        if (device == null) {
            Log.d(TAG, operation + ": 找不到 CameraDevice, realProductId=" + realPid);
        }
        return device;
    }

    // ========================= 引用句柄（供 CameraSessionHandle 持有） =========================

    /**
     * 持有 {@link CameraDevice} + {@link CameraListener} 的引用句柄。
     *
     * <p>不再使用时须调用 {@link #release()} 解除监听，
     * 使 {@link GlobalCameraManager} 在无人监听时自动回收 {@link CameraDevice}。
     */
    public static class CameraReference {

        private CameraDevice device;
        private CameraListener listener;
        private boolean released = false;

        CameraReference(CameraListener listener) {
            this.listener = listener;
        }

        void attach(CameraDevice d) {
            this.device = d;
            if (released) {
                d.removeListener(listener);
                device = null;
            }
        }

        public synchronized void release() {
            released = true;
            if (device != null) {
                device.removeListener(listener);
                device = null;
            }
        }
    }
}