package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import android.os.Build
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SpoofApp
import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1Json
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.toUri
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.parseShadowsocksR
import io.nekohasekai.sagernet.fmt.snell.parseClashSnell
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.XhttpExtraConverter
import io.nekohasekai.sagernet.fmt.v2ray.applyClashXhttpOptions
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.fmt.v2ray.toUriVMessVLESSTrojan
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.hysteria.toUri
import io.nekohasekai.sagernet.ktx.*
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.utils.HwidGenerator
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.Util
import org.ini4j.Ini
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.TypeDescription
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.StringReader
import java.util.Locale
import androidx.core.net.toUri

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    private fun normalizeWireGuardAddress(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.contains("/")) return trimmed
        return if (trimmed.isIpAddressV6()) "$trimmed/128" else "$trimmed/32"
    }

    private fun parseWireGuardAddresses(values: List<String>): String {
        return values
            .flatMap { it.split(",") }
            .map { normalizeWireGuardAddress(it) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun shouldHandleRemnaXrayJsonCompat(subscription: SubscriptionBean): Boolean {
        if (subscription.hwidEnabled != true) return false
        return when (subscription.spoofApp ?: SpoofApp.NONE) {
            SpoofApp.HAPP, SpoofApp.V2RAY_TUN -> true
            else -> false
        }
    }

    private suspend fun parseRawCompat(
        text: String,
        subscription: SubscriptionBean,
        fileName: String = ""
    ): List<AbstractBean>? {
        if (shouldHandleRemnaXrayJsonCompat(subscription)) {
            maybeConvertRemnaXrayJsonSubscription(text)?.let { convertedText ->
                Logs.d("Detected Remna Xray JSON subscription, generated normal links")
                return parseRaw(convertedText, fileName)
            }
            Logs.d("Remna Xray JSON compatibility route skipped")
        }
        return parseRaw(text, fileName)
    }

    private fun maybeConvertRemnaXrayJsonSubscription(text: String): String? {
        val json = runCatching { JSONTokener(text).nextValue() }.getOrNull() ?: return null
        return convertRemnaXrayJson(json)
    }

    private fun convertRemnaXrayJson(json: Any): String? {
        val configs = when (json) {
            is JSONObject -> listOf(json)
            is JSONArray -> mutableListOf<JSONObject>().apply {
                for (i in 0 until json.length()) {
                    add(json.optJSONObject(i) ?: return null)
                }
            }
            else -> return null
        }
        if (configs.isEmpty()) return null

        val converted = mutableListOf<String>()
        var hasProtocolOutbound = false
        var hasTypeOutbound = false

        for (config in configs) {
            val outbounds = config.optJSONArray("outbounds") ?: return null
            val candidateOutbounds = mutableListOf<JSONObject>()
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                if (outbound.has("protocol")) hasProtocolOutbound = true
                if (outbound.has("type")) hasTypeOutbound = true
                if (outbound.has("protocol") || outbound.has("type")) {
                    candidateOutbounds += outbound
                }
            }
            if (candidateOutbounds.isEmpty()) return null

            val remarks = config.optString("remarks").takeIf { it.isNotBlank() }
            for ((index, outbound) in candidateOutbounds.withIndex()) {
                val link = convertRemnaOutbound(outbound, remarks, index, candidateOutbounds.size) ?: continue
                converted += link
            }
        }

        if (!hasProtocolOutbound || hasTypeOutbound || converted.isEmpty()) return null
        return converted.joinToString("\n")
    }

    private fun convertRemnaOutbound(
        outbound: JSONObject,
        remarks: String?,
        index: Int,
        total: Int
    ): String? {
        val protocol = outbound.optString("protocol")
        if (protocol.isBlank()) return null

        val beanName = buildRemnaProxyName(outbound, remarks, index, total, protocol)
        val converted = when (protocol) {
            "vless" -> convertRemnaVlessOutbound(outbound)
            "trojan" -> convertRemnaTrojanOutbound(outbound)
            "shadowsocks" -> convertRemnaShadowsocksOutbound(outbound)
            "hysteria" -> convertRemnaHysteriaOutbound(outbound)
            else -> {
                Logs.w("Unsupported Remna Xray protocol: $protocol")
                null
            }
        } ?: return null

        converted.name = beanName
        return when (converted) {
            is VMessBean -> converted.toUriVMessVLESSTrojan(false)
            is TrojanBean -> converted.toUriVMessVLESSTrojan(true)
            is ShadowsocksBean -> converted.toUri()
            is HysteriaBean -> converted.toUri()
            else -> null
        }
    }

    private fun buildRemnaProxyName(
        outbound: JSONObject,
        remarks: String?,
        index: Int,
        total: Int,
        protocol: String
    ): String {
        val tag = outbound.optString("tag").takeIf { it.isNotBlank() }
        return when {
            !remarks.isNullOrBlank() && total == 1 -> remarks
            !remarks.isNullOrBlank() && !tag.isNullOrBlank() -> "$remarks [$tag]"
            !remarks.isNullOrBlank() -> "$remarks #${index + 1}"
            !tag.isNullOrBlank() -> tag
            else -> "Remna ${protocol.replaceFirstChar { it.uppercase() }}"
        }
    }

    private fun convertRemnaVlessOutbound(outbound: JSONObject): VMessBean? {
        val server = outbound.optJSONObject("settings")
            ?.optJSONArray("vnext")
            ?.optJSONObject(0) ?: return null
        val user = server.optJSONArray("users")?.optJSONObject(0) ?: return null
        return VMessBean().applyDefaultValues().apply {
            alterId = -1
            serverAddress = server.optString("address")
            serverPort = server.optInt("port")
            uuid = user.optString("id")
            encryption = user.optString("flow")
            vlessEncryption = user.optString("encryption").takeIf { it.isNotBlank() } ?: "none"
            applyRemnaStreamSettings(this, outbound)
        }
    }

    private fun convertRemnaTrojanOutbound(outbound: JSONObject): TrojanBean? {
        val server = outbound.optJSONObject("settings")
            ?.optJSONArray("servers")
            ?.optJSONObject(0) ?: return null
        return TrojanBean().applyDefaultValues().apply {
            serverAddress = server.optString("address")
            serverPort = server.optInt("port")
            password = server.optString("password")
            applyRemnaStreamSettings(this, outbound)
        }
    }

    private fun convertRemnaShadowsocksOutbound(outbound: JSONObject): ShadowsocksBean? {
        val server = outbound.optJSONObject("settings")
            ?.optJSONArray("servers")
            ?.optJSONObject(0) ?: return null
        return ShadowsocksBean().applyDefaultValues().apply {
            serverAddress = server.optString("address")
            serverPort = server.optInt("port")
            method = server.optString("method")
            password = server.optString("password")
        }
    }

    private fun convertRemnaHysteriaOutbound(outbound: JSONObject): HysteriaBean? {
        val settings = outbound.optJSONObject("settings") ?: return null
        return HysteriaBean().applyDefaultValues().apply {
            protocolVersion = 2
            serverAddress = settings.optString("address")
            serverPort = settings.optInt("port")
            serverPorts = settings.optInt("port").toString()
            authPayload = outbound.optJSONObject("streamSettings")
                ?.optJSONObject("hysteriaSettings")
                ?.optString("auth")
                .orEmpty()
            obfuscation = ""
            sni = outbound.optJSONObject("streamSettings")
                ?.let { extractRemnaServerName(it) }
                .orEmpty()
            allowInsecure = outbound.optJSONObject("streamSettings")
                ?.optJSONObject("tlsSettings")
                ?.optBoolean("allowInsecure") == true
        }
    }

    private fun applyRemnaStreamSettings(bean: StandardV2RayBean, outbound: JSONObject) {
        val streamSettings = outbound.optJSONObject("streamSettings") ?: return
        bean.type = extractRemnaTransportType(streamSettings)
        bean.host = ""
        bean.path = ""
        bean.headerType = "none"

        when (bean.type) {
            "ws" -> streamSettings.optJSONObject("wsSettings")?.also { settings ->
                bean.path = settings.optString("path")
                bean.host = settings.optJSONObject("headers")?.optString("Host").orEmpty()
                bean.wsMaxEarlyData = settings.optInt("maxEarlyData")
                bean.earlyDataHeaderName = settings.optString("earlyDataHeaderName")
            }
            "httpupgrade" -> streamSettings.optJSONObject("httpupgradeSettings")?.also { settings ->
                bean.host = settings.optString("host")
                bean.path = settings.optString("path")
            }
            "grpc" -> streamSettings.optJSONObject("grpcSettings")?.also { settings ->
                bean.path = settings.optString("serviceName")
            }
            "xhttp" -> streamSettings.optJSONObject("xhttpSettings")?.also { settings ->
                bean.host = settings.optString("host")
                bean.path = settings.optString("path")
                bean.xhttpMode = settings.optString("mode")
                settings.opt("extra")
                    ?.takeIf { it != JSONObject.NULL && !it.toString().trim().equals("null", ignoreCase = true) }
                    ?.let { extra ->
                        bean.xhttpExtra = XhttpExtraConverter.xrayToSingBox(extra.toString())
                    }
            }
            "kcp" -> streamSettings.optJSONObject("kcpSettings")?.also { settings ->
                bean.mKcpSeed = settings.optString("seed")
                bean.headerType = settings.optJSONObject("header")?.optString("type").orEmpty()
                    .ifBlank { "none" }
            }
            "http" -> streamSettings.optJSONObject("tcpSettings")
                ?.optJSONObject("header")
                ?.optJSONObject("request")
                ?.also { request ->
                    bean.path = request.optJSONArray("path")?.optString(0).orEmpty()
                    bean.host = request.optJSONObject("headers")
                        ?.optJSONArray("Host")
                        ?.optString(0)
                        .orEmpty()
                    bean.headerType = "http"
                }
        }

        applyRemnaSecuritySettings(bean, streamSettings)
        applyRemnaMuxSettings(bean, outbound.optJSONObject("mux"))
    }

    private fun extractRemnaTransportType(streamSettings: JSONObject): String {
        return when (streamSettings.optString("network")) {
            "", "tcp" -> if (
                streamSettings.optJSONObject("tcpSettings")
                    ?.optJSONObject("header")
                    ?.optString("type") == "http"
            ) {
                "http"
            } else {
                "tcp"
            }
            else -> streamSettings.optString("network")
        }
    }

    private fun applyRemnaSecuritySettings(bean: StandardV2RayBean, streamSettings: JSONObject) {
        val security = streamSettings.optString("security")
        if (security != "tls" && security != "reality") return
        bean.security = security

        when (security) {
            "tls" -> streamSettings.optJSONObject("tlsSettings")?.also { settings ->
                bean.sni = settings.optString("serverName")
                bean.alpn = settings.optJSONArray("alpn")?.jsonArrayToLines().orEmpty()
                bean.allowInsecure = settings.optBoolean("allowInsecure")
                bean.utlsFingerprint = settings.optString("fingerprint")
            }
            "reality" -> streamSettings.optJSONObject("realitySettings")?.also { settings ->
                bean.sni = settings.optString("serverName")
                bean.utlsFingerprint = settings.optString("fingerprint")
                bean.realityPubKey = settings.optString("publicKey")
                bean.realityShortId = settings.optString("shortId")
            }
        }
    }

    private fun applyRemnaMuxSettings(bean: StandardV2RayBean, mux: JSONObject?) {
        if (mux == null || mux.length() == 0) return
        bean.enableMux = true
        mux.optInt("maxConcurrency").takeIf { it > 0 }?.let { bean.muxConcurrency = it }
        mux.optInt("maxConnections").takeIf { it > 0 }?.let {
            bean.muxMode = 1
            bean.muxMaxConnections = it
        }
        if (mux.has("padding")) bean.muxPadding = mux.optBoolean("padding")
    }

    private fun extractRemnaServerName(streamSettings: JSONObject): String {
        return when (streamSettings.optString("security")) {
            "tls" -> streamSettings.optJSONObject("tlsSettings")?.optString("serverName").orEmpty()
            "reality" -> streamSettings.optJSONObject("realitySettings")?.optString("serverName").orEmpty()
            else -> ""
        }
    }

    private fun JSONArray.jsonArrayToLines(): String {
        return (0 until length())
            .mapNotNull { optString(it).takeIf { value -> value.isNotBlank() } }
            .joinToString("\n")
    }

    @SuppressLint("Recycle")
    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link
        var proxies: List<AbstractBean>
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(link.toUri())
                ?.bufferedReader()
                ?.readText()

            proxies = contentText?.let { parseRaw(contentText) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
        } else {

            val client = Libcore.newHttpClient().apply {
                setTimeoutMillis(GroupUpdater.SUBSCRIPTION_UPDATE_TIMEOUT_MILLIS)
                tryH3Direct()
                when (DataStore.appTLSVersion) {
                    "1.3" -> restrictedTLS()
                }
            }
            try {
                val response = client.newRequest().apply {
                    if (DataStore.allowInsecureOnRequest) {
                        allowInsecure()
                    }
                    setURL(subscription.link)
                    setUserAgent(subscription.customUserAgent.takeIf { it.isNotBlank() } ?: USER_AGENT)
                    if (subscription.hwidEnabled == true) {
                        val spoofApp = subscription.spoofApp ?: SpoofApp.NONE
                        setHeader("X-Hwid", HwidGenerator.generate(app))
                        if (spoofApp != SpoofApp.V2RAY_TUN) {
                            setHeader("X-Device-Os", "Android")
                        }
                        setHeader("X-Ver-Os", Build.VERSION.SDK_INT.toString())
                        setHeader("X-Device-Model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                        when (spoofApp) {
                            SpoofApp.HAPP -> setHeader("X-Device-Locale", Locale.getDefault().language)
                            SpoofApp.V2RAY_TUN -> setHeader("X-App-Version", "5.21.68")
                        }
                    }
                }.execute()

                if (Util.getStringBox(response.getHeader("x-hwid-not-supported")).lowercase() == "true") {
                    error(app.getString(R.string.hwid_not_supported))
                } else if (Util.getStringBox(response.getHeader("x-hwid-max-devices-reached")).lowercase() == "true" ||
                    Util.getStringBox(response.getHeader("x-hwid-limit")).lowercase() == "true"
                ) {
                    error(app.getString(R.string.hwid_max_devices_reached))
                }

                val responseText = Util.getStringBox(response.contentString)
                proxies = parseRawCompat(responseText, subscription)
                    ?: error(app.getString(R.string.no_proxies_found))

                subscription.subscriptionUserinfo =
                    Util.getStringBox(response.getHeader("Subscription-Userinfo"))

                // 修改默认名字
                if (proxyGroup.name?.startsWith("Subscription #") == true) {
                    var remoteName = Util.getStringBox(response.getHeader("content-disposition"))
                    if (remoteName.isNotBlank()) {
                        remoteName = Util.decodeFilename(remoteName)
                        if (remoteName.isNotBlank()) {
                            proxyGroup.name = remoteName
                        }
                    }
                }
            } finally {
                client.close()
            }
        }

        val proxiesMap = LinkedHashMap<String, AbstractBean>()
        for (proxy in proxies) {
            var index = 0
            var name = proxy.displayName()
            while (proxiesMap.containsKey(name)) {
                println("Exists name: $name")
                index++
                name = name.replace(" (${index - 1})", "")
                name = "$name ($index)"
                proxy.name = name
            }
            proxiesMap[proxy.displayName()] = proxy
        }
        proxies = proxiesMap.values.toList()

        if (subscription.forceResolve) forceResolve(proxies, proxyGroup.id)

        val filterMode = subscription.filterMode ?: SubscriptionFilterMode.DISABLED
        val filterRegex = subscription.filterRegex ?: ""
        if (filterMode != SubscriptionFilterMode.DISABLED && filterRegex.isNotBlank()) {
            val regex = filterRegex.toRegex()
            proxies = when (filterMode) {
                SubscriptionFilterMode.INCLUDE -> proxies.filter { regex.containsMatchIn(it.displayName()) }
                SubscriptionFilterMode.EXCLUDE -> proxies.filterNot { regex.containsMatchIn(it.displayName()) }
                else -> proxies
            }
            Logs.d("After filter (mode=$filterMode): ${proxies.size}")
        }

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        val duplicate = ArrayList<String>()
        if (subscription.deduplication) {
            Logs.d("Before deduplication: ${proxies.size}")
            val uniqueProxyHashes = ArrayList<String>()
            val uniqueProxies = LinkedHashMap<String, AbstractBean>()
            val uniqueNames = HashMap<String, String>()
            for (_proxy in proxies) {
                val proxyHash = _proxy.hash
                val existingIndex = uniqueProxyHashes.indexOf(proxyHash)
                if (existingIndex >= 0) {
                    if (uniqueNames.containsKey(proxyHash)) {
                        val name = uniqueNames[proxyHash]!!.replace(" ($existingIndex)", "")
                        if (name.isNotBlank()) {
                            duplicate.add("$name ($existingIndex)")
                            uniqueNames[proxyHash] = ""
                        }
                    }
                    duplicate.add(_proxy.displayName() + " ($existingIndex)")
                } else {
                    uniqueProxyHashes.add(proxyHash)
                    uniqueProxies[proxyHash] = _proxy
                    uniqueNames[proxyHash] = _proxy.displayName()
                }
            }
            uniqueProxies.keys.retainAll(uniqueNames.keys)
            proxies = uniqueProxies.values.toList()
        }

        Logs.d("New profiles: ${proxies.size}")

        val nameMap = proxies.associateBy { bean ->
            bean.displayName()
        }

        Logs.d("Unique profiles: ${nameMap.size}")

        val toDelete = ArrayList<ProxyEntity>()
        val toReplace = exists.mapNotNull { entity ->
            val name = entity.displayName()
            if (nameMap.contains(name)) name to entity else let {
                toDelete.add(entity)
                null
            }
        }.toMap()

        Logs.d("toDelete profiles: ${toDelete.size}")
        Logs.d("toReplace profiles: ${toReplace.size}")

        val toUpdate = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        val deleted = toDelete.map { it.displayName() }

        var userOrder = 1L
        var changed = toDelete.size
        for ((name, bean) in nameMap.entries) {
            if (toReplace.contains(name)) {
                val entity = toReplace[name]!!
                val existsBean = entity.requireBean()
                // 更新订阅，保留自定义覆写设置
                bean.customOutboundJson = existsBean.customOutboundJson
                bean.customConfigJson = existsBean.customConfigJson
                when {
                    existsBean != bean -> {
                        changed++
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        updated[entity.displayName()] = name

                        Logs.d("Updated profile: $name")
                    }

                    entity.userOrder != userOrder -> {
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        entity.userOrder = userOrder

                        Logs.d("Reordered profile: $name")
                    }

                    else -> {
                        Logs.d("Ignored profile: $name")
                    }
                }
            } else {
                changed++
                SagerDatabase.proxyDao.addProxy(
                    ProxyEntity(
                        groupId = proxyGroup.id, userOrder = userOrder
                    ).apply {
                        putBean(bean)
                    })
                added.add(name)
                Logs.d("Inserted profile: $name")
            }
            userOrder++
        }

        SagerDatabase.proxyDao.updateProxy(toUpdate).also {
            Logs.d("Updated profiles: $it")
        }

        SagerDatabase.proxyDao.deleteProxy(toDelete).also {
            Logs.d("Deleted profiles: $it")
        }

        val existCount = SagerDatabase.proxyDao.countByGroup(proxyGroup.id).toInt()

        if (existCount != proxies.size) {
            Logs.e("Exist profiles: $existCount, new profiles: ${proxies.size}")
        }

        subscription.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
        SagerDatabase.groupDao.updateGroup(proxyGroup)

        userInterface?.onUpdateSuccess(
            proxyGroup, changed, added, updated, deleted, duplicate, byUser
        )
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? {

        val proxies = mutableListOf<AbstractBean>()

        if (text.contains("proxies:")) {

            // clash & meta

            try {

                val yaml = Yaml().apply {
                    addTypeDescription(TypeDescription(String::class.java, "str"))
                }.loadAs(text, Map::class.java)

                val globalClientFingerprint = yaml["global-client-fingerprint"]?.toString() ?: ""

                for (proxy in (yaml["proxies"] as? (List<Map<String, Any?>>) ?: error(
                    app.getString(R.string.no_proxies_found_in_file)
                ))) {
                    // Note: YAML numbers parsed as "Long"

                    when (proxy["type"] as String) {
                        "socks5" -> {
                            proxies.add(SOCKSBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                username = proxy["username"]?.toString()
                                password = proxy["password"]?.toString()
                                name = proxy["name"]?.toString()
                            })
                        }

                        "http" -> {
                            proxies.add(HttpBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                username = proxy["username"]?.toString()
                                password = proxy["password"]?.toString()
                                setTLS(proxy["tls"]?.toString() == "true")
                                sni = proxy["sni"]?.toString()
                                name = proxy["name"]?.toString()
                                allowInsecure = proxy["skip-cert-verify"]?.toString() == "true"
                            })
                        }

                        "ss" -> {
                            val ssPlugin = mutableListOf<String>()
                            if (proxy.contains("plugin")) {
                                val opts = proxy["plugin-opts"] as Map<String, Any?>
                                when (proxy["plugin"]) {
                                    "obfs" -> {
                                        ssPlugin.apply {
                                            add("obfs-local")
                                            add("obfs=" + (opts["mode"]?.toString() ?: ""))
                                            add("obfs-host=" + (opts["host"]?.toString() ?: ""))
                                        }
                                    }

                                    "v2ray-plugin" -> {
                                        ssPlugin.apply {
                                            add("v2ray-plugin")
                                            add("mode=" + (opts["mode"]?.toString() ?: ""))
                                            if (opts["mode"]?.toString() == "true") add("tls")
                                            add("host=" + (opts["host"]?.toString() ?: ""))
                                            add("path=" + (opts["path"]?.toString() ?: ""))
                                            if (opts["mux"]?.toString() == "true") add("mux=8")
                                        }
                                    }
                                }
                            }
                            proxies.add(ShadowsocksBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                password = proxy["password"]?.toString()
                                method = clashCipher(proxy["cipher"] as String)
                                plugin = ssPlugin.joinToString(";")
                                name = proxy["name"]?.toString()
                            })
                        }

                        "ssr" -> {
                            proxies.add(ShadowsocksRBean().apply {
                                for (opt in proxy) {
                                    if (opt.value == null) continue
                                    when (opt.key) {
                                        "name" -> name = opt.value.toString()
                                        "server" -> serverAddress = opt.value as String
                                        "port" -> serverPort = opt.value.toString().toInt()
                                        "cipher" -> method = clashCipher(opt.value as String)
                                        "password" -> password = opt.value.toString()
                                        "obfs" -> obfs = opt.value as String
                                        "protocol" -> protocol = opt.value as String
                                        "obfs-param" -> obfsParam = opt.value.toString()
                                        "protocol-param" -> protocolParam = opt.value.toString()
                                    }
                                }
                            })
                        }

                        "vmess", "vless", "trojan" -> {
                            val bean = when (proxy["type"] as String) {
                                "vmess" -> VMessBean()
                                "vless" -> VMessBean().apply {
                                    alterId = -1 // make it VLESS
                                    packetEncoding = 2 // clash meta default XUDP
                                }

                                "trojan" -> TrojanBean().apply {
                                    security = "tls"
                                }

                                else -> error("impossible")
                            }

                            bean.serverAddress = proxy["server"]?.toString() ?: continue
                            bean.serverPort = proxy["port"]?.toString()?.toIntOrNull() ?: continue

                            for (opt in proxy) {
                                when (opt.key) {
                                    "name" -> bean.name = opt.value?.toString()
                                    "password" -> if (bean is TrojanBean) bean.password =
                                        opt.value?.toString()

                                    "uuid" -> if (bean is VMessBean) bean.uuid =
                                        opt.value?.toString()

                                    "alterId" -> if (bean is VMessBean && !bean.isVLESS) bean.alterId =
                                        opt.value?.toString()?.toIntOrNull()

                                    "cipher" -> if (bean is VMessBean && !bean.isVLESS) bean.encryption =
                                        (opt.value as? String)

                                    "flow" -> if (bean is VMessBean && bean.isVLESS) {
                                        (opt.value as? String)?.let {
                                            if (it.contains("xtls-rprx-vision")) {
                                                bean.encryption = "xtls-rprx-vision"
                                            }
                                        }
                                    }

                                    "encryption" -> if (bean is VMessBean && bean.isVLESS) {
                                        bean.vlessEncryption = opt.value?.toString() ?: ""
                                    }

                                    "packet-encoding" -> if (bean is VMessBean) {
                                        bean.packetEncoding = when ((opt.value as? String)) {
                                            "packetaddr" -> 1
                                            "xudp" -> 2
                                            else -> 0
                                        }
                                    }

                                    "tls" -> if (bean is VMessBean) {
                                        bean.security =
                                            if (opt.value as? Boolean == true) "tls" else ""
                                    }

                                    "servername", "sni" -> bean.sni = opt.value?.toString()

                                    "alpn" -> bean.alpn =
                                        (opt.value as? List<Any>)?.joinToString("\n")

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value as? Boolean == true

                                    "client-fingerprint" -> bean.utlsFingerprint =
                                        opt.value as String

                                    "reality-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        bean.security = "tls"
                                        for (realityOpt in it) {
                                            when (realityOpt.key) {
                                                "public-key" -> bean.realityPubKey =
                                                    realityOpt.value?.toString()

                                                "short-id" -> bean.realityShortId =
                                                    realityOpt.value?.toString()
                                            }
                                        }
                                        if (!bean.realityPubKey.isNullOrBlank() && !bean.realityShortId.isNullOrBlank()) {
                                            bean.security = "reality"
                                        }
                                    }

                                    "network" -> {
                                        when (opt.value) {
                                            "h2", "http" -> bean.type = "http"
                                            "ws", "grpc" -> bean.type = opt.value as String
                                            "xhttp" -> if (bean.isVLESS) bean.type = "xhttp"
                                        }
                                    }

                                    "ws-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (wsOpt in it) {
                                            when (wsOpt.key) {
                                                "headers" -> (wsOpt.value as? Map<Any, Any?>)?.forEach { (key, value) ->
                                                    when (key.toString().lowercase()) {
                                                        "host" -> {
                                                            bean.host = value?.toString()
                                                        }
                                                    }
                                                }

                                                "path" -> {
                                                    bean.path = wsOpt.value?.toString()
                                                }

                                                "max-early-data" -> {
                                                    bean.wsMaxEarlyData =
                                                        wsOpt.value?.toString()?.toIntOrNull()
                                                }

                                                "early-data-header-name" -> {
                                                    bean.earlyDataHeaderName =
                                                        wsOpt.value?.toString()
                                                }

                                                "v2ray-http-upgrade" -> {
                                                    if (wsOpt.value as? Boolean == true) {
                                                        bean.type = "httpupgrade"
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    "h2-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (h2Opt in it) {
                                            when (h2Opt.key) {
                                                "host" -> bean.host =
                                                    (h2Opt.value as? List<Any>)?.joinToString("\n")

                                                "path" -> bean.path = h2Opt.value?.toString()
                                            }
                                        }
                                    }

                                    "http-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (httpOpt in it) {
                                            when (httpOpt.key) {
                                                "path" -> bean.path =
                                                    (httpOpt.value as? List<Any>)?.joinToString("\n")

                                                "headers" -> {
                                                    (httpOpt.value as? Map<Any, List<Any>>)?.forEach { (key, value) ->
                                                        when (key.toString().lowercase()) {
                                                            "host" -> {
                                                                bean.host = value.joinToString("\n")
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    "grpc-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (grpcOpt in it) {
                                            when (grpcOpt.key) {
                                                "grpc-service-name" -> bean.path =
                                                    grpcOpt.value?.toString()
                                            }
                                        }
                                    }

                                    "xhttp-opts" -> if (bean.isVLESS && bean.type == "xhttp") {
                                        (opt.value as? Map<String, Any?>)?.also { xhttpOpts ->
                                            applyClashXhttpOptions(bean, xhttpOpts)
                                        }
                                    }

                                    "smux" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (smuxOpt in it) {
                                            when (smuxOpt.key) {
                                                "enabled" -> bean.enableMux =
                                                    smuxOpt.value.toString() == "true"

                                                "max-streams" -> bean.muxConcurrency =
                                                    smuxOpt.value.toString().toInt()

                                                "padding" -> bean.muxPadding =
                                                    smuxOpt.value.toString() == "true"
                                            }
                                        }
                                    }

                                    "ech-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (echOpt in it) {
                                            when (echOpt.key) {
                                                "enable" -> bean.enableECH =
                                                    echOpt.value.toString() == "true"

                                                "config" -> bean.echConfig =
                                                    echOpt.value?.toString()
                                            }
                                        }
                                    }
                                }
                            }
                            proxies.add(bean)
                        }

                        "snell" -> {
                            val bean = parseClashSnell(proxy)
                            proxies.add(bean)
                        }

                        "anytls" -> {
                            val bean = AnyTLSBean()
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPort = opt.value.toString().toInt()
                                    "password" -> bean.password = opt.value.toString()
                                    "client-fingerprint" -> bean.utlsFingerprint =
                                        opt.value as String

                                    "sni" -> bean.sni = opt.value.toString()
                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n")
                                    }
                                    "reality-pub-key", "public-key" -> bean.realityPubKey =
                                        opt.value.toString()
                                    "reality-short-id", "short-id" -> bean.realityShortId =
                                        opt.value.toString()
                                }
                            }
                            proxies.add(bean)
                        }

                        "wireguard" -> {
                            val peers = proxy["peers"] as? List<Map<String, Any?>>
                            val configToUse = peers?.firstOrNull() ?: proxy

                            val bean = WireGuardBean().apply {
                                name = proxy["name"].toString()

                                for ((key, value) in configToUse) {
                                    when (key.replace("_", "-")) {
                                        "server" -> serverAddress = value.toString()
                                        "port" -> serverPort = value.toString().toIntOrNull() ?: 0
                                        "mtu" -> mtu = value.toString().toIntOrNull() ?: 0
                                        "ip" -> {
                                            val ipValue = value.toString()
                                            localAddress = if (!ipValue.contains("/")) {
                                                "$ipValue/32"
                                            } else {
                                                ipValue
                                            }
                                        }
                                        "ipv6" -> {
                                            val ipv6Value = value.toString()
                                            val processedIPv6Value = if (!ipv6Value.contains("/")) {
                                                "$ipv6Value/128"
                                            } else {
                                                ipv6Value
                                            }
                                            if (localAddress.isNullOrEmpty()) {
                                                localAddress = processedIPv6Value
                                            } else {
                                                localAddress += "\n$processedIPv6Value"
                                            }
                                        }
                                        "private-key" -> privateKey = value.toString()
                                        "public-key" -> peerPublicKey = value.toString()
                                        "pre-shared-key", "preshared-key" -> peerPreSharedKey = value.toString()
                                        "persistent-keepalive", "persistent-keepalive-interval" ->
                                            peerPersistentKeepalive = value.toString().toIntOrNull() ?: 0
                                        "reserved" -> {
                                            val reservedValue = value
                                            when (reservedValue) {
                                                is List<*> -> {
                                                    if (reservedValue.size == 1) {
                                                        reserved = reservedValue[0].toString().replace("[\\[\\] ]".toRegex(), "")
                                                    } else {
                                                        reserved = reservedValue.joinToString("\n") { it.toString() }
                                                    }
                                                }
                                                else -> {
                                                    reserved = reservedValue.toString().replace("[\\[\\] ]".toRegex(), "")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            proxies.add(bean)
                        }

                        "hysteria" -> {
                            val bean = HysteriaBean()
                            bean.protocolVersion = 1
                            var hopPorts = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPorts = opt.value.toString()
                                    "ports" -> hopPorts = opt.value.toString()

                                    "obfs" -> bean.obfuscation = opt.value.toString()

                                    "auth-str" -> {
                                        bean.authPayloadType = HysteriaBean.TYPE_STRING
                                        bean.authPayload = opt.value.toString()
                                    }

                                    "sni" -> bean.sni = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "up" -> bean.uploadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull()
                                            ?: 100

                                    "down" -> bean.downloadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull()
                                            ?: 100

                                    "recv-window-conn" -> bean.connectionReceiveWindow =
                                        opt.value.toString().toIntOrNull() ?: 0

                                    "recv-window" -> bean.streamReceiveWindow =
                                        opt.value.toString().toIntOrNull() ?: 0

                                    "disable-mtu-discovery" -> bean.disableMtuDiscovery =
                                        opt.value.toString() == "true" || opt.value.toString() == "1"

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n") ?: "h3"
                                    }
                                }
                            }
                            if (hopPorts.isNotBlank()) {
                                bean.serverPorts = hopPorts
                            }
                            proxies.add(bean)
                        }

                        "hysteria2" -> {
                            val bean = HysteriaBean()
                            bean.protocolVersion = 2
                            var hopPorts = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPorts = opt.value.toString()
                                    "ports" -> hopPorts = opt.value.toString()

                                    "obfs-password" -> bean.obfuscation = opt.value.toString()

                                    "password" -> bean.authPayload = opt.value.toString()

                                    "sni" -> bean.sni = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "up" -> bean.uploadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0

                                    "down" -> bean.downloadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0
                                }
                            }
                            if (hopPorts.isNotBlank()) {
                                bean.serverPorts = hopPorts
                            }
                            proxies.add(bean)
                        }

                        "tuic" -> {
                            val bean = TuicBean()
                            var ip = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value.toString()
                                    "ip" -> ip = opt.value.toString()
                                    "port" -> bean.serverPort = opt.value.toString().toInt()

                                    "token" -> {
                                        bean.protocolVersion = 4
                                        bean.token = opt.value.toString()
                                    }

                                    "uuid" -> bean.uuid = opt.value.toString()

                                    "password" -> bean.token = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "disable-sni" -> bean.disableSNI =
                                        opt.value.toString() == "true"

                                    "reduce-rtt" -> bean.reduceRTT =
                                        opt.value.toString() == "true"

                                    "sni" -> bean.sni = opt.value.toString()

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n")
                                    }

                                    "congestion-controller" -> bean.congestionController =
                                        opt.value.toString()

                                    "udp-relay-mode" -> bean.udpRelayMode = opt.value.toString()

                                }
                            }
                            if (ip.isNotBlank()) {
                                bean.serverAddress = ip
                                if (bean.sni.isNullOrBlank() && !bean.serverAddress.isNullOrBlank() && !bean.serverAddress.isIpAddress()) {
                                    bean.sni = bean.serverAddress
                                }
                            }
                            proxies.add(bean)
                        }
                    }
                }

                // Fix ent
                proxies.forEach {
                    it.initializeDefaultValues()
                    if (it is StandardV2RayBean) {
                        // 1. SNI
                        if (it.isTLS() && it.sni.isNullOrBlank() && !it.host.isNullOrBlank() && !it.host.isIpAddress()) {
                            it.sni = it.host
                        }
                        // 2. globalClientFingerprint
                        if (!it.realityPubKey.isNullOrBlank() && it.utlsFingerprint.isNullOrBlank()) {
                            it.utlsFingerprint = globalClientFingerprint
                            if (it.utlsFingerprint.isNullOrBlank()) it.utlsFingerprint = "chrome"
                        }
                    }
                }
                return proxies
            } catch (e: YAMLException) {
                Logs.w(e)
            }
        } else if (text.contains("[Interface]")) {
            // AmneziaWG or WireGuard .conf
            val isAwg = listOf("Jc", "Jmin", "Jmax", "S1", "S2", "H1", "H2")
                .any { key -> text.contains(Regex("(?m)^$key\\s*=")) }
            if (isAwg) {
                try {
                    proxies.addAll(parseAmneziaWG(text).map {
                        if (fileName.isNotBlank()) it.name = fileName.removeSuffix(".conf")
                        it
                    })
                    return proxies
                } catch (e: Exception) {
                    Logs.w(e)
                }
            } else {
                try {
                    proxies.addAll(parseWireGuard(text).map {
                        if (fileName.isNotBlank()) it.name = fileName.removeSuffix(".conf")
                        it
                    })
                    return proxies
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
        }

        try {
            val json = JSONTokener(text).nextValue()
            return parseJSON(json)
        } catch (ignored: Exception) {
        }

        try {
            return parseProxies(text.decodeBase64UrlSafe()).takeIf { it.isNotEmpty() }
                ?: error("Not found")
        } catch (e: Exception) {
            if (e is AmneziaApiKeyUnsupportedException) throw e
            Logs.w(e)
        }

        try {
            return parseProxies(text).takeIf { it.isNotEmpty() } ?: error("Not found")
        } catch (e: Exception) {
            if (e is AmneziaApiKeyUnsupportedException) throw e
        }

        return null
    }

    fun clashCipher(cipher: String): String {
        return when (cipher) {
            "dummy" -> "none"
            else -> cipher
        }
    }

    fun parseWireGuard(conf: String): List<WireGuardBean> {
        val ini = Ini(StringReader(conf))
        val iface = ini["Interface"] ?: error("Missing 'Interface' selection")
        val bean = WireGuardBean().applyDefaultValues()
        val localAddresses = iface.getAll("Address")
        if (localAddresses.isNullOrEmpty()) error("Empty address in 'Interface' selection")
        bean.localAddress = parseWireGuardAddresses(localAddresses)
        bean.privateKey = iface["PrivateKey"]
        bean.mtu = iface["MTU"]?.toIntOrNull() ?: 1280
        val peers = ini.getAll("Peer")
        if (peers.isNullOrEmpty()) error("Missing 'Peer' selections")
        val beans = mutableListOf<WireGuardBean>()
        for (peer in peers) {
            val endpoint = peer["Endpoint"]?.parseHostAndPort() ?: continue
            val peerBean = bean.clone()
            peerBean.serverAddress = endpoint.host
            peerBean.serverPort = endpoint.port
            peerBean.peerPublicKey = peer["PublicKey"] ?: continue
            peerBean.peerPreSharedKey = peer["PreSharedKey"] ?: peer["PresharedKey"] ?: ""
            peerBean.peerPersistentKeepalive = (peer["PersistentKeepalive"] ?: peer["PersistentKeepAlive"])?.toIntOrNull() ?: 0
            peerBean.reserved = peer["Reserved"] ?: ""
            beans.add(peerBean.applyDefaultValues())
        }
        if (beans.isEmpty()) error("Empty available peer list")
        return beans
    }

    fun parseAmneziaWG(conf: String): List<AmneziaWGBean> {
        val ini = Ini(StringReader(conf))
        val iface = ini["Interface"] ?: error("Missing 'Interface' selection")
        val bean = AmneziaWGBean().applyDefaultValues()
        val localAddresses = iface.getAll("Address")
        if (localAddresses.isNullOrEmpty()) error("Empty address in 'Interface' selection")
        bean.localAddress = parseWireGuardAddresses(localAddresses)
        bean.privateKey = iface["PrivateKey"]
        bean.mtu = iface["MTU"]?.toIntOrNull() ?: 1280
        // AWG 1.0 parameters
        iface["Jc"]?.toIntOrNull()?.let { bean.jc = it }
        iface["Jmin"]?.toIntOrNull()?.let { bean.jmin = it }
        iface["Jmax"]?.toIntOrNull()?.let { bean.jmax = it }
        iface["S1"]?.toIntOrNull()?.let { bean.s1 = it }
        iface["S2"]?.toIntOrNull()?.let { bean.s2 = it }
        iface["H1"]?.let { bean.h1 = it }
        iface["H2"]?.let { bean.h2 = it }
        iface["H3"]?.let { bean.h3 = it }
        iface["H4"]?.let { bean.h4 = it }
        // AWG 1.5 parameters
        iface["I1"]?.let { bean.i1 = it }
        iface["I2"]?.let { bean.i2 = it }
        iface["I3"]?.let { bean.i3 = it }
        iface["I4"]?.let { bean.i4 = it }
        iface["I5"]?.let { bean.i5 = it }
        // AWG 2.0 parameters
        iface["S3"]?.toIntOrNull()?.let { bean.s3 = it }
        iface["S4"]?.toIntOrNull()?.let { bean.s4 = it }
        val peers = ini.getAll("Peer")
        if (peers.isNullOrEmpty()) error("Missing 'Peer' selections")
        val beans = mutableListOf<AmneziaWGBean>()
        for (peer in peers) {
            val endpoint = peer["Endpoint"]?.parseHostAndPort() ?: continue
            val peerBean = bean.clone()
            peerBean.serverAddress = endpoint.host
            peerBean.serverPort = endpoint.port
            peerBean.peerPublicKey = peer["PublicKey"] ?: continue
            peerBean.peerPreSharedKey = peer["PreSharedKey"] ?: peer["PresharedKey"] ?: ""
            peerBean.peerPersistentKeepalive = (peer["PersistentKeepalive"] ?: peer["PersistentKeepAlive"])?.toIntOrNull() ?: 0
            peerBean.reserved = peer["Reserved"] ?: ""
            beans.add(peerBean.applyDefaultValues())
        }
        if (beans.isEmpty()) error("Empty available peer list")
        return beans
    }

    fun parseJSON(json: Any): List<AbstractBean> {
        val proxies = ArrayList<AbstractBean>()

        if (json is JSONObject) {
            when {
                json.has("server") && (json.has("up") || json.has("up_mbps")) -> {
                    return listOf(json.parseHysteria1Json())
                }

                json.has("method") && json.has("obfs") && json.has("protocol") -> {
                    return listOf(json.parseShadowsocksR())
                }

                json.has("method") -> {
                    return listOf(json.parseShadowsocks())
                }

                json.has("remote_addr") -> {
                    return listOf(json.parseTrojanGo())
                }

                json.has("outbounds") -> {
                    return json.getJSONArray("outbounds")
                        .filterIsInstance<JSONObject>()
                        .mapNotNull {
                            val ty = it.getStr("type")
                            if (ty == null || ty == "" ||
                                ty == "dns" || ty == "block" || ty == "direct" || ty == "selector" || ty == "urltest"
                            ) {
                                null
                            } else {
                                it
                            }
                        }.map {
                            ConfigBean().apply {
                                applyDefaultValues()
                                type = 1
                                config = it.toStringPretty()
                                name = it.getStr("tag")
                            }
                        }
                }

                json.has("server") && json.has("server_port") -> {
                    return listOf(ConfigBean().applyDefaultValues().apply {
                        type = 1
                        config = json.toStringPretty()
                    })
                }
            }
        } else {
            json as JSONArray
            json.forEach { _, it ->
                if (isJsonObjectValid(it)) {
                    proxies.addAll(parseJSON(it))
                }
            }
        }

        proxies.forEach { it.initializeDefaultValues() }
        return proxies
    }

}
