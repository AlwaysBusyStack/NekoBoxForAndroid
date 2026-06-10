package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import moe.matsuri.nb4a.makeSingBoxRule

fun buildRouteDnsRules(
    createDnsRule: Boolean,
    outbound: Long,
    uidList: List<Int>,
    domainList: List<String>?,
    ruleSet: List<String>?,
    rulesetTags: List<Pair<String, Boolean>>,
    useFakeDns: Boolean,
): List<DNSRule_DefaultOptions> {
    if (!createDnsRule) return emptyList()

    val dnsRules = mutableListOf<DNSRule_DefaultOptions>()

    fun makeDnsRuleObj(): DNSRule_DefaultOptions {
        return DNSRule_DefaultOptions().apply {
            if (uidList.isNotEmpty()) user_id = uidList
            domainList?.let { makeSingBoxRule(it) }
        }
    }

    fun addRuleSetDnsRules(server: String, configure: DNSRule_DefaultOptions.() -> Unit = {}) {
        val routeRuleSet = ruleSet ?: return
        if (rulesetTags.isEmpty()) return

        for (tag in routeRuleSet) {
            val tagInfo = rulesetTags.find { it.first == tag }
            if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                dnsRules += DNSRule_DefaultOptions().apply {
                    rule_set = mutableListOf(tag)
                    this.server = server
                    configure()
                }
            }
        }
    }

    when (outbound) {
        -1L -> {
            dnsRules += makeDnsRuleObj().apply { server = "dns-direct" }
            addRuleSetDnsRules("dns-direct")
        }

        0L -> {
            if (useFakeDns) {
                dnsRules += makeDnsRuleObj().apply {
                    server = "dns-fake"
                    inbound = listOf(TAG_TUN)
                    query_type = listOf("A", "AAAA")
                }
                addRuleSetDnsRules("dns-fake") {
                    inbound = listOf(TAG_TUN)
                    query_type = listOf("A", "AAAA")
                }
            } else {
                dnsRules += makeDnsRuleObj().apply {
                    server = "dns-remote"
                }
                addRuleSetDnsRules("dns-remote")
            }
        }

        -2L -> {
            dnsRules += makeDnsRuleObj().apply {
                server = "dns-block"
                disable_cache = true
            }
            addRuleSetDnsRules("dns-block") {
                disable_cache = true
            }
        }
    }

    return dnsRules
}
