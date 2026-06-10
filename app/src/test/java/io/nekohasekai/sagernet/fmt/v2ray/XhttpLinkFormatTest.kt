package io.nekohasekai.sagernet.fmt.v2ray

import moe.matsuri.nb4a.SingBoxOptions.V2RayTransportOptions_XHTTPOptions
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XhttpLinkFormatTest {

    @Test
    fun vlessXhttpLinkRoundTripPreservesSingBoxOnlyExtraFields() {
        val extra = JSONObject()
            .put("xPaddingBytes", "100-1000")
            .put("domain_strategy", "prefer_ipv4")
            .put("trusted_x_forwarded_for", listOf("10.0.0.0/8", "192.168.0.0/16"))
            .put("unknown_passthrough", "kept")
            .toString()

        val link = HttpUrl.Builder()
            .scheme("https")
            .username("00000000-0000-0000-0000-000000000000")
            .host("example.com")
            .port(443)
            .addQueryParameter("type", "xhttp")
            .addQueryParameter("encryption", "none")
            .addQueryParameter("host", "cdn.example.com")
            .addQueryParameter("path", "/xhttp")
            .addQueryParameter("mode", "packet-up")
            .addQueryParameter("extra", extra)
            .build()
            .toString()
            .replaceFirst("https://", "vless://")

        val bean = VMessBean().apply {
            alterId = -1
            parseDuckSoft(link.replaceFirst("vless://", "https://").toHttpUrl(), "xhttp")
        }
        bean.initializeDefaultValues()
        bean.name = ""

        val exported = bean.toUriVMessVLESSTrojan(false)
        val exportedUrl = exported.replaceFirst("vless://", "https://").toHttpUrl()
        val exportedExtra = JSONObject(exportedUrl.queryParameter("extra")!!)

        assertEquals("xhttp", exportedUrl.queryParameter("type"))
        assertEquals("packet-up", exportedUrl.queryParameter("mode"))
        assertEquals("100-1000", exportedExtra.getString("xPaddingBytes"))
        assertEquals("prefer_ipv4", exportedExtra.getString("domain_strategy"))
        assertEquals("10.0.0.0/8", exportedExtra.getJSONArray("trusted_x_forwarded_for").getString(0))
        assertEquals("192.168.0.0/16", exportedExtra.getJSONArray("trusted_x_forwarded_for").getString(1))
        assertEquals("kept", exportedExtra.getString("unknown_passthrough"))
    }

    @Test
    fun singBoxXhttpTransportIncludesSingBoxOnlyExtraFields() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            serverAddress = "example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000000"
            host = "cdn.example.com"
            path = "/xhttp"
            xhttpMode = "packet-up"
            xhttpExtra = JSONObject()
                .put("domain_strategy", "prefer_ipv4")
                .put("trusted_x_forwarded_for", listOf("10.0.0.0/8"))
                .toString()
        }

        val transport = buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_XHTTPOptions

        assertEquals("prefer_ipv4", transport.domain_strategy)
        assertTrue(transport.trusted_x_forwarded_for.isJsonArray)
        assertEquals("10.0.0.0/8", transport.trusted_x_forwarded_for.asJsonArray[0].asString)
    }

    @Test
    fun singBoxXhttpPacketUpTransportAppliesAndroidSafeUploadDefaults() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            serverAddress = "example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000000"
            path = "/xhttp"
            xhttpMode = "packet-up"
        }

        val transport = buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_XHTTPOptions

        assertEquals("65536-917504", transport.sc_max_each_post_bytes.asString)
        assertEquals(4, transport.sc_max_buffered_posts.asLong)
    }

    @Test
    fun singBoxXhttpPacketUpTransportKeepsExplicitUploadLimits() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            serverAddress = "example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000000"
            path = "/xhttp"
            xhttpMode = "packet-up"
            xhttpExtra = JSONObject()
                .put("sc_max_each_post_bytes", "1048576-1048576")
                .put("sc_max_buffered_posts", 9)
                .toString()
        }

        val transport = buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_XHTTPOptions

        assertEquals("1048576-1048576", transport.sc_max_each_post_bytes.asString)
        assertEquals(9, transport.sc_max_buffered_posts.asLong)
    }

    @Test
    fun xrayToSingBoxIgnoresNullXmuxObject() {
        val xrayExtra = JSONObject()
            .put("xPaddingBytes", "100-1000")
            .put("xmux", JSONObject.NULL)
            .toString()

        val singBoxExtra = JSONObject(XhttpExtraConverter.xrayToSingBox(xrayExtra))

        assertEquals("100-1000", singBoxExtra.getString("x_padding_bytes"))
        assertEquals(false, singBoxExtra.has("xmux"))
    }

    @Test
    fun xrayToSingBoxIgnoresNullDownloadSettingsObject() {
        val xrayExtra = JSONObject()
            .put("downloadSettings", JSONObject.NULL)
            .toString()

        val singBoxExtra = JSONObject(XhttpExtraConverter.xrayToSingBox(xrayExtra))

        assertEquals(0, singBoxExtra.length())
    }

    @Test
    fun xrayToSingBoxIgnoresNullLiteralInput() {
        assertEquals("", XhttpExtraConverter.xrayToSingBox("null"))
    }

    @Test
    fun extractSupportedToGuiIgnoresNullLiteralInput() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
        }

        assertEquals("", XhttpExtraConverter.extractSupportedToGui(bean, "null"))
    }

    @Test
    fun vlessXhttpLinkIgnoresNullExtraQueryParameter() {
        val link = HttpUrl.Builder()
            .scheme("https")
            .username("00000000-0000-0000-0000-000000000000")
            .host("example.com")
            .port(443)
            .addQueryParameter("type", "xhttp")
            .addQueryParameter("encryption", "none")
            .addQueryParameter("extra", "null")
            .build()
            .toString()
            .replaceFirst("https://", "vless://")

        val bean = VMessBean().apply {
            alterId = -1
            parseDuckSoft(link.replaceFirst("vless://", "https://").toHttpUrl(), "xhttp")
        }

        assertTrue(bean.xhttpExtra.isNullOrBlank())
    }

    @Test
    fun singBoxXhttpTransportIgnoresNullLiteralExtra() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            serverAddress = "example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000000"
            path = "/xhttp"
            xhttpExtra = "null"
        }

        val transport = buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_XHTTPOptions

        assertEquals("xhttp", transport.type)
    }
}
