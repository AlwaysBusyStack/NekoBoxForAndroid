package io.nekohasekai.sagernet.ui

import android.content.DialogInterface
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import kotlinx.coroutines.delay
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.FixedGridLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.JuicitySettingsActivity
import io.nekohasekai.sagernet.ui.profile.MasterDnsVPNSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ProxySetSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksRSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SnellSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanGoSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrustTunnelSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.AmneziaWGSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.byedpi.ByeDPISettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import okhttp3.internal.closeQuietly
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.collections.set
import kotlin.math.roundToInt
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.AmneziaApiKeyUnsupportedException

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2
    private var groupTabMediator: TabLayoutMediator? = null

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    fun getCurrentGroupFragment(): GroupFragment? {
        return try {
            if (::adapter.isInitialized) {
                adapter.groupFragments[DataStore.selectedGroup]
            } else {
                childFragmentManager.findFragmentByTag("f" + DataStore.selectedGroup) as GroupFragment?
            }
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    fun switchAllGroupFragmentsLayout() {
        adapter.groupFragments.values.forEach { fragment ->
            if (fragment.isAdded && fragment.view != null) {
                fragment.switchLayoutMode()
            }
        }
    }

    fun replaceAllGroupFragments() {
        adapter.replaceFragments()
    }

    fun refreshVisibleTraffic() {
        getCurrentGroupFragment()?.refreshVisibleTraffic()
    }

    fun refreshVisibleProfileActions() {
        getCurrentGroupFragment()?.refreshVisibleProfileActions()
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (adapter.groupList.size > position) {
                DataStore.selectedGroup = adapter.groupList[position].id
            }
        }
    }

    override fun onQueryTextChange(query: String): Boolean {
        getCurrentGroupFragment()?.adapter?.filter(query)
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!select) {
            setupProfileToolbarMenu()
            syncToolbarMode()
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }

        setupSearchView()

        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        groupTabMediator = TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                adapter.renderGroupTab(tab, adapter.groupList[position])
            }
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.also { it.attach() }

        syncToolbarMode()

        DataStore.profileCacheStore.registerChangeListener(this)
        DataStore.configurationStore.registerChangeListener(this)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.action_global_mode)?.isChecked = DataStore.globalMode
        super.onPrepareOptionsMenu(menu)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            // editingGroup
            if (store == DataStore.profileCacheStore && key == Key.PROFILE_GROUP) {
                syncSelectedGroup(DataStore.editingGroup)
            } else if (store == DataStore.configurationStore && key == Key.USE_TOOLBAR) {
                if (!select) {
                    try {
                        setupProfileToolbarMenu()
                    } catch (_: UninitializedPropertyAccessException) {
                    }
                }
                syncToolbarMode()
            } else if (store == DataStore.configurationStore && key == Key.SHOW_PROFILE_COUNT_ON_TABS) {
                if (::adapter.isInitialized) {
                    adapter.refreshAllGroupTabs()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GroupConnectionTestController.attach(this)
        syncToolbarMode()
        if (::adapter.isInitialized && ::groupPager.isInitialized) {
            syncSelectedGroup(DataStore.selectedGroup)
            scrollSelectedGroupTabIntoView()
            getCurrentGroupFragment()?.let { fragment ->
                runOnDefaultDispatcher {
                    fragment.adapter?.reloadProfiles()
                }
            }
        }
    }

    override fun onPause() {
        toolbar.findViewById<SearchView>(R.id.action_search)?.let(::cancelSearch)
        super.onPause()
    }

    override fun onDestroyView() {
        GroupConnectionTestController.detach()
        groupTabMediator?.detach()
        groupTabMediator = null
        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
            adapter.close()
        }
        if (::groupPager.isInitialized) {
            groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            groupPager.adapter = null
        }
        super.onDestroyView()
    }

    private fun syncSelectedGroup(targetId: Long) {
        if (targetId <= 0 || !::adapter.isInitialized || !::groupPager.isInitialized) return
        if (DataStore.selectedGroup != targetId) {
            DataStore.selectedGroup = targetId
        }
        val targetIndex = GroupTabSelectionPolicy.selectedIndex(adapter.groupList.map { it.id }, targetId)
        if (targetIndex >= 0) {
            groupPager.setCurrentItem(targetIndex, false)
            scrollSelectedGroupTabIntoView(targetIndex)
        } else {
            adapter.reload()
        }
    }

    private fun scrollSelectedGroupTabIntoView(position: Int = groupPager.currentItem) {
        if (!::tabLayout.isInitialized || position < 0) return

        tabLayout.post {
            scrollSelectedGroupTabIntoViewWhenReady(position)
        }
    }

    private fun scrollSelectedGroupTabIntoViewWhenReady(position: Int) {
        if (!::tabLayout.isInitialized || position < 0 || position >= tabLayout.tabCount) return

        val tabView = tabLayout.getTabAt(position)?.view ?: return
        tabLayout.doOnLayout {
            tabView.doOnLayout {
                if (!::tabLayout.isInitialized || position >= tabLayout.tabCount) return@doOnLayout
                tabLayout.setScrollPosition(position, 0F, true)
                tabView.requestRectangleOnScreen(Rect(0, 0, tabView.width, tabView.height), false)
            }
        }
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        DataStore.configurationStore.unregisterChangeListener(this)

        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }

        super.onDestroy()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = getCurrentGroupFragment()
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) runOnDefaultDispatcher {
                try {
                    val fileName =
                        requireContext().contentResolver.query(file, null, null, null, null)
                            ?.use { cursor ->
                                cursor.moveToFirst()
                                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                    .let(cursor::getString)
                            }
                    val proxies = mutableListOf<AbstractBean>()
                    if (fileName != null && fileName.endsWith(".zip")) {
                        // try parse wireguard zip
                        val zip =
                            ZipInputStream(requireContext().contentResolver.openInputStream(file)!!)
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val fileText = zip.bufferedReader().readText()
                            RawUpdater.parseRaw(fileText, entry.name)
                                ?.let { pl -> proxies.addAll(pl) }
                            zip.closeEntry()
                        }
                        zip.closeQuietly()
                    } else {
                        val fileText =
                            requireContext().contentResolver.openInputStream(file)!!.use {
                                it.bufferedReader().readText()
                            }
                        RawUpdater.parseRaw(fileText, fileName ?: "")
                            ?.let { pl -> proxies.addAll(pl) }
                    }
                    if (proxies.isEmpty()) onMainDispatcher {
                        snackbar(getString(R.string.no_proxies_found_in_file)).show()
                    } else import(proxies)
                } catch (e: SubscriptionFoundException) {
                    (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                } catch (e: AmneziaApiKeyUnsupportedException) {
                    onMainDispatcher {
                        snackbar(getString(R.string.amnezia_api_key_unsupported)).show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

    suspend fun import(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        for (proxy in proxies) {
            ProfileManager.createProfile(targetId, proxy)
        }
        onMainDispatcher {
            DataStore.editingGroup = targetId
            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added, proxies.size, proxies.size
                )
            ).show()
        }

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }

            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else runOnDefaultDispatcher {
                    try {
                        val proxies = RawUpdater.parseRaw(text)
                        if (proxies.isNullOrEmpty()) {
                            onMainDispatcher {
                                snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                            }
                        } else {
                            import(proxies)
                        }
                    } catch (e: SubscriptionFoundException) {
                        onMainDispatcher {
                            if (e.link.startsWith("sn://")) {
                                (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                            } else {
                                val subscriptionLink = Uri.parse(e.link).getQueryParameter("url") ?: e.link

                                val group = ProxyGroup(type = GroupType.SUBSCRIPTION)
                                val subscription = SubscriptionBean()
                                group.subscription = subscription
                                subscription.link = subscriptionLink
                                subscription.autoUpdate = false
                                group.name = ""
                                startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                                    putExtra(GroupSettingsActivity.EXTRA_FROM_CLIPBOARD, true)
                                    putExtra(GroupSettingsActivity.EXTRA_GROUP_SUBSCRIPTION_LINK, subscriptionLink)
                                })
                            }
                        }
                    } catch (e: AmneziaApiKeyUnsupportedException) {
                        onMainDispatcher {
                            snackbar(getString(R.string.amnezia_api_key_unsupported)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }

            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }

            R.id.action_new_socks -> {
                startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            }

            R.id.action_new_http -> {
                startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            }

            R.id.action_new_ss -> {
                startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            }

            R.id.action_new_ssr -> {
                startActivity(Intent(requireActivity(), ShadowsocksRSettingsActivity::class.java))
            }

            R.id.action_new_vmess -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            }

            R.id.action_new_vless -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java).apply {
                    putExtra("vless", true)
                })
            }

            R.id.action_new_trojan -> {
                startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            }

            R.id.action_new_trojan_go -> {
                startActivity(Intent(requireActivity(), TrojanGoSettingsActivity::class.java))
            }

            R.id.action_new_mieru -> {
                startActivity(Intent(requireActivity(), MieruSettingsActivity::class.java))
            }

            R.id.action_new_naive -> {
                startActivity(Intent(requireActivity(), NaiveSettingsActivity::class.java))
            }

            R.id.action_new_hysteria -> {
                startActivity(Intent(requireActivity(), HysteriaSettingsActivity::class.java))
            }

            R.id.action_new_tuic -> {
                startActivity(Intent(requireActivity(), TuicSettingsActivity::class.java))
            }

            R.id.action_new_juicity -> {
                startActivity(Intent(requireActivity(), JuicitySettingsActivity::class.java))
            }

            R.id.action_new_trusttunnel -> {
                startActivity(Intent(requireActivity(), TrustTunnelSettingsActivity::class.java))
            }

            R.id.action_new_masterdnsvpn -> {
                startActivity(Intent(requireActivity(), MasterDnsVPNSettingsActivity::class.java))
            }

            R.id.action_new_byedpi -> {
                startActivity(Intent(requireActivity(), ByeDPISettingsActivity::class.java))
            }

            R.id.action_new_ssh -> {
                startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            }

            R.id.action_new_wg -> {
                startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            }

            R.id.action_new_awg -> {
                startActivity(Intent(requireActivity(), AmneziaWGSettingsActivity::class.java))
            }

            R.id.action_new_shadowtls -> {
                startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            }

            R.id.action_new_anytls -> {
                startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            }

            R.id.action_new_snell -> {
                startActivity(Intent(requireActivity(), SnellSettingsActivity::class.java))
            }

            R.id.action_new_config -> {
                startActivity(Intent(requireActivity(), ConfigSettingActivity::class.java))
            }

            R.id.action_new_chain -> {
                startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))
            }

            R.id.action_new_proxy_set -> {
                startActivity(Intent(requireActivity(), ProxySetSettingsActivity::class.java))
            }

            R.id.action_toolbar_update_subscription,
            R.id.action_update_subscription -> {
                val group = DataStore.currentGroup()
                if (group.type != GroupType.SUBSCRIPTION) {
                    snackbar(R.string.group_not_subscription).show()
                    Logs.e("onMenuItemClick: Group(${group.displayName()}) is not subscription")
                } else {
                    runOnLifecycleDispatcher {
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            }

            R.id.action_clear_traffic_statistics -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.tx != 0L || profile.rx != 0L) {
                            profile.tx = 0
                            profile.rx = 0
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }

            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0) {
                            profile.status = 0
                            profile.ping = 0
                            profile.error = null
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }

            R.id.action_toolbar_connection_test_delete_unavailable,
            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0 && profile.status != 1) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(R.string.delete_unavailable_confirm_prompt)
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_remove_duplicate -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    val uniqueProxyHashes = LinkedHashSet<String>()
                    for (pf in profiles) {
                        val proxyHash = pf.requireBean().hash
                        if (!uniqueProxyHashes.add(proxyHash)) {
                            toClear += pf
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(
                                    getString(R.string.delete_confirm_prompt) + "\n" +
                                            toClear.mapIndexedNotNull { index, proxyEntity ->
                                                if (index < 20) {
                                                    proxyEntity.displayName()
                                                } else if (index == 20) {
                                                    "......"
                                                } else {
                                                    null
                                                }
                                            }.joinToString("\n")
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_toolbar_connection_tcp_ping,
            R.id.action_connection_tcp_ping -> {
                pingTest(false)
            }

            R.id.action_toolbar_connection_url_test,
            R.id.action_connection_url_test -> {
                urlTest()
            }

            R.id.action_global_mode -> {
                item.isChecked = !item.isChecked
                DataStore.globalMode = item.isChecked
                if (DataStore.serviceState.canStop) {
                    runOnDefaultDispatcher {
                        try {
                            // 等待一段时间确保配置已保存
                            delay(500)
                            snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                                runOnDefaultDispatcher {
                                    try {
                                        // 再次等待确保配置已保存
                                        delay(100)
                                        SagerNet.reloadService()
                                    } catch (e: Exception) {
                                        Logs.w(e)
                                        onMainDispatcher {
                                            snackbar(getString(R.string.service_failed)).show()
                                        }
                                    }
                                }
                            }.show()
                        } catch (e: Exception) {
                            Logs.w(e)
                            onMainDispatcher {
                                snackbar(getString(R.string.service_failed)).show()
                            }
                        }
                    }
                }
                return true
            }

            R.id.action_show_active -> {
                focusSelectedProfileGroupAndScroll()
            }
        }
        return false
    }

    fun pingTest(icmpPing: Boolean) {
        if (icmpPing) {
            return
        }
        GroupConnectionTestController.startTcpPing(this)
    }

    fun urlTest() {
        GroupConnectionTestController.startUrlTest(this)
    }

    private fun View.findTextView(): TextView? {
        if (this is TextView) return this
        if (this !is ViewGroup) return null
        children.forEach { child ->
            child.findTextView()?.let { return it }
        }
        return null
    }

    private class ProfileCountSpan(
        private val leftMargin: Int,
        private val horizontalPadding: Int,
        private val verticalPadding: Int,
        private val minHeight: Int,
        private val cornerRadius: Float,
        private val backgroundColor: Int,
        private val textColor: Int,
        private val scale: Float = 0.85F,
    ) : ReplacementSpan() {

        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val scaledHorizontalPadding = (horizontalPadding * scale).roundToInt()
        private val scaledVerticalPadding = (verticalPadding * scale).roundToInt()
        private val scaledMinHeight = (minHeight * scale).roundToInt()
        private val scaledCornerRadius = cornerRadius * scale

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            textPaint.set(paint)
            textPaint.textSize = paint.textSize * scale
            return (leftMargin + textPaint.measureText(text, start, end) + scaledHorizontalPadding * 2)
                .roundToInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            textPaint.set(paint)
            textPaint.textSize = paint.textSize * scale
            textPaint.color = textColor
            backgroundPaint.color = backgroundColor

            val textWidth = textPaint.measureText(text, start, end)
            val height = maxOf(scaledMinHeight.toFloat(), textPaint.fontMetrics.run {
                descent - ascent + scaledVerticalPadding * 2
            })
            val pillStart = x + leftMargin
            val textCenterY = y +
                    (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2F
            val rect = RectF(
                pillStart,
                textCenterY - height / 2F,
                pillStart + textWidth + scaledHorizontalPadding * 2,
                textCenterY + height / 2F,
            )

            canvas.drawRoundRect(rect, scaledCornerRadius, scaledCornerRadius, backgroundPaint)
            canvas.drawText(
                text,
                start,
                end,
                rect.left + scaledHorizontalPadding,
                rect.centerY() - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2F,
                textPaint,
            )
        }
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, GroupFragment> = HashMap()
        private var nextFragmentItemId = 0L
        private val fragmentItemIds = HashMap<Long, Long>()
        private var reloadJob: Job? = null
        @Volatile
        private var closed = false

        private fun itemIdFor(groupId: Long): Long {
            return fragmentItemIds.getOrPut(groupId) {
                nextFragmentItemId++
            }
        }

        fun close() {
            closed = true
            reloadJob?.cancel()
            reloadJob = null
        }

        private fun hasLiveView(): Boolean {
            return !closed &&
                    this@ConfigurationFragment.isAdded &&
                    view != null &&
                    ::tabLayout.isInitialized &&
                    ::groupPager.isInitialized
        }

        private fun postToTabLayout(block: () -> Unit) {
            if (!hasLiveView()) return
            tabLayout.post {
                if (!hasLiveView()) return@post
                block()
            }
        }

        fun renderGroupTab(tab: TabLayout.Tab, group: ProxyGroup) {
            val tabContext = this@ConfigurationFragment.context ?: return
            tab.customView = null
            tab.view.installForegroundRipple(tabContext)
            if (!DataStore.showProfileCountOnTabs) {
                tab.text = group.displayName()
                return
            }

            val count = SagerDatabase.proxyDao.countByGroup(group.id).toString()
            val title = group.displayName()
            tab.text = SpannableStringBuilder(title)
                .append(
                    count,
                    ProfileCountSpan(
                        leftMargin = dp2px(6),
                        horizontalPadding = dp2px(5),
                        verticalPadding = dp2px(1),
                        minHeight = dp2px(18),
                        cornerRadius = dp2px(9).toFloat(),
                        backgroundColor = tabContext
                            .getColorAttr(com.google.android.material.R.attr.colorPrimaryContainer),
                        textColor = tabContext
                            .getColorAttr(com.google.android.material.R.attr.colorOnPrimaryContainer),
                    ),
                    SPAN_EXCLUSIVE_EXCLUSIVE,
                )
        }

        private fun View.installForegroundRipple(context: android.content.Context) {
            if (foreground is RippleDrawable) return
            foreground = RippleDrawable(
                ColorStateList.valueOf(context.getColorAttr(R.attr.tabRippleColor)),
                null,
                null,
            )
            background = null
        }

        private fun refreshGroupTab(groupId: Long) {
            if (!hasLiveView()) return
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return
            val tab = tabLayout.getTabAt(index) ?: return
            renderGroupTab(tab, groupList[index])
        }

        fun refreshAllGroupTabs() {
            if (!hasLiveView()) return
            groupList.forEachIndexed { index, group ->
                tabLayout.getTabAt(index)?.let { tab ->
                    renderGroupTab(tab, group)
                }
            }
        }

        private fun applyGroupTabVisibility() {
            if (!hasLiveView()) return
            val hideTab = groupList.size < 2
            tabLayout.isGone = hideTab
            toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
        }

        fun replaceFragments() {
            groupFragments.clear()
            fragmentItemIds.clear()
            notifyDataSetChanged()
        }

        fun reload(now: Boolean = false) {
            if (!hasLiveView()) return

            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            reloadJob?.cancel()
            reloadJob = runOnDefaultDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                }
                newGroupList.find { it.ungrouped }?.let {
                    if (newGroupList.size > 1 && SagerDatabase.proxyDao.countByGroup(it.id) == 0L) {
                        newGroupList.remove(it)
                    }
                }

                val selectedGroup = selectedItem?.groupId ?: DataStore.currentGroupId()
                var set = false
                if (selectedGroup > 0L) {
                    selectedGroupIndex = GroupTabSelectionPolicy.selectedIndex(
                        newGroupList.map { it.id },
                        selectedGroup
                    )
                    set = selectedGroupIndex >= 0
                }
                if (!set && newGroupList.isNotEmpty()) {
                    selectedGroupIndex = 0
                    val fallbackGroup = newGroupList[0].id
                    if (DataStore.selectedGroup != fallbackGroup) {
                        DataStore.selectedGroup = fallbackGroup
                    }
                    set = true
                }

                if (!isActive || !hasLiveView()) return@runOnDefaultDispatcher

                val applyChanges = Runnable {
                    if (!hasLiveView()) return@Runnable
                    groupList = newGroupList
                    val groupIds = groupList.map { it.id }.toHashSet()
                    groupFragments.keys.retainAll(groupIds)
                    fragmentItemIds.keys.retainAll(groupIds)
                    notifyDataSetChanged()
                    if (set) {
                        groupPager.setCurrentItem(selectedGroupIndex, false)
                        scrollSelectedGroupTabIntoView(selectedGroupIndex)
                    }
                    refreshAllGroupTabs()
                    applyGroupTabVisibility()
                    if (!select && hasLiveView()) {
                        groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                    }
                }
                if (now) {
                    activity?.runOnUiThread(applyChanges)
                } else {
                    groupPager.post(applyChanges)
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return GroupFragment().apply {
                proxyGroup = groupList[position]
                groupFragments[proxyGroup.id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        override fun getItemId(position: Int): Long {
            return itemIdFor(groupList[position].id)
        }

        override fun containsItem(itemId: Long): Boolean {
            return fragmentItemIds.any { (groupId, fragmentItemId) ->
                fragmentItemId == itemId && groupList.any { it.id == groupId }
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            postToTabLayout {
                if (group.ungrouped) {
                    reload()
                    return@postToTabLayout
                }
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) postToTabLayout {
                    tabLayout.isVisible = true
                }

                notifyItemInserted(groupList.size - 1)
                refreshGroupTab(group.id)
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            postToTabLayout {
                reload()
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) return

            postToTabLayout {
                groupList[index] = group
                refreshGroupTab(group.id)
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            postToTabLayout {
                refreshGroupTab(groupId)
            }
        }

        override suspend fun onAdd(profile: ProxyEntity) {
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
            } else {
                postToTabLayout {
                    refreshGroupTab(profile.groupId)
                }
            }
        }

        override suspend fun onUpdated(data: TrafficData) = Unit

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            } else {
                postToTabLayout {
                    refreshGroupTab(groupId)
                }
            }
        }
    }

    class GroupFragment : Fragment() {

        lateinit var proxyGroup: ProxyGroup
        var selected = false

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            return LayoutProfileListBinding.inflate(inflater).root
        }

        lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
        var adapter: ConfigurationAdapter? = null

        fun refreshVisibleTraffic() {
            if (!::configurationListView.isInitialized) return
            configurationListView.post {
                adapter?.refreshVisibleTraffic()
            }
        }

        fun refreshVisibleProfileActions() {
            if (!::configurationListView.isInitialized) return
            configurationListView.post {
                adapter?.refreshVisibleProfileActions()
            }
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            if (::proxyGroup.isInitialized) {
                outState.putParcelable("proxyGroup", proxyGroup)
            }
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.also {
                proxyGroup = it
                onViewCreated(requireView(), null)
            }
        }

        private val isEnabled: Boolean
            get() {
                return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
            }

        lateinit var layoutManager: RecyclerView.LayoutManager
        private lateinit var itemTouchHelper: ItemTouchHelper
        
        private fun setupItemTouchHelper() {
            if (select) return
            
            if (::itemTouchHelper.isInitialized) {
                itemTouchHelper.attachToRecyclerView(null)
            }
            
            itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, 0) {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    val dragFlags = if (DataStore.groupLayoutMode == 1) {
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                    } else {
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN
                    }
                    return makeMovementFlags(dragFlags, 0) // No swipe flags
                }

                override fun getSwipeDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    return 0
                }

                override fun getDragDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    return if (isEnabled) {
                        if (DataStore.groupLayoutMode == 1) {
                            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                        } else {
                            ItemTouchHelper.UP or ItemTouchHelper.DOWN
                        }
                    } else 0
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                ): Boolean {
                    val fromPosition = viewHolder.bindingAdapterPosition
                    val toPosition = target.bindingAdapterPosition
                    
                    if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
                        return false
                    }
                    
                    adapter?.move(fromPosition, toPosition)
                    return true
                }

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) {
                    super.clearView(recyclerView, viewHolder)
                    adapter?.commitMove()
                }
            })
            itemTouchHelper.attachToRecyclerView(configurationListView)
        }
        lateinit var configurationListView: RecyclerView
        private var didInitialPositionList = false
        private var lastGroupUpdateStamp: Int? = null

        val select by lazy {
            try {
                (parentFragment as ConfigurationFragment).select
            } catch (e: Exception) {
                Logs.e(e)
                false
            }
        }
        val selectedItem by lazy {
            try {
                (parentFragment as ConfigurationFragment).selectedItem
            } catch (e: Exception) {
                Logs.e(e)
                null
            }
        }

        override fun onResume() {
            super.onResume()

            if (!::configurationListView.isInitialized) {
                onViewCreated(requireView(), null)
            }
            if (::configurationListView.isInitialized) {
                runOnDefaultDispatcher {
                    adapter?.reloadProfilesIfChanged()
                }
            }
            checkOrderMenu()
            configurationListView.requestFocus()
        }

        override fun onDestroyView() {
            lastGroupUpdateStamp = null
            didInitialPositionList = false
            super.onDestroyView()
        }

        fun checkOrderMenu() {
            if (select) return

            val pf = requireParentFragment() as? ToolbarFragment ?: return
            val menu = pf.toolbar.menu
            val origin = menu.findItem(R.id.action_order_origin)
            val byName = menu.findItem(R.id.action_order_by_name)
            val byDelay = menu.findItem(R.id.action_order_by_delay)
            when (proxyGroup.order) {
                GroupOrder.ORIGIN -> {
                    origin.isChecked = true
                }

                GroupOrder.BY_NAME -> {
                    byName.isChecked = true
                }

                GroupOrder.BY_DELAY -> {
                    byDelay.isChecked = true
                }
            }

            fun updateTo(order: Int, forceReload: Boolean = false) {
                if (proxyGroup.order == order) {
                    if (forceReload && order == GroupOrder.BY_DELAY) {
                        runOnDefaultDispatcher {
                            GroupManager.postReload(proxyGroup.id)
                        }
                    }
                    return
                }
                runOnDefaultDispatcher {
                    proxyGroup.order = order
                    GroupManager.updateGroup(proxyGroup)
                }
            }

            origin.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.ORIGIN)
                true
            }
            byName.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_NAME)
                true
            }
            byDelay.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_DELAY, forceReload = true)
                true
            }
            
            val layoutSingle = menu.findItem(R.id.action_layout_single)
            val layoutDouble = menu.findItem(R.id.action_layout_double)
            val layoutCompact = menu.findItem(R.id.action_layout_compact)
            when (DataStore.groupLayoutMode) {
                0 -> layoutSingle.isChecked = true
                1 -> layoutDouble.isChecked = true
                2 -> layoutCompact.isChecked = true
            }
            layoutSingle.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.groupLayoutMode != 0) {
                    val prevGroupLayoutMode = DataStore.groupLayoutMode
                    DataStore.groupLayoutMode = 0

                    (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()

                    if (prevGroupLayoutMode == 2) {
                        (parentFragment as? ConfigurationFragment)?.replaceAllGroupFragments()
                    }
                }
                true
            }
            layoutDouble.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.groupLayoutMode != 1) {
                    val prevGroupLayoutMode = DataStore.groupLayoutMode
                    DataStore.groupLayoutMode = 1

                    (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()

                    if (prevGroupLayoutMode == 2) {
                        (parentFragment as? ConfigurationFragment)?.replaceAllGroupFragments()
                    }
                }
                true
            }
            layoutCompact.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.groupLayoutMode != 2) {
                    val prevGroupLayoutMode = DataStore.groupLayoutMode
                    DataStore.groupLayoutMode = 2

                    (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()

                    if (prevGroupLayoutMode != 2) {
                        (parentFragment as? ConfigurationFragment)?.replaceAllGroupFragments()
                    }
                }
                true
            }
        }
        
        private fun setupLayoutManager() {
            layoutManager = if (DataStore.groupLayoutMode == 1) {
                FixedGridLayoutManager(configurationListView, 2)
            } else {
                FixedLinearLayoutManager(configurationListView)
            }
        }
        
        fun switchLayoutMode() {
            setupLayoutManager()
            configurationListView.layoutManager = layoutManager
            
            setupItemTouchHelper()
            
            adapter?.notifyDataSetChanged()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            if (!::proxyGroup.isInitialized) return

            configurationListView = view.findViewById(R.id.configuration_list)
            setupLayoutManager()
            configurationListView.layoutManager = layoutManager
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter!!)
            GroupManager.addListener(adapter!!)
            configurationListView.adapter = adapter
            configurationListView.setItemViewCacheSize(20)

            if (!select) {
                undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)
                setupItemTouchHelper()
            }

        }

        override fun onDestroy() {
            adapter?.let {
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }

            super.onDestroy()

            if (!::undoManager.isInitialized) return
            undoManager.flush()
        }

        inner class ConfigurationAdapter : RecyclerView.Adapter<ConfigurationHolder>(),
            ProfileManager.Listener,
            GroupManager.Listener,
            UndoSnackbarManager.Interface<ProxyEntity> {

            init {
                setHasStableIds(true)
            }

            var configurationIdList: MutableList<Long> = mutableListOf()
            val configurationList = HashMap<Long, ProxyEntity>()

            private fun getItem(profileId: Long): ProxyEntity {
                var profile = configurationList[profileId]
                if (profile == null) {
                    profile = ProfileManager.getProfile(profileId)
                    if (profile != null) {
                        configurationList[profileId] = profile
                    }
                }
                return profile!!
            }

            private fun getItemAt(index: Int) = getItem(configurationIdList[index])

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ConfigurationHolder {
                return ConfigurationHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(
                            if (DataStore.groupLayoutMode == 2)
                                R.layout.layout_profile_compact
                            else
                                R.layout.layout_profile,
                            parent,
                            false
                        )
                )
            }

            override fun getItemId(position: Int): Long {
                return configurationIdList[position]
            }

            override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
                try {
                    holder.bind(getItemAt(position))
                } catch (ignored: NullPointerException) { // when group deleted
                }
            }

            override fun getItemCount(): Int {
                return configurationIdList.size
            }

            private val updated = HashSet<ProxyEntity>()

            fun filter(name: String) {
                if (name.isEmpty()) {
                    reloadProfiles()
                    return
                }
                configurationIdList.clear()
                val lower = name.lowercase()
                configurationIdList.addAll(configurationList.filter {
                    it.value.displayName().lowercase().contains(lower) ||
                            it.value.displayType().lowercase().contains(lower) ||
                            it.value.displayAddress().lowercase().contains(lower)
                }.keys)
                notifyDataSetChanged()
            }

            fun move(from: Int, to: Int) {
                if (from == to) return
                
                if (DataStore.groupLayoutMode == 1) {
                    moveDualColumn(from, to)
                } else {
                    moveLinear(from, to)
                }
            }
            
            private fun moveLinear(from: Int, to: Int) {
                val first = getItemAt(from)
                var previousOrder = first.userOrder
                val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                    -1, to + 1 downTo from
                )
                for (i in range) {
                    val next = getItemAt(i + step)
                    val order = next.userOrder
                    next.userOrder = previousOrder
                    previousOrder = order
                    configurationIdList[i] = next.id
                    updated.add(next)
                }
                first.userOrder = previousOrder
                configurationIdList[to] = first.id
                updated.add(first)
                notifyItemMoved(from, to)
            }
            
            private fun moveDualColumn(from: Int, to: Int) {
                val draggedItemId = configurationIdList[from]

                configurationIdList.removeAt(from)
                configurationIdList.add(to, draggedItemId)
                
                for (i in configurationIdList.indices) {
                    val item = getItem(configurationIdList[i])
                    val newOrder = (i + 1).toLong()
                    if (item.userOrder != newOrder) {
                        item.userOrder = newOrder
                        updated.add(item)
                    }
                }
                
                notifyItemMoved(from, to)
            }

            fun commitMove() = runOnDefaultDispatcher {
                updated.forEach { SagerDatabase.proxyDao.updateProxy(it) }
                updated.clear()
            }

            private fun shouldShowProfileTraffic(): Boolean {
                return DataStore.profileTrafficUpdateInterval > 0 && DataStore.profileTrafficStatistics
            }

            fun remove(pos: Int) {
                if (pos < 0) return
                configurationIdList.removeAt(pos)
                notifyItemRemoved(pos)
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                for ((index, item) in actions) {
                    configurationListView.post {
                        configurationList[item.id] = item
                        configurationIdList.add(index, item.id)
                        notifyItemInserted(index)
                    }
                }
            }

            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                val profiles = actions.map { it.second }
                runOnDefaultDispatcher {
                    for (entity in profiles) {
                        ProfileManager.deleteProfile(entity.groupId, entity.id)
                    }
                }
            }

            override suspend fun onAdd(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id) return

                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val pos = itemCount
                    configurationList[profile.id] = profile
                    configurationIdList.add(profile.id)
                    notifyItemInserted(pos)
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
                if (profile.groupId != proxyGroup.id) return
                val index = configurationIdList.indexOf(profile.id)
                if (index < 0) return
                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    configurationList[profile.id] = profile
                    notifyItemChanged(index)
                    //
                    val oldProfile = configurationList[profile.id]
                    if (noTraffic && oldProfile != null) {
                        runOnDefaultDispatcher {
                            onUpdated(
                                TrafficData(
                                    id = profile.id,
                                    rx = oldProfile.rx,
                                    tx = oldProfile.tx
                                )
                            )
                        }
                    }
                }
            }

            override suspend fun onUpdated(data: TrafficData) {
                try {
                    onMainDispatcher {
                        if (!shouldShowProfileTraffic()) {
                            refreshVisibleTraffic()
                            return@onMainDispatcher
                        }
                        val index = configurationIdList.indexOf(data.id)
                        if (index != -1) {
                            configurationList[data.id]?.let {
                                it.tx = data.tx
                                it.rx = data.rx
                            }
                            val holder = layoutManager.findViewByPosition(index)
                                ?.let { configurationListView.getChildViewHolder(it) } as ConfigurationHolder?
                            if (holder != null && holder.entity.id == data.id) {
                                holder.bindTraffic(data)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }

            fun refreshVisibleTraffic() {
                for (i in 0 until configurationListView.childCount) {
                    val holder = configurationListView.getChildViewHolder(
                        configurationListView.getChildAt(i)
                    ) as? ConfigurationHolder ?: continue
                    holder.bindTraffic()
                }
            }

            fun refreshVisibleProfileActions() {
                for (i in 0 until configurationListView.childCount) {
                    val holder = configurationListView.getChildViewHolder(
                        configurationListView.getChildAt(i)
                    ) as? ConfigurationHolder ?: continue
                    holder.updateActionState()
                }
            }

            fun shouldShowTraffic(): Boolean {
                return shouldShowProfileTraffic()
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (groupId != proxyGroup.id) return
                val index = configurationIdList.indexOf(profileId)
                if (index < 0) return

                configurationListView.post {
                    configurationIdList.removeAt(index)
                    configurationList.remove(profileId)
                    notifyItemRemoved(index)
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (group.id != proxyGroup.id) return
                proxyGroup = group
                reloadProfiles()
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (groupId != proxyGroup.id) return
                proxyGroup = SagerDatabase.groupDao.getById(groupId) ?: return
                reloadProfiles()
            }

            private fun currentGroupUpdateStamp(): Int {
                return proxyGroup.subscription?.lastUpdated ?: -1
            }

            fun reloadProfilesIfChanged() {
                val updateStamp = currentGroupUpdateStamp()
                if (lastGroupUpdateStamp == updateStamp) return
                reloadProfiles()
            }

            fun reloadProfiles() {
                var newProfiles = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                when (proxyGroup.order) {
                    GroupOrder.BY_NAME -> {
                        newProfiles = newProfiles.sortedBy { it.displayName() }

                    }

                    GroupOrder.BY_DELAY -> {
                        newProfiles =
                            newProfiles.sortedBy { if (it.status == 1) it.ping else 114514 }
                    }
                }

                configurationList.clear()
                configurationList.putAll(newProfiles.associateBy { it.id })
                val newProfileIds = newProfiles.map { it.id }

                var selectedProfileIndex = -1

                if (selected) {
                    val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                    selectedProfileIndex = newProfileIds.indexOf(selectedProxy)
                }

                configurationListView.post {
                    configurationIdList.clear()
                    configurationIdList.addAll(newProfileIds)
                    notifyDataSetChanged()

                    if (!didInitialPositionList) {
                        didInitialPositionList = true
                        if (selectedProfileIndex != -1) {
                            configurationListView.scrollTo(selectedProfileIndex, true)
                        } else if (selected) {
                            configurationListView.scrollToPosition(0)
                        }
                    }

                }
                lastGroupUpdateStamp = currentGroupUpdateStamp()
            }

        }

        val profileAccess = Mutex()
        val reloadAccess = Mutex()

        inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyEntity
            
            private fun showShareMenu(anchor: View, proxyEntity: ProxyEntity) {
                val popup = PopupMenu(requireContext(), anchor)
                popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                when {
                    !proxyEntity.haveLink() -> {
                        popup.menu.removeItem(R.id.action_group_qr)
                        popup.menu.removeItem(R.id.action_group_clipboard)
                    }

                    !proxyEntity.haveStandardLink() -> {
                        popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                        popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                            R.id.action_standard_clipboard
                        )
                    }
                }

                if (proxyEntity.nekoBean != null) {
                    popup.menu.removeItem(R.id.action_group_configuration)
                }

                popup.setOnMenuItemClickListener(this)
                popup.show()
            }

            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)

            val trafficText: TextView = view.findViewById(R.id.traffic_text)
            val selectedView: LinearLayout = view.findViewById(R.id.selected_view)
            val editButton: ImageView = view.findViewById(R.id.edit)
            val urlTestButton: ImageView = view.findViewById(R.id.url_test)
            val doubleColumnMenuButton: ImageView = view.findViewById(R.id.double_column_menu)
            val shareLayout: LinearLayout = view.findViewById(R.id.share)
            val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
            val shareButton: ImageView = view.findViewById(R.id.shareIcon)
            val removeButton: ImageView = view.findViewById(R.id.remove)

            private val isCurrentRunningProfile: Boolean
                get() = DataStore.serviceState.started && DataStore.currentProfile == entity.id

            fun updateActionState() {
                if (!::entity.isInitialized) return
                val enabled = !isCurrentRunningProfile
                editButton.isEnabled = enabled
                removeButton.isEnabled = enabled
                urlTestButton.isEnabled = !UrlTest.isUnsupportedByeDPIProfile(entity)
            }

            fun bindTraffic(trafficData: TrafficData? = null) {
                val pf = parentFragment as? ConfigurationFragment ?: return

                var rx = entity.rx
                var tx = entity.tx
                if (adapter?.shouldShowTraffic() != true) {
                    tx = 0L
                    rx = 0L
                } else if (trafficData != null) {
                    tx = trafficData.tx
                    rx = trafficData.rx
                }

                val showTraffic = rx + tx != 0L
                val isCompact = DataStore.groupLayoutMode == 2
                val trafficString = if (showTraffic) {
                    view.context.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(view.context, tx),
                        Formatter.formatFileSize(view.context, rx)
                    )
                } else {
                    ""
                }
                trafficText.isVisible = showTraffic && isCompact
                if (showTraffic) {
                    trafficText.text = trafficString
                } else {
                    trafficText.text = ""
                }

                var address = entity.displayAddress()
                if (showTraffic && address.length >= 30) {
                    address = address.substring(0, 27) + "..."
                }

                if (entity.requireBean().name.isBlank() || !pf.alwaysShowAddress) {
                    address = ""
                }

                profileAddress.text = address
                (trafficText.parent as View).isGone =
                    (!(showTraffic && isCompact) || entity.status <= 0) && address.isBlank()

                if (entity.status <= 0) {
                    if (showTraffic) {
                        profileStatus.text = trafficString
                        profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                        trafficText.text = ""
                    } else {
                        profileStatus.text = ""
                    }
                } else if (entity.status == 1) {
                    val statusText = getString(R.string.available, entity.ping)
                    if (showTraffic && !isCompact) {
                        profileStatus.text = SpannableStringBuilder(statusText)
                            .append("  ")
                            .append(
                                trafficString,
                                ForegroundColorSpan(
                                    requireContext().getColorAttr(android.R.attr.textColorSecondary)
                                ),
                                SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                    } else {
                        profileStatus.text = statusText
                    }
                    profileStatus.setTextColor(requireContext().getColour(R.color.material_green_500))
                } else {
                    profileStatus.setTextColor(requireContext().getColour(R.color.material_red_500))
                    if (entity.status == 2) {
                        profileStatus.text = entity.error
                    }
                }

                if (entity.status == 3) {
                    val err = entity.error ?: "<?>"
                    val msg = Protocols.genFriendlyMsg(err)
                    profileStatus.text = if (msg != err) msg else getString(R.string.unavailable)
                    profileStatus.setOnClickListener {
                        alert(err).tryToShow()
                    }
                } else {
                    profileStatus.setOnClickListener(null)
                }
            }

            fun bind(proxyEntity: ProxyEntity, trafficData: TrafficData? = null) {
                entity = proxyEntity

                if (select) {
                    view.setOnClickListener {
                        (requireActivity() as SelectCallback).returnProfile(proxyEntity.id)
                    }
                } else {
                    view.setOnClickListener {
                        runOnDefaultDispatcher {
                            var update: Boolean
                            var lastSelected: Long
                            profileAccess.withLock {
                                update = DataStore.selectedProxy != proxyEntity.id
                                lastSelected = DataStore.selectedProxy
                                DataStore.selectedProxy = proxyEntity.id
                                onMainDispatcher {
                                    selectedView.visibility = View.VISIBLE
                                }
                            }

                            if (update) {
                                ProfileManager.postUpdate(lastSelected)
                                if (DataStore.serviceState.canStop && reloadAccess.tryLock()) {
                                    SagerNet.reloadService()
                                    reloadAccess.unlock()
                                }
                            } else if (SagerNet.isTv) {
                                if (DataStore.serviceState.started) {
                                    SagerNet.stopService()
                                } else {
                                    SagerNet.startService()
                                }
                            }
                        }

                    }
                }

                profileName.text = proxyEntity.displayName()
                profileType.text = proxyEntity.displayType()
                profileType.setTextColor(requireContext().getProtocolColor(proxyEntity.type))
                bindTraffic(trafficData)

                editButton.setOnClickListener {
                    it.context.startActivity(
                        proxyEntity.settingIntent(
                            it.context, proxyGroup.type == GroupType.SUBSCRIPTION
                        )
                    )
                }

                urlTestButton.setOnClickListener {
                    ProfileUrlTestController.start(proxyEntity)
                }

                removeButton.setOnClickListener {
                    adapter?.let { adapter ->
                        val index = adapter.configurationIdList.indexOf(proxyEntity.id)
                        if (DataStore.confirmProfileDelete) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.delete_confirm_prompt)
                                // .setMessage(getString(R.string.delete_confirm_prompt))
                                .setPositiveButton(R.string.yes) { dialog: DialogInterface, which: Int ->
                                    adapter.remove(index)
                                    undoManager.remove(index to proxyEntity)
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        } else {
                            adapter.remove(index)
                            undoManager.remove(index to proxyEntity)
                        }
                    }
                }
                
                doubleColumnMenuButton.setOnClickListener {
                    val popup = PopupMenu(requireContext(), it)
                    popup.menuInflater.inflate(R.menu.double_column_item_menu, popup.menu)
                    popup.menu.findItem(R.id.action_edit)?.isEnabled = !isCurrentRunningProfile
                    popup.menu.findItem(R.id.action_url_test)?.isEnabled =
                        !UrlTest.isUnsupportedByeDPIProfile(proxyEntity)
                    popup.setOnMenuItemClickListener { menuItem ->
                        when (menuItem.itemId) {
                            R.id.action_edit -> {
                                if (isCurrentRunningProfile) return@setOnMenuItemClickListener true
                                it.context.startActivity(
                                    proxyEntity.settingIntent(
                                        it.context, proxyGroup.type == GroupType.SUBSCRIPTION
                                    )
                                )
                                true
                            }
                            R.id.action_url_test -> {
                                if (UrlTest.isUnsupportedByeDPIProfile(proxyEntity)) {
                                    return@setOnMenuItemClickListener true
                                }
                                ProfileUrlTestController.start(proxyEntity)
                                true
                            }
                            R.id.action_share -> {
                                showShareMenu(it, proxyEntity)
                                true
                            }
                            R.id.action_delete -> {
                                adapter?.let { adapter ->
                                    val index = adapter.configurationIdList.indexOf(proxyEntity.id)
                                    if (DataStore.confirmProfileDelete) {
                                        MaterialAlertDialogBuilder(requireContext())
                                            .setTitle(R.string.delete_confirm_prompt)
                                            .setPositiveButton(R.string.yes) { dialog: DialogInterface, which: Int ->
                                                adapter.remove(index)
                                                undoManager.remove(index to proxyEntity)
                                            }
                                            .setNegativeButton(R.string.no, null)
                                            .show()
                                    } else {
                                        adapter.remove(index)
                                        undoManager.remove(index to proxyEntity)
                                    }
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    popup.show()
                }

                val selectOrChain = select || proxyEntity.type == ProxyEntity.TYPE_CHAIN
                val isDoubleColumn = DataStore.groupLayoutMode == 1

                if (isDoubleColumn) {
                    editButton.isGone = true
                    urlTestButton.isGone = true
                    shareLayout.isGone = true
                    removeButton.isGone = true
                    doubleColumnMenuButton.isVisible = true
                } else {
                    shareLayout.isGone = selectOrChain
                    editButton.isGone = select
                    urlTestButton.isGone = select
                    removeButton.isGone = select
                    doubleColumnMenuButton.isGone = true
                }

                proxyEntity.nekoBean?.apply {
                    if (!isDoubleColumn) {
                        shareLayout.isGone = true
                    }
                }
                updateActionState()

                runOnDefaultDispatcher {
                    val selected = (selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id
                    onMainDispatcher {
                        selectedView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                    }

                    if (!(select || proxyEntity.type == ProxyEntity.TYPE_CHAIN)) {
                        onMainDispatcher {
                            shareLayer.setBackgroundColor(Color.TRANSPARENT)
                            shareButton.setImageResource(R.drawable.ic_social_share)
                            shareButton.setColorFilter(Color.GRAY)
                            shareButton.isVisible = true

                            shareLayout.setOnClickListener {
                                showShareMenu(it, proxyEntity)
                            }
                        }
                    }
                }

            }

            var currentName = ""
            fun showCode(link: String) {
                QRCodeDialog(link, currentName).showAllowingStateLoss(parentFragmentManager)
            }

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                (activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                try {
                    currentName = entity.displayName()!!
                    when (item.itemId) {
                        R.id.action_standard_qr -> showCode(entity.toStdLink())
                        R.id.action_standard_clipboard -> export(entity.toStdLink())
                        R.id.action_universal_qr -> showCode(entity.requireBean().toUniversalLink())
                        R.id.action_universal_clipboard -> export(
                            entity.requireBean().toUniversalLink()
                        )

                        R.id.action_config_export_clipboard -> export(entity.exportConfig().first)
                        R.id.action_config_export_file -> {
                            val cfg = entity.exportConfig()
                            DataStore.serverConfig = cfg.first
                            startFilesForResult(
                                (parentFragment as ConfigurationFragment).exportConfig, cfg.second
                            )
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    (activity as MainActivity).snackbar(e.readableMessage).show()
                    return true
                }
                return true
            }
        }

    }

    private val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(data)!!
                            .bufferedWriter()
                            .use {
                                it.write(DataStore.serverConfig)
                            }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }

                }
            }
        }

    private fun cancelSearch(searchView: SearchView) {
        searchView.setQuery("", false)
        searchView.isIconified = true
        searchView.clearFocus()
    }

    private fun syncToolbarMode() {
        if (select) return
        val activeToolbar = try {
            toolbar
        } catch (_: UninitializedPropertyAccessException) {
            return
        }

        val useToolbar = DataStore.useToolbar
        val menu = activeToolbar.menu
        menu.findItem(R.id.action_toolbar_update_subscription)?.isVisible = useToolbar
        menu.findItem(R.id.action_toolbar_connection_tcp_ping)?.isVisible = useToolbar
        menu.findItem(R.id.action_toolbar_connection_url_test)?.isVisible = useToolbar
        menu.findItem(R.id.action_toolbar_connection_test_delete_unavailable)?.isVisible = useToolbar
        menu.findItem(R.id.action_add)?.setShowAsAction(
            if (useToolbar) MenuItem.SHOW_AS_ACTION_ALWAYS else MenuItem.SHOW_AS_ACTION_IF_ROOM
        )

        activeToolbar.post {
            if (useToolbar) activeToolbar.setDenseActionButtons()
            activeToolbar.titleTextView()?.apply {
                isGone = useToolbar
                isClickable = !useToolbar
                isFocusable = !useToolbar
                setOnClickListener(if (useToolbar) null else View.OnClickListener {
                    focusSelectedProfileGroupAndScroll()
                })
            }
        }
    }

    private fun setupProfileToolbarMenu() {
        toolbar.menu.clear()
        toolbar.inflateMenu(R.menu.add_profile_menu)
        toolbar.menu.findItem(R.id.action_global_mode)?.isChecked = DataStore.globalMode
        toolbar.setOnMenuItemClickListener(this)
        setupSearchView()
    }

    private fun setupSearchView() {
        toolbar.findViewById<SearchView>(R.id.action_search)?.apply {
            setOnQueryTextListener(this@ConfigurationFragment)
            maxWidth = Int.MAX_VALUE
            setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    cancelSearch(this)
                }
            }
        }
    }

    private fun Toolbar.setDenseActionButtons() {
        val actionWidth = dp2px(40)
        val horizontalPadding = dp2px(8)
        val denseActionIds = setOf(
            R.id.action_toolbar_update_subscription,
            R.id.action_toolbar_connection_tcp_ping,
            R.id.action_toolbar_connection_url_test,
            R.id.action_toolbar_connection_test_delete_unavailable,
            R.id.action_add,
            R.id.action_misc,
        )
        children
            .filterIsInstance<ActionMenuView>()
            .flatMap { it.children }
            .filter { it.id in denseActionIds }
            .forEach { child ->
                child.minimumWidth = 0
                child.setPadding(
                    horizontalPadding,
                    child.paddingTop,
                    horizontalPadding,
                    child.paddingBottom
                )
                child.layoutParams = child.layoutParams.apply {
                    width = actionWidth
                }
            }
    }

    private fun Toolbar.titleTextView(): TextView? {
        val expectedTitle = title?.toString() ?: return null

        return children
            .filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString() == expectedTitle }
    }

    private fun scrollCurrentGroupToSelectedProfile() {
        val fragment = getCurrentGroupFragment() ?: return
        val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy

        val selectedProfileIndex =
            fragment.adapter?.configurationIdList?.indexOf(selectedProxy) ?: -1

        if (selectedProfileIndex >= 0) {
            fragment.configurationListView.scrollTo(selectedProfileIndex, true)
        } else {
            fragment.configurationListView.scrollTo(0)
        }
    }

    private fun focusSelectedProfileGroupAndScroll() {
        val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
        if (selectedProxy <= 0) return

        runOnDefaultDispatcher {
            val selectedProfile = SagerDatabase.proxyDao.getById(selectedProxy) ?: return@runOnDefaultDispatcher
            val targetGroupId = selectedProfile.groupId

            onMainDispatcher {
                val targetIndex = adapter.groupList.indexOfFirst { it.id == targetGroupId }
                if (targetIndex < 0) return@onMainDispatcher

                if (DataStore.selectedGroup != targetGroupId || groupPager.currentItem != targetIndex) {
                    DataStore.selectedGroup = targetGroupId
                    groupPager.setCurrentItem(targetIndex, false)

                    groupPager.post {
                        scrollCurrentGroupToSelectedProfile()
                    }
                } else {
                    scrollCurrentGroupToSelectedProfile()
                }
            }
        }
    }
}
