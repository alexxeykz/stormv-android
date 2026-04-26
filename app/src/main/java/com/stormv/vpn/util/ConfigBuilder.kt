package com.stormv.vpn.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.stormv.vpn.model.Protocol
import com.stormv.vpn.model.ServerConfig

/**
 * Генерирует sing-box JSON конфиг для всех 7 протоколов.
 * Архитектура: sing-box как mixed (SOCKS5+HTTP) прокси на 127.0.0.1:2080.
 * TUN fd → tun2socks → sing-box:2080 → VPN сервер.
 *
 * Маршрутизация (split tunneling по доменам):
 *   Telegram + YouTube домены + IP + пользовательские сайты → proxy
 *   Всё остальное → direct (браузер идёт в интернет напрямую)
 */
object ConfigBuilder {

    const val PROXY_PORT = 2080
    const val CLASH_API_PORT = 9090

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    // Telegram: домены (TLS SNI) + основные IP диапазонов DC
    private val TELEGRAM_DOMAINS = listOf(
        "telegram.org", "telegram.me", "t.me", "telegra.ph",
        "tdesktop.com", "api.telegram.org", "core.telegram.org", "cdn.telegram.org"
    )
    private val TELEGRAM_IP_CIDRS = listOf(
        "149.154.160.0/20",   // DC1–5
        "91.108.4.0/22",      // DC1, DC3
        "91.108.8.0/22",      // DC3, DC5
        "91.108.16.0/22",     // DC4
        "91.108.56.0/22"      // DC3
    )

    // YouTube / Google Video
    private val YOUTUBE_DOMAINS = listOf(
        "youtube.com", "youtu.be", "googlevideo.com",
        "ytimg.com", "yt3.ggpht.com", "youtube.googleapis.com"
    )

    private val LOCAL_IP_CIDRS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
        "127.0.0.0/8", "169.254.0.0/16", "fc00::/7"
    )

    // Строит список правил маршрутизации с поддержкой пользовательских доменов.
    // proxyTag — "auto" для urltest-режима, "proxy" для одиночного сервера.
    private fun buildRoutingRules(proxyTag: String, userVpnSites: List<String>): List<Map<String, Any>> {
        val proxyDomains = TELEGRAM_DOMAINS + YOUTUBE_DOMAINS + userVpnSites
        return listOf(
            // Telegram + YouTube домены и пользовательские сайты → VPN
            mapOf("domain_suffix" to proxyDomains, "outbound" to proxyTag),
            // Telegram DC IP-соединения (MTPROTO без SNI) → VPN
            mapOf("ip_cidr" to TELEGRAM_IP_CIDRS, "outbound" to proxyTag),
            // Локальные сети → напрямую
            mapOf("ip_cidr" to LOCAL_IP_CIDRS, "outbound" to "direct")
        )
    }

    private fun buildMixedInbound() = mapOf(
        "type" to "mixed",
        "tag" to "mixed-in",
        "listen" to "127.0.0.1",
        "listen_port" to PROXY_PORT,
        "sniff" to true,
        "sniff_override_destination" to true
    )

    // ── Auto (urltest) режим ──────────────────────────────────────────────────

    fun buildAuto(serverOutbounds: List<Any>, userVpnSites: List<String> = emptyList()): String {
        val config = mapOf(
            "log" to mapOf("level" to "info", "timestamp" to true),
            "experimental" to mapOf(
                "clash_api" to mapOf("external_controller" to "127.0.0.1:$CLASH_API_PORT")
            ),
            "inbounds" to listOf(buildMixedInbound()),
            "outbounds" to serverOutbounds,
            "route" to mapOf(
                "rules" to buildRoutingRules("auto", userVpnSites),
                "final" to "direct"
            )
        )
        return gson.toJson(config)
    }

    /**
     * Обновляет route-секцию хранящегося singboxConfig с актуальными пользовательскими сайтами.
     * Не трогает outbounds — числа остаются числами, никакой конвертации типов Gson.
     */
    fun applyRoutingPolicy(storedJson: String, userVpnSites: List<String>): String {
        return try {
            // Заменяем только route — outbounds не трогаем (без риска Double вместо Int)
            val routeObj = com.google.gson.JsonObject()
            routeObj.add("rules", gson.toJsonTree(buildRoutingRules("auto", userVpnSites)))
            routeObj.addProperty("final", "direct")
            config.add("route", routeObj)

            // Добавляем clash_api если отсутствует
            val expObj = if (config.has("experimental"))
                config.getAsJsonObject("experimental")
            else com.google.gson.JsonObject().also { config.add("experimental", it) }

            if (!expObj.has("clash_api")) {
                val clashObj = com.google.gson.JsonObject()
                clashObj.addProperty("external_controller", "127.0.0.1:$CLASH_API_PORT")
                expObj.add("clash_api", clashObj)
            }

            gson.toJson(config)
        } catch (e: Exception) {
            AppLogger.w("ConfigBuilder", "applyRoutingPolicy fallback: ${e.message}")
            storedJson
        }
    }

    // ── Одиночный сервер ──────────────────────────────────────────────────────

    fun build(server: ServerConfig, userVpnSites: List<String> = emptyList()): String {
        val config = mapOf(
            "log" to mapOf("level" to "info", "timestamp" to true),
            "inbounds" to listOf(buildMixedInbound()),
            "outbounds" to listOf(
                buildOutbound(server),
                mapOf("type" to "direct", "tag" to "direct"),
                mapOf("type" to "block",  "tag" to "block")
            ),
            "route" to mapOf(
                "rules" to buildRoutingRules("proxy", userVpnSites),
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
            val fp = s.fingerprint.ifEmpty { "chrome" }
            mapOf(
                "enabled" to true,
                "server_name" to s.sni,
                "utls" to mapOf("enabled" to true, "fingerprint" to fp),
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
