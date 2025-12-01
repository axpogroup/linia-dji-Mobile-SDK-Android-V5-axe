package dji.v5.ux.core.widget.hsi;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dji.sdk.keyvalue.key.GimbalKey;
import dji.sdk.keyvalue.key.KeyTools;
import dji.sdk.keyvalue.value.common.Attitude;
import dji.sdk.keyvalue.value.common.CameraLensType;
import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.sdk.keyvalue.value.gimbal.GimbalAttitudeRange;
import dji.v5.manager.KeyManager;
import dji.v5.ux.core.base.DJISDKModel;
import dji.v5.ux.core.base.ICameraIndex;
import dji.v5.ux.core.base.WidgetModel;
import dji.v5.ux.core.communication.ObservableInMemoryKeyedStore;
import dji.v5.ux.core.util.DataProcessor;

/**
 * Description :
 *
 * @author: Byte.Cai
 * date : 2022/11/1
 * <p>
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
public class GimbalPitchBarModel extends WidgetModel implements ICameraIndex {
    private ComponentIndexType cameraIndex = ComponentIndexType.PORT_1;
    private CameraLensType lensType = CameraLensType.CAMERA_LENS_ZOOM;
    private final Map<Integer, DataProcessor<Attitude>> gimbalAttitudeInDegreesProcessorList = new HashMap<>();
    private final DataProcessor<Attitude> gimbalAttitudeInDegrees0Processor = DataProcessor.create(new Attitude());
    private final DataProcessor<Attitude> gimbalAttitudeInDegrees1Processor = DataProcessor.create(new Attitude());
    private final DataProcessor<Attitude> gimbalAttitudeInDegrees2Processor = DataProcessor.create(new Attitude());
    private final DataProcessor<Attitude> gimbalAttitudeInDegrees3Processor = DataProcessor.create(new Attitude());
    private final DataProcessor<Attitude> gimbalAttitudeInDegrees4Processor = DataProcessor.create(new Attitude());
    private final DataProcessor<Attitude> gimbalAttitudeInDegrees5Processor = DataProcessor.create(new Attitude());
    private final DataProcessor<Attitude> gimbalAttitudeInDegrees6Processor = DataProcessor.create(new Attitude());


    private final Map<Integer, DataProcessor<GimbalAttitudeRange>> gimbalAttitudeGimbalAttitudeRangeProcessorList = new HashMap<>();
    private final DataProcessor<GimbalAttitudeRange> gimbalAttitudeGimbalAttitudeRange0Processor = DataProcessor.create(new GimbalAttitudeRange());
    private final DataProcessor<GimbalAttitudeRange> gimbalAttitudeGimbalAttitudeRange1Processor = DataProcessor.create(new GimbalAttitudeRange());
    private final DataProcessor<GimbalAttitudeRange> gimbalAttitudeGimbalAttitudeRange2Processor = DataProcessor.create(new GimbalAttitudeRange());
    private final DataProcessor<GimbalAttitudeRange> gimbalAttitudeGimbalAttitudeRange3Processor = DataProcessor.create(new GimbalAttitudeRange());
    private final DataProcessor<GimbalAttitudeRange> gimbalAttitudeGimbalAttitudeRange4Processor = DataProcessor.create(new GimbalAttitudeRange());
    private final DataProcessor<GimbalAttitudeRange> gimbalAttitudeGimbalAttitudeRange5Processor = DataProcessor.create(new GimbalAttitudeRange());
    private final DataProcessor<GimbalAttitudeRange> gimbalAttitudeGimbalAttitudeRange6Processor = DataProcessor.create(new GimbalAttitudeRange());

    protected GimbalPitchBarModel(@NonNull DJISDKModel djiSdkModel, @NonNull ObservableInMemoryKeyedStore uxKeyManager) {
        super(djiSdkModel, uxKeyManager);
    }



    @Override
    protected void inSetup() {
        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.LEFT_OR_MAIN), gimbalAttitudeInDegrees0Processor);
        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.RIGHT), gimbalAttitudeInDegrees1Processor);
        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.UP), gimbalAttitudeInDegrees2Processor);
        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.PORT_1), gimbalAttitudeInDegrees3Processor);
        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.PORT_2), gimbalAttitudeInDegrees3Processor);
        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.PORT_3), gimbalAttitudeInDegrees3Processor);
        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.PORT_4), gimbalAttitudeInDegrees3Processor);
        gimbalAttitudeInDegreesProcessorList.put(ComponentIndexType.LEFT_OR_MAIN.value(), gimbalAttitudeInDegrees0Processor);
        gimbalAttitudeInDegreesProcessorList.put(ComponentIndexType.RIGHT.value(), gimbalAttitudeInDegrees1Processor);
        gimbalAttitudeInDegreesProcessorList.put(ComponentIndexType.UP.value(), gimbalAttitudeInDegrees2Processor);
        gimbalAttitudeInDegreesProcessorList.put(ComponentIndexType.PORT_1.value(), gimbalAttitudeInDegrees3Processor);
        gimbalAttitudeInDegreesProcessorList.put(ComponentIndexType.PORT_2.value(), gimbalAttitudeInDegrees4Processor);
        gimbalAttitudeInDegreesProcessorList.put(ComponentIndexType.PORT_3.value(), gimbalAttitudeInDegrees5Processor);
        gimbalAttitudeInDegreesProcessorList.put(ComponentIndexType.PORT_4.value(), gimbalAttitudeInDegrees6Processor);

        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitudeRange, ComponentIndexType.LEFT_OR_MAIN),gimbalAttitudeGimbalAttitudeRange0Processor);
        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitudeRange, ComponentIndexType.RIGHT), gimbalAttitudeGimbalAttitudeRange1Processor);
        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitudeRange, ComponentIndexType.UP), gimbalAttitudeGimbalAttitudeRange2Processor);
        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitudeRange, ComponentIndexType.PORT_1), gimbalAttitudeGimbalAttitudeRange3Processor);
        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitudeRange, ComponentIndexType.PORT_2), gimbalAttitudeGimbalAttitudeRange4Processor);
        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitudeRange, ComponentIndexType.PORT_3), gimbalAttitudeGimbalAttitudeRange5Processor);
        bindDataProcessor(KeyTools.createKey(GimbalKey.KeyGimbalAttitudeRange, ComponentIndexType.PORT_4), gimbalAttitudeGimbalAttitudeRange6Processor);
        gimbalAttitudeGimbalAttitudeRangeProcessorList.put(ComponentIndexType.LEFT_OR_MAIN.value(), gimbalAttitudeGimbalAttitudeRange0Processor);
        gimbalAttitudeGimbalAttitudeRangeProcessorList.put(ComponentIndexType.RIGHT.value(), gimbalAttitudeGimbalAttitudeRange1Processor);
        gimbalAttitudeGimbalAttitudeRangeProcessorList.put(ComponentIndexType.UP.value(), gimbalAttitudeGimbalAttitudeRange2Processor);
        gimbalAttitudeGimbalAttitudeRangeProcessorList.put(ComponentIndexType.PORT_1.value(), gimbalAttitudeGimbalAttitudeRange3Processor);
        gimbalAttitudeGimbalAttitudeRangeProcessorList.put(ComponentIndexType.PORT_2.value(), gimbalAttitudeGimbalAttitudeRange4Processor);
        gimbalAttitudeGimbalAttitudeRangeProcessorList.put(ComponentIndexType.PORT_3.value(), gimbalAttitudeGimbalAttitudeRange5Processor);
        gimbalAttitudeGimbalAttitudeRangeProcessorList.put(ComponentIndexType.PORT_4.value(), gimbalAttitudeGimbalAttitudeRange6Processor);
    }

    @Override
    protected void inCleanup() {
        KeyManager.getInstance().cancelListen(this);
        gimbalAttitudeInDegreesProcessorList.clear();
        gimbalAttitudeGimbalAttitudeRangeProcessorList.clear();

    }

    @NonNull
    @Override
    public ComponentIndexType getCameraIndex() {
        return cameraIndex;
    }

    @NonNull
    @Override
    public CameraLensType getLensType() {
        return lensType;
    }

    @Override
    public void updateCameraSource(@NonNull ComponentIndexType cameraIndex, @NonNull CameraLensType lensType) {
        this.cameraIndex = cameraIndex;
        this.lensType = lensType;
        restart();
    }

    public Map<Integer, DataProcessor<Attitude>> getGimbalAttitudeInDegreesProcessorList() {
        return gimbalAttitudeInDegreesProcessorList;
    }

    public Map<Integer, DataProcessor<GimbalAttitudeRange>> getGimbalAttitudeGimbalAttitudeRangeProcessorList() {
        return gimbalAttitudeGimbalAttitudeRangeProcessorList;
    }

}
