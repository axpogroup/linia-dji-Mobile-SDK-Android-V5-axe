package dji.v5.ux.core.ui.setting.fragment;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dji.sdk.keyvalue.utils.ProductUtil;
import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.v5.utils.common.ContextUtil;
import dji.v5.utils.common.StringUtils;
import dji.v5.ux.R;
import dji.v5.ux.core.ui.setting.ui.MenuFragment;
import dji.v5.ux.gimbal.GimbalSettingWidget;

/**
 * Description :
 *
 * @author: Byte.Cai
 * date : 2022/11/21
 * <p>
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
public class GimbalMenuFragment extends MenuFragment {
    @Override
    protected String getPreferencesTitle() {
        return StringUtils.getResStr(ContextUtil.getContext(), R.string.uxsdk_setting_menu_title_gimbal);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.uxsdk_setting_menu_gimbal_layout;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        GimbalSettingWidget widget = view.findViewById(R.id.gimbalSettingWidget);
        if(ProductUtil.isM400Product()) widget.updateGimbalIndex(ComponentIndexType.PORT_1);
    }
}
