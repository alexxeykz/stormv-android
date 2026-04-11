package com.stormv.vpn.data

import com.tencent.mmkv.MMKV

object SettingsRepository {
    private val kv by lazy { MMKV.defaultMMKV() }

    var dnsPrimary: String
        get() = kv.decodeString("dns_primary", "8.8.8.8") ?: "8.8.8.8"
        set(v) { kv.encode("dns_primary", v) }

    var dnsSecondary: String
        get() = kv.decodeString("dns_secondary", "8.8.4.4") ?: "8.8.4.4"
        set(v) { kv.encode("dns_secondary", v) }

    var autoConnectOnStart: Boolean
        get() = kv.decodeBool("auto_connect", false)
        set(v) { kv.encode("auto_connect", v) }

    var bypassList: List<String>
        get() = (kv.decodeString("bypass_list", "192.168.0.0/16\n10.0.0.0/8\n172.16.0.0/12") ?: "")
            .split("\n").filter { it.isNotBlank() }
        set(v) { kv.encode("bypass_list", v.joinToString("\n")) }

    var lastServerId: String
        get() = kv.decodeString("last_server_id", "") ?: ""
        set(v) { kv.encode("last_server_id", v) }
}
