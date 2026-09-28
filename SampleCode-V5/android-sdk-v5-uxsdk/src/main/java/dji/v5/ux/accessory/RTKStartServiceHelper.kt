package dji.v5.ux.accessory

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dji.rtk.CoordinateSystem
import dji.sdk.keyvalue.key.*
import dji.sdk.keyvalue.value.product.ProductType
import dji.sdk.keyvalue.value.remotecontroller.RCMode
import dji.sdk.keyvalue.value.rtkbasestation.RTKCustomNetworkSetting
import dji.sdk.keyvalue.value.rtkbasestation.RTKReferenceStationSource
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.utils.RxUtil
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.aircraft.rtk.RTKCenter
import dji.v5.manager.aircraft.rtk.RTKSystemStateListener
import dji.v5.manager.interfaces.INetworkRTKManager
import dji.v5.utils.common.*
import dji.v5.ux.R
import dji.v5.ux.core.util.DataProcessor
import dji.v5.ux.core.util.ViewUtil
import io.reactivex.rxjava3.core.Flowable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min


/**
 * Description :用于实现自动重连RTK逻辑
 *
 * Network RTK providers usually accept only one connection per login at a time, so the service is
 * only started when it is not running or starting already. Automatic triggers (the aircraft
 * connecting, the RTK source becoming known, the flight screen opening) leave a running service
 * alone; only the pilot saving the settings or choosing a coordinate system restarts it. A failed
 * start is retried with a growing delay, one retry at a time. Once the pilot disconnects the
 * service, only the pilot starts it again.
 *
 * @author: Byte.Cai
 *  date : 2022/8/16
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
object RTKStartServiceHelper {
    private const val TAG = "RTKStartServiceHelper"
    private const val START_TIMEOUT_MS = 15_000L
    private const val FIRST_RETRY_DELAY_MS = 5_000L
    private const val MAX_RETRY_DELAY_MS = 60_000L

    private val rtkCenter = RTKCenter.getInstance()
    private val qxRTKManager = RTKCenter.getInstance().qxrtkManager
    private val customManager = RTKCenter.getInstance().customRTKManager
    private val cmccRtkManager = RTKCenter.getInstance().cmccrtkManager
    private var isStartByUser=false

    private var productType: ProductType = ProductType.UNKNOWN
    private var rtkDongleConnection = false
    private var fcConnected = false
    private val rtkModuleAvailableProcessor = DataProcessor.create(false)
    private var rtkSource: RTKReferenceStationSource = RTKReferenceStationSource.UNKNOWN
    private val isStartRTKing = AtomicBoolean(false)
    private val isHasStartRTK = AtomicBoolean(false)
    private val handle = Handler(Looper.getMainLooper())

    /** The source the service was last started for, see [isHasStartRTK]. */
    private var serviceSource: RTKReferenceStationSource = RTKReferenceStationSource.UNKNOWN
    private var isStoppedByUser = false
    /** The pilot asked for a restart while a start was in progress; it runs once that start is done. */
    private var isRestartPending = false
    private var retryDelayMs = FIRST_RETRY_DELAY_MS
    /** Identifies the latest start, so that the callbacks of an abandoned start are ignored. */
    private var startId = 0
    private val startTimeout = Runnable { onStartTimeout() }
    private val retry = Runnable {
        log("Retrying to start the RTK service")
        startRtkService()
    }

    /**
     * Receives a description of every start, stop and retry of the RTK service, including why it
     * happened or was skipped. Called on arbitrary threads.
     */
    var connectionEventListener: ((String) -> Unit)? = null


    private val rtkSystemStateListener = RTKSystemStateListener {
        if (rtkSource != it.rtkReferenceStationSource) {
            onRtkSourceChanged(it.rtkReferenceStationSource)
        }
    }

    init {
        //观测RTK模块的连接情况
        observerRTKNoduleAvailable()
        //观测RTKSource的变化
        rtkCenter.addRTKSystemStateListener(rtkSystemStateListener)
    }

    @Synchronized
    private fun onRtkSourceChanged(source: RTKReferenceStationSource) {
        rtkSource = source
        log("RTK source changed to $rtkSource")
        startRtkService()
    }

    private fun observerRTKNoduleAvailable() {
        RxUtil.addListener(
            KeyTools.createKey(
                ProductKey.KeyProductType), this).subscribe {
            if (it != ProductType.UNRECOGNIZED && productType != it) {
                LogUtils.i(TAG, "productType=$it")
                productType = it
                updateData()
            }

        }
        RxUtil.addListener(
            KeyTools.createKey(
                RtkMobileStationKey.KeyIsRTKDongleConnect), this).subscribe {
            if (rtkDongleConnection != it) {
                LogUtils.i(TAG, "rtkDongleConnection=$it")
                rtkDongleConnection = it
                updateData()
            }

        }

        RxUtil.addListener(
            KeyTools.createKey(
                FlightControllerKey.KeyConnection), this).subscribe {
            if (fcConnected != it) {
                LogUtils.i(TAG, "fcConnected=$it")
                fcConnected = it
                updateData()
            }

        }
    }

    @Synchronized
    private fun updateData() {
        val isRtkModuleAvailable = when (productType) {
            ProductType.DJI_MAVIC_3_ENTERPRISE_SERIES ->
                // 外接RTK的判断RTK Dongle连接状态
                rtkDongleConnection && fcConnected
            else ->
                // 其他行业飞机内置RTK的飞机都是true
                fcConnected
        }
        rtkModuleAvailableProcessor.onNext(isRtkModuleAvailable)

        if (isRtkModuleAvailable && !isHasStartRTK.get()) {
            startRtkService()
        }
    }

    /**
     * Starts the RTK service for the current RTK source.
     *
     * @param isStartByUser the pilot asked for it, e.g. by saving the settings: a running service
     * is restarted so that it picks up new settings, and a disconnect by the pilot is lifted.
     * Otherwise a service that is running or starting is left alone, and nothing happens while
     * the pilot has disconnected the service.
     */
    @Synchronized
    fun startRtkService(isStartByUser:Boolean=false) {
        log("RTK service start requested " + if (isStartByUser) "by the pilot" else "automatically")
        if (isStartByUser) {
            isStoppedByUser = false
            retryDelayMs = FIRST_RETRY_DELAY_MS
            handle.removeCallbacks(retry)
        } else if (isStoppedByUser) {
            log("Not starting the RTK service: the pilot disconnected it")
            return
        } else if (isHasStartRTK.get() && serviceSource == rtkSource) {
            log("Not starting the RTK service: it is already running")
            return
        }
        if (isStartRTKing.get()) {
            if (isStartByUser) {
                isRestartPending = true
                log("RTK service start in progress, restarting once it is done")
            } else {
                log("Not starting the RTK service: a start is already in progress")
            }
            return
        }
        this.isStartByUser =isStartByUser
        if (!rtkModuleAvailableProcessor.value) {
            log("Not starting the RTK service: the RTK module is unavailable")
            return
        }
        if (!isNeedStartRtkNetworkService()) {
            log("Not starting the RTK service: no network RTK source, network or aircraft connection (source=$rtkSource)")
            return
        }
        LogUtils.i(TAG, "rtkSource=$rtkSource")
        when (rtkSource) {
            RTKReferenceStationSource.CUSTOM_NETWORK_SERVICE -> startRtkCustomNetworkService()
            RTKReferenceStationSource.QX_NETWORK_SERVICE -> startQxRtkService()
            RTKReferenceStationSource.NTRIP_NETWORK_SERVICE -> startCMCCRtkService()
            RTKReferenceStationSource.BASE_STATION -> {
                LogUtils.i(TAG, "D-RTK2 固件底层已实现自动重连，不需要外部手动重连")
            }
            else -> {
                LogUtils.e(TAG, "UnKnown rtkSource:$rtkSource")
            }
        }
    }

    /**
     * Stops the RTK service, which frees the login at the RTK provider.
     *
     * @param isStopByUser the pilot disconnected the service: it stays stopped until the pilot
     * starts it again. Otherwise the next automatic trigger may start it again.
     */
    @Synchronized
    fun stopRtkService(isStopByUser: Boolean = false) {
        log("RTK service stop requested " + if (isStopByUser) "by the pilot" else "automatically")
        if (isStopByUser) {
            isStoppedByUser = true
        }
        handle.removeCallbacks(retry)
        isRestartPending = false
        val isActive = isHasStartRTK.get() || isStartRTKing.get()
        startId++
        setStartRTKState(false)
        isHasStartRTK.set(false)
        if (!isActive && !isStopByUser) {
            log("Not stopping the RTK service: it is not running")
            return
        }
        val source = if (isActive) serviceSource else rtkSource
        val manager = networkRTKManager(source) ?: run {
            log("Not stopping the RTK service: $source is no network RTK source")
            return
        }
        manager.stopNetworkRTKService(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                log("RTK service for $source stopped")
            }

            override fun onFailure(error: IDJIError) {
                log("Stopping the RTK service for $source failed: $error")
            }
        })
    }

    private fun networkRTKManager(source: RTKReferenceStationSource): INetworkRTKManager? = when (source) {
        RTKReferenceStationSource.CUSTOM_NETWORK_SERVICE -> customManager
        RTKReferenceStationSource.QX_NETWORK_SERVICE -> qxRTKManager
        RTKReferenceStationSource.NTRIP_NETWORK_SERVICE -> cmccRtkManager
        else -> null
    }


    @Synchronized
    private fun startCMCCRtkService() {
        val rtkNetworkCoordinateSystem = RTKUtil.getNetRTKCoordinateSystem(RTKReferenceStationSource.NTRIP_NETWORK_SERVICE)
        LogUtils.i(TAG, "startCMCCRtkService,rtkNetworkCoordinateSystem=$rtkNetworkCoordinateSystem")
        if (rtkNetworkCoordinateSystem == null) {
            log("Not starting the RTK service: no coordinate system for $rtkSource")
            return
        }
        restartService(cmccRtkManager, StringUtils.getResStr(R.string.uxsdk_rtk_setting_menu_setting_fail)) { callback ->
            cmccRtkManager.startNetworkRTKService(rtkNetworkCoordinateSystem, callback)
        }
    }

    private fun showToast(msg: String) {
        ViewUtil.showToast(ContextUtil.getContext(), msg, Toast.LENGTH_SHORT)
    }

    /**
     * 启动千寻RTK
     */
    @Synchronized
    private fun startQxRtkService() {
        var rtkNetworkCoordinateSystem = RTKUtil.getNetRTKCoordinateSystem(RTKReferenceStationSource.QX_NETWORK_SERVICE)
        LogUtils.i(TAG, "startQxRtkService rtkNetworkCoordinateSystem=$rtkNetworkCoordinateSystem")
        if (rtkNetworkCoordinateSystem != null) {
            startQxRtkService(rtkNetworkCoordinateSystem)
        } else {
            RTKCenter.getInstance().qxrtkManager.getNetworkRTKCoordinateSystem(object :
                CommonCallbacks.CompletionCallbackWithParam<CoordinateSystem> {
                override fun onSuccess(t: CoordinateSystem?) {
                    t?.let { startQxRtkService(it) }
                }

                override fun onFailure(error: IDJIError) {
                    //未实现
                }
            })
        }
    }

    @Synchronized
    private fun startQxRtkService(coordinateSystem: CoordinateSystem) {
        restartService(qxRTKManager, StringUtils.getResStr(R.string.uxsdk_rtk_setting_menu_setting_fail)) { callback ->
            qxRTKManager.startNetworkRTKService(coordinateSystem, callback)
        }
    }


    /**
     * 从本地缓存中获取自定义网络RTK配置信息启动自定义网络RTK
     */
    @Synchronized
    private fun startRtkCustomNetworkService() {
        val rtkCustomNetworkSetting: RTKCustomNetworkSetting? = RTKUtil.getRtkCustomNetworkSetting()
        if (rtkCustomNetworkSetting == null || rtkCustomNetworkSetting.serverAddress.isNullOrEmpty()) {
            log("Not starting the RTK service: no custom network RTK settings saved")
            return
        }
        // The password stays out of the log
        log(
            "Custom network RTK settings: host=${rtkCustomNetworkSetting.serverAddress}, " +
                    "port=${rtkCustomNetworkSetting.port}, mountpoint=${rtkCustomNetworkSetting.mountPoint}, " +
                    "user=${rtkCustomNetworkSetting.userName}"
        )
        restartService(customManager, StringUtils.getResStr(R.string.uxsdk_rtk_setting_menu_customer_rtk_save_failed_tips)) { callback ->
            customManager.customNetworkRTKSettings = rtkCustomNetworkSetting
            customManager.startNetworkRTKService(callback)
        }
    }

    /**
     * Stops the service of [manager] and then starts it through [start], so that there is never
     * more than one connection. [failureTip] is shown to the pilot if a start they asked for fails.
     */
    private fun restartService(
        manager: INetworkRTKManager,
        failureTip: String,
        start: (CommonCallbacks.CompletionCallback) -> Unit,
    ) {
        val id = ++startId
        val source = rtkSource
        serviceSource = source
        isHasStartRTK.set(false)
        setStartRTKState(true)
        log("Stopping the RTK service for $source before starting it")
        manager.stopNetworkRTKService(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                startAfterStop(id, source, failureTip, start)
            }

            override fun onFailure(error: IDJIError) {
                onStartFinished(id, source, "stopping the running service failed: $error", failureTip)
            }
        })
    }

    @Synchronized
    private fun startAfterStop(
        id: Int,
        source: RTKReferenceStationSource,
        failureTip: String,
        start: (CommonCallbacks.CompletionCallback) -> Unit,
    ) {
        if (id != startId) {
            log("Not starting the RTK service for $source: the start was abandoned")
            return
        }
        log("Starting the RTK service for $source")
        start(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                onStartFinished(id, source, null, failureTip)
            }

            override fun onFailure(error: IDJIError) {
                onStartFinished(id, source, error.toString(), failureTip)
            }
        })
    }

    /** Records the outcome of a start; [error] is null if it succeeded. */
    @Synchronized
    private fun onStartFinished(id: Int, source: RTKReferenceStationSource, error: String?, failureTip: String) {
        if (id != startId) {
            log("Ignoring the result of an abandoned RTK service start for $source: ${error ?: "success"}")
            if (error == null && !isStartRTKing.get() && !isHasStartRTK.get()) {
                // The service was stopped while this start was under way, and it came up anyway
                log("Stopping the RTK service for $source again, it started after it was stopped")
                networkRTKManager(source)?.stopNetworkRTKService(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        log("RTK service for $source stopped")
                    }

                    override fun onFailure(error: IDJIError) {
                        log("Stopping the RTK service for $source failed: $error")
                    }
                })
            }
            return
        }
        setStartRTKState(false)
        if (error == null) {
            log("RTK service for $source started")
            isHasStartRTK.set(true)
            handle.removeCallbacks(retry)
            retryDelayMs = FIRST_RETRY_DELAY_MS
        } else {
            log("Starting the RTK service for $source failed: $error")
            isHasStartRTK.set(false)
            if (isStartByUser) {
                showToast(failureTip)
            }
            scheduleRetry()
        }
        runPendingRestart()
    }

    @Synchronized
    private fun onStartTimeout() {
        if (!isStartRTKing.get()) return
        // The start stays valid: if it still succeeds, the retry is cancelled
        log("Starting the RTK service for $serviceSource timed out")
        setStartRTKState(false)
        isHasStartRTK.set(false)
        scheduleRetry()
        runPendingRestart()
    }

    private fun runPendingRestart() {
        if (isRestartPending) {
            isRestartPending = false
            startRtkService(true)
        }
    }

    private fun scheduleRetry() {
        if (isStoppedByUser) return
        log("Retrying to start the RTK service in ${retryDelayMs / 1000} s")
        handle.removeCallbacks(retry)
        handle.postDelayed(retry, retryDelayMs)
        retryDelayMs = min(retryDelayMs * 2, MAX_RETRY_DELAY_MS)
    }

    private fun log(message: String) {
        LogUtils.i(TAG, message)
        connectionEventListener?.invoke(message)
    }


    /**
     * 是否允许启动网络RTK （未判断网络数据模式）
     */
    private fun isNeedStartRtkNetworkService(): Boolean {
        val isConnected: Boolean = FlightControllerKey.KeyConnection.create().get(false)
        return (isConnected
                && isNetworkRTK(rtkSource)
                && NetworkUtils.isNetworkAvailable()
                && !isChannelB()
                && rtkModuleAvailableProcessor.value)
                && !isStartRTKing.get()
    }


    //后续供其他RTK相关Widget使用
    val rtkModuleAvailable: Flowable<Boolean>
        get() = rtkModuleAvailableProcessor.toFlowable()

    /**
     * 判断一个差分数据源是否是网络RTK
     */
    fun isNetworkRTK(source: RTKReferenceStationSource?): Boolean {
        return when (source) {
            RTKReferenceStationSource.QX_NETWORK_SERVICE,
            RTKReferenceStationSource.CUSTOM_NETWORK_SERVICE,
            RTKReferenceStationSource.NTRIP_NETWORK_SERVICE,
            -> true
            else -> false
        }
    }

    /**
     * 是否为B控，非双控机型返回false
     */
    fun isChannelB(): Boolean {
        return RCMode.CHANNEL_B == RemoteControllerKey.KeyRcMachineMode.create().get(RCMode.UNKNOWN)
    }

    private fun setStartRTKState(isRTKStart: Boolean) {
        handle.removeCallbacks(startTimeout)
        isStartRTKing.set(isRTKStart)
        if (isRTKStart) {
            handle.postDelayed(startTimeout, START_TIMEOUT_MS)
        }
    }


}
