package com.stormv.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.stormv.vpn.MainActivity
import com.stormv.vpn.R
import com.stormv.vpn.model.ServerConfig
import com.stormv.vpn.util.AppLogger
import com.stormv.vpn.util.ConfigBuilder
import com.stormv.vpn.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class StormVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.stormv.vpn.START"
        const val ACTION_STOP  = "com.stormv.vpn.STOP"
        const val EXTRA_SERVER = "server_json"
        const val CHANNEL_ID   = "stormv_vpn"
        const val NOTIF_ID     = 1

        var isRunning = false
            private set
        var lastError: String? = null
            private set

        var onStatusChanged: ((Boolean, String?) -> Unit)? = null
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnJob: Job? = null
    private var singBoxProcess: Process? = null
    private var tunPfd: ParcelFileDescriptor? = null

    inner class LocalBinder : Binder() {
        fun getService() = this@StormVpnService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val json = intent.getStringExtra(EXTRA_SERVER) ?: return START_NOT_STICKY
                startForeground(NOTIF_ID, buildNotification("Подключение..."))
                startVpn(json)
            }
            ACTION_STOP -> stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn(serverJson: String) {
        vpnJob = scope.launch {
            try {
                val server = com.google.gson.Gson().fromJson(serverJson, ServerConfig::class.java)
                AppLogger.i("VpnService", "Запуск: ${server.displayName} [${server.protocol}] ${server.host}:${server.port}")

                // ── 1. Создаём TUN интерфейс ──────────────────────────────────
                val builder = Builder()
                    .setSession("StormV")
                    .addAddress("172.19.0.1", 30)
                    .addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .setMtu(8500)
                    .setBlocking(false)
                    .addDisallowedApplication(packageName) // наш процесс (sing-box) идёт в обход VPN

                tunPfd = builder.establish()
                    ?: throw Exception("Не удалось создать TUN интерфейс")
                val tunFd = tunPfd!!.fd
                AppLogger.i("VpnService", "TUN создан, fd=$tunFd")

                // Снимаем FD_CLOEXEC чтобы fd был виден дочернему процессу sing-box
                clearFdCloexec(tunPfd!!)

                // ── 2. Запускаем sing-box в TUN режиме (fd → VPN сервер) ─────
                val configDir = File(filesDir, "singbox").also { it.mkdirs() }
                val configFile = File(configDir, "config.json")
                configFile.writeText(ConfigBuilder.build(server, tunFd))

                val singBoxFile = File(applicationInfo.nativeLibraryDir, "libsingbox.so")
                if (!singBoxFile.exists()) throw Exception("sing-box не найден. Переустановите приложение.")

                AppLogger.i("VpnService", "Запуск sing-box TUN: fd=$tunFd → ${server.host}:${server.port}")
                singBoxProcess = ProcessBuilder(singBoxFile.absolutePath, "run", "-c", configFile.absolutePath)
                    .redirectErrorStream(true)
                    .directory(configDir)
                    .start()

                // Ждём 2 сек и проверяем — если упал, читаем вывод синхронно
                Thread.sleep(2000)
                if (singBoxProcess?.isAlive == false) {
                    val output = singBoxProcess?.inputStream?.bufferedReader()?.readText()?.trim() ?: ""
                    throw Exception("sing-box упал:\n$output")
                }

                // Процесс жив — читаем логи в фоне
                scope.launch {
                    singBoxProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                        val level = when {
                            line.contains("error", ignoreCase = true)  -> LogLevel.ERROR
                            line.contains("warn", ignoreCase = true)   -> LogLevel.WARNING
                            line.contains("debug", ignoreCase = true)  -> LogLevel.DEBUG
                            else                                        -> LogLevel.INFO
                        }
                        AppLogger.write(level, "sing-box", line)
                    }
                }

                AppLogger.i("VpnService", "sing-box запущен")

                isRunning = true
                lastError = null
                onStatusChanged?.invoke(true, null)
                updateNotification("Подключено")
                AppLogger.i("VpnService", "Подключено!")

                // Ждём завершения любого из процессов
                val exitCode = singBoxProcess!!.waitFor()
                AppLogger.w("VpnService", "sing-box завершился с кодом $exitCode")
                if (exitCode != 0) throw Exception("sing-box завершился с кодом $exitCode")

            } catch (e: Exception) {
                AppLogger.e("VpnService", "Ошибка: ${e.message}")
                isRunning = false
                lastError = e.message
                onStatusChanged?.invoke(false, e.message)
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        AppLogger.i("VpnService", "Остановка VPN...")
        vpnJob?.cancel()
        singBoxProcess?.destroyForcibly()
        singBoxProcess = null
        tunPfd?.close()
        tunPfd = null
        isRunning = false
        lastError = null
        onStatusChanged?.invoke(false, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        AppLogger.i("VpnService", "VPN остановлен")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // Снимаем FD_CLOEXEC через доступный публичный API или reflection
    private fun clearFdCloexec(pfd: android.os.ParcelFileDescriptor) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                @Suppress("NewApi")
                android.system.Os.fcntlInt(pfd.fileDescriptor, android.system.OsConstants.F_SETFD, 0)
                AppLogger.d("VpnService", "FD_CLOEXEC cleared via fcntlInt (API 34+)")
            } else {
                // На API < 34 пробуем через libcore reflection
                val libcore = Class.forName("libcore.io.Libcore")
                val os = libcore.getDeclaredField("os").apply { isAccessible = true }.get(null)
                val method = os!!.javaClass.methods.firstOrNull { m ->
                    m.name == "fcntl" && m.parameterCount == 3
                }
                method?.invoke(os, pfd.fileDescriptor, android.system.OsConstants.F_SETFD, 0)
                AppLogger.d("VpnService", "FD_CLOEXEC cleared via reflection")
            }
        } catch (e: Exception) {
            AppLogger.w("VpnService", "clearFdCloexec: ${e.message} — fd may not be inherited")
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "StormV VPN",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Статус VPN подключения" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, StormVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("StormV")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(intent)
            .addAction(Notification.Action.Builder(null, "Отключить", stopIntent).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
