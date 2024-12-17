package dji.v5.ux.core.widget.setting

import android.content.Context
import android.util.AttributeSet
import android.view.View
import dji.v5.ux.R
import dji.v5.ux.core.base.widget.ConstraintLayoutWidget
import dji.v5.ux.core.communication.OnStateChangeCallback

/**
 * Description : 左上角设置页的入口Widget
 *
 * @author: Byte.Cai
 *  date : 2022/11/17
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */


class SettingWidget @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayoutWidget<Boolean>(context, attrs, defStyleAttr), View.OnClickListener {

    override fun initView(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        View.inflate(context, R.layout.uxsdk_widget_setting, this)
    }

    init {
        setOnClickListener(this)
    }

    var stateChangeCallback: OnStateChangeCallback<Any>? = null

    override fun reactToModelChanges() {
        //do nothing
    }

    override fun getIdealDimensionRatioString(): String? {
        return null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        destroyListener()
    }

    override fun onClick(v: View?) {
        stateChangeCallback?.onStateChange(null)
    }

    private fun destroyListener() {
        stateChangeCallback = null
    }
}