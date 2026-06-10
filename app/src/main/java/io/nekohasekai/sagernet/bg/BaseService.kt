package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.NetworkCapabilities
import android.os.*
import android.app.ActivityManager
import android.widget.Toast
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException

private const val NETWORK_RECOVERY_DEBOUNCE_MS = 1_000L

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null
        var networkRecoveryJob: Job? = null

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.reload()
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (SagerNet.power.isDeviceIdleMode) {
                            proxy?.box?.sleep()
                        } else {
                            proxy?.box?.wake()
                            service.handleConnectionRecovery(
                                reconnect = DataStore.wakeReconnect,
                                reset = DataStore.wakeResetConnections
                            )
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    service.resetCoreNetwork()
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> service.stopRunner()
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null
        val lifecycleMutex = Mutex()

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
            }
        }

        val callbackIdMap = mutableMapOf<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun urlTest(): Int {
            if (data?.proxy?.box == null) {
                error("core not started")
            }
            try {
                return Libcore.urlTest(
                    data!!.proxy!!.box,
                    DataStore.connectionTestURL,
                    DataStore.connectionTestTimeout,
                    DataStore.profileTestType
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        override fun isCoreProfilingRunning(): Boolean = Libcore.coreProfilingRunning()

        override fun hasCoreProfilerSnapshot(): Boolean = Libcore.hasCoreProfilerSnapshot()

        override fun performLibcoreGcSweep() {
            if (data?.proxy?.isInitialized() != true) {
                error("Service is not running")
            }
            Libcore.performLibcoreGCSweep()
        }

        override fun startCoreProfiling() {
            if (data?.proxy?.isInitialized() != true) {
                error("Core is not started yet")
            }
            Libcore.startCoreProfiling()
        }

        override fun stopCoreProfiling() {
            Libcore.stopCoreProfiling()
        }

        override fun writeCoreProfilerSnapshot(outputDir: String) {
            if (data?.proxy?.isInitialized() != true && !Libcore.hasCoreProfilerSnapshot()) {
                error("Core is not started yet")
            }
            Libcore.writeCoreProfilerSnapshot(outputDir)
        }

        override fun deleteCoreProfilerSnapshot() {
            Libcore.deleteCoreProfilerSnapshot()
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun masterDnsVPNResolverProgress(found: Int, total: Int, ready: Boolean) = launch {
            broadcast { it.cbMasterDnsVPNResolverProgress(found, total, ready) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
            }
            if (canReloadSelector()) {
                val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                val tag = data.proxy!!.config.profileTagMap[ent?.id] ?: ""
                if (tag.isNotBlank() && ent != null) {
                    // select from GUI
                    val proxy = data.proxy!!
                    runBlocking {
                        proxy.looper?.pauseUpdates {
                            proxy.box.selectOutbound(tag)
                            resetCoreNetwork()
                        } ?: run {
                            proxy.box.selectOutbound(tag)
                            resetCoreNetwork()
                        }
                    }
                    // or select from webui
                    // => selector_OnProxySelected
                }
                return
            }
            val s = data.state
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> stopRunner(true)
                else -> Logs.w("Illegal state $s when invoking use")
            }
        }

        fun canReloadSelector(): Boolean {
            if ((data.proxy?.config?.selectorGroupId ?: -1L) < 0) return false
            val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return false
            val tmpBox = ProxyInstance(ent)
            tmpBox.buildConfigTmp()
            if (tmpBox.lastSelectorGroupId == data.proxy?.lastSelectorGroupId) {
                return true
            }
            return false
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun hasActiveWifiRules(): Boolean {
            return SagerDatabase.rulesDao.enabledRules().any { RuleEntity.hasActiveWifiIdentity(it) }
        }

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        /**
         * Some devices throttle service/native cleanup aggressively while running on battery.
         * Hold a short, local PARTIAL_WAKE_LOCK only for the disconnect/reconnect critical section
         * so VpnService/tun/core teardown is not delayed by idle CPU scheduling.
         */
        private inline fun <T> withStopWakeLock(block: () -> T): T {
            this as Context
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val stopWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:VpnServiceStop"
            ).apply {
                setReferenceCounted(false)
                acquire(15_000L)
            }
            return try {
                block()
            } finally {
                if (stopWakeLock.isHeld) {
                    runCatching { stopWakeLock.release() }.onFailure { Logs.w(it) }
                }
            }
        }

        fun removeForegroundNotification() {
            this as Service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }

        fun killProcesses() {
            data.networkRecoveryJob?.cancel()
            data.networkRecoveryJob = null
            SagerNet.application.nativeInterface.unregisterWifiStateListener()
            SagerNet.application.nativeInterface.setWifiRuleMonitoringEnabled(false)
            data.proxy?.let { proxy ->
                runCatching {
                    runBlocking {
                        proxy.looper?.stop()
                        proxy.looper = null
                    }
                    runCatching {
                        if (Libcore.coreProfilingRunning()) {
                            Libcore.stopCoreProfiling()
                        }
                    }.onFailure { Logs.w(it) }
                    if (proxy.isInitialized()) {
                        proxy.box.closeTimeout(3000)
                    } else {
                        proxy.close()
                    }
                }.onFailure {
                    Logs.w(it)
                    runCatching { proxy.close() }.onFailure { closeError -> Logs.w(closeError) }
                }
            }
            wakeLock?.apply {
                release()
                wakeLock = null
            }
            runOnDefaultDispatcher {
                DefaultNetworkListener.stop(this)
            }
        }

        fun resetCoreNetwork() {
            val proxy = data.proxy
            if (proxy != null && proxy.isInitialized()) {
                runCatching {
                    proxy.box.resetNetwork()
                }.onFailure {
                    Logs.w(it)
                    Libcore.resetAllConnections(true)
                }
                return
            }
            Libcore.resetAllConnections(true)
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            if (data.state == State.Stopping) return
            data.notification?.destroy()
            data.notification = null
            this as Service

            data.changeState(State.Stopping)

            runOnMainDispatcher {
                val currentJob = currentCoroutineContext()[Job]
                val connectingJob = data.connectingJob
                if (connectingJob != null && connectingJob != currentJob) {
                    connectingJob.cancelAndJoin() // ensure stop connecting first
                }

                data.lifecycleMutex.withLock {
                    // Remove the foreground notification promptly, but keep the service alive until
                    // native/VPN cleanup finishes. This makes the visible disconnect state immediate
                    // while avoiding a new start racing the old tun/core instance.
                    runCatching { removeForegroundNotification() }.onFailure { Logs.w(it) }

                    withContext(Dispatchers.IO) {
                        withStopWakeLock {
                            killProcesses()
                        }
                    }

                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                    data.proxy = null

                    DataStore.baseService = null
                    DataStore.vpnService = null

                    // change the state
                    data.changeState(State.Stopped, msg)
                }

                // stop the service if nothing has bound to it
                if (restart) {
                    // Give Android's VpnService/tun teardown a short chance to settle before creating
                    // a new tun instance. This avoids click-to-reconnect races on some devices.
                    delay(300)
                    startRunner()
                } else {
                    stopSelf()
                }
            }
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        fun handleConnectionRecovery(reconnect: Boolean, reset: Boolean) {
            if (reconnect && data.state.canStop) {
                DataStore.pendingResetConnectionsAfterReconnect = reset
                stopRunner(true)
                return
            }
            if (reset) {
                resetCoreNetwork()
            }
        }

        fun isVpnNetwork(network: Network?): Boolean {
            if (network == null) return false
            val capabilities = SagerNet.connectivity.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                return true
            }
            return SagerNet.connectivity.getLinkProperties(network)?.interfaceName?.startsWith("tun") == true
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            val networkChangeRecoveryPolicy = NetworkChangeRecoveryPolicy()

            fun handleNetworkUpdate(network: Network?) {
                SagerNet.underlyingNetwork = network
                SagerNet.application.nativeInterface.syncNetworkState(network)
                DataStore.vpnService?.updateUnderlyingNetwork()
                val link = network?.let { current -> SagerNet.connectivity.getLinkProperties(current) }
                val currentName = link?.interfaceName
                upstreamInterfaceName = currentName
                val decision = networkChangeRecoveryPolicy.onNetworkChanged(
                    interfaceName = currentName,
                    isVpnNetwork = isVpnNetwork(network),
                    reconnectEnabled = DataStore.networkChangeReconnect,
                    resetEnabled = DataStore.networkChangeResetConnections,
                )
                if (decision.reconnect || decision.reset) {
                    Logs.d("Network changed: ${decision.oldInterfaceName} -> ${decision.newInterfaceName}")
                    data.networkRecoveryJob?.cancel()
                    data.networkRecoveryJob = runOnDefaultDispatcher {
                        delay(NETWORK_RECOVERY_DEBOUNCE_MS)
                        if (!data.state.started) return@runOnDefaultDispatcher
                        handleConnectionRecovery(
                            reconnect = decision.reconnect,
                            reset = decision.reset
                        )
                    }
                }
                if (decision.ignoredReconnectForVpn) {
                    Logs.d(
                        "Ignore VPN network change for reconnect: " +
                            "${decision.oldInterfaceName} -> ${decision.newInterfaceName}"
                    )
                }
            }

            DefaultNetworkListener.start(this) {
                handleNetworkUpdate(it)
            }
            runCatching {
                DefaultNetworkListener.get()
            }.onSuccess { network ->
                handleNetworkUpdate(network)
            }.onFailure { error ->
                Logs.w("Unable to fetch initial default network: ${error.message}")
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DataStore.baseService = this

            val data = data
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            runOnDefaultDispatcher {
                SubscriptionUpdater.syncBootReceiverEnabled()
            }
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null
                    )
                }
                data.closeReceiverRegistered = true
            }

            data.changeState(State.Connecting)
            data.connectingJob = runOnDefaultDispatcher {
                data.lifecycleMutex.withLock {
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            data.notification = createNotification(ServiceNotification.genTitle(profile))
                        }

                        Executable.killAll()    // clean up old processes
                        val hasActiveWifiRules = hasActiveWifiRules()
                        SagerNet.application.nativeInterface.setWifiRuleMonitoringEnabled(hasActiveWifiRules)
                        SagerNet.application.nativeInterface.unregisterWifiStateListener()
                        preInit()
                        proxy.init()
                        if (hasActiveWifiRules) {
                            SagerNet.application.nativeInterface.registerWifiStateListener()
                            SagerNet.application.nativeInterface.notifyWifiStateChanged("post-init")
                        }
                        DataStore.currentProfile = profile.id

                        proxy.processes = GuardedProcessPool {
                            Logs.w(it)
                            stopRunner(false, it.readableMessage)
                        }

                        startProcesses()
                        if (DataStore.enableCoreProfiling) {
                            Libcore.startCoreProfiling()
                        }
                        data.changeState(State.Connected)
                        SagerNet.application.nativeInterface.maybeShowRedactedWifiToastOnConnect()

                        lateInit()
                        if (DataStore.pendingResetConnectionsAfterReconnect) {
                            DataStore.pendingResetConnectionsAfterReconnect = false
                            resetCoreNetwork()
                        }
                    } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                    } catch (_: UnknownHostException) {
                        stopRunner(false, getString(R.string.invalid_server))
                    } catch (e: PluginManager.PluginNotFoundException) {
                        withContext(Dispatchers.Main.immediate) {
                            Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                        }
                        Logs.w(e)
                        data.binder.missingPlugin(e.plugin)
                        stopRunner(false, null)
                    } catch (exc: Throwable) {
                        if (exc.readableMessage.contains("no working DNS resolvers found", ignoreCase = true)) {
                            withContext(Dispatchers.Main.immediate) {
                                Toast.makeText(
                                    this@Interface,
                                    R.string.masterdnsvpn_no_working_dns,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            stopRunner(false, null)
                            return@withLock
                        }
                        if (exc.javaClass.name.endsWith("proxyerror")) {
                            // error from golang
                            Logs.w(exc.readableMessage)
                        } else {
                            Logs.w(exc)
                        }
                        stopRunner(
                            false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                        )
                    } finally {
                        data.connectingJob = null
                    }
                }
            }
            return Service.START_NOT_STICKY
        }
    }

}
