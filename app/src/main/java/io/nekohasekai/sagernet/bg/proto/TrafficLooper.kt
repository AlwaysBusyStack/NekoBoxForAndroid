package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TrafficLooper
    (
    val data: BaseService.Data, private val sc: CoroutineScope
) {

    private var job: Job? = null
    private var trafficUpdater: TrafficUpdater? = null
    private val access = Mutex()
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>() // id to 1 data
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>() // tag to 1 data

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        // finally traffic post
        if (!DataStore.profileTrafficStatistics || DataStore.profileTrafficUpdateInterval <= 0) return
        val traffic = mutableMapOf<Long, TrafficData>()
        access.withLock {
            trafficUpdater?.updateAll()
            data.proxy?.config?.trafficMap?.forEach { (_, ents) ->
                for (ent in ents) {
                    val item = idMap[ent.id] ?: return@forEach
                    ent.rx = item.rx
                    ent.tx = item.tx
                    traffic[ent.id] = TrafficData(
                        id = ent.id,
                        rx = ent.rx,
                        tx = ent.tx,
                    )
                }
            }
        }
        traffic.values.forEach {
            ProfileManager.updateTraffic(it.id, it.rx, it.tx)
        }
        data.binder.broadcast { b ->
            for (t in traffic) {
                b.cbTrafficUpdate(t.value)
            }
        }
        Logs.d("finally traffic post done")
    }

    fun start() {
        job = sc.launch { loop() }
    }

    var selectorNowId = -114514L
    var selectorNowFakeTag = ""

    suspend fun selectMain(id: Long) {
        access.withLock {
            selectMainLocked(id)
        }
    }

    suspend fun pauseUpdates(work: () -> Unit) {
        access.withLock {
            work()
        }
    }

    suspend fun selectorChanged(id: Long, work: () -> Unit) {
        access.withLock {
            work()
            selectMainLocked(id)
        }
    }

    private fun selectMainLocked(id: Long) {
        if (id == selectorNowId) return
        Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
        val oldData = idMap[selectorNowId]
        val newData = idMap[id] ?: return
        oldData?.apply {
            tag = selectorNowFakeTag
            ignore = true
            // post traffic when switch
            if (DataStore.profileTrafficStatistics && DataStore.profileTrafficUpdateInterval > 0) {
                data.proxy?.config?.trafficMap?.get(tag)?.firstOrNull()?.let {
                    it.rx = rx
                    it.tx = tx
                    runOnDefaultDispatcher {
                        ProfileManager.updateTraffic(it.id, it.rx, it.tx)
                    }
                }
            }
        }
        selectorNowFakeTag = newData.tag
        selectorNowId = id
        newData.apply {
            tag = TAG_PROXY
            ignore = false
        }
    }

    private suspend fun loop() {
        val speedDelayMs = TrafficLooperPolicy.sanitizeInterval(DataStore.speedInterval.toLong())
        val trafficDelayMs = TrafficLooperPolicy.sanitizeInterval(DataStore.profileTrafficUpdateInterval.toLong())
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics && trafficDelayMs > 0
        if (speedDelayMs <= 0L && !profileTrafficStatistics) return
        var lastSpeedUpdate = 0L
        var lastTrafficUpdate = 0L

        var proxy: ProxyInstance?

        // for display
        val itemBypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)

        while (sc.isActive) {
            val hasSpeedConsumer = TrafficLooperPolicy.hasSpeedConsumer(
                hasForegroundCallback = data.binder.callbackIdMap.containsValue(
                    SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
                ),
                notificationListening = data.notification?.listenPostSpeed == true,
            )
            val delayMs = TrafficLooperPolicy.nextDelay(
                speedDelayMs = speedDelayMs,
                trafficDelayMs = trafficDelayMs,
                profileTrafficStatistics = profileTrafficStatistics,
                hasSpeedConsumer = hasSpeedConsumer,
            ) ?: return
            val now = System.currentTimeMillis()
            val shouldPostSpeed = hasSpeedConsumer &&
                TrafficLooperPolicy.isDue(speedDelayMs, lastSpeedUpdate, now)
            val shouldPostTraffic = profileTrafficStatistics &&
                TrafficLooperPolicy.isDue(trafficDelayMs, lastTrafficUpdate, now)
            if (!shouldPostSpeed && !shouldPostTraffic) {
                delay(delayMs)
                continue
            }

            proxy = data.proxy
            if (proxy == null) {
                delay(delayMs)
                continue
            }
            val currentProxy = proxy

            if (trafficUpdater == null) {
                if (!currentProxy.isInitialized()) {
                    delay(delayMs)
                    continue
                }
                val tags = access.withLock {
                    idMap.clear()
                    tagMap.clear()
                    idMap[-1] = itemBypass
                    //
                    val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
                    currentProxy.config.trafficMap.forEach { (tag, ents) ->
                        tags.add(tag)
                        for (ent in ents) {
                            val item = TrafficUpdater.TrafficLooperData(
                                tag = tag,
                                rx = ent.rx,
                                tx = ent.tx,
                                rxBase = ent.rx,
                                txBase = ent.tx,
                                ignore = currentProxy.config.selectorGroupId >= 0L,
                            )
                            idMap[ent.id] = item
                            tagMap[tag] = item
                            Logs.d("traffic count $tag to ${ent.id}")
                        }
                    }
                    if (currentProxy.config.selectorGroupId >= 0L) {
                        selectMainLocked(currentProxy.config.mainEntId)
                    }
                    tags
                }
                //
                trafficUpdater = TrafficUpdater(
                    box = currentProxy.box, items = idMap.values.toList()
                )
                currentProxy.box.setV2rayStats(tags.joinToString("\n"))
            }

            val updater = trafficUpdater ?: return
            val traffic = access.withLock {
                updater.updateAll()
                if (!sc.isActive) return@withLock null

                // add all non-bypass to "main"
                var mainTxRate = 0L
                var mainRxRate = 0L
                var mainTx = 0L
                var mainRx = 0L
                tagMap.forEach { (_, it) ->
                    if (!it.ignore) {
                        mainTxRate += it.txRate
                        mainRxRate += it.rxRate
                    }
                    mainTx += it.tx - it.txBase
                    mainRx += it.rx - it.rxBase
                }

                // speed
                val speed = SpeedDisplayData(
                    mainTxRate,
                    mainRxRate,
                    if (showDirectSpeed) itemBypass.txRate else 0L,
                    if (showDirectSpeed) itemBypass.rxRate else 0L,
                    mainTx,
                    mainRx
                )
                val traffic = if (profileTrafficStatistics) {
                    idMap.map { (id, item) -> TrafficData(id = id, rx = item.rx, tx = item.tx) }
                } else {
                    emptyList()
                }
                speed to traffic
            } ?: return
            val speed = traffic.first
            val profileTraffic = traffic.second

            // broadcast (MainActivity)
            if (data.state == BaseService.State.Connected
                && (shouldPostSpeed || shouldPostTraffic)
                && data.binder.callbackIdMap.containsValue(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            ) {
                data.binder.broadcast { b ->
                    if (data.binder.callbackIdMap[b] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        if (shouldPostSpeed) {
                            b.cbSpeedUpdate(speed)
                        }
                        if (shouldPostTraffic) {
                            profileTraffic.forEach {
                                b.cbTrafficUpdate(it) // display
                            }
                        }
                    }
                }
            }
            if (shouldPostSpeed) {
                lastSpeedUpdate = now
            }
            if (shouldPostTraffic) {
                lastTrafficUpdate = now
            }

            // ServiceNotification
            if (shouldPostSpeed) {
                data.notification?.apply {
                    if (listenPostSpeed) postNotificationSpeedUpdate(speed)
                }
            }

            delay(delayMs)
        }
    }
}
