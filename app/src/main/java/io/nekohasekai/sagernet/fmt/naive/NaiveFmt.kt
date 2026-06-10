package io.nekohasekai.sagernet.fmt.naive

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

fun parseNaive(link: String): NaiveBean {
    val proto = link.substringAfter("+").substringBefore(":")
    val url = ("https://" + link.substringAfter("://")).toHttpUrlOrNull()
        ?: error("Invalid naive link: $link")
    return NaiveBean().also {
        it.proto = proto
    }.apply {
        serverAddress = url.host
        serverPort = url.port
        username = url.username
        password = url.password
        sni = url.queryParameter("sni")
        certificates = url.queryParameter("cert")
        extraHeaders = url.queryParameter("extra-headers")?.unUrlSafe()?.replace("\r\n", "\n")
        insecureConcurrency = url.queryParameter("insecure-concurrency")?.toIntOrNull()
        url.queryParameter("uot")?.let { sUoT = (it == "1" || it.toBoolean()) }
        name = url.fragment
        initializeDefaultValues()
    }
}

fun NaiveBean.toUri(proxyOnly: Boolean = false): String {
    val builder = linkBuilder().host(finalAddress).port(finalPort)
    if (username.isNotBlank()) {
        builder.username(username)
        if (password.isNotBlank()) {
            builder.password(password)
        }
    }
    if (!proxyOnly) {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        if (certificates.isNotBlank()) {
            builder.addQueryParameter("cert", certificates)
        }
        if (extraHeaders.isNotBlank()) {
            val headers = extraHeaders
                .replace("\r\n", "\n")
                .split("\n")
                .filter { it.isNotBlank() }
                .joinToString("\r\n")
            if (headers.isNotBlank()) {
                builder.addQueryParameter("extra-headers", headers)
            }
        }
        if (name.isNotBlank()) {
            builder.encodedFragment(name.urlSafe())
        }
        if (insecureConcurrency > 0) {
            builder.addQueryParameter("insecure-concurrency", "$insecureConcurrency")
        }
        if (sUoT) {
            builder.addQueryParameter("uot", "1")
        }
    }
    return builder.toLink(if (proxyOnly) proto else "naive+$proto", false)
}

fun NaiveBean.buildNaiveConfig(port: Int): String {
    return JSONObject().apply {
        // process ipv6
        finalAddress = finalAddress.wrapIPV6Host()
        serverAddress = serverAddress.wrapIPV6Host()

        // process sni
        if (sni.isNotBlank()) {
            put("host-resolver-rules", "MAP $sni $finalAddress")
            finalAddress = sni
        } else {
            if (serverAddress.isIpAddress()) {
                // for naive, using IP as SNI name hardly happens
                // and host-resolver-rules cannot resolve the SNI problem
                // so do nothing
            } else {
                put("host-resolver-rules", "MAP $serverAddress $finalAddress")
                finalAddress = serverAddress
            }
        }

        put("listen", "socks://$LOCALHOST:$port")
        put("proxy", toUri(true))
        if (extraHeaders.isNotBlank()) {
            put("extra-headers", extraHeaders.split("\n").joinToString("\r\n"))
        }
        if (DataStore.logLevel > 0) {
            put("log", "")
        }
        if (insecureConcurrency > 0) {
            put("insecure-concurrency", insecureConcurrency)
        }
    }.toStringPretty()
}

fun buildSingBoxOutboundNaiveBean(bean: NaiveBean): SingBoxOptions.Outbound_NaiveOptions {
    return SingBoxOptions.Outbound_NaiveOptions().apply {
        type = "naive"
        server = bean.serverAddress
        server_port = bean.serverPort
        username = bean.username
        password = bean.password
        insecure_concurrency = bean.insecureConcurrency.takeIf { it > 0 }
        quic = bean.proto == "quic"

        tls = SingBoxOptions.OutboundTLSOptions().apply {
            enabled = true
            server_name = bean.sni.takeIf { it.isNotBlank() }
            certificate = bean.certificates.takeIf { it.isNotBlank() }
        }

        if (bean.sUoT == true) {
            _hack_config_map["udp_over_tcp"] = true
        }

        val extraHeadersMap = bean.extraHeaders
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val separatorIndex = line.indexOf(':')
                if (separatorIndex <= 0) return@mapNotNull null
                val name = line.substring(0, separatorIndex).trim()
                val value = line.substring(separatorIndex + 1).trim()
                if (name.isEmpty()) return@mapNotNull null
                name to value
            }
            .toMap(LinkedHashMap())
        if (extraHeadersMap.isNotEmpty()) {
            _hack_config_map["extra_headers"] = extraHeadersMap
        }
    }
}
