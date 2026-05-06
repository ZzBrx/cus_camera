package com.ruide.service.middleware.impl

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Binder
import android.util.Log
import android.view.Surface
import com.ruide.aidl.bean.Response
import com.ruide.aidl.func.ICAMService
import com.ruide.aidl.para.CAM
import com.ruide.camera.UvcCmdConstant
import com.ruide.camera.session.CameraSessionManager
import com.ruide.command.chain.provider.GlobalShareProvider
import com.ruide.command.hard.angle.AngleCorrectCommand
import com.ruide.command.hard.angle.ReadAngleCommand
import com.ruide.common.constant.GRCode
import com.ruide.core.bean.EnumCommons
import com.ruide.service.core.fram.para.SetEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * ICAMService 的 AIDL 实现（精简版）。
 *
 * 职责：
 * - 参数校验
 * - 委托 CameraSessionManager 执行操作
 * - 不做状态编排
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

    private val implicitOpenCameras = ConcurrentHashMap<CAM.CAM_ID_TYPE, Long>()

    private val SEND_IMAGE_TO_MB = 0 //传图像到主控
    private val GPIO_SEND_IMAGE_CTRL = 102//图像传输方向选择开关


    // ========================= 工具 =========================

    private val clientId: Long
        get() = Binder.getCallingUid().toLong() * 100_000L + Binder.getCallingPid().toLong()

    // ========================= 相机控制 =========================

    override fun openCamera(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val cid = clientId
        if(cameraType == CAM.CAM_ID_TYPE.OAC){
            shareProvider.deviceManage.driverSupport.gpio().setGpio(GPIO_SEND_IMAGE_CTRL, SEND_IMAGE_TO_MB)
        }

        return when (val result = sessionManager.openSession(cid, CAM.convertCameraTypeToNum(cameraType))) {
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
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        Log.d(TAG, "closeCamera: $cameraType")

        // 清理隐式打开记录
        implicitOpenCameras.remove(cameraType)

        sessionManager.releaseRecording(cameraId)
        doStopPreview(cid, cameraType)

        when (sessionManager.closeSession(cid, cameraId)) {
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
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        if (surface == null || !surface.isValid) {
            Log.w(TAG, "startPreview: Surface 无效, cid=$cid")
            return responseWithGRCode(GRCode.GRC_NOTOK)
        }
        if (!sessionManager.isSessionOpen(cameraId)) {
            Log.w(TAG, "startPreview: Session 未打开, cid=$cid")
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }

        // 检查是否已经在预览
        val existingSurface = sessionManager.getPreview(cameraId, cid)
        if (existingSurface != null && existingSurface.isValid && existingSurface == surface) {
            Log.i(TAG, "startPreview: 已在预览中, cid=$cid, cameraType=$cameraType")
            return responseWithGRCOk()
        }

        // 如果有旧 Surface，先解绑
        if (existingSurface != null) {
            Log.d(TAG, "startPreview: 替换 Surface, cid=$cid, cameraType=$cameraType")
            sessionManager.unbindPreview(cameraId, cid)
        }

        val success = sessionManager.bindPreview(cameraId, cid, surface)
        if (success) {
            Log.i(TAG, "startPreview 成功: cid=$cid, cameraType=$cameraType")
            return responseWithGRCOk()
        } else {
            Log.w(TAG, "startPreview 失败: cid=$cid, cameraType=$cameraType")
            return responseWithGRCode(GRCode.GRC_NOTOK)
        }
    }

    override fun stopPreview(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val cid = clientId
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        sessionManager.releaseRecording(cameraId)
        doStopPreview(cid, cameraType)
        Log.i(TAG, "stopPreview 完成: cid=$cid, cameraType=$cameraType")
        return responseWithGRCOk()
    }

    // ========================= 状态查询 =========================

    override fun isCameraOpened(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        return if (sessionManager.isSessionOpen(cameraId)) responseWithGRCOk()
        else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun isPreviewing(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        val surface = sessionManager.getPreview(cameraId, clientId)
        return if (surface != null && surface.isValid) responseWithGRCOk()
        else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    // ========================= 分辨率 =========================

    override fun getSupportedResolutions(cameraType: CAM.CAM_ID_TYPE): Response<List<String>> {
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        // 先检查 session 是否打开
        if (!sessionManager.isSessionOpen(cameraId)) {
            Log.w(TAG, "getSupportedResolutions: Session 未打开, cameraType=$cameraType")
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }

        val sizes = sessionManager.getSupportedResolutions(cameraId)
            .map { "${it.width}x${it.height}" }

        if (sizes.isEmpty()) {
            Log.w(TAG, "getSupportedResolutions: 返回空列表, cameraType=$cameraType")
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }

        return responseWithGRCOk(sizes)
    }

    override fun setResolution(cameraType: CAM.CAM_ID_TYPE, resolution: String): Response<Void> {
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        val (width, height) = parseResolution(resolution) ?: run {
            Log.w(TAG, "setResolution: 格式错误, resolution=$resolution")
            return responseWithGRCode(GRCode.GRC_IVPARAM)
        }
        val success = sessionManager.setResolution(cameraId, width, height)
        Log.d(TAG, "setResolution $cameraType ${width}x${height} -> success=$success")
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    // ========================= 变焦 =========================

    override fun setZoom(cameraType: CAM.CAM_ID_TYPE, zoomFactor: Int): Response<Void> {
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        if (!CAM.isValidZoomFactor(zoomFactor)) return responseWithGRCode(GRCode.GRC_IVPARAM)
        val success = sessionManager.setZoom(cameraId, zoomFactor)
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun getZoom(cameraType: CAM.CAM_ID_TYPE): Response<Int> {
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        val zoom = sessionManager.getZoom(cameraId)
        return if (zoom != null) responseWithGRCOk(zoom) else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    // ========================= FoV =========================

    override fun getCameraFoV(cameraType: CAM.CAM_ID_TYPE, zoomFactor: Int): Response<CAM.FoVHzBean> {
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
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        if (!sessionManager.isSessionOpen(cameraId)) {
            Log.w(TAG, "takeImage: 相机未打开, cameraType=$cameraType")
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }

        val captured = sessionManager.captureFrame(cameraId)
        if (captured == null) {
            Log.e(TAG, "takeImage: 截帧失败")
            return responseWithGRCode(GRCode.GRC_NOTOK)
        }

        val dir = CAM.getImageDir(cameraId).also { if (!it.exists()) it.mkdirs() }
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
                ).compressToJpeg(Rect(0, 0, captured.width, captured.height), 90, fos)
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

    override fun getOacCrossHairPos(): Response<CAM.RoCrossHairPos> {
        val cameraId = CAM.convertCameraTypeToNum(CAM.CAM_ID_TYPE.OAC)
        if (!sessionManager.isSessionOpen(cameraId)) {
            return responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }
        val task = createTask()
        val response = task.pack(ReadAngleCommand()).pack(AngleCorrectCommand(angleConfig)).asWorkFlow(5).responseByCall()
        if(response.isSuccess){
            val bean = (if(response.data.mSideFlag == SetEnum.SideFlag.SIDE_FLAG_I){
                CAM.RoCrossHairPos().apply {
                    dX = shareProvider.systemPara.imagePara.sideIDX.data.toDouble()
                    dY = shareProvider.systemPara.imagePara.sideIDY.data.toDouble()
                }
            }else {
                CAM.RoCrossHairPos().apply {
                    dX = shareProvider.systemPara.imagePara.sideIIDX.data.toDouble()
                    dY = shareProvider.systemPara.imagePara.sideIIDY.data.toDouble()
                }
            })
            return responseWithGRCOk(bean)
        }
        return responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun setCameraProperties(
        cameraType: CAM.CAM_ID_TYPE,
        resolution: CAM.CAM_RESOLUTION,
        compression: CAM.CAM_COMPRESSION,
        quality: CAM.CAM_JPEG_COMPARE_QUALITY
    ): Response<Void> {
        val getResResponse = getSupportedResolutions(cameraType)
        if(getResResponse.isSuccess){
            val targetResolution = CAM.convertResolutionTypeToString(resolution)
            if(getResResponse.data.contains(targetResolution)){
                return setResolution(cameraType, targetResolution)
            }
            return responseWithGRCode(GRCode.GRC_NOTOK)
        }else{
            return responseWithGRCode(GRCode.GRC_IVPARAM)
        }
    }

    // ========================= 白平衡 =========================

    override fun setWhiteBalanceMode(cameraType: CAM.CAM_ID_TYPE, mode: Int): Response<Void> {
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        val success = sessionManager.setWhiteBalance(cameraId, mode)
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    // ========================= 设备就绪 / 电源 =========================

    override fun isCameraReady(cameraType: CAM.CAM_ID_TYPE): Response<Void> {
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        return if (sessionManager.isCameraReady(cameraId)) {
            responseWithGRCOk()
        } else {
            Log.w(TAG, "isCameraReady: $cameraType 不可用")
            responseWithGRCode(GRCode.GRC_CAM_NOT_READY)
        }
    }

    override fun setCameraPowerSwitch(cameraType: CAM.CAM_ID_TYPE, state: CAM.ON_OFF_TYPE): Response<Void> {
        val (deviceType, isPowerOff) = when (cameraType) {
            CAM.CAM_ID_TYPE.OVC -> EnumCommons.DeviceType.kDeviceDiffImage to (state != CAM.ON_OFF_TYPE.ON)
            else -> EnumCommons.DeviceType.kDeviceCoaxialImage to (state != CAM.ON_OFF_TYPE.ON)
        }
        if (isPowerOff) {
            releaseAllForCamera(cameraType)
            shareProvider.deviceManage.powerOff(deviceType)
        } else {
            shareProvider.deviceManage.powerOn(deviceType)
        }
        return responseWithGRCOk()
    }

    override fun getCameraPowerSwitch(cameraType: CAM.CAM_ID_TYPE): Response<CAM.ON_OFF_TYPE> {
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        return if (sessionManager.isDeviceReallyAlive(cameraId)) {
            responseWithGRCOk(CAM.ON_OFF_TYPE.ON)
        } else {
            responseWithGRCOk(CAM.ON_OFF_TYPE.OFF)
        }
    }

    override fun waitForCameraReady(cameraType: CAM.CAM_ID_TYPE, ulTimeout: Long): Response<Void> {
        val cid = clientId
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        val deadline = System.currentTimeMillis() + ulTimeout

        while (System.currentTimeMillis() < deadline) {
            val result = sessionManager.openSession(cid, cameraId)
            when (result) {
                is CameraSessionManager.SessionResult.Success,
                is CameraSessionManager.SessionResult.AlreadyOpen -> {
                    supportedResolutionsCache.remove(cameraType)
                    return responseWithGRCOk()
                }
                else -> Thread.sleep(50)
            }
        }

        Log.w(TAG, "waitForCameraReady 超时: $cameraType")
        return responseWithGRCode(GRCode.GRC_TIME_OUT)
    }

    // ========================= 电机 / 对焦 =========================

    override fun setMotorPosition(motorPosition: Long): Response<Void> {
        val success = sessionManager.setCommonOrder(
            CAM.convertCameraTypeToNum(CAM.CAM_ID_TYPE.OAC),
            UvcCmdConstant.MIRROR_BARREL_POSITION,
            motorPosition.toInt()
        )
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun getMotorPosition(): Response<Int> {
        val value = sessionManager.getCommonOrder(
            CAM.convertCameraTypeToNum(CAM.CAM_ID_TYPE.OAC),
            UvcCmdConstant.MIRROR_BARREL_POSITION
        )
        return if (value != -1) responseWithGRCOk(value) else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun positFocusMotorToDist(dis: Double): Response<Void> {
        if (dis < 1.5) return responseWithGRCode(GRCode.GRC_NOTOK)
        val success = positFocusMotorToDistInternal(dis, UvcCmdConstant.THEORETICAL_FOCAL_POSITION)
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
    }

    override fun positFocusMotorToInfinity(): Response<Void> {
        val target = 820 + (factoryPara.infinityPosition ?: 4700).toInt()
        val success = sessionManager.setCommonOrder(
            CAM.convertCameraTypeToNum(CAM.CAM_ID_TYPE.OAC),
            UvcCmdConstant.THEORETICAL_FOCAL_POSITION,
            target
        )
        return if (success) responseWithGRCOk() else responseWithGRCode(GRCode.GRC_NOTOK)
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
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        sessionManager.unbindPreview(cameraId, cid)
    }

    private fun releaseAllForCamera(cameraType: CAM.CAM_ID_TYPE) {
        val cameraId = CAM.convertCameraTypeToNum(cameraType)
        implicitOpenCameras.remove(cameraType)
        sessionManager.releaseRecording(cameraId)
        sessionManager.forceCloseSession(cameraId)
        Log.i(TAG, "releaseAllForCamera: $cameraId")
    }

    private fun positFocusMotorToDistInternal(dis: Double, order: Int): Boolean {
        val base = 4437
        val temp = 28.85 / dis
        val L = if (dis >= 20) {
            (temp * 0.8416 + temp * temp * 0.0066 - 0.0025 * sqrt(temp)) * 1000
        } else {
            val temp2 = temp - 1.4470
            (0.8496 * temp2 + 0.0190 * temp2 * temp2 + 1.2540) * 1000
        } + base

        return sessionManager.setCommonOrder(
            CAM.convertCameraTypeToNum(CAM.CAM_ID_TYPE.OAC),
            order,
            L.toInt()
        )
    }
}