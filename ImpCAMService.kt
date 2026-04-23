package com.ruide.service.middleware.impl

import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaRecorder
import android.os.Binder
import android.util.Log
import android.view.Surface
import com.google.gson.Gson
import com.ruide.aidl.bean.Response
import com.ruide.aidl.func.ICAMService
import com.ruide.aidl.para.CAM
import com.ruide.camera.GlobalCameraManager
import com.ruide.camera.UvcCmdConstant
import com.ruide.command.chain.provider.GlobalShareProvider
import com.ruide.command.chain.provider.ProviderKey
import com.ruide.command.hard.angle.AngleCorrectCommand
import com.ruide.command.hard.angle.ReadAngleCommand
import com.ruide.command.hard.edm.EdmStartCommand
import com.ruide.command.hard.edm.MeaStopCommand
import com.ruide.command.hard.edm.ReadDisCommand
import com.ruide.command.hard.servo.ServoAtrUpdateSearchRt
import com.ruide.command.hard.servo.ServoReadLockDataCommand
import com.ruide.command.hard.servo.ServoStartDebugPsAtr
import com.ruide.command.hard.tilt.ReadTiltCommand
import com.ruide.command.hard.tilt.TiltCorrectCommand
import com.ruide.command.isEdmSinglePrismMode
import com.ruide.common.bean.WebSocketDeviceBean
import com.ruide.common.constant.GRCode
import com.ruide.core.bean.EnumCommons
import com.ruide.core.device.def.ServoDef
import com.ruide.service.CameraSessionRegistry
import com.ruide.service.MediaStreamService
import com.ruide.service.ScreenRecordManager
import com.ruide.service.bean.CameraContext
import com.ruide.service.command.EdmGeoCorrectCommand
import com.ruide.service.core.fram.para.SetEnum
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * ICAMService 的 AIDL 实现，作为上层 Binder 门面。
 *
 * 职责：
 * - 请求合法性校验（相机是否打开、Surface 是否有效等）
 * - 委托 [CameraSessionRegistry] 管理客户端归属状态
 * - 委托 [GlobalCameraManager] 执行实际硬件操作
 * - 生命周期联动：客户端断连或断电时通过 registry 批量清理资源
 *
 * 锁策略：
 * - 状态变更均由 [CameraSessionRegistry] 内部加锁保护
 * - IO 密集型或耗时操作（takeImage 写文件、startRemoteVideo 发 Intent）在锁外执行，
 *   避免持锁期间阻塞导致 ANR
 */
