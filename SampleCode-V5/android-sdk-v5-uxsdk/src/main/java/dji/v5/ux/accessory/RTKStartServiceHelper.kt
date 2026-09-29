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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


/**
 * Description :用于实现自动重连RTK逻辑
 *
 * Network RTK providers usually accept only one connection per login at a time, so the service is
 * only started when it is not running or starting already. Automatic triggers (the aircraft
 * connecting, the RTK source becoming known, the flight screen opening) leave a running service
 * alone; only the pilot saving the settings or choosing a coordinate system restarts it. A failed
 * start is not retried, the pilot saves again instead.
 *
 * @author: Byte.Cai
 *  date : 2022/8/16
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
object RTKStartServiceHelper {
    private const val TAG = "RTKStartServiceHelper"
    /**
     * Time between stopping and starting the service again, so that the RTK provider has noticed
     * the old connection is gone before the new one logs in with the same login.
     */
    private const val RESTART_DELAY_MS = 1_000L
    /** Covers the [RESTART_DELAY_MS] plus the time the SDK takes to start the service. */
    private const val START_TIMEOUT_MS = RESTART_DELAY_MS + 15_000L

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
    /** Identifies the latest start, so that the callbacks of an abandoned start are ignored. */
    private var startId = 0
    /** A start whose callback never arrives must not block later starts. */
    private val startTimeout = Runnable {
        log("Starting the RTK service for $serviceSource timed out")
        isStartRTKing.set(false)
    }

    /**
     * Receives a description of every start and stop of the RTK service, including why it
     * happened or was skipped. Called on arbitrary threads.
     */
    var connectionEventListener: ((String) -> Unit)? = null

    /** Host and port of the NTRIP caster the custom network service was last started for. */
    @Volatile
    private var startedCaster: Pair<String, Int>? = null
    /** Runs [NtripConnections] off the main thread, one call after the other. */
    private val casterConnectionExecutor = Executors.newSingleThreadExecutor()


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
     * @param isStartByUser the pilot asked for it, e.g. by saving the settings: the service is
     * restarted so that it picks up new settings. Otherwise a service that is running or
     * starting is left alone.
     */
    @Synchronized
    fun startRtkService(isStartByUser:Boolean=false) {
        log("RTK service start requested " + if (isStartByUser) "by the pilot" else "automatically")
        if (!isStartByUser) {
            val reason = when {
                isStartRTKing.get() -> "a start is already in progress"
                isHasStartRTK.get() && serviceSource == rtkSource -> "it is already running"
                else -> null
            }
            if (reason != null) {
                log("Not starting the RTK service: $reason")
                return
            }
        }
        this.isStartByUser =isStartByUser
        if (!isNeedStartRtkNetworkService()) {
            log("Not starting the RTK service: no RTK module, network RTK source, network or aircraft connection (source=$rtkSource)")
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

    /** Stops the RTK service, which frees the login at the RTK provider. */
    @Synchronized
    fun stopRtkService() {
        log("RTK service stop requested")
        startId++
        setStartRTKState(false)
        isHasStartRTK.set(false)
        stopService(rtkSource)
    }

    private fun stopService(source: RTKReferenceStationSource) {
        val manager = when (source) {
            RTKReferenceStationSource.CUSTOM_NETWORK_SERVICE -> customManager
            RTKReferenceStationSource.QX_NETWORK_SERVICE -> qxRTKManager
            RTKReferenceStationSource.NTRIP_NETWORK_SERVICE -> cmccRtkManager
            else -> {
                log("Not stopping the RTK service: $source is no network RTK source")
                return
            }
        }
        manager.stopNetworkRTKService(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                log("RTK service for $source stopped")
                releaseCasterConnections(source)
            }

            override fun onFailure(error: IDJIError) {
                log("Stopping the RTK service for $source failed: $error")
            }
        })
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
            startedCaster = rtkCustomNetworkSetting.serverAddress to rtkCustomNetworkSetting.port
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
                log("RTK service for $source stopped, starting it in ${RESTART_DELAY_MS / 1000} s")
                releaseCasterConnections(source) {
                    handle.postDelayed({
                        synchronized(this@RTKStartServiceHelper) {
                            if (id != startId) {
                                log("Not starting the RTK service for $source: the start was abandoned")
                                return@postDelayed
                            }
                            log("Starting the RTK service for $source")
                            start(object : CommonCallbacks.CompletionCallback {
                                override fun onSuccess() = onStartFinished(id, source, null, failureTip)
                                override fun onFailure(error: IDJIError) = onStartFinished(id, source, error.toString(), failureTip)
                            })
                        }
                    }, RESTART_DELAY_MS)
                }
            }

            override fun onFailure(error: IDJIError) {
                onStartFinished(id, source, "stopping the running service failed: $error", failureTip)
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
                stopService(source)
            }
            return
        }
        setStartRTKState(false)
        logCasterConnections(source)
        if (error == null) {
            log("RTK service for $source started")
            isHasStartRTK.set(true)
        } else {
            log("Starting the RTK service for $source failed: $error")
            if (isStartByUser) {
                showToast(failureTip)
            }
        }
    }

    /**
     * Shuts down the connections to the caster that a stopped custom network service left open,
     * see [NtripConnections], and then runs [then].
     */
    private fun releaseCasterConnections(source: RTKReferenceStationSource, then: () -> Unit = {}) {
        val caster = startedCaster.takeIf { source == RTKReferenceStationSource.CUSTOM_NETWORK_SERVICE }
        casterConnectionExecutor.execute {
            if (caster != null) {
                val (host, port) = caster
                runCatching { NtripConnections.shutDown(host, port) }
                    .onSuccess {
                        log(
                            if (it.isEmpty()) "No connection to $host:$port left open"
                            else "Shut down ${it.size} connection(s) to $host:$port left open: ${it.joinToString()}"
                        )
                    }
                    .onFailure { log("Looking for connections to $host:$port failed: $it") }
            }
            then()
        }
    }

    /** Logs the open connections to the caster of a custom network service. */
    private fun logCasterConnections(source: RTKReferenceStationSource) {
        val (host, port) = startedCaster.takeIf { source == RTKReferenceStationSource.CUSTOM_NETWORK_SERVICE } ?: return
        casterConnectionExecutor.execute {
            runCatching { NtripConnections.find(host, port) }
                .onSuccess { log("${it.size} connection(s) to $host:$port open: ${it.joinToString()}") }
                .onFailure { log("Looking for connections to $host:$port failed: $it") }
        }
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
