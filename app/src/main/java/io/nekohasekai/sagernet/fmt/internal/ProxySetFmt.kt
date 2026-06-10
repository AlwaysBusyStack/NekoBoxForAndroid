package io.nekohasekai.sagernet.fmt.internal

import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxOutboundProxySetBean(
    bean: ProxySetBean,
    outbounds: List<String>,
): SingBoxOptions.Outbound {
    return SingBoxOptions.Outbound_URLTestOptions().apply {
        type = "urltest"
        this.outbounds = outbounds
        url = bean.testURL
        interval = bean.testInterval
        idle_timeout = bean.testIdleTimeout
        tolerance = bean.testTolerance
        interrupt_exist_connections = bean.interruptExistConnections
    }
}
