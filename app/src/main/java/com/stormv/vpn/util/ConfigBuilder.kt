package com.stormv.vpn.util

import com.google.gson.GsonBuilder
import com.stormv.vpn.model.Protocol
import com.stormv.vpn.model.ServerConfig

/**
 * Генерирует sing-box JSON конфиг для всех 7 протоколов.
 * Архитектура: sing-box в TUN режиме — принимает fd от Android VpnService напрямую.
 * tun2socks не нужен.
 */
object ConfigBuilder {

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    fun build(server: ServerConfig, tunFd: Int): String {
        val config = mapOf(
            "log" to mapOf("level" to "info", "timestamp" to true),
            "inbounds" to listOf(
                mapOf(
                    "type" to "tun",
                    "tag" to "tun-in",
                    "fd" to tunFd,
                    "mtu" to 8500,
                    "auto_route" to false,
                    "stack" to "system",
                    "sniff" to true,
                    "sniff_override_destination" to false
                )
            ),
            "outbounds" to listOf(
                buildOutbound(server),
                mapOf("type" to "direct", "tag" to "direct"),
                mapOf("type" to "block", "tag" to "block")
            ),
            "route" to mapOf(
                "rules" to listOf(
                    // Локальные адреса — напрямую
                    mapOf(
                        "ip_cidr" to listOf(
                            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                            "127.0.0.0/8", "169.254.0.0/16", "fc00::/7"
                        ),
                        "outbound" to "direct"
                    ),
                    // Telegram — через прокси
                    mapOf(
                        "domain_suffix" to listOf("telegram.org", "t.me", "telegram.me", "telesco.pe"),
                        "outbound" to "proxy"
                    ),
                    mapOf(
                        "ip_cidr" to listOf(
                            "91.108.0.0/16", "91.105.192.0/23",
                            "149.154.160.0/20", "185.76.151.0/24", "95.161.76.0/24"
                        ),
                        "outbound" to "proxy"
                    ),
                    // YouTube — через прокси
                    mapOf(
                        "domain_suffix" to listOf(
                            "youtube.com", "youtu.be", "googlevideo.com",
                            "ytimg.com", "ggpht.com", "youtube-nocookie.com"
                        ),
                        "outbound" to "proxy"
                    )
                ),
                "final" to "direct"
            )
        )
        return gson.toJson(config)
    }

    private fun buildOutbound(s: ServerConfig): Map<String, Any?> = when (s.protocol) {
        Protocol.VLESS       -> buildVless(s)
        Protocol.VMESS       -> buildVmess(s)
        Protocol.SHADOWSOCKS -> buildShadowsocks(s)
        Protocol.TROJAN      -> buildTrojan(s)
        Protocol.HYSTERIA2   -> buildHysteria2(s)
        Protocol.TUIC        -> buildTuic(s)
        Protocol.WIREGUARD   -> buildWireGuard(s)
    }

    private fun buildVless(s: ServerConfig) = mapOf(
        "type" to "vless", "tag" to "proxy",
        "server" to s.host, "server_port" to s.port,
        "uuid" to s.uuid,
        "flow" to s.flow.ifEmpty { null },
        "tls" to buildTls(s),
        "transport" to buildTransport(s)
    )

    private fun buildVmess(s: ServerConfig) = mapOf(
        "type" to "vmess", "tag" to "proxy",
        "server" to s.host, "server_port" to s.port,
        "uuid" to s.uuid, "security" to "auto",
        "alter_id" to s.alterId,
        "tls" to if (s.security == "tls") buildTls(s) else null,
        "transport" to buildTransport(s)
    )

    private fun buildShadowsocks(s: ServerConfig) = mapOf(
        "type" to "shadowsocks", "tag" to "proxy",
        "server" to s.host, "server_port" to s.port,
        "method" to s.method, "password" to s.password
    )

    private fun buildTrojan(s: ServerConfig) = mapOf(
        "type" to "trojan", "tag" to "proxy",
        "server" to s.host, "server_port" to s.port,
        "password" to s.password,
        "tls" to mapOf(
            "enabled" to true,
            "server_name" to s.sni.ifEmpty { s.host },
            "insecure" to s.skipCertVerify
        ),
        "transport" to buildTransport(s)
    )

    private fun buildHysteria2(s: ServerConfig) = mapOf(
        "type" to "hysteria2", "tag" to "proxy",
        "server" to s.host, "server_port" to s.port,
        "password" to s.password,
        "obfs" to if (s.obfs.isEmpty()) null else mapOf(
            "type" to s.obfs, "password" to s.obfsPassword
        ),
        "tls" to mapOf(
            "enabled" to true,
            "server_name" to s.sni.ifEmpty { s.host },
            "insecure" to s.skipCertVerify
        )
    )

    private fun buildTuic(s: ServerConfig) = mapOf(
        "type" to "tuic", "tag" to "proxy",
        "server" to s.host, "server_port" to s.port,
        "uuid" to s.uuid, "password" to s.password,
        "congestion_control" to s.congestionControl,
        "tls" to mapOf(
            "enabled" to true,
            "server_name" to s.sni.ifEmpty { s.host },
            "insecure" to s.skipCertVerify
        )
    )

    private fun buildWireGuard(s: ServerConfig) = mapOf(
        "type" to "wireguard", "tag" to "proxy",
        "private_key" to s.privateKey,
        "peers" to listOf(mapOf(
            "server" to s.host, "server_port" to s.port,
            "public_key" to s.publicKey,
            "pre_shared_key" to s.presharedKey.ifEmpty { null },
            "allowed_ips" to s.allowedIps
        )),
        "local_address" to listOf(s.localAddress),
        "mtu" to s.mtu
    )

    private fun buildTls(s: ServerConfig): Map<String, Any?>? {
        if (s.security == "none" || s.security.isEmpty()) return null
        return if (s.security == "reality") {
            mapOf(
                "enabled" to true,
                "server_name" to s.sni,
                "utls" to mapOf("enabled" to true, "fingerprint" to s.fingerprint),
                "reality" to mapOf(
                    "enabled" to true,
                    "public_key" to s.realityPublicKey,
                    "short_id" to s.realityShortId
                )
            )
        } else {
            mapOf(
                "enabled" to true,
                "server_name" to s.sni.ifEmpty { s.host },
                "utls" to mapOf("enabled" to s.fingerprint.isNotEmpty(), "fingerprint" to s.fingerprint),
                "insecure" to s.skipCertVerify
            )
        }
    }

    private fun buildTransport(s: ServerConfig): Map<String, Any?>? {
        val wsHost = s.host2.ifEmpty { null }
        return when (s.network) {
            "ws" -> mapOf(
                "type" to "ws",
                "path" to s.path.ifEmpty { "/" },
                "headers" to if (wsHost != null) mapOf("Host" to wsHost) else null
            )
            "grpc" -> mapOf("type" to "grpc", "service_name" to s.path)
            "http" -> mapOf(
                "type" to "http",
                "path" to s.path.ifEmpty { "/" },
                "host" to if (wsHost != null) listOf(wsHost) else null
            )
            else -> null
        }
    }
}
