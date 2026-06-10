package io.nekohasekai.sagernet.ui

import android.content.DialogInterface
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.ProfileStatusUpdater
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import okhttp3.internal.closeQuietly
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(DelicateCoroutinesApi::class)
object GroupConnectionTestController {
    private enum class TestType {
        TcpPing,
        UrlTest,
    }

    private val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
    private val finishedN = AtomicInteger(0)
    private val nextRunId = AtomicLong(0)
    private val testJobs = mutableListOf<Job>()

    @Volatile
    private var activeRunId = 0L
    private var testType: TestType? = null
    private var mainJob: Job? = null
    private var notification: ConnectionTestNotification? = null
    private var dialog: AlertDialog? = null
    private var binding: LayoutProgressListBinding? = null
    private var lastProfile: ProxyEntity? = null
    private var proxyN = 0
    private var groupId = 0L
    private var title = ""
    private var minimized = false
    private var restorePending = false
    @Volatile
    private var completing = false

    val activeGroupId: Long
        get() = groupId

    val isActive: Boolean
        get() = DataStore.runningTest && testType != null && !completing

    val isRestorePending: Boolean
        get() = restorePending

    private fun isActive(runId: Long): Boolean {
        return activeRunId == runId && isActive
    }

    fun startTcpPing(fragment: ConfigurationFragment) {
        if (DataStore.runningTest) return
        val group = DataStore.currentGroup()
        start(fragment, TestType.TcpPing, group.id, "[${group.displayName()}] ${fragment.getString(R.string.connection_test)}")
    }

    fun startUrlTest(fragment: ConfigurationFragment) {
        if (DataStore.runningTest) return
        val group = DataStore.currentGroup()
        start(fragment, TestType.UrlTest, group.id, "[${group.displayName()}] ${fragment.getString(R.string.connection_test)}")
    }

    fun requestRestore(): Boolean {
        if (!isActive) return false
        restorePending = true
        return true
    }

    fun cancelFromNotification() {
        if (isActive) {
            complete(cancelJobs = true)
        } else {
            mainJob?.cancel()
            testJobs.forEach { it.cancel() }
            DataStore.runningTest = false
            activeRunId = 0L
            testType = null
            restorePending = false
            minimized = false
            notification?.updateNotification(finishedN.get().coerceAtMost(proxyN), proxyN, finished = true)
            notification = null
        }
    }

    fun restore(fragment: ConfigurationFragment) {
        if (!requestRestore()) return
        if (showDialog(fragment)) {
            minimized = false
            restorePending = false
            notification?.updateNotification(finishedN.get().coerceAtMost(proxyN), proxyN, finished = true)
            notification = null
        }
    }

    fun attach(fragment: ConfigurationFragment) {
        if (isActive && (!minimized || restorePending) && showDialog(fragment)) {
            minimized = false
            restorePending = false
            notification?.updateNotification(finishedN.get().coerceAtMost(proxyN), proxyN, finished = true)
            notification = null
        }
    }

