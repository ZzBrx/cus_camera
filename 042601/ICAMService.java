package com.ruide.aidl.func;

import android.view.Surface;

import com.ruide.aidl.bean.Response;
import com.ruide.aidl.para.CAM;

import java.util.List;

/**
 * author：zhangzhou
 * email：1030334863@qq.com
 * createdAt:2026/2/4
 **/
public interface ICAMService {

    // 相机控制
    Response<Void> openCamera(CAM.CAM_ID_TYPE cameraType);
    Response<Void> closeCamera(CAM.CAM_ID_TYPE cameraType);

    // 预览控制
    Response<Void> startPreview(CAM.CAM_ID_TYPE cameraType, Surface surface);
    Response<Void> stopPreview(CAM.CAM_ID_TYPE cameraType);

    // 状态查询
    Response<Void> isCameraOpened(CAM.CAM_ID_TYPE cameraType);
    Response<Void> isPreviewing(CAM.CAM_ID_TYPE cameraType);

    Response<List<String>> getSupportedResolutions(CAM.CAM_ID_TYPE cameraType);
    Response<Void> setResolution(CAM.CAM_ID_TYPE cameraType, String resolution);

    Response<Void> setZoom(CAM.CAM_ID_TYPE cameraType, int zoomFactor);

    Response<Integer> getZoom(CAM.CAM_ID_TYPE cameraType);

    Response<CAM.FoVHzBean> getCameraFoV(CAM.CAM_ID_TYPE cameraType, int zoomFactor);
    Response<Void> setActualImageName(CAM.CAM_ID_TYPE cameraType, String szName, int iNumber);
    Response<Void> takeImage(CAM.CAM_ID_TYPE cameraType);
    Response<Void> setWhiteBalanceMode(CAM.CAM_ID_TYPE cameraType, int mode);
    Response<Void> isCameraReady(CAM.CAM_ID_TYPE cameraType);

    Response<CAM.ON_OFF_TYPE> getCameraPowerSwitch(CAM.CAM_ID_TYPE cameraType);
    Response<Void> setCameraPowerSwitch(CAM.CAM_ID_TYPE cameraType, CAM.ON_OFF_TYPE state);
    Response<Void> waitForCameraReady(CAM.CAM_ID_TYPE cameraType, long ulTimeout);
    Response<Void> setMotorPosition(long motorPosition);

    Response<Integer> getMotorPosition();
    Response<Void> positFocusMotorToDist(double dis);
    Response<Void> positFocusMotorToInfinity();
    Response<CAM.RoCrossHairPos> getOacCrossHairPos();
    Response<Void> setCameraProperties(CAM.CAM_ID_TYPE cameraType, CAM.CAM_RESOLUTION resolution, CAM.CAM_COMPRESSION compression, CAM.CAM_JPEG_COMPARE_QUALITY quality);
}
