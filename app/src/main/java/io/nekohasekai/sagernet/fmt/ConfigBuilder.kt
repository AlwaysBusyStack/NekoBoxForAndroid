package io.nekohasekai.sagernet.fmt

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.internal.buildSingBoxOutboundProxySetBean
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.masterdns.buildSingBoxOutboundMasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.buildSingBoxOutboundSnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.fmt.trusttunnel.buildSingBoxOutboundTrustTunnelBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.buildSingBoxOutboundJuicityBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildSingBoxOutboundNaiveBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.buildSingBoxOutboundShadowsocksRBean
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireguardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxOutboundAwgBean
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.byedpi.ByeDPIBean
import moe.matsuri.nb4a.proxy.byedpi.buildSingBoxOutboundByeDPIBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.NGUtil.isIpv4Address
import moe.matsuri.nb4a.utils.NGUtil.isPureIpAddress
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val TAG_TUN = "tun-in"
const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val TAG_FRAGMENT = "fragment"
const val TAG_FRAGMENT_EXCLAVE = "fragment-exclave"
const val TAG_BYEDPI_FRAGMENT = "byedpi-fragment"

const val LOCALHOST = "127.0.0.1"

private fun showConfigToast(message: CharSequence, duration: Int) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(SagerNet.application, message, duration).show()
    }
}

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

private fun buildDomainResolverConfig(server: String, strategy: String = ""): Map<String, Any> {
    return linkedMapOf<String, Any>().apply {
        put("server", server)
        if (strategy.isNotBlank()) {
            put("strategy", strategy)
        }
    }
}

private fun validateByeDPIChainProfile(profile: ProxyEntity, context: String) {
    if (profile.isByeDPI()) return
    when (val bean = profile.requireBean()) {
        is ChainBean -> {
            val profiles = SagerDatabase.proxyDao.getEntities(bean.proxies).associateBy { it.id }
            var seenByeDPI = false
            bean.proxies.forEachIndexed { index, proxyId ->
                val child = profiles[proxyId] ?: return@forEachIndexed
                if (child.containsByeDPI()) {
                    if (index != 0 || !child.startsWithByeDPI() || seenByeDPI) {
                        error("ByeDPI must be the first profile in $context")
                    }
                    seenByeDPI = true
                }
                validateByeDPIChainProfile(child, context)
            }
        }

        is ProxySetBean -> {
            // Proxy sets silently skip ByeDPI candidates during member resolution.
        }
    }
}

