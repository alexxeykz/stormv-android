package com.stormv.vpn.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object PingUtil {
    /**
     * TCP-пинг к хосту:порту.
     * @return задержка в мс, или null если недоступен
     */
    suspend fun ping(host: String, port: Int, timeoutMs: Int = 3000): Int? =
        withContext(Dispatchers.IO) {
            runCatching {
                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
                (System.currentTimeMillis() - start).toInt()
            }.getOrNull()
        }
}
