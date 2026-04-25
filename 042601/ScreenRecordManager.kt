package com.ruide.service

import android.media.MediaRecorder
import android.util.Log
import com.ruide.aidl.para.CAM
import com.ruide.camera.CameraSessionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理 USB 相机的本地录像（适配 CameraSessionManager）。
 */
class ScreenRecordManager(
    private val sessionManager: CameraSessionManager,
    private val recordDir: (CAM.CAM_ID_TYPE) -> File
) {
    companion object {
        private const val TAG = "ScreenRecordManager"
        private const val SURFACE_KEY_PREFIX = "screenRecord_"
    }

    private val recorderMap = ConcurrentHashMap<CAM.CAM_ID_TYPE, MediaRecorder>()

    fun start(productId: Int, width: Int, height: Int, cameraType: CAM.CAM_ID_TYPE): Boolean {
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
        sessionManager.bindPreview(
            cameraType,
            -1L, // 系统内部使用，不需要 clientId
            recorder.surface
        )
        recorder.start()
        Log.i(TAG, "start: $cameraType -> ${outputFile.absolutePath}")
        return true
    }

    fun stop(productId: Int, cameraType: CAM.CAM_ID_TYPE) {
        sessionManager.unbindPreview(cameraType, -1L)
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

    private fun buildOutputFile(cameraType: CAM.CAM_ID_TYPE): File {
        val dir = recordDir(cameraType).also { if (!it.exists()) it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date())
        return File(dir, "REC_${cameraType.name}_$ts.mp4")
    }
}