    fun detach() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        binding = null
    }

    private fun start(fragment: ConfigurationFragment, type: TestType, testGroupId: Long, testTitle: String) {
        DataStore.runningTest = true
        val runId = nextRunId.incrementAndGet()
        activeRunId = runId
        testType = type
        groupId = testGroupId
        title = testTitle
        proxyN = 0
        minimized = false
        restorePending = false
        completing = false
        lastProfile = null
        results.clear()
        finishedN.set(0)
        testJobs.clear()
        showDialog(fragment, runId)

        mainJob = runOnDefaultDispatcher {
            try {
                when (type) {
                    TestType.TcpPing -> runTcpPing(runId)
                    TestType.UrlTest -> runUrlTest(runId)
                }
                testJobs.joinAll()
            } finally {
                runOnMainDispatcher {
                    complete(runId, cancelJobs = false)
                }
            }
        }
    }

    private suspend fun CoroutineScope.runTcpPing(runId: Long) {
        val profilesList = SagerDatabase.proxyDao.getByGroup(groupId).filter {
            it.requireBean().canTCPing()
        }
        if (!isActive(runId)) return
        proxyN = profilesList.size
        runOnMainDispatcher { updateUi() }
        val profiles = ConcurrentLinkedQueue(profilesList)
        repeat(DataStore.connectionTestConcurrent) {
            testJobs.add(launch(Dispatchers.IO) {
                while (GroupConnectionTestController.isActive(runId)) {
                    val profile = profiles.poll() ?: break
                    profile.status = 0
                    var address = profile.requireBean().serverAddress
                    if (!address.isIpAddress()) {
                        try {
                            SagerNet.underlyingNetwork?.getAllByName(address)?.apply {
                                if (isNotEmpty()) {
                                    address = this[0].hostAddress
                                }
                            }
                        } catch (ignored: UnknownHostException) {
                        }
                    }
                    if (!GroupConnectionTestController.isActive(runId)) break
                    if (!address.isIpAddress()) {
                        profile.status = 2
                        profile.error = app.getString(R.string.connection_test_domain_not_found)
                        update(runId, profile)
                        continue
                    }
                    try {
                        val socket = SagerNet.underlyingNetwork?.socketFactory?.createSocket() ?: Socket()
                        try {
                            socket.soTimeout = 3000
                            socket.bind(InetSocketAddress(0))
                            val start = SystemClock.elapsedRealtime()
                            socket.connect(
                                InetSocketAddress(address, profile.requireBean().serverPort),
                                3000
                            )
                            if (!GroupConnectionTestController.isActive(runId)) break
                            profile.status = 1
                            profile.ping = (SystemClock.elapsedRealtime() - start).toInt()
                            update(runId, profile)
                        } finally {
                            socket.closeQuietly()
                        }
                    } catch (e: Exception) {
                        if (!GroupConnectionTestController.isActive(runId)) break
                        val message = e.readableMessage
                        profile.status = 2
                        when {
                            !message.contains("failed:") -> {
                                profile.error = app.getString(R.string.connection_test_timeout_error)
                            }

                            message.contains("ECONNREFUSED") -> {
                                profile.error = app.getString(R.string.connection_test_refused)
                            }

                            message.contains("ENETUNREACH") -> {
                                profile.error = app.getString(R.string.connection_test_unreachable)
                            }

                            else -> {
                                profile.status = 3
                                profile.error = message
                            }
                        }
                        update(runId, profile)
                    }
                }
            })
        }
    }

    private suspend fun CoroutineScope.runUrlTest(runId: Long) {
        val activeProfileId = DataStore.currentProfile.takeIf {
            DataStore.serviceState.connected && it > 0L
        }
        val profilesList = SagerDatabase.proxyDao.getByGroup(groupId)
            .filterNot {
                it.id == activeProfileId || UrlTest.isUnsupportedByeDPIProfile(it)
            }
        if (!isActive(runId)) return
        proxyN = profilesList.size
        runOnMainDispatcher { updateUi() }
        val profiles = ConcurrentLinkedQueue(profilesList)
        repeat(DataStore.connectionTestConcurrent) {
            testJobs.add(launch(Dispatchers.IO) {
                val urlTest = UrlTest()
                while (GroupConnectionTestController.isActive(runId)) {
                    val profile = profiles.poll() ?: break
                    profile.status = 0

                    try {
                        val result = urlTest.doTest(profile)
                        profile.status = 1
                        profile.ping = result
                    } catch (e: PluginManager.PluginNotFoundException) {
                        profile.status = 2
                        profile.error = e.readableMessage
                    } catch (e: Exception) {
                        profile.status = 3
                        profile.error = e.readableMessage
                    }

                    update(runId, profile)
                }
            })
        }
    }

    private fun update(runId: Long, profile: ProxyEntity) {
        if (!isActive(runId)) return
        results.add(profile)
        lastProfile = profile
        finishedN.addAndGet(1)
        runOnMainDispatcher {
            if (!isActive(runId)) return@runOnMainDispatcher
            val completed = finishedN.get().coerceAtMost(proxyN)
            val finished = completed >= proxyN
            notification?.updateNotification(completed, proxyN, finished)
            updateUi()
        }
    }

    private fun showDialog(fragment: ConfigurationFragment, runId: Long = activeRunId): Boolean {
        if (!fragment.isAdded || fragment.view == null) return false
        if (dialog?.isShowing == true) return true
        val newBinding = LayoutProgressListBinding.inflate(fragment.layoutInflater)
        binding = newBinding
        dialog = MaterialAlertDialogBuilder(fragment.requireContext())
            .setView(newBinding.root)
            .setPositiveButton(R.string.minimize, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .show()
            .apply {
                getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    minimize()
                }
                getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                    complete(runId, cancelJobs = true)
                }
            }
        updateUi()
        return true
    }

    private fun minimize() {
        minimized = true
        notification = ConnectionTestNotification(SagerNet.application, title)
        notification?.updateNotification(finishedN.get().coerceAtMost(proxyN), proxyN, finished = false)
        detach()
    }

    private fun complete(runId: Long = activeRunId, cancelJobs: Boolean) {
        if (activeRunId != runId || completing) return
        completing = true
        val finishedResults = results.toList()
        val finishedGroupId = groupId
        if (cancelJobs) {
            mainJob?.cancel()
            testJobs.forEach { it.cancel() }
        }
        detach()
        notification?.updateNotification(finishedN.get().coerceAtMost(proxyN), proxyN, finished = true)
        notification = null
        activeRunId = 0L
        mainJob = null
        testType = null
        groupId = 0L
        proxyN = 0
        lastProfile = null
        minimized = false
        restorePending = false
        DataStore.runningTest = false
        results.clear()
        testJobs.clear()
        runOnDefaultDispatcher {
            finishedResults.forEach {
                try {
                    ProfileStatusUpdater.update(
                        it.id,
                        it.status,
                        it.ping,
                        it.error,
                        reloadDelayOrderedGroup = false
                    )
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
            GroupManager.postReload(finishedGroupId)
            completing = false
        }
    }

    private fun updateUi() {
        val activeBinding = binding ?: return
        val context = dialog?.context ?: return
        val profile = lastProfile
        activeBinding.progress.text = "${finishedN.get().coerceAtMost(proxyN)} / $proxyN"
        if (profile == null) return

        var profileStatusText: String? = null
        var profileStatusColor = 0

        when (profile.status) {
            -1 -> {
                profileStatusText = profile.error
                profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
            }

            0 -> {
                profileStatusText = context.getString(R.string.connection_test_testing)
                profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
            }

            1 -> {
                profileStatusText = context.getString(R.string.available, profile.ping)
                profileStatusColor = context.getColour(R.color.material_green_500)
            }

            2 -> {
                profileStatusText = profile.error
                profileStatusColor = context.getColour(R.color.material_red_500)
            }

            3 -> {
                val err = profile.error ?: ""
                val msg = Protocols.genFriendlyMsg(err)
                profileStatusText = if (msg != err) msg else context.getString(R.string.unavailable)
                profileStatusColor = context.getColour(R.color.material_red_500)
            }
        }

        activeBinding.nowTesting.text = SpannableStringBuilder().apply {
            append("\n" + profile.displayName())
            append("\n")
            append(
                profile.displayType(),
                ForegroundColorSpan(context.getProtocolColor(profile.type)),
                SPAN_EXCLUSIVE_EXCLUSIVE
            )
            append(" ")
            append(
                profileStatusText,
                ForegroundColorSpan(profileStatusColor),
                SPAN_EXCLUSIVE_EXCLUSIVE
            )
            append("\n")
        }
    }
}
