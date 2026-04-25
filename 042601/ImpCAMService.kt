package com.ruide.service.middleware.impl

import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Binder
import android.util.Log
import android.view.Surface
import com.google.gson.Gson
import com.ruide.aidl.bean.Response
import com.ruide.aidl.func.ICAMService
import com.ruide.aidl.para.CAM
import com.ruide.camera.CameraSessionManager
import com.ruide.camera.DisconnectReason
import com.ruide.camera.CameraEvent
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
import com.ruide.service.MediaStreamService
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
 * ICAMService 的 AIDL 实现（精简版）。
 *
 * ## 职责
 * - 请求合法性校验（相机是否打开、Surface 是否有效等）
 * - 委托 [CameraSessionManager] 执行所有相机操作
 * - 不做任何状态编排
 *
 * ## 锁策略
 * - 所有状态由 [CameraSessionManager] 内部管理
 * - 耗时操作（takeImage 写文件、startRemoteVideo 发 Intent）在锁外执行
 */
@com.ruide.service.middleware.annotation.ImpClass(ICAMService::class)
class ImpCAMService(globalShareProvider: GlobalShareProvider) :
    AbstractImpObj(globalShareProvider), ICAMService {

    companion object {
        private const val TAG = "ImpCAMService"
    }

    // ========================= 依赖 =========================

    private val sessionManager: CameraSessionManager by lazy {
        CameraSessionManager.getInstance(shareProvider.application).also {
            it.initScreenRecordManager { cameraType -> CAM.getImageDir(cameraType) }
        }
    }

    // cameraType → 图片命名配置
    private val imageNameConfigs = ConcurrentHashMap<CAM.CAM_ID_TYPE, CAM.ImageNameConfig>()

    // cameraType → 支持的分辨率缓存
    private val supportedResolutionsCache = ConcurrentHashMap<CAM.CAM_ID_TYPE, List<Pair<Int, Int>>>()

    // 连续自动对焦相关
    private var lastTarget: SetEnum.DistTarget? = null
    private var distMode: SetEnum.DistMode? = null
    private var distAverageSwitch: SetEnum.DistAverageSwitch? = null
    private var continuousAutofocusJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastFocusDistance: Double = 0.0
    private val focusThreshold: Double = 0.5

    // ========================= 工具 =========================

    private val clientId: Long
        get() = Binder.getCallingUid().toLong() * 100_000L + Binder.getCallingPid().toLong()

    // ========================= 相机控制 =========================

    override fun openCamera(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val cid = clientId

        // 注册断连监听（USB 物理拔出时自动停录像）
        sessionManager.subscribeEvents(cameraType) { event ->
            when (event) {
                is CameraEvent.Disconnected -> {
                    if (event.reason == DisconnectReason.PHYSICAL_DETACH) {
                        sessionManager.releaseRecording(cameraType)
                    }
                }
                else -> {}
            }
        }

        return when (val result = sessionManager.openSession(cid, cameraType)) {
            is CameraSessionManager.SessionResult.Success,
            is CameraSessionManager.SessionResult.AlreadyOpen -> {
                supportedResolutionsCache.remove(cameraType)
                Log.i(TAG, "openCamera 成功: cid=$cid, cameraType=$cameraType")
                responseWithGRCOk()
            }
            is CameraSessionManager.SessionResult.DeviceNotFound -> {
                Log.e(TAG, "openCamera: 设备未找到, cameraType=$cameraType")
                responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
            }
            is CameraSessionManager.SessionResult.Timeout -> {
                Log.e(TAG, "openCamera: 超时, cameraType=$cameraType")
                responseWithGRCode(GRCode.GRC_TIME_OUT)
            }
            is CameraSessionManager.SessionResult.Error -> {
                Log.e(TAG, "openCamera: 失败 ${result.message}, cameraType=$cameraType")
                responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
            }
            is CameraSessionManager.SessionResult.Ignored -> {
                Log.w(TAG, "openCamera: 结果被忽略, cameraType=$cameraType")
                responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
            }
        }
    }

    override fun closeCamera(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val cid = clientId
        Log.d(TAG, "closeCamera: $cameraType")

        sessionManager.releaseRecording(cameraType)
        doStopMedia(cameraType)
        doStopPreview(cid, cameraType)

        when (val result = sessionManager.closeSession(cid, cameraType)) {
            is CameraSessionManager.CloseResult.FullyReleased -> {
                supportedResolutionsCache.remove(cameraType)
                Log.i(TAG, "closeCamera: 完全释放, cameraType=$cameraType")
            }
            is CameraSessionManager.CloseResult.PartialReleased -> {
                Log.i(TAG, "closeCamera: 部分释放, cameraType=$cameraType")
            }
            is CameraSessionManager.CloseResult.NotOpenedByClient -> {
                Log.w(TAG, "closeCamera: 客户端未持有, cid=$cid, cameraType=$cameraType")
            }
        }

        return responseWithGRCOk()
    }

    // ========================= 预览控制 =========================

    override fun startPreview(cameraType: CAM.CAM_ID_TYPE, surface: Surface?): Response<Void> {
        val cid = clientId

        if (surface == null || !surface.isValid) {
            Log.w(TAG, "startPreview: Surface 无效, cid=$cid")
            return responseWithGRCode(GRCode.GRC_NOTOK)
        }
        if (!sessionManager.isSessionOpen(cameraType)) {
            Log.w(TAG, "startPreview: Session 未打开, cid=$cid")
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }

        val success = sessionManager.bindPreview(cameraType, cid, surface)
        if (success) {
            Log.i(TAG, "startPreview 成功: cid=$cid, cameraType=$cameraType")
            return responseWithGRCOk()
        } else {
            Log.w(TAG, "startPreview 失败: cid=$cid, cameraType=$cameraType")
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }
    }

    override fun stopPreview(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val cid = clientId
        sessionManager.releaseRecording(cameraType)
        doStopPreview(cid, cameraType)
        Log.i(TAG, "stopPreview 完成: cid=$cid, cameraType=$cameraType")
        return responseWithGRCOk()
    }

    // ========================= 状态查询 =========================

    override fun isCameraOpened(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        return if (sessionManager.isSessionOpen(cameraType)) responseWithGRCOk()
        else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun isPreviewing(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val surface = sessionManager.getPreview(cameraType, clientId)
        return if (surface != null && surface.isValid) responseWithGRCOk()
        else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun getSupportedResolutions(cameraType: CAM.CAM_ID_TYPE): Response<List<String>> {
        val sizes = sessionManager.getSupportedResolutions(cameraType)
            .map { "${it.width}x${it.height}" }
        return if (sizes.isEmpty()) responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        else responseWithGRCOk(sizes)
    }

    override fun setResolution(cameraType: CAM.CAM_ID_TYPE, resolution: String): Response<Void> {
        val (width, height) = parseResolution(resolution) ?: run {
            Log.w(TAG, "setResolution: 格式错误, resolution=$resolution")
            return responseWithGRCode(GRCode.GRC_IVPARAM)
        }
        val success = sessionManager.setResolution(cameraType, width, height)
        Log.d(TAG, "setResolution $cameraType ${width}x${height} -> success=$success")
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    // ========================= 变焦 =========================

    override fun setZoom(cameraType: CAM.CAM_ID_TYPE, zoomFactor: Int): Response<Void> {
        if (!CAM.isValidZoomFactor(zoomFactor)) return responseWithGRCode(GRCode.GRC_IVPARAM)
        val success = sessionManager.setZoom(cameraType, zoomFactor)
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun getZoom(cameraType: CAM.CAM_ID_TYPE): Response<Int> {
        val zoom = sessionManager.getZoom(cameraType)
        return if (zoom != null) responseWithGRCOk(zoom) else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    // ========================= FoV =========================

    override fun getCameraFoV(
        cameraType: CAM.CAM_ID_TYPE,
        zoomFactor: Int
    ): Response<CAM.FoVHzBean> {
        if (!CAM.isValidZoomFactor(zoomFactor)) return responseWithGRCode(GRCode.GRC_NOTOK)
        val bean = CAM.FoVHzBean().apply {
            rFoVHz = CAM.calcZoomedFoV(CAM.getFovH(cameraType), zoomFactor)
            rFoVV = CAM.calcZoomedFoV(CAM.getFovV(cameraType), zoomFactor)
        }
        return responseWithGRCOk(bean)
    }

    // ========================= 图像采集 =========================

    override fun setActualImageName(
        cameraType: CAM.CAM_ID_TYPE,
        szName: String?,
        iNumber: Int
    ): Response<Void> {
        imageNameConfigs[cameraType] = CAM.ImageNameConfig(szName, iNumber)
        return responseWithGRCOk()
    }

    override fun takeImage(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        if (!sessionManager.isSessionOpen(cameraType)) {
            Log.w(TAG, "takeImage: 相机未打开, cameraType=$cameraType")
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }

        val captured = sessionManager.captureFrame(cameraType)
        if (captured == null) {
            Log.e(TAG, "takeImage: 截帧失败")
            return responseWithGRCode(GRCode.GRC_NOTOK)
        }

        val dir = CAM.getImageDir(cameraType).also { if (!it.exists()) it.mkdirs() }
        val config = imageNameConfigs[cameraType]
        val fileName = CAM.resolveImageName(config)

        return try {
            val file = File(dir, "$fileName.jpg")
            FileOutputStream(file).use { fos ->
                YuvImage(
                    captured.data,
                    ImageFormat.NV21,
                    captured.width,
                    captured.height,
                    null
                ).compressToJpeg(
                    Rect(0, 0, captured.width, captured.height),
                    90,
                    fos
                )
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
        return if (bStart) {
            if (sessionManager.startRecording(cameraType)) responseWithGRCOk()
            else responseWithGRCode(GRCode.GRC_NOTOK)
        } else {
            sessionManager.stopRecording(cameraType)
            responseWithGRCOk()
        }
    }

    // ========================= 白平衡 =========================

    override fun setWhiteBalanceMode(cameraType: CAM.CAM_ID_TYPE, mode: Int): Response<Void> {
        val success = sessionManager.setWhiteBalance(cameraType, mode)
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    // ========================= 设备就绪 / 电源 =========================

    override fun isCameraReady(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        return if (sessionManager.isCameraReady(cameraType)) {
            responseWithGRCOk()
        } else {
            Log.w(TAG, "isCameraReady: $cameraType 不可用")
            responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }
    }

    override fun setCameraPowerSwitch(
        cameraType: CAM.CAM_ID_TYPE,
        state: Int
    ): Response<Void> {
        val (deviceType, isPowerOff) = when (cameraType) {
            CAM.CAM_ID_TYPE.OVC -> EnumCommons.DeviceType.kDeviceDiffImage to (state != 0)
            else -> EnumCommons.DeviceType.kDeviceCoaxialImage to (state != 0)
        }
        if (isPowerOff) {
            releaseAllForCamera(cameraType)
            shareProvider.deviceManage.powerOff(deviceType)
        } else {
            shareProvider.deviceManage.powerOn(deviceType)
        }
        return responseWithGRCOk()
    }

    override fun getCameraPowerSwitch(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        return if (sessionManager.isDevicePresent(cameraType)) {
            responseWithGRCOk()
        } else {
            responseWithGRCode(GRCode.GRC_NOTOK)
        }
    }

    // ========================= 媒体推流 =========================

    override fun startRemoteVideo(
        cameraType: CAM.CAM_ID_TYPE,
        isPublic: Boolean,
        address: String,
        port: Int
    ): Response<Void> {
        val cid = clientId

        // 停止旧推流
        doStopMedia(cameraType)

        // 如果客户端尚未持有此相机，隐式注册
        if (!sessionManager.isSessionOpen(cameraType)) {
            if (sessionManager.openSession(cid, cameraType) !is CameraSessionManager.SessionResult.Success &&
                sessionManager.openSession(cid, cameraType) !is CameraSessionManager.SessionResult.AlreadyOpen
            ) {
                Log.e(TAG, "startRemoteVideo: 隐式注册相机失败, cid=$cid, cameraType=$cameraType")
                return responseWithGRCode(GRCode.GRC_NOTOK)
            }
        }

        // 注册推流归属
        sessionManager.registerMedia(cid, cameraType)

        // 发 Intent（锁外）
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
        sessionManager.unregisterAllMedia().forEach { cameraType ->
            MediaStreamService.stopStreamSync(cameraType)
            sessionManager.closeSession(clientId, cameraType)
        }
        return responseWithGRCOk()
    }

    override fun waitForCameraReady(
        cameraType: CAM.CAM_ID_TYPE,
        ulTimeout: Long
    ): Response<Void> {
        val cid = clientId
        val deadline = System.currentTimeMillis() + ulTimeout

        while (System.currentTimeMillis() < deadline) {
            val result = sessionManager.openSession(cid, cameraType)
            when (result) {
                is CameraSessionManager.SessionResult.Success,
                is CameraSessionManager.SessionResult.AlreadyOpen -> {
                    supportedResolutionsCache.remove(cameraType)
                    return responseWithGRCOk()
                }
                else -> {
                    Thread.sleep(50)
                }
            }
        }

        Log.w(TAG, "waitForCameraReady 超时: $cameraType")
        return responseWithGRCode(GRCode.GRC_TIME_OUT)
    }

    // ========================= 电机 / 对焦 =========================

    override fun setMotorPosition(motorPosition: Long): Response<Void> {
        val success = sessionManager.setCommonOrder(
            CAM.CAM_ID_TYPE.OAC,
            UvcCmdConstant.MIRROR_BARREL_POSITION,
            motorPosition.toInt()
        )
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun getMotorPosition(): Response<Long> {
        val success = sessionManager.getCommonOrder(
            CAM.CAM_ID_TYPE.OAC,
            UvcCmdConstant.MIRROR_BARREL_POSITION
        )
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun positFocusMotorToDist(dis: Double): Response<Void> {
        if (dis < 1.5) return responseWithGRCode(GRCode.GRC_NOTOK)
        val base = factoryPara.infinityPosition ?: 4700
        val offset = when (dis) {
            in 0.0..2.0 -> 20000
            in 2.0..2.5 -> 16000
            in 2.5..5.0 -> 12000
            in 5.0..10.0 -> 6000
            in 10.0..30.0 -> 3000
            else -> 820
        }
        val success = sessionManager.setCommonOrder(
            CAM.CAM_ID_TYPE.OAC,
            UvcCmdConstant.MIRROR_BARREL_POSITION,
            base.toInt() + offset
        )
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun positFocusMotorToInfinity(): Response<Void> {
        val target = 820 + (factoryPara.infinityPosition ?: 4700).toInt()
        val success = sessionManager.setCommonOrder(
            CAM.CAM_ID_TYPE.OAC,
            UvcCmdConstant.THEORETICAL_FOCAL_POSITION,
            target
        )
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun continuousAutofocus(bStart: Boolean): Response<Void> {
        val cid = clientId
        if (!sessionManager.isSessionOpen(CAM.CAM_ID_TYPE.OAC)) {
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }

        if (bStart) {
            if (continuousAutofocusJob?.isActive == true) {
                Log.w(TAG, "continuousAutofocus: 已经在运行中")
                return responseWithGRCOk()
            }

            val distCoordParaProxy = sp.distCoordPara
            lastTarget = distCoordParaProxy.distTarget.data
            if (!distCoordParaProxy.setDistTarget(SetEnum.DistTarget.DIST_TARGET_NO_PRISM)) {
                return responseWithGRCode(GRCode.GRC_NOTOK)
            }

            distMode = sp.distCoordPara.distMode.data
            distAverageSwitch = sp.distCoordPara.distAverageSwitch.data
            sp.distCoordPara.setDistMode(SetEnum.DistMode.DIST_MODE_SERIES)
            sp.distCoordPara.setDistAverageSwitch(SetEnum.DistAverageSwitch.DIST_AVERAGE_SWITCH_OFF)

            distCorrectConfig.update()
            val builder = createTask()
                .ifWhen { isEdmSinglePrismMode(sp) }
                .pack(ServoStartDebugPsAtr(ServoDef.PS_ATR_MODE5))
                .ifClose()
                .pack(EdmStartCommand(distCorrectConfig, false))

            if (!builder.responseByCall().isSuccess) {
                Log.e(TAG, "continuousAutofocus: 启动测量失败")
                restoreDistSettings()
                return responseWithGRCode(GRCode.GRC_NOTOK)
            }

            lastFocusDistance = 0.0

            continuousAutofocusJob = serviceScope.launch {
                Log.i(TAG, "continuousAutofocus: 开始连续读取, 阈值=${focusThreshold}m")
                while (isActive) {
                    try {
                        val disResponse = createTask()
                            .pack(ReadDisCommand(timeout = 5000))
                            .responseByCall()

                        if (disResponse.isSuccess) {
                            val currentDis = (disResponse.data.mDistance / 10000).toDouble()
                            if (shouldRefocus(currentDis)) {
                                Log.i(TAG, "continuousAutofocus: 对焦 ${lastFocusDistance}m -> ${currentDis}m")
                                if (positFocusMotorToDistInternal(cid, currentDis)) {
                                    lastFocusDistance = currentDis
                                }
                            }
                        }
                        delay(200)
                    } catch (e: CancellationException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "continuousAutofocus: 异常", e)
                        break
                    }
                }
                Log.i(TAG, "continuousAutofocus: 协程退出")
            }
            return responseWithGRCOk()
        } else {
            if (continuousAutofocusJob?.isActive != true) {
                return responseWithGRCOk()
            }
            continuousAutofocusJob?.cancel()
            continuousAutofocusJob = null
            restoreDistSettings()

            shareProvider.bindValue(ProviderKey.SERVO_HAS_STOP, false)
            val stopResponse = createTask().pack(MeaStopCommand()).responseByCall()
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
        if (!sessionManager.isSessionOpen(CAM.CAM_ID_TYPE.OAC)) {
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }
        try {
            distCorrectConfig.update()
            val builder = createTask()
                .ifWhen { isEdmSinglePrismMode(sp) }
                .pack(ServoStartDebugPsAtr(ServoDef.PS_ATR_MODE5))
                .ifClose()
                .pack(EdmStartCommand(distCorrectConfig, false))

            if (!builder.responseByCall().isSuccess) {
                return responseWithGRCode(GRCode.GRC_NOTOK)
            }

            val task = createTask()
            val disResponse = task.pack(ReadDisCommand(timeout = 5000)).responseByCall()
            task.pack(ReadAngleCommand())
                .ifWhen { sp.anglePara.tiltSwitch.data != SetEnum.TiltSwitch.TILT_SWITCH_OFF }
                .pack(ReadTiltCommand(true)).pack(TiltCorrectCommand()).ifClose()
                .ifWhen { sp.psAtrPara.userAtrState.data == SetEnum.NormalSwitch.NORMAL_SWITCH_ON }
                .pack(ServoAtrUpdateSearchRt()).ifClose()
                .ifWhen { sp.psAtrPara.userLockState.data == SetEnum.NormalSwitch.NORMAL_SWITCH_ON }
                .pack(ServoReadLockDataCommand()).ifClose()
                .pack(AngleCorrectCommand(angleConfig, true)).responseByCall()

            if (disResponse.isSuccess) {
                val correctedBuilder = task
                    .pack(EdmGeoCorrectCommand(distCorrectConfig, null))
                    .responseBySafeCall()
                val currentDis = correctedBuilder.data.sd / 10000
                if (currentDis < 1.5) return responseWithGRCode(GRCode.GRC_NOTOK)
                val focusResult = positFocusMotorToDistInternal(clientId, currentDis)
                return responseWithGRCOk(focusResult)
            }
            return responseWithGRCode(GRCode.GRC_NOTOK)
        } catch (e: Exception) {
            Log.e(TAG, "singleShotAutofocus: 异常 ${e.message}", e)
            return responseWithGRCode(GRCode.GRC_NOTOK)
        }
    }

    override fun getChipWindowSize(cameraType: CAM.CAM_ID_TYPE): Response<CAM.ChipWindowSize> {
        val resolutionInfo = sessionManager.getCurrentResolutionAndZoom(cameraType)
            ?: return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)

        val width = resolutionInfo[0]
        val height = resolutionInfo[1]
        val zoomFactor = resolutionInfo[2]

        val chipWindow = CAM.ChipWindowSize().apply {
            dX = width.toDouble() / zoomFactor
            dY = height.toDouble() / zoomFactor
        }
        return responseWithGRCOk(chipWindow)
    }

    // ========================= 私有工具 =========================

    private fun parseResolution(resolution: String): Pair<Int, Int>? {
        val parts = resolution.split("x")
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val h = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return w to h
    }

    private fun doStopPreview(cid: Long, cameraType: CAM.CAM_ID_TYPE) {
        sessionManager.unbindPreview(cameraType, cid)
    }

    private fun doStopMedia(cameraType: CAM.CAM_ID_TYPE) {
        if (sessionManager.unregisterMedia(cameraType)) {
            MediaStreamService.stopStreamSync(cameraType)
            sessionManager.closeSession(clientId, cameraType)
            Log.i(TAG, "doStopMedia: $cameraType 推流已停止")
        }
    }

    private fun releaseAllForCamera(cameraType: CAM.CAM_ID_TYPE) {
        sessionManager.releaseRecording(cameraType)
        doStopMedia(cameraType)
        sessionManager.forceCloseSession(cameraType)
        Log.i(TAG, "releaseAllForCamera: $cameraType")
    }

    private fun restoreDistSettings() {
        val proxy = sp.distCoordPara
        lastTarget?.let { proxy.setDistTarget(it); lastTarget = null }
        distMode?.let { proxy.setDistMode(it); distMode = null }
        distAverageSwitch?.let { proxy.setDistAverageSwitch(it); distAverageSwitch = null }
    }

    private fun shouldRefocus(currentDis: Double): Boolean {
        if (lastFocusDistance <= 0) return currentDis >= 1.5
        return Math.abs(currentDis - lastFocusDistance) >= focusThreshold
    }

    private fun positFocusMotorToDistInternal(cid: Long, dis: Double): Boolean {
        if (dis < 1.5) return false
        if (!sessionManager.isSessionOpen(CAM.CAM_ID_TYPE.OAC)) return false

        val base = 4437
        val temp = 28.85 / dis
        val L = if (dis >= 20) {
            (temp * 0.8416 + temp * temp * 0.0066 - 0.0025 * sqrt(temp)) * 1000
        } else {
            val temp2 = temp - 1.4470
            (0.8496 * temp2 + 0.0190 * temp2 * temp2 + 1.2540) * 1000
        } + base

        Log.d(TAG, "positFocusMotorToDistInternal: dis=${dis}m, targetPosition=$L")
        return sessionManager.setCommonOrder(
            CAM.CAM_ID_TYPE.OAC,
            UvcCmdConstant.THEORETICAL_FOCAL_POSITION,
            L.toInt()
        )
    }
}