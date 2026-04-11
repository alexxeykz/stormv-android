package com.stormv.vpn.util

import android.util.Base64
import com.stormv.vpn.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

object SubscriptionManager {

    /**
     * Скачивает подписку по URL и возвращает список серверов.
     * Поддерживает base64-encoded и plain-text форматы.
     */
    suspend fun fetch(subscriptionUrl: String): Result<List<ServerConfig>> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = URL(subscriptionUrl).readText(Charsets.UTF_8).trim()

            // Пробуем декодировать как base64
            val content = tryBase64Decode(raw) ?: raw

            val servers = content
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line -> UrlParser.parse(line) }

            if (servers.isEmpty()) error("Не найдено ни одного сервера в подписке")
            servers
        }
    }

    private fun tryBase64Decode(s: String): String? = runCatching {
        val padded = s.padEnd((s.length + 3) / 4 * 4, '=')
        String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8).also {
            // Проверяем что результат выглядит как список ссылок
            if (!it.contains("://")) return null
        }
    }.getOrNull()
}
