package com.stormv.vpn.model

import java.util.UUID

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: Protocol,
    val host: String = "",
    val port: Int = 0,
    val rawUrl: String = "",

    // VLESS / VMess
    val uuid: String = "",
    val flow: String = "",
    val encryption: String = "none",
    val alterId: Int = 0,

    // Transport
    val network: String = "tcp",
    val security: String = "none",
    val sni: String = "",
    val path: String = "",
    val host2: String = "",

    // REALITY
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val fingerprint: String = "chrome",
    val spiderX: String = "",

    // Shadowsocks / Trojan / Hysteria2 / TUIC
    val method: String = "",
    val password: String = "",

    // Hysteria2
    val obfs: String = "",
    val obfsPassword: String = "",
    val skipCertVerify: Boolean = false,

    // TUIC
    val congestionControl: String = "bbr",

    // WireGuard
    val privateKey: String = "",
    val publicKey: String = "",
    val presharedKey: String = "",
    val localAddress: String = "",
    val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    val mtu: Int = 1420,
) {
    val displayName: String
        get() = name.ifBlank { "${protocol.label} · $host:$port" }
}
