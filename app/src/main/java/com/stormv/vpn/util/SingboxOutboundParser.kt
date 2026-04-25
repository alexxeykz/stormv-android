package com.stormv.vpn.util

import com.stormv.vpn.model.Protocol
import com.stormv.vpn.model.ServerConfig

object SingboxOutboundParser {

    private val SKIP_TYPES = setOf("urltest", "selector", "direct", "block", "dns")

    fun parse(ob: Map<*, *>): ServerConfig? {
        val type = ob["type"] as? String ?: return null
        if (type in SKIP_TYPES) return null
        val host = ob["server"] as? String ?: return null
        val port = (ob["server_port"] as? Number)?.toInt() ?: return null
        val tag = ob["tag"] as? String ?: ""

        val tls = ob["tls"] as? Map<*, *>
        val reality = tls?.get("reality") as? Map<*, *>
        val utls = tls?.get("utls") as? Map<*, *>
        val tlsEnabled = tls?.get("enabled") as? Boolean ?: false
        val security = when {
            reality?.get("enabled") as? Boolean == true -> "reality"
            tlsEnabled -> "tls"
            else -> "none"
        }
        val sni = tls?.get("server_name") as? String ?: ""
        val fingerprint = utls?.get("fingerprint") as? String ?: "chrome"
        val skipCertVerify = tls?.get("insecure") as? Boolean ?: false
        val transport = ob["transport"] as? Map<*, *>
        val network = transport?.get("type") as? String ?: "tcp"
        val path = transport?.get("path") as? String ?: ""
        val host2 = (transport?.get("headers") as? Map<*, *>)?.get("Host") as? String ?: ""

        return when (type) {
            "vless" -> ServerConfig(
                name = tag, protocol = Protocol.VLESS,
                host = host, port = port,
                uuid = ob["uuid"] as? String ?: "",
                flow = ob["flow"] as? String ?: "",
                security = security, sni = sni, fingerprint = fingerprint,
                realityPublicKey = reality?.get("public_key") as? String ?: "",
                realityShortId = reality?.get("short_id") as? String ?: "",
                network = network, path = path, host2 = host2,
                skipCertVerify = skipCertVerify,
                isSubscription = true
            )
            "vmess" -> ServerConfig(
                name = tag, protocol = Protocol.VMESS,
                host = host, port = port,
                uuid = ob["uuid"] as? String ?: "",
                alterId = (ob["alter_id"] as? Number)?.toInt() ?: 0,
                security = if (tlsEnabled) "tls" else "none",
                sni = sni, fingerprint = fingerprint,
                network = network, path = path, host2 = host2,
                skipCertVerify = skipCertVerify,
                isSubscription = true
            )
            "trojan" -> ServerConfig(
                name = tag, protocol = Protocol.TROJAN,
                host = host, port = port,
                password = ob["password"] as? String ?: "",
                security = "tls", sni = sni, fingerprint = fingerprint,
                skipCertVerify = skipCertVerify,
                network = network, path = path,
                isSubscription = true
            )
            "shadowsocks" -> ServerConfig(
                name = tag, protocol = Protocol.SHADOWSOCKS,
                host = host, port = port,
                method = ob["method"] as? String ?: "",
                password = ob["password"] as? String ?: "",
                isSubscription = true
            )
            "hysteria2" -> ServerConfig(
                name = tag, protocol = Protocol.HYSTERIA2,
                host = host, port = port,
                password = ob["password"] as? String ?: "",
                sni = sni, skipCertVerify = skipCertVerify,
                isSubscription = true
            )
            "tuic" -> ServerConfig(
                name = tag, protocol = Protocol.TUIC,
                host = host, port = port,
                uuid = ob["uuid"] as? String ?: "",
                password = ob["password"] as? String ?: "",
                congestionControl = ob["congestion_control"] as? String ?: "bbr",
                sni = sni, skipCertVerify = skipCertVerify,
                isSubscription = true
            )
            "wireguard" -> {
                val peer = (ob["peers"] as? List<*>)?.firstOrNull() as? Map<*, *>
                val addrs = ob["local_address"] as? List<*>
                ServerConfig(
                    name = tag, protocol = Protocol.WIREGUARD,
                    host = peer?.get("server") as? String ?: host,
                    port = (peer?.get("server_port") as? Number)?.toInt() ?: port,
                    privateKey = ob["private_key"] as? String ?: "",
                    publicKey = peer?.get("public_key") as? String ?: "",
                    presharedKey = peer?.get("pre_shared_key") as? String ?: "",
                    localAddress = (addrs?.firstOrNull() as? String) ?: "",
                    isSubscription = true
                )
            }
            else -> null
        }
    }
}
