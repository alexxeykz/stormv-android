package com.stormv.vpn.util

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stormv.vpn.model.Protocol
import com.stormv.vpn.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object SubscriptionManager {

    suspend fun fetch(subscriptionUrl: String): Result<List<ServerConfig>> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(subscriptionUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "StormV/1.0 sing-box")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()

            tryParseSingbox(raw)?.let { return@runCatching listOf(it) }

            val content = tryBase64Decode(raw) ?: raw
            val servers = content
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { UrlParser.parse(it) }

            if (servers.isEmpty()) error("Не найдено ни одного сервера в подписке")
            servers
        }
    }

    private fun tryParseSingbox(raw: String): ServerConfig? = runCatching {
        if (!raw.startsWith("{")) return null
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val json: Map<String, Any> = Gson().fromJson(raw, type) ?: return null
        val outbounds = json["outbounds"] as? List<*> ?: return null

        val filtered = outbounds.filterIsInstance<Map<*, *>>().filter { ob ->
            (ob["type"] as? String) !in listOf("selector", "dns")
        }

        val serverCount = outbounds.filterIsInstance<Map<*, *>>().count { ob ->
            (ob["type"] as? String) !in listOf("urltest", "selector", "direct", "block", "dns")
        }

        if (serverCount == 0) return null

        val config = ConfigBuilder.buildAuto(filtered)

        ServerConfig(
            name = "Auto · $serverCount серв.",
            protocol = Protocol.VLESS,
            isAuto = true,
            singboxConfig = config,
            serverCount = serverCount
        )
    }.getOrNull()

    private fun tryBase64Decode(s: String): String? = runCatching {
        val padded = s.padEnd((s.length + 3) / 4 * 4, '=')
        String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8).also {
            if (!it.contains("://")) return null
        }
    }.getOrNull()
}
