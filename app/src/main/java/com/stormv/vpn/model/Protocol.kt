package com.stormv.vpn.model

enum class Protocol(val label: String, val schemes: List<String>) {
    VLESS("VLESS", listOf("vless")),
    VMESS("VMess", listOf("vmess")),
    SHADOWSOCKS("SS", listOf("ss")),
    TROJAN("Trojan", listOf("trojan")),
    HYSTERIA2("Hy2", listOf("hysteria2", "hy2")),
    TUIC("TUIC", listOf("tuic")),
    WIREGUARD("WG", listOf("wireguard", "wg"));

    companion object {
        fun fromScheme(scheme: String): Protocol? =
            entries.find { it.schemes.contains(scheme.lowercase()) }
    }
}