fun buildConfig(
    proxy: ProxyEntity,
    forTest: Boolean = false,
    forExport: Boolean = false,
    urlTestCacheKey: String = proxy.id.toString(),
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            val tagProxy = proxy.displayName()
            return ConfigBuildResult(
                if (forTest) bean.config.withUrlTestCacheFile(urlTestCacheKey) else bean.config,
                listOf(),
                proxy.id, //
                mapOf(tagProxy to listOf(proxy)), //
                mapOf(proxy.id to tagProxy), //
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val readableNames = mutableSetOf(
        TAG_DIRECT,
        TAG_BYPASS,
        TAG_BLOCK,
        TAG_FRAGMENT,
        TAG_FRAGMENT_EXCLAVE,
        TAG_BYEDPI_FRAGMENT,
        TAG_MIXED,
        TAG_PROXY
    )
    val group = SagerDatabase.groupDao.getById(proxy.groupId)
    val frontProxy = group?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) }
    val landingProxy = group?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) }
    val groupForceUTLS = group?.forceUTLS?.takeIf { it.isNotBlank() }
    val trafficFragmentation = DataStore.trafficFragmentation
    val trafficFragmentationTag = when (trafficFragmentation) {
        TrafficFragmentation.STARIFLY -> TAG_FRAGMENT
        TrafficFragmentation.EXCLAVE -> TAG_FRAGMENT_EXCLAVE
        TrafficFragmentation.BYEDPI -> TAG_BYEDPI_FRAGMENT
        else -> null
    }

    fun SingBoxOption.hasEnabledTLS(): Boolean {
        val outboundMap = asMap()
        val tlsOptions = outboundMap["tls"] as? Map<*, *>
        return tlsOptions?.get("enabled") == true
    }

    fun AbstractBean.isTLSBased(): Boolean {
        return when (this) {
            is StandardV2RayBean -> security == "tls" || security == "reality"
            is ShadowTLSBean, is AnyTLSBean -> true
            is NaiveBean -> proto == "https"
            else -> false
        }
    }

    fun AbstractBean.isTCPBased(): Boolean {
        return when (this) {
            is StandardV2RayBean -> type !in setOf("kcp", "quic")
            is NaiveBean -> proto != "quic"
            is MieruBean -> protocol != "UDP"
            else -> canTCPing()
        }
    }

    fun shouldApplyTrafficFragmentation(outbound: SingBoxOption, bean: AbstractBean): Boolean {
        if (trafficFragmentationTag == null) return false
        if (bean is ByeDPIBean) return false
        val tlsBased = outbound.hasEnabledTLS() || bean.isTLSBased()
        return when (trafficFragmentation) {
            TrafficFragmentation.STARIFLY -> tlsBased
            TrafficFragmentation.EXCLAVE -> when (DataStore.exclaveFragmentMethod) {
                ExclaveFragmentationMethod.TCP_SEGMENTATION -> bean.isTCPBased()
                ExclaveFragmentationMethod.TLS_RECORD_FRAGMENTATION_AND_TCP_SEGMENTATION ->
                    tlsBased || bean.isTCPBased()
                else -> tlsBased
            }
            else -> true
        }
    }

    validateByeDPIChainProfile(proxy, "chains")
    frontProxy?.let {
        if (!it.startsWithByeDPI() && it.containsByeDPI()) {
            error("ByeDPI must be the first profile in front proxy chains")
        }
    }
    if (landingProxy?.containsByeDPI() == true) {
        error("ByeDPI is not allowed as landing proxy")
    }

    fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean is ChainBean) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                if (item.type == ProxyEntity.TYPE_MASTERDNSVPN) {
                    error("MasterDnsVPN is not allowed in proxy chains")
                }
                beanList.addAll(item.resolveChainInternal())
            }
            return beanList.asReversed()
        }
        if (bean is ProxySetBean) {
            val beans = when (bean.type) {
                ProxySetBean.TYPE_LIST -> SagerDatabase.proxyDao.getEntities(bean.proxies)
                ProxySetBean.TYPE_GROUP -> SagerDatabase.proxyDao.getByGroup(bean.groupId)
                else -> throw IllegalStateException("invalid proxy set type ${bean.type}")
            }
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            val regex = bean.groupFilterNotRegex.takeIf { it.isNotBlank() }?.toRegex()
            val ids = if (bean.type == ProxySetBean.TYPE_LIST) bean.proxies else beans.map { it.id }
            for (proxyId in ids) {
                val item = beansMap[proxyId] ?: continue
                if (item.id == id) continue
                if (item.type == ProxyEntity.TYPE_MASTERDNSVPN) continue
                if (regex != null && !regex.containsMatchIn(item.displayName())) continue
                if (item.containsByeDPI()) continue
                when (item.type) {
                    ProxyEntity.TYPE_PROXY_SET -> error("Nested proxy set are not supported")
                    ProxyEntity.TYPE_CHAIN -> if (bean.type == ProxySetBean.TYPE_GROUP) {
                        error("Chain is incompatible with group bean")
                    }
                }
                beanList.add(item)
            }
            beanList.add(this)
            return beanList
        }
        return mutableListOf(this)
    }

    fun readableTag(name_: String): String {
        var name = name_
        var count = 0
        while (!readableNames.add(name)) {
            count++
            name = "$name_-$count"
        }
        return name
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.addAll(frontProxy.resolveChainInternal())
        }
        if (landingProxy != null) {
            list.addAll(0, landingProxy.resolveChainInternal())
        }
        return list
    }

    val selectedGroupProfileIds = if (group == null) {
        emptySet()
    } else {
        buildSet {
            addAll(SagerDatabase.proxyDao.getIdsByGroup(group.id))
            frontProxy?.resolveChainInternal()?.forEach { add(it.id) }
            landingProxy?.resolveChainInternal()?.forEach { add(it.id) }
        }
    }
    val groupForceUTLSProfileIds = if (groupForceUTLS == null) emptySet() else selectedGroupProfileIds

    fun AbstractBean.allowsUTLS(): Boolean {
        return when (this) {
            is StandardV2RayBean -> security == "tls" || security == "reality"
            is AnyTLSBean -> true
            else -> false
        }
    }

    fun SingBoxOption.applyGroupForceUTLS(bean: AbstractBean, proxyEntity: ProxyEntity, enabled: Boolean) {
        val fingerprint = groupForceUTLS ?: return
        if (!enabled || proxyEntity.id !in groupForceUTLSProfileIds || !bean.allowsUTLS()) return
        val tls = try {
            javaClass.getField("tls").get(this) as? OutboundTLSOptions
        } catch (_: Exception) {
            null
        } ?: return
        tls.utls = OutboundUTLSOptions().apply {
            this.enabled = true
            this.fingerprint = fingerprint
        }
    }

    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    extraProxies.values.forEach { validateByeDPIChainProfile(it, "route outbounds") }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    val needSniff = DataStore.trafficSniffing > 0
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    return MyOptions().apply {
	if (!forTest) {
            experimental = ExperimentalOptions().apply {
                cache_file = CacheFile().apply {
                    enabled = true
                    path = "../cache/cache.db"
                    // if (DataStore.enableClashAPI) {
                    store_fakeip = true
                    // }
                }
                
                if (DataStore.enableClashAPI) {
                    clash_api = ClashAPIOptions().apply {
                        external_controller = when (DataStore.hideClashApi) {
                            true -> "unix://../cache/${DataStore.CLASH_API_SOCKET_NAME}"
                            false -> "${DataStore.CLASH_API_HOST}:${DataStore.CLASH_API_PORT}"
                        }
                        external_ui = DataStore.CLASH_API_EXTERNAL_UI
                        external_ui_download_url = DataStore.CLASH_API_EXTERNAL_UI_DOWNLOAD_URL
                        secret = DataStore.clashApiSecret
                    }
                }
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            independent_cache = true
        }

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = TAG_TUN
                interface_name = "tun0"
                stack = when (DataStore.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    else -> "mixed"
                }
                endpoint_independent_nat = true
                mtu = DataStore.mtu
                auto_route = true
                strict_route = DataStore.strictRoute
                address = when (ipv6Mode) {
                    IPv6Mode.DISABLE -> listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                    IPv6Mode.ONLY -> listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    else -> listOf(
                        VpnService.PRIVATE_VLAN4_CLIENT + "/28",
                        VpnService.PRIVATE_VLAN6_CLIENT + "/126"
                    )
                }
            })

            if (!isVPN || DataStore.requireProxyInVPN) {
                inbounds.add(Inbound_MixedOptions().apply {
                    type = "mixed"
                    tag = TAG_MIXED
                    listen = bind
                    listen_port = DataStore.mixedPort
                    if (DataStore.mixedUsername.isNotBlank() || DataStore.mixedPassword.isNotBlank()) {
                        users = listOf(
                            User().apply {
                                username = DataStore.mixedUsername
                                password = DataStore.mixedPassword
                            },
                        )
                    }
                })
            }
        }

        outbounds = mutableListOf()
        endpoints = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            override_android_vpn = true
            rules = mutableListOf()
            rule_set = mutableListOf()

            // 添加并发拨号设置
             concurrent_dial = DataStore.concurrentDial
        }

        // returns outbound tag
        @Suppress("UNCHECKED_CAST")
        fun buildChain(
            chainId: Long,
            entity: ProxyEntity,
            applyGroupForceUTLS: Boolean,
            includeGroupProxyChain: Boolean,
        ): String {
            val profileList = if (includeGroupProxyChain) {
                entity.resolveChain()
            } else {
                entity.resolveChainInternal()
            }
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                if (entity.type == ProxyEntity.TYPE_CHAIN || entity.type == ProxyEntity.TYPE_PROXY_SET) {
                    add(entity)
                } else {
                    plusAssign(profileList)
                    add(entity)
                }
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()
            val outboundsByTag = HashMap<String, SingBoxOption>()
            val mappingInboundTags = HashMap<Long, String>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false
            var pastChainEntity: ProxyEntity? = null

            val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")
            val isProxySet = entity.type == ProxyEntity.TYPE_PROXY_SET

            fun ProxyEntity.resolveProxySetMembers(): List<ProxyEntity> {
                if (type != ProxyEntity.TYPE_PROXY_SET) return emptyList()
                val chain = resolveChainInternal()
                return if (chain.isEmpty()) emptyList() else chain.dropLast(1)
            }

            val reservedTags = HashMap<Long, String>()

            fun reserveTag(proxyEntity: ProxyEntity): String {
                reservedTags[proxyEntity.id]?.let { return it }
                val tag = readableTag(proxyEntity.displayName())
                reservedTags[proxyEntity.id] = tag
                return tag
            }

            fun SingBoxOption.setDetour(tag: String) {
                val outboundType = (this as? Outbound)?.type
                    ?: (_hack_config_map["type"] as? String)
                    ?: (asMap()["type"] as? String)
                // A profile-local ByeDPI outbound is already the fragmentation layer.
                if (outboundType != "byedpi") {
                    _hack_config_map["detour"] = tag
                }
            }

            val proxySetMemberIds = LinkedHashSet<Long>().apply {
                for (proxyEntity in profileList) {
                    if (proxyEntity.requireBean() is ProxySetBean) {
                        for (member in proxyEntity.resolveProxySetMembers()) {
                            add(member.id)
                        }
                    }
                }
            }
            val hasProxySet = proxySetMemberIds.isNotEmpty()

            fun connectChainNode(previousEntity: ProxyEntity, currentTag: String) {
                if (previousEntity.requireBean() is ProxySetBean) {
                    for (member in previousEntity.resolveProxySetMembers()) {
                        val memberTag = checkNotNull(reservedTags[member.id])
                        outboundsByTag[memberTag]?.setDetour(currentTag)
                    }
                    return
                }
                if (previousEntity.needExternal()) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        inbound = listOf(checkNotNull(mappingInboundTags[previousEntity.id]))
                        outbound = currentTag
                    })
                } else {
                    val previousTag = checkNotNull(reservedTags[previousEntity.id])
                    outboundsByTag[previousTag]?.setDetour(currentTag)
                }
            }

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()
                var currentIsEndpoint = false
                val isProxySetMember = proxySetMemberIds.contains(proxyEntity.id) && bean !is ProxySetBean
                val isChainNode = !isProxySetMember

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = if (hasProxySet) reserveTag(proxyEntity) else "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (!hasProxySet && index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans += proxyEntity.requireBean()
                }

                if (!hasProxySet && index == 0) {
                    tagOut = readableTag(bean.displayName())
                }


                // chain rules
                if (!isProxySet) {
                    if (hasProxySet) {
                        if (isChainNode) {
                            if (pastChainEntity != null) {
                                connectChainNode(pastChainEntity!!, tagOut)
                            } else {
                                chainTagOut = tagOut
                            }
                        }
                    } else {
                        if (index > 0) {
                            // chain route/proxy rules
                            if (pastEntity!!.needExternal()) {
                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(pastInboundTag)
                                    outbound = tagOut
                                })
                            } else {
                                pastOutbound.setDetour(tagOut)
                            }
                        } else {
                            // index == 0 means last profile in chain / not chain
                            chainTagOut = tagOut
                        }
                    }
                }

                // now tagOut is determined
                if (needGlobal) {
                    globalOutbounds[proxyEntity.id]?.let {
                        if (index == 0) chainTagOut = it // single, duplicate chain
                        return@forEachIndexed
                    }
                    globalOutbounds[proxyEntity.id] = tagOut
                }

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config) as SingBoxOption

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is JuicityBean ->
                            buildSingBoxOutboundJuicityBean(bean)

                        is TrustTunnelBean ->
                            buildSingBoxOutboundTrustTunnelBean(bean)

                        is MasterDnsVPNBean ->
                            buildSingBoxOutboundMasterDnsVPNBean(bean, proxyEntity.id)

                        is ByeDPIBean ->
                            buildSingBoxOutboundByeDPIBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is NaiveBean ->
                            buildSingBoxOutboundNaiveBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        is ShadowsocksRBean ->
                            buildSingBoxOutboundShadowsocksRBean(bean)

                        is WireGuardBean -> {
                            // WireGuard is now an endpoint in sing-box 1.13+
                            val wgEndpoint = buildSingBoxEndpointWireguardBean(bean)
                            wgEndpoint._hack_config_map["tag"] = tagOut
                            wgEndpoint._hack_config_map["type"] = "wireguard"
                            currentIsEndpoint = true
                            endpoints!!.add(wgEndpoint)
                            wgEndpoint
                        }

                        is AmneziaWGBean ->
                            buildSingBoxOutboundAwgBean(bean)

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        is SnellBean ->
                            buildSingBoxOutboundSnellBean(bean)

                        is ProxySetBean -> {
                            val memberTags = LinkedHashSet<String>()
                            for (member in proxyEntity.resolveProxySetMembers()) {
                                memberTags.add(reserveTag(member))
                            }
                            buildSingBoxOutboundProxySetBean(bean, memberTags.toList().filterNot { it == tagOut })
                        }

                        else -> throw IllegalStateException("can't reach")
                    }

                    currentOutbound.applyGroupForceUTLS(bean, proxyEntity, applyGroupForceUTLS)

                    // internal mux
                    if (!muxApplied && bean !is ProxySetBean) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }

                    if (needGlobal && shouldApplyTrafficFragmentation(currentOutbound, bean)) {
                        currentOutbound.setDetour(checkNotNull(trafficFragmentationTag))
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    if (bean !is ProxySetBean && bean !is MasterDnsVPNBean) {
                        _hack_config_map["domain_resolver"] = buildDomainResolverConfig(
                            if (forTest) "dns-local" else "dns-direct",
                            if (forTest) "" else defaultServerDomainStrategy
                        )
                    }

                    _hack_config_map["tag"] = tagOut

                    _hack_custom_config = bean.customOutboundJson
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    // With ss protect, don't use mapping
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(Inbound_DirectOptions().apply {
                            type = "direct"
                            listen = LOCALHOST
                            listen_port = mappingPort
                            tag = "$chainTag-mapping-${proxyEntity.id}"

                            override_address = bean.serverAddress
                            override_port = bean.serverPort

                                    pastInboundTag = tag
                                    mappingInboundTags[proxyEntity.id] = tag

                                    // no chain rule and not outbound, so need to set to direct
                            if (index == profileList.lastIndex) {
                                if (shouldApplyTrafficFragmentation(currentOutbound, bean)) {
                                    route.rules.add(Rule_DefaultOptions().apply {
                                        network = listOf("tcp")
                                        inbound = listOf(tag)
                                        outbound = checkNotNull(trafficFragmentationTag)
                                    })
                                }

                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    outbound = TAG_DIRECT
                                })
                            }
                        })
                    }
                }

                // Endpoint entries participate in chaining, but are emitted separately.
                if (!currentIsEndpoint) {
                    outbounds!!.add(currentOutbound)
                }
                chainOutbounds.add(currentOutbound)
                outboundsByTag[tagOut] = currentOutbound
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
                if (!isProxySet && isChainNode) {
                    pastChainEntity = proxyEntity
                }
            }

            if (isProxySet) {
                val chainNodes = profileList.filter { proxyEntity ->
                    val bean = proxyEntity.requireBean()
                    !proxySetMemberIds.contains(proxyEntity.id) || bean is ProxySetBean
                }
                if (chainNodes.isNotEmpty()) {
                    chainTagOut = checkNotNull(reservedTags[chainNodes.first().id])
                    for (nodeIndex in 1 until chainNodes.size) {
                        val currentTag = checkNotNull(reservedTags[chainNodes[nodeIndex].id])
                        connectChainNode(chainNodes[nodeIndex - 1], currentTag)
                    }
                    val lastChainNode = chainNodes.last()
                    if (lastChainNode.requireBean() is ProxySetBean) {
                        for (member in lastChainNode.resolveProxySetMembers()) {
                            val memberTag = checkNotNull(reservedTags[member.id])
                            val memberOutbound = outboundsByTag[memberTag] ?: continue
                            if (shouldApplyTrafficFragmentation(memberOutbound, member.requireBean())) {
                                memberOutbound.setDetour(checkNotNull(trafficFragmentationTag))
                            }
                        }
                    } else {
                        val lastChainNodeTag = checkNotNull(reservedTags[lastChainNode.id])
                        val lastOutbound = outboundsByTag[lastChainNodeTag]
                        if (lastOutbound != null && shouldApplyTrafficFragmentation(lastOutbound, lastChainNode.requireBean())) {
                            connectChainNode(lastChainNode, checkNotNull(trafficFragmentationTag))
                        }
                    }

                    val proxySetTag = checkNotNull(reservedTags[entity.id])
                    val chunkStart = (outbounds!!.size - profileList.size).coerceAtLeast(0)
                    val proxySetIndex = outbounds!!.indexOfLast {
                        it._hack_config_map["tag"] == proxySetTag
                    }
                    if (proxySetIndex in chunkStart..outbounds!!.lastIndex) {
                        outbounds!!.add(chunkStart, outbounds!!.removeAt(proxySetIndex))
                    }
                }
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // build outbounds
        if (buildSelector) {
            val list = group.id.let { SagerDatabase.proxyDao.getByGroup(it) }
            if (list.any { it.containsByeDPI() }) {
                error("ByeDPI is not allowed in selector groups")
            }
            list.forEach {
                tagMap[it.id] = buildChain(it.id, it, true, true)
            }
            outbounds.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                default_ = tagMap[proxy.id]
                outbounds = tagMap.values.toList()
            })
        } else {
            val mainTag = buildChain(0, proxy, true, true)
            tagMap[proxy.id] = mainTag
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            val includeGroupProxyChain = p.id in selectedGroupProfileIds && !p.containsByeDPI()
            tagMap[key] = buildChain(key, p, false, includeGroupProxyChain)
        }

        val mainProxyTag = (if (buildSelector) TAG_PROXY else tagMap[proxy.id]) ?: TAG_PROXY
        val mainProxyIsEndpoint = endpoints?.any {
            it._hack_config_map["tag"] == mainProxyTag
        } == true

        // 在应用用户规则之前检查全局模式
        if (forTest) {
            route.final_ = mainProxyTag
        } else if (DataStore.globalMode) {
            // 全局模式下的规则处理
            
            // 绕过内部网络（如果启用）
            if (DataStore.bypassLan) {
                route.rules.add(Rule_DefaultOptions().apply {
                    ip_cidr = listOf(
                        "224.0.0.0/3",
                        "172.16.0.0/12",
                        "127.0.0.0/8",
                        "10.0.0.0/8",
                        "192.168.0.0/16",
                        "169.254.0.0/16",
                        "::1/128",
                        "fc00::/7",
                        "fe80::/10"
                    )
                    outbound = TAG_DIRECT
                })
            }

            route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf(TAG_TUN)
                outbound = mainProxyTag
            })

            route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf(TAG_MIXED)
                outbound = mainProxyTag
            })

            route.final_ = mainProxyTag
        } else {
            if (mainProxyIsEndpoint) {
                // Preserve the historical "selected profile is the implicit default" behavior
                // when the selected main hop is emitted as an endpoint instead of an outbound.
                route.final_ = mainProxyTag
            }

            val nonBypassPackages = mutableListOf<String>()

            // 应用用户规则
            for (rule in extraRules) {
                if (rule.packages.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                }
                val uidList = rule.packages.map {
                    if (!isVPN) {
                        showConfigToast(
                            SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                            Toast.LENGTH_SHORT
                        )
                    }
                    PackageCache[it]?.takeIf { uid -> uid >= 1000 }
                }.toHashSet().filterNotNull()
                val ruleSets = mutableListOf<RuleSet>()

                val ruleObj = Rule_DefaultOptions().apply {
                    if (uidList.isNotEmpty()) {
                        PackageCache.awaitLoadSync()
                        user_id = uidList
                    }
                    var domainList: List<String>? = null
                    if (rule.domains.isNotBlank()) {
                        domainList = rule.domains.listByLineOrComma()
                        makeSingBoxRule(domainList, false)
                    }
                    if (rule.ip.isNotBlank()) {
                        makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                    }
                    
                    if (rule_set != null) generateRuleSet(rule_set, ruleSets)
                    
		    // 存储ruleset标签和类型信息
                    val rulesetTags = mutableListOf<Pair<String, Boolean>>()
                    
                    // 处理远程ruleset
                    if (rule.ruleset.isNotBlank()) {
                        val rulesetUrls = rule.ruleset.listByLineOrComma()
                        rulesetUrls.forEach { origUrl ->
                            val (url, isIPRuleset) = processRulesetUrl(origUrl)
                            
                            val tag = generateRemoteRuleSet(url, ruleSets, DataStore.rulesUpdateInterval, mainProxyTag)
                            
                            rulesetTags.add(Pair(tag, isIPRuleset))
                            
                            rule_set = (rule_set ?: mutableListOf()).apply {
                                add(tag)
                            }
                        }
                    }

                    if (rule.port.isNotBlank()) {
                        port = mutableListOf<Int>()
                        port_range = mutableListOf<String>()
                        rule.port.listByLineOrComma().map {
                            if (it.contains(":")) {
                                port_range.add(it)
                            } else {
                                it.toIntOrNull()?.apply { port.add(this) }
                            }
                        }
                    }
                    if (rule.sourcePort.isNotBlank()) {
                        source_port = mutableListOf<Int>()
                        source_port_range = mutableListOf<String>()
                        rule.sourcePort.listByLineOrComma().map {
                            if (it.contains(":")) {
                                source_port_range.add(it)
                            } else {
                                it.toIntOrNull()?.apply { source_port.add(this) }
                            }
                        }
                    }
                    if (rule.networkType.isNotEmpty()) {
                        network_type = rule.networkType.toList()
                    }
                    if (RuleEntity.isWifiIdentityVisible(rule.networkType)) {
                        val wifiSsidList = RuleEntity.normalizeWifiSsidList(rule.wifiSsid)
                        if (wifiSsidList.isNotEmpty()) {
                            wifi_ssid = wifiSsidList
                        }
                        val wifiBssidList = RuleEntity.normalizeWifiBssidList(rule.wifiBssid)
                        if (wifiBssidList.isNotEmpty()) {
                            wifi_bssid = wifiBssidList
                        }
                    }
                    if (rule.network.isNotBlank()) {
                        network = listOf(rule.network)
                    }
                    if (rule.source.isNotBlank()) {
                        source_ip_cidr = rule.source.listByLineOrComma()
                    }
                    if (rule.protocol.isNotBlank()) {
                        protocol = rule.protocol.listByLineOrComma()
                    }

                    userDNSRuleList += buildRouteDnsRules(
                        createDnsRule = rule.createDnsRule,
                        outbound = rule.outbound,
                        uidList = uidList,
                        domainList = domainList,
                        ruleSet = rule_set,
                        rulesetTags = rulesetTags,
                        useFakeDns = useFakeDns,
                    )

                    outbound = when (val outId = rule.outbound) {
                        0L -> mainProxyTag
                        -1L -> TAG_BYPASS
                        -2L -> TAG_BLOCK
                        else -> if (outId == proxy.id) mainProxyTag else tagMap[outId] ?: ""
                    }

                    _hack_custom_config = rule.config
                }

                if (!ruleObj.checkEmpty()) {
                    if (ruleObj.outbound.isNullOrBlank()) {
                        showConfigToast(
                            "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                            Toast.LENGTH_LONG
                        )
                    } else {
                        // block 改用新的写法
                        if (ruleObj.outbound == TAG_BLOCK) {
                            ruleObj.outbound = null
                            ruleObj.action = "reject"
                        }
                        route.rules.add(ruleObj)
                        route.rule_set.addAll(ruleSets)
                    }
                }

                // List of all per-rule packages where outbound is not Bypass.
                // This excludes those packages from TUN filter rules and enables user-defined rules for them.
                if (rule.packages.isNotEmpty() && rule.outbound != -1L) {
                    nonBypassPackages.addAll(rule.packages)
                }
            }

            // System per-app enforcement rules: prevent force-bound apps from
            // leaking through the wrong outbound. Only apply them to the TUN
            // inbound so the optional local mixed/SOCKS listener stays usable.
            if (isVPN && DataStore.proxyApps) {
                val bypassMode = DataStore.bypass
                val individualPackages = (DataStore.individual
                    .split('\n')
                    .filter { it.isNotBlank() } + if (bypassMode) mutableListOf<String>() else nonBypassPackages)
                    .toSet()

                val dnsLeakList = DataStore.tunDnsWhitelist
                    .split('\n', ',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .filter { isPureIpAddress(it) }
                    .map { if (isIpv4Address(it)) "$it/32" else "$it/128" }
                val dotLeakList = DataStore.tunDotWhitelist
                    .split('\n', ',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val dohLeakList = DataStore.tunDohWhitelist
                    .split('\n', ',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val dotLeakIps = dotLeakList
                    .filter { isPureIpAddress(it) }
                    .map { if (isIpv4Address(it)) "$it/32" else "$it/128" }
                val dohLeakIps = dohLeakList
                    .filter { isPureIpAddress(it) }
                    .map { if (isIpv4Address(it)) "$it/32" else "$it/128" }
                val dotLeakDomains = dotLeakList
                    .filter { !isPureIpAddress(it) }
                    .map { it.lowercase() }
                val dohLeakDomains = dohLeakList
                    .filter { !isPureIpAddress(it) }
                    .map { it.lowercase() }

                val tunDnsMode = when (DataStore.tunSystemDnsTraffic) {
                    "proxy" -> mainProxyTag
                    "direct" -> TAG_BYPASS
                    else -> if (bypassMode) TAG_BYPASS else mainProxyTag
                }

                // Explicitly route direct DNS to direct.
                val directDnsRule = DataStore.directDns
                    .lineSequence().firstNotNullOfOrNull { parseDnsEndpoint(it) }
                    ?.let { endpoint ->
                        val isIp = isPureIpAddress(endpoint.host)
                        val isIpv4 = isIp && isIpv4Address(endpoint.host)

                        val portValues: List<Int> = endpoint.port?.let { listOf(it) } ?: when (endpoint.scheme) {
                            "https" -> listOf(443)
                            "tls", "quic" -> listOf(853, 443)
                            else -> listOf(53)
                        }

                        Rule_DefaultOptions().apply {
                            inbound = listOf(TAG_TUN)

                            if (isIp) {
                                ip_cidr = listOf(
                                    if (isIpv4) "${endpoint.host}/32"
                                    else "${endpoint.host}/128"
                                )
                            } else {
                                domain = listOf(endpoint.host.lowercase())
                            }

                            network = when (endpoint.scheme) {
                                "https", "tls", "tcp" -> listOf("tcp")
                                "quic" -> listOf("udp")
                                else -> listOf("tcp", "udp")
                            }

                            port = portValues

                            protocol = when (endpoint.scheme) {
                                "tls" -> listOf("tls")
                                "quic" -> listOf("quic")
                                else -> null
                            }

                            outbound = TAG_BYPASS
                        }
                    }

                // DNS hangup prevention: route well-known resolvers via
                // proxy regardless of which app sends the query.
                val dnsLeakRule = if (dnsLeakList.count() > 0) Rule_DefaultOptions().apply {
                    inbound = listOf(TAG_TUN)
                    ip_cidr = dnsLeakList.toMutableList()
                    network = listOf("udp")
                    port = listOf(53)
                    outbound = tunDnsMode
                } else null

                val dotLeakRule = if (dotLeakIps.count() > 0 || dotLeakDomains.count() > 0) Rule_DefaultOptions().apply {
                    inbound = listOf(TAG_TUN)
                    domain = dotLeakDomains.toMutableList()
                    ip_cidr = dotLeakIps.toMutableList()
                    network = listOf("tcp", "udp")
                    protocol = listOf("tls", "quic")
                    port = listOf(853)
                    outbound = tunDnsMode
                } else null

                val dohLeakRule = if (dohLeakIps.count() > 0 || dohLeakDomains.count() > 0) Rule_DefaultOptions().apply {
                    inbound = listOf(TAG_TUN)
                    domain = dohLeakDomains.toMutableList()
                    ip_cidr = dohLeakIps.toMutableList()
                    network = listOf("tcp", "udp")
                    protocol = listOf("tls", "quic")
                    port = listOf(443)
                    outbound = tunDnsMode
                } else null

                val catchMyself = Rule_DefaultOptions().apply {
                    inbound = listOf(TAG_TUN)
                    package_name = listOf<String>(BuildConfig.APPLICATION_ID)
                    outbound = mainProxyTag
                }

                val tunMode = DataStore.tunUnrecognizedTraffic
                val catchUnmatched = if (tunMode != "insecure") {
                    Rule_DefaultOptions().apply {
                        inbound = listOf(TAG_TUN)
                        package_name = mutableListOf("android")
                        outbound = when (tunMode) {
                            "block" -> TAG_BLOCK
                            "normal-direct-bypass-block" -> if (bypassMode) TAG_BLOCK else TAG_DIRECT
                            "direct" -> TAG_DIRECT
                            "proxy" -> mainProxyTag
                            else -> TAG_BLOCK
                        }
                    }
                } else null

                var packageRule = if (individualPackages.isNotEmpty()) {
                    Rule_DefaultOptions().apply {
                        inbound = listOf(TAG_TUN)
                        package_name = when (bypassMode) {
                            true -> individualPackages.toMutableList()
                            false -> null
                        }
                        package_name_exclude = when (bypassMode) {
                            false -> individualPackages.toMutableList()
                            true -> null
                        }
                        outbound = TAG_BYPASS
                    }
                } else null

                val catchAllRule = if (bypassMode) {
                    Rule_DefaultOptions().apply {
                        inbound = listOf(TAG_TUN)
                        package_name_exclude = mutableListOf("none")
                        outbound = mainProxyTag
                    }
                } else null

                var index = 0

                route.rules.add(index, catchMyself)
                index++

                directDnsRule?.let {
                    route.rules.add(index, it)
                    index++
                }

                dnsLeakRule?.let {
                    route.rules.add(index, it)
                    index++
                }

                dotLeakRule?.let {
                    route.rules.add(index, it)
                    index++
                }

                dohLeakRule?.let {
                    route.rules.add(index, it)
                    index++
                }

                catchUnmatched?.let {
                    route.rules.add(index, it)
                    index++
                }

                packageRule?.let { route.rules.add(index, it) }
                catchAllRule?.let { route.rules.add(catchAllRule) }
            }
        }

        // 对 rule_set tag 去重，后出现的定义覆盖前面的同名 tag
        if (route.rule_set != null) {
            route.rule_set = route.rule_set
                .asReversed()
                .distinctBy { it.tag }
                .asReversed()
        }

        fun buildExclaveFragmentOutbound(tagValue: String): Outbound {
            return Outbound().apply {
                tag = tagValue
                type = "fragment-exclave"
                when (DataStore.exclaveFragmentMethod) {
                    ExclaveFragmentationMethod.TCP_SEGMENTATION -> {
                        _hack_config_map["tcp_segmentation"] = true
                    }
                    ExclaveFragmentationMethod.TLS_RECORD_FRAGMENTATION_AND_TCP_SEGMENTATION -> {
                        _hack_config_map["tls_record_fragmentation"] = true
                        _hack_config_map["tcp_segmentation"] = true
                    }
                    else -> {
                        _hack_config_map["tls_record_fragmentation"] = true
                    }
                }
            }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(
            if (trafficFragmentation == TrafficFragmentation.EXCLAVE &&
                DataStore.exclaveFragmentForDirect &&
                freedom == TAG_DIRECT
            ) {
                buildExclaveFragmentOutbound(freedom)
            } else {
                Outbound().apply {
                    tag = freedom
                    type = "direct"
                }
            }
        )

        when (trafficFragmentation) {
            TrafficFragmentation.STARIFLY -> {
                val fragmentOutbound = Outbound().apply {
                    tag = TAG_FRAGMENT
                    type = "direct"
                    _hack_config_map["fragment"] = Fragment().apply {
                        length = DataStore.fragmentLength
                        interval = DataStore.fragmentInterval
                    }.asMap()
                }
                outbounds.add(fragmentOutbound)
            }
            TrafficFragmentation.EXCLAVE -> {
                outbounds.add(buildExclaveFragmentOutbound(TAG_FRAGMENT_EXCLAVE))
            }
            TrafficFragmentation.BYEDPI -> {
                outbounds.add(Outbound_ByeDPIOptions().apply {
                    tag = TAG_BYEDPI_FRAGMENT
                    type = "byedpi"
                    cli = DataStore.byedpiFragmentCli
                })
            }
            else -> Unit
        }

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                domainListDNSDirectForce.add("full:${serverAddr}")
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        dns.servers.add(DNSServerOptions().apply {
            address = "rcode://success"
            tag = "dns-block"
        })

        dns.servers.add(DNSServerOptions().apply {
            address = "local"
            tag = "dns-local"
            detour = TAG_DIRECT
        })

        directDNS.firstOrNull().let {
            dns.servers.add(DNSServerOptions().apply {
                address = it ?: throw Exception("No direct DNS, check your settings!")
                tag = "dns-direct"
                detour = TAG_DIRECT
                address_resolver = "dns-local"
                strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
            })
        }

        remoteDns.firstOrNull().let {
            // Always use direct DNS for urlTest
            if (!forTest) dns.servers.add(DNSServerOptions().apply {
                address = it ?: throw Exception("No remote DNS, check your settings!")
                tag = "dns-remote"
                address_resolver = "dns-direct"
                strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
            })
        }

        dns.final_ = if (forTest) "dns-local" else "dns-remote"

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        if (forTest) {
            dns.servers = listOf(DNSServerOptions().apply {
                address = "local"
                tag = "dns-local"
                detour = TAG_DIRECT
            })
            dns.rules = listOf()
        } else {
            // built-in DNS rules
            route.rules.add(0, Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                action = "hijack-dns"
            })
            route.rules.add(0, Rule_DefaultOptions().apply {
                port = listOf(53)
                action = "hijack-dns"
            })
            // Migrate legacy inbound sniff/domain_strategy to route rule actions (sing-box 1.13)
            val routeActionInbounds = buildList {
                if (isVPN) add(TAG_TUN)
                if (!isVPN || DataStore.requireProxyInVPN) add(TAG_MIXED)
            }
            val domainStrategyStr = genDomainStrategy(DataStore.resolveDestination)
            routeActionInbounds.asReversed().forEach { inboundTag ->
                if (domainStrategyStr.isNotEmpty()) {
                    route.rules.add(0, Rule_DefaultOptions().apply {
                        inbound = listOf(inboundTag)
                        action = "resolve"
                        strategy = domainStrategyStr
                    })
                }
                if (needSniff) {
                    route.rules.add(0, Rule_DefaultOptions().apply {
                        inbound = listOf(inboundTag)
                        action = "sniff"
                    })
                }
            }
            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // block mcast
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS obj
            if (useFakeDns) {
                dns.fakeip = DNSFakeIPOptions().apply {
                    enabled = true
                    inet4_range = "198.18.0.0/15"
                    inet6_range = "fc00::/18"
                }
                dns.servers.add(DNSServerOptions().apply {
                    address = "fakeip"
                    tag = "dns-fake"
                    strategy = "ipv4_only"
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf(TAG_TUN)
                    server = "dns-fake"
                    disable_cache = true
                    query_type = listOf("A", "AAAA")
                })
            }
            // avoid loopback
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                })
            }
        }

        if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
    }.let {
        val configMap = it.asMap()
        Util.mergeJSON(configMap, proxy.requireBean().customConfigJson)

        if (!forTest && isVPN && (!DataStore.appendHttpProxy && !DataStore.requireProxyInVPN)) {
            @Suppress("UNCHECKED_CAST")
            val inboundsList = configMap["inbounds"] as? MutableList<MutableMap<String, Any?>>
            inboundsList?.removeAll { inbound ->
                val type = inbound["type"] as? String
                type != null && type != "tun" && type != "direct"
            }
        }

        ConfigBuildResult(
            gson.toJson(configMap).let {
                if (forTest) it.withUrlTestCacheFile(urlTestCacheKey) else it
            },
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L
        )
    }
}

private fun String.withUrlTestCacheFile(cacheKey: String): String {
    return runCatching {
        val config = gson.fromJson(this, JsonObject::class.java) ?: return this
        val existingExperimental = config.get("experimental")
        val experimental = if (existingExperimental is JsonObject) {
            existingExperimental
        } else {
            JsonObject().also { config.add("experimental", it) }
        }
        experimental.add("cache_file", JsonObject().apply {
            addProperty("enabled", false)
            addProperty(
                "path",
                "../cache/urltest_${cacheKey}_${SystemClock.elapsedRealtimeNanos()}.db"
            )
        })
        gson.toJson(config)
    }.getOrElse {
        this
    }
}
