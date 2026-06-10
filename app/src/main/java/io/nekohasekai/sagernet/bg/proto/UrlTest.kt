package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteria1Config
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildMieruConfig
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.delay
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.io.File

class UrlTest {

    private val link = DataStore.connectionTestURL
    private val timeout: Int
    private val testType: Int
    private val runKey = SystemClock.elapsedRealtimeNanos().toString()
    private val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    private val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    private val cacheFiles = ArrayList<File>()
    private var processes: GuardedProcessPool? = null

    constructor(
        timeout: Int = DataStore.connectionGroupTestTimeout,
        testType: Int = DataStore.groupTestType,
    ) {
        this.timeout = timeout
        this.testType = testType
    }

    companion object {
        fun isUnsupportedByeDPIProfile(profile: ProxyEntity): Boolean {
            return profile.isByeDPI() ||
                    profile.type == ProxyEntity.TYPE_CHAIN && profile.containsByeDPI()
        }
    }

    private fun cleanupUrlTestCacheFiles(profileId: Long) {
        val prefix = "urltest_${profileId}_${runKey}_"
        SagerNet.application.cacheDir.listFiles { _, name ->
            name.startsWith(prefix)
        }?.forEach { file ->
            runCatching { file.delete() }
        }
    }

    suspend fun doTest(profile: ProxyEntity): Int {
        return try {
            if (isUnsupportedByeDPIProfile(profile)) {
                error("ByeDPI is not supported in URLTest")
            }
            val config = buildConfig(
                profile,
                forTest = true,
                urlTestCacheKey = "${profile.id}_$runKey"
            )
            initPlugins(config)

            if (config.externalIndex.any { it.chain.isNotEmpty() }) {
                processes = GuardedProcessPool { throw it }
                launchPlugins(config)
                delay(500L)
            }

            Libcore.newInstanceURLTest(config.config, "", link, timeout, testType, LocalResolverImpl)
        } finally {
            processes?.closeAndJoin()
            processes = null
            cacheFiles.forEach { file ->
                runCatching { file.delete() }
            }
            cacheFiles.clear()
            cleanupUrlTestCacheFiles(profile.id)
            pluginConfigs.clear()
        }
    }

    private fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name)!! }
    }

    private fun initPlugins(config: ConfigBuildResult) {
        for ((chain) in config.externalIndex) {
            chain.entries.forEach { (port, profile) ->
                when (val bean = profile.requireBean()) {
                    is TrojanGoBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(port)
                    }

                    is MieruBean -> {
                        initPlugin("mieru-plugin")
                        pluginConfigs[port] = profile.type to bean.buildMieruConfig(port)
                    }

                    is HysteriaBean -> {
                        initPlugin("hysteria-plugin")
                        pluginConfigs[port] = profile.type to bean.buildHysteria1Config(port) {
                            File(
                                SagerNet.application.cacheDir,
                                "hysteria_" + SystemClock.elapsedRealtime() + ".ca"
                            ).apply {
                                parentFile?.mkdirs()
                                cacheFiles.add(this)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun launchPlugins(config: ConfigBuildResult) {
        val processPool = processes ?: return
        val cacheDir = File(SagerNet.application.cacheDir, "tmpcfg")
        cacheDir.mkdirs()

        for ((chain) in config.externalIndex) {
            chain.entries.forEach { (port, profile) ->
                val bean = profile.requireBean()
                val (_, pluginConfig) = pluginConfigs[port] ?: (0 to "")

                when (bean) {
                    is TrojanGoBean -> {
                        val configFile = File(
                            cacheDir,
                            "trojan_go_" + SystemClock.elapsedRealtime() + ".json"
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(pluginConfig)
                        cacheFiles.add(configFile)

                        processPool.start(
                            mutableListOf(
                                initPlugin("trojan-go-plugin").path,
                                "-config",
                                configFile.absolutePath
                            )
                        )
                    }

                    is MieruBean -> {
                        val configFile = File(
                            cacheDir,
                            "mieru_" + SystemClock.elapsedRealtime() + ".json"
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(pluginConfig)
                        cacheFiles.add(configFile)

                        processPool.start(
                            mutableListOf(initPlugin("mieru-plugin").path, "run"),
                            mutableMapOf(
                                "MIERU_CONFIG_JSON_FILE" to configFile.absolutePath,
                                "MIERU_PROTECT_PATH" to "protect_path"
                            )
                        )
                    }

                    is HysteriaBean -> {
                        val configFile = File(
                            cacheDir,
                            "hysteria_" + SystemClock.elapsedRealtime() + ".json"
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(pluginConfig)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("hysteria-plugin").path,
                            "--no-check",
                            "--config",
                            configFile.absolutePath,
                            "--log-level",
                            if (DataStore.logLevel > 0) "trace" else "warn",
                            "client"
                        )

                        if (bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) {
                            commands.addAll(0, listOf("su", "-c"))
                        }

                        processPool.start(commands)
                    }
                }
            }
        }
    }
}
