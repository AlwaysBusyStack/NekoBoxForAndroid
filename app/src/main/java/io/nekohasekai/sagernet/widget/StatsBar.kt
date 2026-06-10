package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.ProfileStatusUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.ConnectionIpResolver
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var ipText: TextView
    private lateinit var behavior: YourBehavior

    var allowShow = true
    private var masterDnsVPNResolverChecking = false

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideDown(child)
        }
    }


    override fun onFinishInflate() {
        super.onFinishInflate()
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        ipText = findViewById(R.id.ip)
        applyThemeColors()
    }

    override fun setOnClickListener(l: OnClickListener?) {
        super.setOnClickListener(l)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (this::statusText.isInitialized) applyThemeColors()
    }

    private fun applyThemeColors() {
        val usePrimary = Theme.isCustom() && DataStore.customThemeStatsBarPrimary
        val backgroundColor = context.getColorAttr(
            if (usePrimary) R.attr.colorPrimary else R.attr.colorSurfaceContainerHigh
        )
        val textColor = context.getColorAttr(
            if (usePrimary) R.attr.colorOnPrimary else R.attr.colorOnSurface
        )
        backgroundTintList = ColorStateList.valueOf(backgroundColor)
        listOf(statusText, txText, rxText, ipText).forEach { it.setTextColor(textColor) }
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, buildTooltipText(text))
    }

    private fun setIpInfo(text: CharSequence?) {
        if (text.isNullOrBlank()) {
            ipText.text = " "
        } else {
            ipText.text = text
        }
        TooltipCompat.setTooltipText(this, buildTooltipText(statusText.text))
    }

    private fun buildTooltipText(status: CharSequence): CharSequence {
        val ip = ipText.text?.takeIf { ipText.isVisible && it.isNotBlank() }
        return if (ip == null) status else "$ip\n$status"
    }

    private tailrec fun Context.mainActivity(): MainActivity? {
        return when (this) {
            is MainActivity -> this
            is ContextWrapper -> baseContext.mainActivity()
            else -> null
        }
    }

    fun changeState(state: BaseService.State) {
        if (
            masterDnsVPNResolverChecking &&
            (state == BaseService.State.Connecting || state == BaseService.State.Connected)
        ) {
            return
        }
        if (state != BaseService.State.Connecting) {
            masterDnsVPNResolverChecking = false
            isEnabled = true
        }
        val activity = context.mainActivity() ?: return
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
            postWhenStarted {
                if (allowShow) performShow()
                setStatus(app.getText(R.string.vpn_connected))
            }
        } else {
            postWhenStarted {
                performHide()
            }
            setIpInfo(null)
            updateSpeed(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    fun showMasterDnsVPNResolverProgress(found: Int, total: Int, ready: Boolean) {
        if (ready) {
            masterDnsVPNResolverChecking = false
            isEnabled = DataStore.serviceState.connected
            hideOnScroll = true
            setIpInfo(null)
            setStatus(app.getText(R.string.vpn_connected))
            val activity = context.mainActivity() ?: return
            activity.lifecycleScope.launch(Dispatchers.Main) {
                delay(100L)
                activity.whenStarted {
                    if (allowShow) performShow()
                }
            }
            return
        }
        masterDnsVPNResolverChecking = true
        hideOnScroll = false
        isEnabled = false
        setIpInfo(null)
        updateSpeed(0, 0)
        setStatus(context.getString(R.string.masterdnsvpn_checking_resolvers, found, total))
        val activity = context.mainActivity() ?: return
        activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted {
                if (allowShow) performShow()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        if (DataStore.speedInterval <= 0) {
            txText.text = " "
            rxText.isGone = true
            return
        }

        rxText.isGone = false
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun testConnection() {
        val activity = context.mainActivity() ?: return
        val profileId = DataStore.currentProfile
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                updateCurrentProfileStatus(profileId, status = 1, ping = elapsed, error = null)
                val status = app.getString(
                    if (DataStore.connectionTestURL.startsWith("https://")) {
                        R.string.connection_test_available
                    } else {
                        R.string.connection_test_available_http
                    }, elapsed
                )
                val ipResult = async(Dispatchers.IO) { ConnectionIpResolver.resolve() }
                onMainDispatcher {
                    isEnabled = true
                    setStatus(status)
                    setIpInfo(null)
                }
                val ipInfo = ipResult.await()
                onMainDispatcher {
                    if (
                        ipInfo == null &&
                        DataStore.serviceMode == Key.MODE_VPN &&
                        !DataStore.requireProxyInVPN
                    ) {
                        setIpInfo(null)
                    } else {
                        setIpInfo(ipInfo ?: context.getString(R.string.failed_to_obtain_ip))
                    }
                }
            } catch (e: Exception) {
                Logs.w(e.toString())
                updateCurrentProfileStatus(profileId, status = 3, error = e.readableMessage)
                val errorStatus = app.getString(
                    R.string.connection_test_error,
                    e.readableMessage
                )
                onMainDispatcher {
                    isEnabled = true
                    setStatus(errorStatus)

                    activity.snackbar(errorStatus).show()
                }
            }
        }
    }

    private suspend fun updateCurrentProfileStatus(
        profileId: Long,
        status: Int,
        ping: Int = 0,
        error: String?,
    ) {
        ProfileStatusUpdater.update(
            profileId,
            status,
            ping,
            error,
            reloadDelayOrderedGroup = false
        )
    }

}
