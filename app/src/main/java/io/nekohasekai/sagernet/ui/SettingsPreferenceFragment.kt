package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.preference.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TrafficFragmentation
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.CrashHandler
import io.nekohasekai.sagernet.utils.CustomTheme
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.*
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import moe.matsuri.nb4a.utils.SendLog
import moe.matsuri.nb4a.utils.Util
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SettingsPreferenceFragment : PreferenceFragmentCompat(), OnPreferenceDataStoreChangeListener {

    private lateinit var isProxyApps: MaterialSwitchPreference
    private var syncingProxyAppsPreference = false
    private lateinit var globalCustomConfig: EditConfigPreference
    private lateinit var enableCoreProfiling: MaterialSwitchPreference
    private lateinit var performLibcoreGcSweep: Preference
    private lateinit var saveCoreProfilerSnapshot: Preference
    private lateinit var deleteCoreProfilerSnapshot: Preference
    private var coreProfilerProgressDialog: AlertDialog? = null


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (showMaterialEditTextPreferenceDialog(preference)) return
        super.onDisplayPreferenceDialog(preference)
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.global_preferences)

        val appTheme = findPreference<ColorPickerPreference>(Key.APP_THEME)!!
        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)!!
        val configureCustomTheme = findPreference<Preference>("configureCustomTheme")!!.apply {
            setOnPreferenceClickListener {
                (activity as? MainActivity)?.displayFragment(CustomThemeFragment())
                true
            }
        }

        fun syncCustomThemePreference(themeValue: Int = DataStore.appTheme) {
            configureCustomTheme.isVisible = themeValue == Theme.CUSTOM && CustomTheme.isSupported
        }

        fun syncNightThemePreference(themeValue: Int) {
            val forceNightMode = Theme.isNightModeForced(themeValue)
            if (forceNightMode) {
                DataStore.nightTheme = 1
                Theme.currentNightMode = 1
                if (nightTheme.value != "1") {
                    nightTheme.value = "1"
                }
                Theme.applyNightTheme()
            }
            nightTheme.isEnabled = !forceNightMode
        }

        syncNightThemePreference(DataStore.appTheme)
        syncCustomThemePreference(DataStore.appTheme)

        appTheme.setOnPreferenceChangeListener { _, newTheme ->
            val selectedTheme = newTheme as Int
            if (selectedTheme == Theme.CUSTOM) {
                CustomTheme.ensureDefaults(requireContext())
            }
            syncNightThemePreference(selectedTheme)
            syncCustomThemePreference(selectedTheme)
            val theme = Theme.getTheme(selectedTheme)
            app.setTheme(theme)
            requireActivity().apply {
                setTheme(theme)
                ActivityCompat.recreate(this)
            }
            true
        }

        nightTheme.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            Theme.applyNightTheme()
            true
        }
        val appLanguage = findPreference<SimpleMenuPreference>(Key.APP_LANGUAGE)!!
        appLanguage.setOnPreferenceChangeListener { _, newValue ->
            AppLocale.apply(newValue as String)
            true
        }

        val requireProxyInVPN = findPreference<MaterialSwitchPreference>(Key.REQUIRE_PROXY_IN_VPN)!!
        val mixedPort = findPreference<EditTextPreference>(Key.MIXED_PORT)!!
        val mixedUsername = findPreference<EditTextPreference>(Key.MIXED_USERNAME)!!
        val mixedPassword = findPreference<EditTextPreference>(Key.MIXED_PASSWORD)!!
        val serviceMode = findPreference<Preference>(Key.SERVICE_MODE)!!
        val allowAccess = findPreference<Preference>(Key.ALLOW_ACCESS)!!
        val appendHttpProxy = findPreference<MaterialSwitchPreference>(Key.APPEND_HTTP_PROXY)!!
        val strictRoute = findPreference<MaterialSwitchPreference>(Key.STRICT_ROUTE)!!

        val showDirectSpeed = findPreference<MaterialSwitchPreference>(Key.SHOW_DIRECT_SPEED)!!
        val ipv6Mode = findPreference<Preference>(Key.IPV6_MODE)!!
        val trafficSniffing = findPreference<SimpleMenuPreference>(Key.TRAFFIC_SNIFFING)!!

        val bypassLan = findPreference<MaterialSwitchPreference>(Key.BYPASS_LAN)!!
        val bypassLanInCore = findPreference<MaterialSwitchPreference>(Key.BYPASS_LAN_IN_CORE)!!

        val remoteDns = findPreference<EditTextPreference>(Key.REMOTE_DNS)!!
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)!!
        val enableDnsRouting = findPreference<MaterialSwitchPreference>(Key.ENABLE_DNS_ROUTING)!!
        val enableFakeDns = findPreference<MaterialSwitchPreference>(Key.ENABLE_FAKEDNS)!!

        val trafficFragmentation = findPreference<SimpleMenuPreference>(Key.TRAFFIC_FRAGMENTATION)!!
        val fragmentLength = findPreference<EditTextPreference>(Key.FRAGMENT_LENGTH)!!
        val fragmentInterval = findPreference<EditTextPreference>(Key.FRAGMENT_INTERVAL)!!
        val exclaveFragmentMethod = findPreference<SimpleMenuPreference>(Key.EXCLAVE_FRAGMENT_METHOD)!!
        val exclaveFragmentForDirect = findPreference<MaterialSwitchPreference>(Key.EXCLAVE_FRAGMENT_FOR_DIRECT)!!
        val byedpiFragmentCli = findPreference<EditTextPreference>(Key.BYEDPI_FRAGMENT_CLI)!!

        val logLevel = findPreference<LongClickListPreference>(Key.LOG_LEVEL)!!
        val mtu = findPreference<MTUPreference>(Key.MTU)!!
        val certProvider = findPreference<SimpleMenuPreference>(Key.CERT_PROVIDER)!!
        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)!!
        globalCustomConfig.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)
        enableCoreProfiling = findPreference(Key.ENABLE_CORE_PROFILING)!!
        performLibcoreGcSweep = findPreference(Key.PERFORM_LIBCORE_GC_SWEEP)!!
        saveCoreProfilerSnapshot = findPreference(Key.SAVE_CORE_PROFILER_SNAPSHOT)!!
        deleteCoreProfilerSnapshot = findPreference(Key.DELETE_CORE_PROFILER_SNAPSHOT)!!
        syncCoreProfilerPreferences()
        enableCoreProfiling.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (!enabled) {
                cleanupCoreProfilerData {
                    if (isAdded) needRestart()
                }
            } else {
                needRestart()
            }
            syncCoreProfilerPreferences(enabled)
            true
        }
        performLibcoreGcSweep.setOnPreferenceClickListener {
            handlePerformLibcoreGcSweep()
            true
        }
        saveCoreProfilerSnapshot.setOnPreferenceClickListener {
            handleSaveCoreProfilerSnapshot()
            true
        }
        deleteCoreProfilerSnapshot.setOnPreferenceClickListener {
            handleDeleteCoreProfilerSnapshot()
            true
        }

        logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        logLevel.setOnLongClickListener {
            if (context == null) return@setOnLongClickListener true

            val view = EditText(context).apply {
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                var size = DataStore.logBufSize
                if (size == 0) size = 50
                setText(size.toString())
            }

            MaterialAlertDialogBuilder(requireContext()).setTitle("Log buffer size (kb)")
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DataStore.logBufSize = view.text.toString().toInt()
                    if (DataStore.logBufSize <= 0) DataStore.logBufSize = 50
                    needRestart()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        mixedPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        mixedPassword.summaryProvider = ProxyPasswordSummaryProvider

        val metedNetwork = findPreference<Preference>(Key.METERED_NETWORK)!!
        if (Build.VERSION.SDK_INT < 28) {
            metedNetwork.remove()
        }
        isProxyApps = findPreference(Key.PROXY_APPS)!!
        isProxyApps.setOnPreferenceChangeListener { _, newValue ->
            if (syncingProxyAppsPreference) return@setOnPreferenceChangeListener true
            startActivity(Intent(activity, AppManagerActivity::class.java))
            if (newValue as Boolean) DataStore.dirty = true
            newValue
        }
        syncProxyAppsPreference()

        val profileTrafficStatistics =
            findPreference<MaterialSwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)!!
        val profileTrafficUpdateInterval =
            findPreference<SimpleMenuPreference>(Key.PROFILE_TRAFFIC_UPDATE_INTERVAL)!!
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)!!
        profileTrafficStatistics.isEnabled = profileTrafficUpdateInterval.value.toString() != "0"
        profileTrafficUpdateInterval.setOnPreferenceChangeListener { _, newValue ->
            profileTrafficStatistics.isEnabled = newValue.toString() != "0"
            needReload()
            true
        }
        speedInterval.setOnPreferenceChangeListener { _, _ ->
            needReload()
            true
        }

        val tunUnrecognizedTraffic = findPreference<ListPreference>(Key.TUN_UNRECOGNIZED_TRAFFIC)!!
        tunUnrecognizedTraffic.onPreferenceChangeListener = reloadListener
        val tunSystemDnsTraffic = findPreference<ListPreference>(Key.TUN_SYSTEM_DNS_TRAFFIC)!!
        tunSystemDnsTraffic.onPreferenceChangeListener = reloadListener
        val tunDnsWhitelist = findPreference<EditTextPreference>(Key.TUN_DNS_WHITELIST)!!
        val tunDotWhitelist = findPreference<EditTextPreference>(Key.TUN_DOT_WHITELIST)!!
        val tunDohWhitelist = findPreference<EditTextPreference>(Key.TUN_DOH_WHITELIST)!!
        tunDnsWhitelist.setOnBindEditTextListener(EditTextPreferenceModifiers.Multiline)
        tunDotWhitelist.setOnBindEditTextListener(EditTextPreferenceModifiers.Multiline)
        tunDohWhitelist.setOnBindEditTextListener(EditTextPreferenceModifiers.Multiline)
        tunDnsWhitelist.onPreferenceChangeListener = reloadListener
        tunDotWhitelist.onPreferenceChangeListener = reloadListener
        tunDohWhitelist.onPreferenceChangeListener = reloadListener
        val updateTunPrefVisibility = {
            val isVpn = DataStore.serviceMode == Key.MODE_VPN
            tunUnrecognizedTraffic.isVisible = isVpn
            tunSystemDnsTraffic.isVisible = isVpn
            tunDnsWhitelist.isVisible = isVpn
            tunDotWhitelist.isVisible = isVpn
            tunDohWhitelist.isVisible = isVpn
        }
        updateTunPrefVisibility()

        serviceMode.setOnPreferenceChangeListener { _, newValue ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            updateTunPrefVisibility()
            if (newValue == Key.MODE_PROXY) {
                Toast.makeText(requireContext(), R.string.proxy_ip_leak_warning, Toast.LENGTH_LONG).show()
            }
            true
        }

        val tunImplementation = findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)!!
        val resolveDestination = findPreference<MaterialSwitchPreference>(Key.RESOLVE_DESTINATION)!!
        if (DataStore.trafficSniffing > 1) {
            DataStore.trafficSniffing = 1
            trafficSniffing.value = "1"
        }
        val acquireWakeLock = findPreference<MaterialSwitchPreference>(Key.ACQUIRE_WAKE_LOCK)!!
        val hideFromRecentApps = findPreference<MaterialSwitchPreference>(Key.HIDE_FROM_RECENT_APPS)!!
        val enableClashAPI = findPreference<MaterialSwitchPreference>(Key.ENABLE_CLASH_API)!!
        val hideClashAPI = findPreference<MaterialSwitchPreference>(Key.HIDE_CLASH_API)!!
        val resetClashApiSecret = findPreference<Preference>("resetClashApiSecret")!!
        enableClashAPI.setOnPreferenceChangeListener { _, newValue ->
            (activity as MainActivity?)?.refreshNavMenu(newValue as Boolean)
            needReload()
            true
        }
        hideClashAPI.setOnPreferenceChangeListener { _, _ ->
            needReload()
            true
        }
        resetClashApiSecret.setOnPreferenceClickListener {
            DataStore.clashApiSecret = Util.generateCryptoSecurePassword(16, Util.securePasswordCharsNoSymbols)
            needRestart()
            true
        }

        val rulesProvider = findPreference<SimpleMenuPreference>(Key.RULES_PROVIDER)!!
        val rulesGeositeUrl = findPreference<EditTextPreference>(Key.RULES_GEOSITE_URL)!!
        val rulesGeoipUrl = findPreference<EditTextPreference>(Key.RULES_GEOIP_URL)!!
        rulesGeositeUrl.isVisible = DataStore.rulesProvider == DataStore.RULES_PROVIDER_CUSTOM
        rulesGeoipUrl.isVisible = DataStore.rulesProvider == DataStore.RULES_PROVIDER_CUSTOM
        rulesProvider.setOnPreferenceChangeListener { _, newValue ->
            val provider = (newValue as String).toInt()
            rulesGeositeUrl.isVisible = provider == DataStore.RULES_PROVIDER_CUSTOM
            rulesGeoipUrl.isVisible = provider == DataStore.RULES_PROVIDER_CUSTOM
            true
        }

        requireProxyInVPN.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                Toast.makeText(requireContext(), R.string.proxy_ip_leak_warning, Toast.LENGTH_LONG).show()
            }
            needReload()
            true
        }
        mixedPort.onPreferenceChangeListener = reloadListener
        mixedUsername.onPreferenceChangeListener = reloadListener
        mixedPassword.onPreferenceChangeListener = reloadListener
        appendHttpProxy.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                Toast.makeText(requireContext(), R.string.proxy_ip_leak_warning, Toast.LENGTH_LONG).show()
            }
            needReload()
            true
        }
        strictRoute.onPreferenceChangeListener = reloadListener
        showDirectSpeed.onPreferenceChangeListener = reloadListener
        trafficSniffing.onPreferenceChangeListener = reloadListener
        bypassLan.onPreferenceChangeListener = reloadListener
        bypassLanInCore.onPreferenceChangeListener = reloadListener
        mtu.onPreferenceChangeListener = reloadListener

        val concurrentDial = findPreference<MaterialSwitchPreference>(Key.CONCURRENT_DIAL)!!
        concurrentDial.onPreferenceChangeListener = reloadListener

        enableFakeDns.onPreferenceChangeListener = reloadListener
        remoteDns.onPreferenceChangeListener = reloadListener
        directDns.onPreferenceChangeListener = reloadListener
        enableDnsRouting.onPreferenceChangeListener = reloadListener

        ipv6Mode.onPreferenceChangeListener = reloadListener
        allowAccess.onPreferenceChangeListener = reloadListener

        resolveDestination.onPreferenceChangeListener = reloadListener
        tunImplementation.onPreferenceChangeListener = reloadListener
        acquireWakeLock.onPreferenceChangeListener = reloadListener
        certProvider.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        hideFromRecentApps.setOnPreferenceChangeListener { _, newValue ->
            (activity as? MainActivity)?.applyHideFromRecentApps(newValue as Boolean)
            // needReload()
            true
        }

        fun updateTrafficFragmentationVisibility(value: String = DataStore.trafficFragmentation) {
            fragmentLength.isVisible = value == TrafficFragmentation.STARIFLY
            fragmentInterval.isVisible = value == TrafficFragmentation.STARIFLY
            exclaveFragmentMethod.isVisible = value == TrafficFragmentation.EXCLAVE
            exclaveFragmentForDirect.isVisible = value == TrafficFragmentation.EXCLAVE
            byedpiFragmentCli.isVisible = value == TrafficFragmentation.BYEDPI
        }

        if (trafficFragmentation.value != DataStore.trafficFragmentation) {
            trafficFragmentation.value = DataStore.trafficFragmentation
        }
        updateTrafficFragmentationVisibility()
        trafficFragmentation.setOnPreferenceChangeListener { _, newValue ->
            updateTrafficFragmentationVisibility(newValue as String)
            needReload()
            true
        }
        fragmentLength.onPreferenceChangeListener = reloadListener
        fragmentInterval.onPreferenceChangeListener = reloadListener
        exclaveFragmentMethod.onPreferenceChangeListener = reloadListener
        exclaveFragmentForDirect.onPreferenceChangeListener = reloadListener
        byedpiFragmentCli.onPreferenceChangeListener = reloadListener

        // 恢复默认设置功能
        val resetSettings = findPreference<Preference>("resetSettings")!!
        resetSettings.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.confirm)
                setMessage(R.string.reset_settings_message)
                setNegativeButton(R.string.no, null)
                setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
            }.show()
            true
        }

        // 清理缓存功能
        val clearCache = findPreference<Preference>(Key.CLEAR_CACHE)!!
        clearCache.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.clear_cache)
                setMessage(R.string.clear_cache_confirm)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    clearAppCache()
                }
                setNegativeButton(android.R.string.cancel, null)
            }.show()
            true
        }
    }

    override fun onResume() {
        super.onResume()

        syncProxyAppsPreference()
        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.notifyChanged()
        }
        syncCoreProfilerPreferences()
    }

    override fun onStart() {
        super.onStart()
        DataStore.configurationStore.registerChangeListener(this)
    }

    override fun onStop() {
        DataStore.configurationStore.unregisterChangeListener(this)
        super.onStop()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == Key.PROXY_APPS && ::isProxyApps.isInitialized) {
            syncProxyAppsPreference()
        }
    }

    private fun syncProxyAppsPreference() {
        if (!::isProxyApps.isInitialized) return
        syncingProxyAppsPreference = true
        isProxyApps.isChecked = DataStore.proxyApps
        syncingProxyAppsPreference = false
    }

    private fun syncCoreProfilerPreferences(enabled: Boolean = DataStore.enableCoreProfiling) {
        if (!::saveCoreProfilerSnapshot.isInitialized) return
        val coreActive = DataStore.serviceState.started
        saveCoreProfilerSnapshot.isVisible = enabled
        deleteCoreProfilerSnapshot.isVisible = enabled
        if (!enabled) return

        saveCoreProfilerSnapshot.isEnabled = !coreActive
        deleteCoreProfilerSnapshot.isEnabled = !coreActive

        if (coreActive) {
            val collecting = getString(R.string.core_profiler_collecting)
            saveCoreProfilerSnapshot.summary = collecting
            deleteCoreProfilerSnapshot.summary = collecting
            return
        }

        val profilerDataSize = coreProfilerDataSize()
        saveCoreProfilerSnapshot.summary = if (profilerDataSize > 0L) {
            getString(R.string.core_profiler_data_size, formatProfilerMegabytes(profilerDataSize))
        } else {
            getString(R.string.core_profiler_no_data)
        }
        deleteCoreProfilerSnapshot.summary = null
    }

    private fun coreProfilerService(): ISagerNetService? {
        return (activity as? MainActivity)?.connection?.service
    }

    private fun handlePerformLibcoreGcSweep() {
        val service = coreProfilerService()
        if (service == null || !DataStore.serviceState.started) {
            Toast.makeText(requireContext(), R.string.service_is_not_running, Toast.LENGTH_SHORT).show()
            return
        }
        runOnDefaultDispatcher {
            try {
                service.performLibcoreGcSweep()
                onMainDispatcher {
                    Toast.makeText(requireContext(), R.string.done, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                Logs.w(e)
                onMainDispatcher {
                    Toast.makeText(requireContext(), R.string.service_is_not_running, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun cleanupCoreProfilerData(afterCleanup: (() -> Unit)? = null) {
        val service = coreProfilerService()
        runOnDefaultDispatcher {
            runCatching {
                service?.deleteCoreProfilerSnapshot()
                deleteLocalCoreProfilerData()
            }.onFailure { Logs.w(it) }
            onMainDispatcher {
                syncCoreProfilerPreferences(false)
                afterCleanup?.invoke()
            }
        }
    }

    private fun handleSaveCoreProfilerSnapshot() {
        val service = coreProfilerService()
        showCoreProfilerProgress()
        runOnDefaultDispatcher {
            try {
                val exportRoot = File(app.cacheDir, "core-profiler-export").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val profilerDir = File(exportRoot, "profiler").apply { mkdirs() }
                if (service != null) {
                    service.writeCoreProfilerSnapshot(profilerDir.absolutePath)
                } else if (!copyLocalProfilerSnapshot(profilerDir)) {
                    dismissCoreProfilerProgress()
                    showCoreProfilerToast(R.string.core_profiler_no_snapshot)
                    return@runOnDefaultDispatcher
                }

                val zipFile = File(
                    File(app.cacheDir, "log").also { it.mkdirs() },
                    "NB4A-profiler-${profilerTimestamp()}.zip"
                )
                writeProfilerZip(zipFile, profilerDir)
                onMainDispatcher {
                    dismissCoreProfilerProgress()
                    shareProfilerZip(zipFile)
                    syncCoreProfilerPreferences()
                    Toast.makeText(requireContext(), R.string.core_profiler_snapshot_saved, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                dismissCoreProfilerProgress()
                showCoreProfilerError(e)
            }
        }
    }

    private fun handleDeleteCoreProfilerSnapshot() {
        val service = coreProfilerService()
        runOnDefaultDispatcher {
            try {
                service?.deleteCoreProfilerSnapshot()
                deleteLocalCoreProfilerData()
                onMainDispatcher {
                    syncCoreProfilerPreferences()
                    Toast.makeText(requireContext(), R.string.core_profiler_snapshot_deleted, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                showCoreProfilerError(e)
            }
        }
    }

    fun syncServiceState() {
        syncCoreProfilerPreferences()
    }

    private fun deleteLocalCoreProfilerData() {
        File(app.cacheDir, "core-profiler").deleteRecursively()
        File(app.cacheDir, "core-profiler-export").deleteRecursively()
    }

    private fun coreProfilerDataSize(): Long {
        val sourceDir = File(app.cacheDir, "core-profiler")
        if (!sourceDir.exists()) return 0L
        return sourceDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length().coerceAtLeast(0L) }
    }

    private fun formatProfilerMegabytes(bytes: Long): String {
        val megabytes = bytes.toDouble() / (1024.0 * 1024.0)
        val visibleMegabytes = if (megabytes > 0.0) maxOf(0.1, megabytes) else 0.0
        val pattern = if (visibleMegabytes < 10.0) "%.1f" else "%.0f"
        return String.format(Locale.US, pattern, visibleMegabytes)
    }

    private suspend fun showCoreProfilerError(error: Throwable) {
        val message = error.readableMessage
        val resId = when {
            message.contains("Core is not started yet", ignoreCase = true) -> R.string.core_not_started_yet
            message.contains("no profiler snapshot", ignoreCase = true) -> R.string.core_profiler_no_snapshot
            else -> null
        }
        if (resId != null) {
            showCoreProfilerToast(resId)
        } else {
            onMainDispatcher {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.core_profiler_failed, message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun showCoreProfilerToast(resId: Int) {
        onMainDispatcher {
            Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCoreProfilerProgress() {
        if (coreProfilerProgressDialog?.isShowing == true) return
        val progress = ProgressBar(requireContext()).apply {
            isIndeterminate = true
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(progress)
        }
        coreProfilerProgressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.core_profiler_saving)
            .setView(container)
            .setCancelable(false)
            .show()
    }

    private suspend fun dismissCoreProfilerProgress() {
        onMainDispatcher {
            coreProfilerProgressDialog?.dismiss()
            coreProfilerProgressDialog = null
        }
    }

    private fun writeProfilerZip(zipFile: File, profilerDir: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            addTextEntry(zip, "report-header.txt", CrashHandler.buildReportHeader())
            addLogcatEntry(zip)
            addByteEntry(zip, "neko.log", SendLog.getNekoLog(0))
            profilerDir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(profilerDir).invariantSeparatorsPath }
                .forEach { file ->
                    addFileEntry(zip, "profiler/${file.relativeTo(profilerDir).invariantSeparatorsPath}", file)
                }
        }
    }

    private fun copyLocalProfilerSnapshot(outputDir: File): Boolean {
        val sourceDir = File(app.cacheDir, "core-profiler")
        if (!sourceDir.exists()) return false
        val profilerFiles = sourceDir.listFiles()?.filter { it.isFile && it.length() > 0L } ?: return false
        if (profilerFiles.isEmpty()) return false
        profilerFiles.forEach { source ->
            source.copyTo(File(outputDir, source.name), overwrite = true)
        }
        return true
    }

    private fun addLogcatEntry(zip: ZipOutputStream) {
        try {
            Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream.use { input ->
                zip.putNextEntry(ZipEntry("logcat.txt"))
                input.copyTo(zip)
                zip.closeEntry()
            }
        } catch (e: IOException) {
            addTextEntry(zip, "logcat.txt", "Export logcat error: " + CrashHandler.formatThrowable(e))
        }
    }

    private fun addTextEntry(zip: ZipOutputStream, name: String, text: String) {
        addByteEntry(zip, name, text.toByteArray(Charsets.UTF_8))
    }

    private fun addByteEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun addFileEntry(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun shareProfilerZip(zipFile: File) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("application/zip")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                            requireContext(), BuildConfig.APPLICATION_ID + ".cache", zipFile
                        )
                    ), getString(R.string.abc_shareactionprovider_share_with)
            )
        )
    }

    private fun profilerTimestamp(): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }

    private fun clearAppCache() {
        try {
            val cacheDir = SagerNet.application.cacheDir
            clearDirFiles(cacheDir, skipFiles = setOf("neko.log"))
            
            val parentDir = cacheDir.parentFile
            val relativeCache = File(parentDir, "cache")
            if (relativeCache.exists() && relativeCache.isDirectory) {
                clearDirFiles(relativeCache)
            }
            
            Toast.makeText(requireContext(), R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
            
            Handler(Looper.getMainLooper()).postDelayed({
                needReload()
            }, 500)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.clear_cache_failed, e.message), Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }
    
    private fun clearDirFiles(dir: File, skipFiles: Set<String> = emptySet()): Boolean {
        if (dir.isDirectory) {
            val children = dir.list() ?: return true
            
            for (child in children) {
                val childFile = File(dir, child)
                
                if (child == "neko.log") {
                    try {
                        childFile.writeText("")
                        continue
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                if (child in skipFiles) {
                    continue
                }
                
                if (childFile.isDirectory) {
                    clearDirFiles(childFile, skipFiles)
                } else {
                    childFile.delete()
                }
            }
            
            return true
        }
        return false
    }

    private object ProxyPasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {
        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "*".repeat(text.length)
            }
        }
    }

}
