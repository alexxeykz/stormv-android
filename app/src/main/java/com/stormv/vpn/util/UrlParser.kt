package com.stormv.vpn.util

import android.net.Uri
import android.util.Base64
import com.stormv.vpn.model.Protocol
import com.stormv.vpn.model.ServerConfig
import org.json.JSONObject

object UrlParser {

    fun parse(url: String): ServerConfig? {
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore("://").lowercase()
        return when (Protocol.fromScheme(scheme)) {
            Protocol.VLESS       -> parseVless(trimmed)
            Protocol.VMESS       -> parseVmess(trimmed)
            Protocol.SHADOWSOCKS -> parseShadowsocks(trimmed)
            Protocol.TROJAN      -> parseTrojan(trimmed)
            Protocol.HYSTERIA2   -> parseHysteria2(trimmed)
            Protocol.TUIC        -> parseTuic(trimmed)
            Protocol.WIREGUARD   -> parseWireGuard(trimmed)
            null                 -> null
        }
    }

    // ── VLESS ─────────────────────────────────────────────────────────────────
    private fun parseVless(url: String): ServerConfig? = runCatching {
        val uri = Uri.parse(url)
        val q = uri.queryParams()
        ServerConfig(
            rawUrl = url,
            protocol = Protocol.VLESS,
            uuid = uri.userInfo ?: "",
            host = uri.host ?: "",
            port = uri.port,
            name = uri.fragment?.urlDecode() ?: "",
            encryption = q["encryption"] ?: "none",
            flow = q["flow"] ?: "",
            network = q["type"] ?: "tcp",
            security = q["security"] ?: "none",
            sni = q["sni"] ?: "",
            fingerprint = q["fp"] ?: "chrome",
            realityPublicKey = q["pbk"] ?: "",
            realityShortId = q["sid"] ?: "",
            spiderX = q["spx"] ?: "",
            path = (q["path"] ?: "").urlDecode(),
            host2 = q["host"] ?: "",
        )
    }.getOrNull()

    // ── VMESS ─────────────────────────────────────────────────────────────────
    private fun parseVmess(url: String): ServerConfig? = runCatching {
        val b64 = url.removePrefix("vmess://")
        val json = JSONObject(b64.base64Decode())
        ServerConfig(
            rawUrl = url,
            protocol = Protocol.VMESS,
            name = json.optString("ps"),
            host = json.optString("add"),
            port = json.optInt("port"),
            uuid = json.optString("id"),
            alterId = json.optInt("aid"),
            network = json.optString("net", "tcp"),
            security = if (json.optString("tls") == "tls") "tls" else "none",
            sni = json.optString("sni"),
            path = json.optString("path"),
            host2 = json.optString("host"),
        )
    }.getOrNull()

    // ── SHADOWSOCKS ───────────────────────────────────────────────────────────
    private fun parseShadowsocks(url: String): ServerConfig? = runCatching {
        val uri = Uri.parse(url)
        val name = uri.fragment?.urlDecode() ?: ""
        val userInfo = uri.userInfo ?: ""
        val decoded = runCatching { userInfo.base64Decode() }.getOrDefault(userInfo)
        val colonIdx = decoded.indexOf(':')
        val method = decoded.substring(0, colonIdx)
        val password = decoded.substring(colonIdx + 1)
        ServerConfig(
            rawUrl = url,
            protocol = Protocol.SHADOWSOCKS,
            name = name,
            host = uri.host ?: "",
            port = uri.port,
            method = method,
            password = password,
        )
    }.getOrNull()

    // ── TROJAN ────────────────────────────────────────────────────────────────
    private fun parseTrojan(url: String): ServerConfig? = runCatching {
        val uri = Uri.parse(url)
        val q = uri.queryParams()
        ServerConfig(
            rawUrl = url,
            protocol = Protocol.TROJAN,
            name = uri.fragment?.urlDecode() ?: "",
            host = uri.host ?: "",
            port = uri.port,
            password = uri.userInfo ?: "",
            security = "tls",
            sni = q["sni"] ?: uri.host ?: "",
            fingerprint = q["fp"] ?: "chrome",
            skipCertVerify = q["allowInsecure"] == "1",
            network = q["type"] ?: "tcp",
            path = (q["path"] ?: "").urlDecode(),
        )
    }.getOrNull()

    // ── HYSTERIA2 ─────────────────────────────────────────────────────────────
    private fun parseHysteria2(url: String): ServerConfig? = runCatching {
        val normalized = url.replace("hy2://", "hysteria2://")
        val uri = Uri.parse(normalized)
        val q = uri.queryParams()
        ServerConfig(
            rawUrl = url,
            protocol = Protocol.HYSTERIA2,
            name = uri.fragment?.urlDecode() ?: "",
            host = uri.host ?: "",
            port = if (uri.port > 0) uri.port else 443,
            password = uri.userInfo ?: "",
            sni = q["sni"] ?: uri.host ?: "",
            obfs = q["obfs"] ?: "",
            obfsPassword = q["obfs-password"] ?: "",
            skipCertVerify = q["insecure"] == "1",
        )
    }.getOrNull()

    // ── TUIC ──────────────────────────────────────────────────────────────────
    private fun parseTuic(url: String): ServerConfig? = runCatching {
        val uri = Uri.parse(url)
        val q = uri.queryParams()
        val parts = (uri.userInfo ?: "").split(":")
        ServerConfig(
            rawUrl = url,
            protocol = Protocol.TUIC,
            name = uri.fragment?.urlDecode() ?: "",
            host = uri.host ?: "",
            port = uri.port,
            uuid = parts.getOrElse(0) { "" },
            password = parts.getOrElse(1) { "" },
            sni = q["sni"] ?: uri.host ?: "",
            congestionControl = q["congestion_control"] ?: "bbr",
            skipCertVerify = q["allow_insecure"] == "1",
        )
    }.getOrNull()

    // ── WIREGUARD ─────────────────────────────────────────────────────────────
    private fun parseWireGuard(url: String): ServerConfig? = runCatching {
        val normalized = url.replace("wg://", "wireguard://")
        val uri = Uri.parse(normalized)
        val q = uri.queryParams()
        ServerConfig(
            rawUrl = url,
            protocol = Protocol.WIREGUARD,
            name = uri.fragment?.urlDecode() ?: "",
            host = uri.host ?: "",
            port = if (uri.port > 0) uri.port else 51820,
            privateKey = uri.userInfo?.urlDecode() ?: "",
            publicKey = q["publickey"] ?: "",
            presharedKey = q["presharedkey"] ?: "",
            localAddress = q["ip"] ?: "10.0.0.2/32",
            mtu = q["mtu"]?.toIntOrNull() ?: 1420,
        )
    }.getOrNull()

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun Uri.queryParams(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        queryParameterNames.forEach { key ->
            getQueryParameter(key)?.let { map[key] = it }
        }
        return map
    }

    private fun String.urlDecode(): String =
        java.net.URLDecoder.decode(this, "UTF-8")

    private fun String.base64Decode(): String {
        val padded = this.padEnd((this.length + 3) / 4 * 4, '=')
        return String(Base64.decode(padded, Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
    }
}
