package com.ruide.service

import android.media.MediaRecorder
import android.util.Log
import com.ruide.aidl.para.CAM
import com.ruide.camera.GlobalCameraManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理 USB 相机的本地录像。
 *
 * 职责：
 * - 维护 cameraType → MediaRecorder 的状态机
 * - 通过 GlobalCameraManager.addPreviewSurface 将 MediaRecorder.surface
 *   注入帧分发管道，复用已有 YUV→Surface 渲染路径
 * - 对外暴露 start / stop / releaseForCamera，不感知 AIDL/客户端细节
 */
class ScreenRecordManager(
    private val cameraManager: GlobalCameraManager,
    private val recordDir: (CAM.CAM_ID_TYPE) -> File,   // 文件目录策略，由外部注入
) {
    companion object {
        private const val TAG = "ScreenRecordManager"
        private const val SURFACE_KEY_PREFIX = "screenRecord_"
    }

    private val recorderMap = ConcurrentHashMap<CAM.CAM_ID_TYPE, MediaRecorder>()

    /** 开始录像；已在录制中则幂等返回 true */
    fun start(pid: Int, width: Int, height: Int, cameraType: CAM.CAM_ID_TYPE): Boolean {
        if (recorderMap.containsKey(cameraType)) {
            Log.d(TAG, "start: $cameraType 已在录制中")
            return true
        }
        val outputFile = buildOutputFile(cameraType)
        val recorder = try {
            MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(25)
                setVideoEncodingBitRate(4 * 1024 * 1024)
                setOutputFile(outputFile.absolutePath)
                prepare()
            }
        } catch (e: Exception) {
            Log.e(TAG, "start: prepare 失败", e)
            return false
        }

        recorderMap[cameraType] = recorder
        cameraManager.addPreviewSurface(pid, surfaceKey(cameraType), recorder.surface)
        recorder.start()
        Log.i(TAG, "start: $cameraType -> ${outputFile.absolutePath}")
        return true
    }

    /** 停止录像；未在录制中则幂等返回 */
    fun stop(pid: Int, cameraType: CAM.CAM_ID_TYPE) {
        cameraManager.removePreviewSurface(pid, surfaceKey(cameraType)) // 先摘，再 stop
        val recorder = recorderMap.remove(cameraType) ?: return
        try {
            recorder.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop: recorder.stop 异常", e)
        } finally {
            recorder.release()
        }
        Log.i(TAG, "stop: $cameraType")
    }

    /** 断电/断连时强制释放，不抛异常 */
    fun releaseForCamera(pid: Int, cameraType: CAM.CAM_ID_TYPE) = stop(pid, cameraType)

    private fun surfaceKey(cameraType: CAM.CAM_ID_TYPE) = SURFACE_KEY_PREFIX + cameraType.name

    private fun buildOutputFile(cameraType: CAM.CAM_ID_TYPE): File {
        val dir = recordDir(cameraType).also { if (!it.exists()) it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date())
        return File(dir, "REC_${cameraType.name}_$ts.mp4")
    }
}