@com.ruide.service.middleware.annotation.ImpClass(ICAMService::class)
class ImpCAMService(globalShareProvider: GlobalShareProvider) :
    AbstractImpObj(globalShareProvider), ICAMService {

    companion object {
        private const val TAG = "ImpCAMService"
        private const val RECORD_SURFACE_KEY_PREFIX = "screenRecord_"
    }

    private val cameraManager: GlobalCameraManager by lazy {
        GlobalCameraManager.getInstance(shareProvider.application)
    }

    private val registry = CameraSessionRegistry()

    // cameraType → 图片命名配置
    private val imageNameConfigs = ConcurrentHashMap<CAM.CAM_ID_TYPE, CAM.ImageNameConfig>()

    // cameraType → 支持的分辨率缓存（相机打开/关闭时失效）
    private val supportedResolutionsCache = ConcurrentHashMap<CAM.CAM_ID_TYPE, List<Pair<Int, Int>>>()

    private var lastTarget: SetEnum.DistTarget ?= null

    private var distMode: SetEnum.DistMode ?= null
    private var distAverageSwitch: SetEnum.DistAverageSwitch ?= null

    // 连续自动对焦相关
    private var continuousAutofocusJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 连续自动对焦状态管理
    private var lastFocusDistance: Double = 0.0  // 上次对焦的距离
    private val focusThreshold: Double = 0.5     // 距离变化阈值（米），超过此值才重新对焦

    private val screenRecordManager by lazy {
        ScreenRecordManager(cameraManager) { cameraType -> CAM.getImageDir(cameraType) }
    }

    // ========================= 工具 =========================

    /**
     * 当前 Binder 调用方的唯一标识。
     */
    private val clientId: Long
        get() = Binder.getCallingUid().toLong() * 100_000L + Binder.getCallingPid().toLong()

    // ========================= 相机控制 =========================

    override fun openCamera(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val cid = clientId
        if (!registry.registerCamera(cid, cameraType)) {
            Log.i(TAG, "openCamera: cid=$cid 已打开 $cameraType，忽略重复调用")
            return responseWithGRCOk()
        }
        if (!cameraManager.openSession(CAM.getCameraPid(cameraType))) {
            registry.rollbackCamera(cid, cameraType)
            Log.e(TAG, "openCamera 失败: cid=$cid, cameraType=$cameraType")
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }
        supportedResolutionsCache.remove(cameraType)

        // 注册断连监听，USB 物理拔出时自动停录像
        cameraManager.addDisconnectObserver(CAM.getCameraPid(cameraType)) {
            screenRecordManager.releaseForCamera(CAM.getCameraPid(cameraType), cameraType)
        }
        Log.i(TAG, "openCamera 成功: cid=$cid, cameraType=$cameraType")
        return responseWithGRCOk()
    }

    override fun closeCamera(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val cid = clientId
        Log.d(TAG, "closeCamera: $cameraType")
        val pid = CAM.getCameraPid(cameraType)
        screenRecordManager.releaseForCamera(pid, cameraType)
        when (registry.unregisterCamera(cid, cameraType)) {
            CameraSessionRegistry.UnregisterResult.NOT_FOUND -> {
                Log.w(TAG, "closeCamera: cid=$cid 未打开 $cameraType，忽略")
                return responseWithGRCOk()
            }
            CameraSessionRegistry.UnregisterResult.NEED_STOP_MEDIA -> {
                MediaStreamService.stopStreamSync(cameraType)
                cameraManager.closeSession(pid) // 归还 media 持有的引用
                Log.i(TAG, "closeCamera: 已停止推流并释放 media 引用, cameraType=$cameraType")
            }
            CameraSessionRegistry.UnregisterResult.OK -> Unit
        }
        supportedResolutionsCache.remove(cameraType)
        doStopPreview(cid, cameraType)
        cameraManager.closeSession(pid) // 归还客户端自身持有的引用
        Log.i(TAG, "closeCamera 完成: cid=$cid, cameraType=$cameraType")
        return responseWithGRCOk()
    }

    // ========================= 预览控制 =========================

    override fun startPreview(cameraType: CAM.CAM_ID_TYPE, surface: Surface?): Response<Void> {
        val cid = clientId
        val pid = CAM.getCameraPid(cameraType)

        if (surface == null || !surface.isValid) {
            Log.w(TAG, "startPreview 失败：Surface 无效, cid=$cid")
            return responseWithGRCode(GRCode.GRC_NOTOK)
        }
        if (!registry.isOwner(cid, cameraType)) {
            Log.w(TAG, "startPreview 失败：cid=$cid 未调用 openCamera($cameraType)")
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }
        if (!cameraManager.isSessionOpen(pid)) {
            Log.w(TAG, "startPreview 失败：Session 未打开, cid=$cid")
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }

        val previewKey = CameraSessionRegistry.PreviewKey(cid, cameraType)
        registry.registerPreview(cid, cameraType, surface)
        cameraManager.addPreviewSurface(pid, previewKey.toString(), surface)

        Log.i(TAG, "startPreview 成功: cid=$cid, cameraType=$cameraType")
        return responseWithGRCOk()
    }

    override fun stopPreview(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val ctx = requireCameraContext(cameraType) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        screenRecordManager.releaseForCamera(ctx.pid, cameraType)
        doStopPreview(ctx.cid, ctx.cameraType)
        Log.i(TAG, "stopPreview 完成: cid=${ctx.cid}, cameraType=$cameraType")
        return responseWithGRCOk()
    }

    // ========================= 状态查询 =========================

    override fun isCameraOpened(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val isOpen = cameraManager.isSessionOpen(CAM.getCameraPid(cameraType))
        return if (isOpen) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun isPreviewing(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val surface = registry.getPreview(clientId, cameraType)
        return if (surface != null && surface.isValid) responseWithGRCOk()
        else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun getSupportedResolutions(cameraType: CAM.CAM_ID_TYPE): Response<List<String>> {
        val ctx = requireCameraContext(cameraType) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        val sizes = cameraManager.getSupportedSizes(ctx.pid).map { "${it.width}x${it.height}" }
        return responseWithGRCOk(sizes)
    }

    override fun setResolution(cameraType: CAM.CAM_ID_TYPE, resolution: String): Response<Void> {
        val (width, height) = parseResolution(resolution) ?: run {
            Log.w(TAG, "setResolution 失败：格式错误或无效数值, resolution=$resolution")
            return responseWithGRCode(GRCode.GRC_IVPARAM)
        }
        val ctx = requireCameraContext(cameraType) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        val supportedSizes = supportedResolutionsCache.getOrPut(cameraType) {
            cameraManager.getSupportedSizes(ctx.pid).map { it.width to it.height }
        }
        if (supportedSizes.none { it.first == width && it.second == height }) {
            Log.w(TAG, "setResolution 失败：不支持的分辨率 ${width}x${height}")
            return responseWithGRCode(GRCode.GRC_IVPARAM)
        }
        val success = cameraManager.resetPreview(ctx.pid, width, height)
        Log.d(TAG, "setResolution $cameraType ${width}x${height} -> success=$success")
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    // ========================= 变焦 =========================

    override fun setZoom(cameraType: CAM.CAM_ID_TYPE, zoomFactor: Int): Response<Void> {
        if (!CAM.isValidZoomFactor(zoomFactor)) return responseWithGRCode(GRCode.GRC_IVPARAM)
        val ctx = requireCameraContext(cameraType) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        val success = cameraManager.setZoomScale(ctx.pid, zoomFactor)
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun getZoom(cameraType: CAM.CAM_ID_TYPE): Response<Int> {
        val ctx = requireCameraContext(cameraType) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        val zoom = cameraManager.getZoomScale(ctx.pid)
        return if (zoom != null) responseWithGRCOk(zoom) else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    // ========================= FoV =========================

    override fun getCameraFoV(cameraType: CAM.CAM_ID_TYPE, zoomFactor: Int): Response<CAM.FoVHzBean> {
        if (!CAM.isValidZoomFactor(zoomFactor)) return responseWithGRCode(GRCode.GRC_NOTOK)
        val bean = CAM.FoVHzBean().apply {
            rFoVHz = CAM.calcZoomedFoV(CAM.getFovH(cameraType), zoomFactor)
            rFoVV = CAM.calcZoomedFoV(CAM.getFovV(cameraType), zoomFactor)
        }
        Log.d(TAG, "getCameraFoV $cameraType ${zoomFactor}x -> H=${bean.rFoVHz} V=${bean.rFoVV} rad")
        return responseWithGRCOk(bean)
    }

    // ========================= 图像采集 =========================

    override fun setActualImageName(
        cameraType: CAM.CAM_ID_TYPE,
        szName: String?,
        iNumber: Int,
    ): Response<Void> {
        imageNameConfigs[cameraType] = CAM.ImageNameConfig(szName, iNumber)
        Log.i(TAG, "setActualImageName: $cameraType, szName=$szName, iNumber=$iNumber")
        return responseWithGRCOk()
    }

    override fun takeImage(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val pid = CAM.getCameraPid(cameraType)
        if (!cameraManager.isSessionOpen(pid)) {
            Log.w(TAG, "takeImage 失败：相机未打开, cameraType=$cameraType")
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }
        // 截帧（耗时，锁外执行）
        val captured = cameraManager.captureFrame(pid) ?: run {
            Log.e(TAG, "takeImage: 截帧失败")
            return responseWithGRCode(GRCode.GRC_NOTOK)
        }
        // 文件 IO（耗时，锁外执行）
        val dir = CAM.getImageDir(cameraType).also { if (!it.exists()) it.mkdirs() }
        val config = imageNameConfigs[cameraType]
        val fileName = CAM.resolveImageName(config)
        return try {
            val file = File(dir, "$fileName.jpg")
            FileOutputStream(file).use { fos ->
                YuvImage(captured.data, ImageFormat.NV21, captured.width, captured.height, null)
                    .compressToJpeg(Rect(0, 0, captured.width, captured.height), 90, fos)
            }
            Log.i(TAG, "takeImage: 保存成功 -> ${file.absolutePath}")
            if (config != null && config.iNumber > 0) {
                imageNameConfigs[cameraType] = config.copy(config.iNumber + 1)
            }
            responseWithGRCOk()
        } catch (e: Exception) {
            Log.e(TAG, "takeImage: 保存失败", e)
            responseWithGRCode(GRCode.GRC_NOTOK)
        }
    }

    override fun screenRecord(cameraType: CAM.CAM_ID_TYPE, bStart: Boolean): Response<Void> {
        val ctx = requireCameraContext(cameraType) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        return if (bStart) {
            val resolution = cameraManager.getCurrentResolutionAndZoom(ctx.pid)
            val w = resolution?.get(0) ?: 1280
            val h = resolution?.get(1) ?: 720
            if (screenRecordManager.start(ctx.pid, w, h, cameraType)) responseWithGRCOk()
            else responseWithGRCode(GRCode.GRC_NOTOK)
        } else {
            screenRecordManager.stop(ctx.pid, cameraType)
            responseWithGRCOk()
        }
    }

    // ========================= 白平衡 =========================

    override fun setWhiteBalanceMode(cameraType: CAM.CAM_ID_TYPE, mode: Int): Response<Void> {
        val ctx = requireCameraContext(cameraType) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        val success = cameraManager.setWhiteBalanceMode(ctx.pid, mode)
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    // ========================= 设备就绪 / 电源 =========================

    override fun isCameraReady(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        return if (cameraManager.isCameraReady(CAM.getCameraPid(cameraType))) {
            responseWithGRCOk()
        } else {
            Log.w(TAG, "isCameraReady: $cameraType 不可用")
            responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }
    }

    override fun setCameraPowerSwitch(cameraType: CAM.CAM_ID_TYPE, state: Int): Response<Void> {
        val (deviceType, isPowerOff) = when (cameraType) {
            CAM.CAM_ID_TYPE.OVC -> EnumCommons.DeviceType.kDeviceDiffImage to (state != 0)
            else -> EnumCommons.DeviceType.kDeviceCoaxialImage to (state != 0)
        }
        if (isPowerOff) {
            releaseAllClientsForCamera(cameraType)
            shareProvider.deviceManage.powerOff(deviceType)
        } else {
            shareProvider.deviceManage.powerOn(deviceType)
        }
        return responseWithGRCOk()
    }

    override fun getCameraPowerSwitch(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val pid = CAM.getCameraPid(cameraType)
        return if (cameraManager.isDevicePresent(pid)) {
            responseWithGRCOk()
        } else {
            Log.w(TAG, "getCameraPowerSwitch: USB 设备不存在, cameraType=$cameraType")
            responseWithGRCode(GRCode.GRC_NOTOK)
        }
    }

    // ========================= 媒体推流 =========================

    override fun startRemoteVideo(
        cameraType: CAM.CAM_ID_TYPE,
        isPublic: Boolean,
        address: String,
        port: Int,
    ): Response<Void> {
        val cid = clientId
        val pid = CAM.getCameraPid(cameraType)

        // 停止旧推流并归还其引用（若存在）
        if (registry.unregisterMedia(cameraType)) {
            MediaStreamService.stopStreamSync(cameraType)
            cameraManager.closeSession(pid)
        }

        // 推流需要独立持有一个 Session 引用
        if (!cameraManager.openSession(pid)) {
            Log.e(TAG, "startRemoteVideo: 相机打开失败, cameraType=$cameraType")
            return responseWithGRCode(GRCode.GRC_NOTOK)
        }

        // 如果客户端尚未持有此相机，需要为其额外打开一个引用（隐式注册）
        if (!registry.isOwner(cid, cameraType)) {
            if (!cameraManager.openSession(pid)) {
                Log.e(TAG, "startRemoteVideo: 隐式注册相机失败, cid=$cid, cameraType=$cameraType")
                cameraManager.closeSession(pid) // 回滚推流的引用
                return responseWithGRCode(GRCode.GRC_NOTOK)
            }
            registry.registerCamera(cid, cameraType)
        }

        registry.registerMedia(cid, cameraType)

        // Intent 发送耗时，锁外执行
        val configBean = WebSocketDeviceBean(
            ip_address = address,
            port = port,
            device_name = factoryPara.instrumentSerial,
            rssi = "",
            public = isPublic
        )
        val intent = Intent(shareProvider.application, MediaStreamService::class.java).apply {
            action = MediaStreamService.ACTION_START
            putExtra(MediaStreamService.EXTRA_CAMERA_TYPE, cameraType.name)
            putExtra(MediaStreamService.EXTRA_STREAM_CONFIG, Gson().toJson(configBean))
        }
        shareProvider.application.startService(intent)
        return responseWithGRCOk()
    }

    override fun stopRemoteVideo(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        doStopMedia(cameraType)
        return responseWithGRCOk()
    }

    override fun stopAllMedia(): Response<Void> {
        registry.unregisterAllMedia().forEach { cameraType ->
            MediaStreamService.stopStreamSync(cameraType)
            cameraManager.closeSession(CAM.getCameraPid(cameraType))
        }
        return responseWithGRCOk()
    }

    override fun waitForCameraReady(cameraType: CAM.CAM_ID_TYPE, ulTimeout: Long): Response<Void> {
        val cid = clientId
        val pid = CAM.getCameraPid(cameraType)
        Log.d(TAG, "waitForCameraReady: cid=$cid, cameraType=$cameraType, timeout=${ulTimeout}ms")

        val startTime = System.currentTimeMillis()
        val deadline = startTime + ulTimeout
        var opened = false

        while (System.currentTimeMillis() < deadline) {
            if (cameraManager.isSessionOpen(pid) || cameraManager.openSession(pid)) {
                opened = true
                break
            }
            Thread.sleep(50)
        }

        if (!opened) {
            Log.w(TAG, "waitForCameraReady 超时: $cameraType, timeout=${ulTimeout}ms")
            return responseWithGRCode(GRCode.GRC_TIME_OUT)
        }

        registry.registerCamera(cid, cameraType)
        supportedResolutionsCache.remove(cameraType)
        Log.i(TAG, "waitForCameraReady 成功: cid=$cid, cameraType=$cameraType, 耗时=${System.currentTimeMillis() - startTime}ms")
        return responseWithGRCOk()
    }

    // ========================= 电机 / 对焦 =========================

    override fun setMotorPosition(motorPosition: Long): Response<Void> {
        val ctx = requireCameraContext(CAM.CAM_ID_TYPE.OAC) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        val success = cameraManager.setCommonOrder(
            ctx.pid, UvcCmdConstant.MIRROR_BARREL_POSITION, motorPosition.toInt()
        )
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun getMotorPosition(): Response<Long> {
        val ctx = requireCameraContext(CAM.CAM_ID_TYPE.OAC) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        val success = cameraManager.getCommonOrder(ctx.pid, UvcCmdConstant.MIRROR_BARREL_POSITION)
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun positFocusMotorToDist(dis: Double): Response<Void> {
        if (dis < 1.5) return responseWithGRCode(GRCode.GRC_NOTOK)
        val ctx = requireCameraContext(CAM.CAM_ID_TYPE.OAC) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        val base = factoryPara.infinityPosition ?: 4700
        val offset = when (dis) {
            in 0.0..2.0   -> 20000
            in 2.0..2.5   -> 16000
            in 2.5..5.0   -> 12000
            in 5.0..10.0  -> 6000
            in 10.0..30.0 -> 3000
            else          -> 820
        }
        val success = cameraManager.setCommonOrder(
            ctx.pid, UvcCmdConstant.MIRROR_BARREL_POSITION, base.toInt() + offset
        )
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun positFocusMotorToInfinity(): Response<Void> {
        val ctx = requireCameraContext(CAM.CAM_ID_TYPE.OAC) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        val target = 820 + (factoryPara.infinityPosition ?: 4700).toInt()
        val success = cameraManager.setCommonOrder(
            ctx.pid, UvcCmdConstant.THEORETICAL_FOCAL_POSITION, target
        )
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun continuousAutofocus(bStart: Boolean): Response<Void> {
        val cid = clientId
        requireCameraContext(CAM.CAM_ID_TYPE.OAC) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        if (bStart){
            // 启动连续自动对焦
            if (continuousAutofocusJob?.isActive == true) {
                Log.w(TAG, "continuousAutofocus: 已经在运行中")
                return responseWithGRCOk()
            }

            // 设置合作目标
            val distCoordParaProxy = sp.distCoordPara
            lastTarget = distCoordParaProxy.distTarget.data
            val res = distCoordParaProxy.setDistTarget(SetEnum.DistTarget.DIST_TARGET_NO_PRISM)
            if (!res) {
                return responseWithGRCode(GRCode.GRC_NOTOK)
            }

            // 设置测量模式
            distMode = sp.distCoordPara.distMode.data
            distAverageSwitch = sp.distCoordPara.distAverageSwitch.data
            sp.distCoordPara.setDistMode(SetEnum.DistMode.DIST_MODE_SERIES)
            sp.distCoordPara.setDistAverageSwitch(SetEnum.DistAverageSwitch.DIST_AVERAGE_SWITCH_OFF)

            // 启动测量命令
            distCorrectConfig.update()
            val builder = createTask()
                .ifWhen { isEdmSinglePrismMode(sp) }
                .pack(ServoStartDebugPsAtr(ServoDef.PS_ATR_MODE5))
                .ifClose()
                .pack(EdmStartCommand(distCorrectConfig, false))

            val startResponse = builder.responseByCall()
            if (!startResponse.isSuccess) {
                Log.e(TAG, "continuousAutofocus: 启动测量失败")
                restoreDistSettings()
                return responseWithGRCode(GRCode.GRC_NOTOK)
            }

            lastFocusDistance = 0.0

            continuousAutofocusJob = serviceScope.launch {
                Log.i(TAG, "continuousAutofocus: 开始连续读取测量数据，阈值=${focusThreshold}m")
                while (isActive) {
                    try {
                        val task = createTask()
                        val disResponse = task.pack(ReadDisCommand(timeout = 5000)).responseByCall()

                        if (disResponse.isSuccess) {
                            val currentDis = (disResponse.data.mDistance / 10000).toDouble()
                            Log.d(TAG, "continuousAutofocus: 当前距离 = ${currentDis}m")

                            // 判断是否需要重新对焦
                            if (shouldRefocus(currentDis)) {
                                Log.i(TAG, "continuousAutofocus: 距离变化超过阈值，执行对焦: ${lastFocusDistance}m -> ${currentDis}m")
                                val focusResult = positFocusMotorToDistInternal(cid, currentDis)
                                if (focusResult) {
                                    lastFocusDistance = currentDis
                                    Log.i(TAG, "continuousAutofocus: 对焦成功，更新基准距离为 ${currentDis}m")
                                } else {
                                    Log.w(TAG, "continuousAutofocus: 对焦失败，保持原基准距离 ${lastFocusDistance}m")
                                }
                            } else {
                                Log.d(TAG, "continuousAutofocus: 距离变化未超阈值，跳过对焦 (差值=${Math.abs(currentDis - lastFocusDistance)}m)")
                            }
                        } else {
                            Log.w(TAG, "continuousAutofocus: 读取失败, errorCode=${disResponse.errorCode}")
                        }
                        // 控制读取频率（200ms）
                        delay(200)
                    } catch (e: CancellationException) {
                        Log.i(TAG, "continuousAutofocus: 协程被取消")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "continuousAutofocus: 读取异常", e)
                        break
                    }
                }
                Log.i(TAG, "continuousAutofocus: 连续读取协程退出")
            }

            return responseWithGRCOk()
        }else{
            // 停止连续自动对焦
            if (continuousAutofocusJob?.isActive != true) {
                Log.w(TAG, "continuousAutofocus: 未在运行中")
                return responseWithGRCOk()
            }

            // 取消协程
            continuousAutofocusJob?.cancel()
            continuousAutofocusJob = null

            // 恢复原始设置
            restoreDistSettings()

            // 停止测量
            shareProvider.bindValue(ProviderKey.SERVO_HAS_STOP, false)
            val builder = createTask().pack(MeaStopCommand())
            val stopResponse = builder.responseByCall()

            return if (stopResponse.isSuccess) {
                Log.i(TAG, "continuousAutofocus: 已停止")
                responseWithGRCOk()
            } else {
                Log.e(TAG, "continuousAutofocus: 停止失败")
                responseWithGRCode(GRCode.GRC_NOTOK)
            }
        }
    }

    override fun singleShotAutofocus(): Response<Boolean> {
        requireCameraContext(CAM.CAM_ID_TYPE.OAC) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        // 启动测量命令
        try {
            distCorrectConfig.update()
            val builder = createTask()
                .ifWhen { isEdmSinglePrismMode(sp) }
                .pack(ServoStartDebugPsAtr(ServoDef.PS_ATR_MODE5))
                .ifClose()
                .pack(EdmStartCommand(distCorrectConfig, false))

            val startResponse = builder.responseByCall()
            if (!startResponse.isSuccess) {
                Log.e(TAG, "continuousAutofocus: 启动测量失败")
                restoreDistSettings()
                return responseWithGRCode(GRCode.GRC_NOTOK)
            }

            val task = createTask()
            val disResponse = task.pack(ReadDisCommand(timeout = 5000)).responseByCall()
            var angleResponse = task.pack(ReadAngleCommand()).ifWhen { sp.anglePara.tiltSwitch.data != SetEnum.TiltSwitch.TILT_SWITCH_OFF }
                .pack(ReadTiltCommand(true)).pack(TiltCorrectCommand()).ifClose()
                .ifWhen { sp.psAtrPara.userAtrState.data == SetEnum.NormalSwitch.NORMAL_SWITCH_ON }.pack(ServoAtrUpdateSearchRt()).ifClose()
                .ifWhen { sp.psAtrPara.userLockState.data == SetEnum.NormalSwitch.NORMAL_SWITCH_ON }.pack(ServoReadLockDataCommand()).ifClose()
                .pack(AngleCorrectCommand(angleConfig, true)).responseByCall()

            if (disResponse.isSuccess){
                val builder = task.pack(EdmGeoCorrectCommand(distCorrectConfig, angleResponse.data)).responseBySafeCall()
                val currentDis = builder.data.sd / 10000
                Log.d(TAG, "continuousAutofocus: 当前距离 = ${currentDis}m")
                if(currentDis < 1.5){
                    return responseWithGRCode(GRCode.GRC_NOTOK)
                }
                val focusResult = positFocusMotorToDistInternal(clientId, currentDis)
                return responseWithGRCOk(focusResult)
            }
            return responseWithGRCode(GRCode.GRC_NOTOK)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.d(TAG, "异常 = ${e.message}m")
            return responseWithGRCode(GRCode.GRC_NOTOK)
        }
    }

    override fun getChipWindowSize(cameraType: CAM.CAM_ID_TYPE): Response<CAM.ChipWindowSize> {
        val ctx = requireCameraContext(cameraType) ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        val resolutionInfo = cameraManager.getCurrentResolutionAndZoom(ctx.pid)
            ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)

        val width = resolutionInfo[0]
        val height = resolutionInfo[1]
        val zoomFactor = resolutionInfo[2]

        val chipWindow = CAM.ChipWindowSize().apply {
            dX = width.toDouble() / zoomFactor
            dY = height.toDouble() / zoomFactor
        }
        Log.d(TAG, "getChipWindowSize: $cameraType, resolution=${width}x${height}, zoom=${zoomFactor}x, chipWindow=${chipWindow.dX}x${chipWindow.dY}")
        return responseWithGRCOk(chipWindow)
    }

    // ========================= 私有工具 =========================

    /**
     * 解析 "宽x高" 格式字符串；格式错误或数值非法时返回 null。
     */
    private fun parseResolution(resolution: String): Pair<Int, Int>? {
        val parts = resolution.split("x")
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val h = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return w to h
    }

    /**
     * 停止指定客户端对某相机的预览：注销 Surface 记录并通知 Manager。
     * [closeCamera] 和 [stopPreview] 共用，保证逻辑一致。
     */
    private fun doStopPreview(cid: Long, cameraType: CAM.CAM_ID_TYPE) {
        val removed = registry.unregisterPreview(cid, cameraType)
        if (removed != null) {
            val key = CameraSessionRegistry.PreviewKey(cid, cameraType).toString()
            cameraManager.removePreviewSurface(CAM.getCameraPid(cameraType), key)
        }
    }

    /**
     * 强制释放某相机的所有客户端：清理 registry、通知 Manager 批量移除 Surface 并强制关闭 Session。
     * 通常在断电前调用。
     */
    private fun releaseAllClientsForCamera(cameraType: CAM.CAM_ID_TYPE) {
        val camNum = CAM.getCameraPid(cameraType)
        screenRecordManager.releaseForCamera(camNum, cameraType)
        doStopMedia(cameraType)
        val previewKeys = registry.releaseAllForCamera(cameraType)
        cameraManager.removeAllPreviewSurfaces(camNum, previewKeys.map { it.toString() })
        cameraManager.forceCloseSession(camNum)
        Log.i(TAG, "releaseAllClientsForCamera: $cameraType 所有客户端已释放")
    }

    /**
     * 停止推流 Service 并归还 media 持有的引用计数。
     */
    private fun doStopMedia(cameraType: CAM.CAM_ID_TYPE) {
        if (registry.unregisterMedia(cameraType)) {
            MediaStreamService.stopStreamSync(cameraType)
            cameraManager.closeSession(CAM.getCameraPid(cameraType))
            Log.i(TAG, "doStopMedia: $cameraType 推流已停止")
        }
    }

    /**
     * 校验当前客户端是否持有指定相机，并返回操作上下文。
     * 任一条件不满足时返回 null，调用方直接返回 [GRCode.GRC_CAM_NOT_READY]。
     */
    private fun requireCameraContext(cameraType: CAM.CAM_ID_TYPE): CameraContext? {
        val cid = clientId
        val pid = CAM.getCameraPid(cameraType)
        if (!registry.isOwner(cid, cameraType)) {
            Log.w(TAG, "requireCameraContext: cid=$cid 未持有 $cameraType")
            return null
        }
        if (!cameraManager.isSessionOpen(pid)) {
            Log.e(TAG, "requireCameraContext: Session 未打开，但 registry 状态已记录, cameraType=$cameraType")
            return null
        }
        return CameraContext(cid, pid, cameraType)
    }

    /**
     * 恢复距离测量的原始设置
     */
    private fun restoreDistSettings() {
        val distCoordParaProxy = sp.distCoordPara
        lastTarget?.let {
            distCoordParaProxy.setDistTarget(it)
            lastTarget = null
        }
        distMode?.let {
            distCoordParaProxy.setDistMode(it)
            distMode = null
        }
        distAverageSwitch?.let {
            distCoordParaProxy.setDistAverageSwitch(it)
            distAverageSwitch = null
        }
    }

    /**
     * 判断是否需要重新对焦
     * @param currentDis 当前测量距离
     * @return true 需要重新对焦，false 不需要
     */
    private fun shouldRefocus(currentDis: Double): Boolean {
        // 首次对焦或距离无效
        if (lastFocusDistance <= 0) {
            return currentDis >= 1.5  // 最小有效距离
        }

        // 计算距离差值的绝对值
        val distanceDiff = Math.abs(currentDis - lastFocusDistance)

        // 超过阈值才需要重新对焦
        return distanceDiff >= focusThreshold
    }

    /**
     * 内部方法：执行对焦电机控制（不返回 Response，方便协程内调用）
     * @param dis 目标距离
     * @return true 成功，false 失败
     */
    private fun positFocusMotorToDistInternal(cid: Long, dis: Double): Boolean {
        if (dis < 1.5) {
            Log.w(TAG, "positFocusMotorToDistInternal: 距离过小 ($dis)，无法对焦")
            return false
        }

        val pid = CAM.getCameraPid(CAM.CAM_ID_TYPE.OAC)
        if (!registry.isOwner(cid, CAM.CAM_ID_TYPE.OAC) || !cameraManager.isSessionOpen(pid)) {
            Log.e(TAG, "positFocusMotorToDistInternal: 相机上下文无效")
            return false
        }

        val ctx = CameraContext(cid, pid, CAM.CAM_ID_TYPE.OAC)
//        val base = factoryPara.infinityPosition ?: 4700
        val base = 4437
//        val offset = when (dis) {
//            in 0.0..2.0   -> 20000
//            in 2.0..2.5   -> 16000
//            in 2.5..5.0   -> 12000
//            in 5.0..10.0  -> 6000
//            in 10.0..30.0 -> 3000
//            else          -> 820
//        }
//
//        val targetPosition = base.toInt() + offset
//        Log.d(TAG, "targetPosition: $targetPosition")
//        val success = cameraManager.setCommonOrder(
//            ctx.camNum, UvcCmdConstant.THEORETICAL_FOCAL_POSITION, targetPosition
//        )


        val temp = 28.85.div(dis)
        var L = if(dis >= 20){
            (temp.times(0.8416).plus(temp.times(temp).times(0.0066)).minus(0.0025.times(sqrt(temp)))).times(1000)
        }else{
            val temp2 = temp.minus(1.4470)
            (0.8496.times(temp2).plus(0.0190.times(temp2).times(temp2)).plus(1.2540)).times(1000)
        }
        L += base

        Log.d(TAG, "targetPosition: $L")
        val success = cameraManager.setCommonOrder(
            ctx.pid, UvcCmdConstant.THEORETICAL_FOCAL_POSITION, L.toInt()
        )

        if (success) {
            Log.d(TAG, "positFocusMotorToDistInternal: 对焦成功, dis=${dis}m, position=$L")
        } else {
            Log.e(TAG, "positFocusMotorToDistInternal: 对焦失败, dis=${dis}m")
        }

        return success
    }
}