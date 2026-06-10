package io.nekohasekai.sagernet.database

import android.os.Parcel
import android.os.Parcelable
import androidx.room.*
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app

@Entity(tableName = "rules")
@TypeConverters(StringCollectionConverter::class)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var name: String = "",
    @ColumnInfo(defaultValue = "")
    var config: String = "",
    var userOrder: Long = 0L,
    var enabled: Boolean = false,
    var domains: String = "",
    var ip: String = "",
    var port: String = "",
    var sourcePort: String = "",
    @ColumnInfo(defaultValue = "")
    var networkType: Set<String> = emptySet(),
    @ColumnInfo(defaultValue = "")
    var wifiSsid: String = "",
    @ColumnInfo(defaultValue = "")
    var wifiBssid: String = "",
    var network: String = "",
    var source: String = "",
    var protocol: String = "",
    @ColumnInfo(name = "ruleset", defaultValue = "")
    var ruleset: String = "",
    var outbound: Long = 0,
    var packages: Set<String> = emptySet(),
    @ColumnInfo(defaultValue = "1")
    var createDnsRule: Boolean = true,
) : Parcelable {

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<RuleEntity> {
            override fun createFromParcel(parcel: Parcel): RuleEntity {
                return RuleEntity(parcel)
            }

            override fun newArray(size: Int): Array<RuleEntity?> {
                return arrayOfNulls(size)
            }
        }

        fun isWifiIdentityVisible(networkType: Set<String>): Boolean {
            return networkType.isEmpty() || networkType.contains("wifi")
        }

        fun hasActiveWifiIdentity(rule: RuleEntity): Boolean {
            return rule.enabled &&
                isWifiIdentityVisible(rule.networkType) &&
                (
                    normalizeWifiSsidList(rule.wifiSsid).isNotEmpty() ||
                        normalizeWifiBssidList(rule.wifiBssid).isNotEmpty()
                    )
        }

        fun normalizeWifiSsid(rawValue: String): String {
            return normalizeMultilineValue(rawValue)
        }

        fun normalizeWifiSsidList(rawValue: String): List<String> {
            return normalizeMultilineValueList(rawValue)
        }

        fun normalizeWifiBssid(rawValue: String): String {
            return normalizeWifiBssidList(rawValue).joinToString("\n")
        }

        fun normalizeWifiBssidList(rawValue: String): List<String> {
            return rawValue.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(String::lowercase)
                .distinct()
                .toList()
        }

        fun normalizeMultilineValue(rawValue: String): String {
            return normalizeMultilineValueList(rawValue).joinToString("\n")
        }

        private fun normalizeMultilineValueList(rawValue: String): List<String> {
            return rawValue.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
        }

        private fun readOptionalString(parcel: Parcel): String {
            return try {
                parcel.readString().orEmpty()
            } catch (_: RuntimeException) {
                ""
            }
        }

        private fun readOptionalBoolean(parcel: Parcel, defaultValue: Boolean): Boolean {
            return try {
                parcel.readByte() != 0.toByte()
            } catch (_: RuntimeException) {
                defaultValue
            }
        }
    }

    constructor(parcel: Parcel) : this(
        id = parcel.readLong(),
        name = parcel.readString().orEmpty(),
        config = parcel.readString().orEmpty(),
        userOrder = parcel.readLong(),
        enabled = parcel.readByte() != 0.toByte(),
        domains = parcel.readString().orEmpty(),
        ip = parcel.readString().orEmpty(),
        port = parcel.readString().orEmpty(),
        sourcePort = parcel.readString().orEmpty(),
        networkType = parcel.createStringArrayList()?.toSet().orEmpty(),
        network = parcel.readString().orEmpty(),
        source = parcel.readString().orEmpty(),
        protocol = parcel.readString().orEmpty(),
        ruleset = parcel.readString().orEmpty(),
        outbound = parcel.readLong(),
        packages = parcel.createStringArrayList()?.toSet().orEmpty(),
        wifiSsid = readOptionalString(parcel),
        wifiBssid = readOptionalString(parcel),
        createDnsRule = readOptionalBoolean(parcel, true),
    )

    fun displayName(): String {
        return name.takeIf { it.isNotBlank() } ?: "Rule $id"
    }

    fun mkSummary(): String {
        var summary = ""
        if (config.isNotBlank()) summary += "[config]\n"
        if (domains.isNotBlank()) summary += "$domains\n"
        if (ip.isNotBlank()) summary += "$ip\n"
        if (source.isNotBlank()) summary += "src ip: $source\n"
        if (sourcePort.isNotBlank()) summary += "src port: $sourcePort\n"
        if (port.isNotBlank()) summary += "dst port: $port\n"
        if (networkType.isNotEmpty()) summary += "network_type: [${networkType.joinToString(", ") { "\"" + it + "\"" }}]\n"
        if (wifiSsid.isNotBlank() && isWifiIdentityVisible(networkType)) summary += "wifi_ssid: [${normalizeWifiSsidList(wifiSsid).joinToString(", ") { "\"" + it + "\"" }}]\n"
        if (wifiBssid.isNotBlank() && isWifiIdentityVisible(networkType)) summary += "wifi_bssid: [${normalizeWifiBssidList(wifiBssid).joinToString(", ") { "\"" + it + "\"" }}]\n"
        if (network.isNotBlank()) summary += "network: $network\n"
        if (protocol.isNotBlank()) summary += "protocol: $protocol\n"
        if (ruleset.isNotBlank()) summary += "$ruleset\n"
        if (packages.isNotEmpty()) summary += app.getString(
            R.string.apps_message, packages.size
        ) + "\n"
        val lines = summary.trim().split("\n")
        return if (lines.size > 3) {
            lines.subList(0, 3).joinToString("\n", postfix = "\n...")
        } else {
            summary.trim()
        }
    }

    fun displayOutbound(): String {
        return when (outbound) {
            0L -> app.getString(R.string.route_proxy)
            -1L -> app.getString(R.string.route_bypass)
            -2L -> app.getString(R.string.route_block)
            else -> ProfileManager.getProfile(outbound)?.displayName()
                ?: app.getString(R.string.error_title)
        }
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeString(name)
        parcel.writeString(config)
        parcel.writeLong(userOrder)
        parcel.writeByte(if (enabled) 1.toByte() else 0.toByte())
        parcel.writeString(domains)
        parcel.writeString(ip)
        parcel.writeString(port)
        parcel.writeString(sourcePort)
        parcel.writeStringList(networkType.toList())
        parcel.writeString(network)
        parcel.writeString(source)
        parcel.writeString(protocol)
        parcel.writeString(ruleset)
        parcel.writeLong(outbound)
        parcel.writeStringList(packages.toList())
        parcel.writeString(wifiSsid)
        parcel.writeString(wifiBssid)
        parcel.writeByte(if (createDnsRule) 1.toByte() else 0.toByte())
    }

    override fun describeContents(): Int {
        return 0
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * from rules WHERE (packages != '') AND enabled = 1")
        fun checkVpnNeeded(): List<RuleEntity>

        @Query("SELECT * FROM rules ORDER BY userOrder")
        fun allRules(): List<RuleEntity>

        @Query("SELECT * FROM rules WHERE enabled = :enabled ORDER BY userOrder")
        fun enabledRules(enabled: Boolean = true): List<RuleEntity>

        @Query("SELECT MAX(userOrder) + 1 FROM rules")
        fun nextOrder(): Long?

        @Query("SELECT * FROM rules WHERE id = :ruleId")
        fun getById(ruleId: Long): RuleEntity?

        @Query("DELETE FROM rules WHERE id = :ruleId")
        fun deleteById(ruleId: Long): Int

        @Delete
        fun deleteRule(rule: RuleEntity)

        @Delete
        fun deleteRules(rules: List<RuleEntity>)

        @Insert
        fun createRule(rule: RuleEntity): Long

        @Update
        fun updateRule(rule: RuleEntity)

        @Update
        fun updateRules(rules: List<RuleEntity>)

        @Query("DELETE FROM rules")
        fun reset()

        @Insert
        fun insert(rules: List<RuleEntity>)

    }


}
