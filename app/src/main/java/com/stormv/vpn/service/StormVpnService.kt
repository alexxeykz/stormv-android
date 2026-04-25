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
import com.stormv.vpn.data.SettingsRepository
import com.stormv.vpn.model.ServerConfig
import com.stormv.vpn.util.AppLogger
import com.stormv.vpn.util.ConfigBuilder
import com.stormv.vpn.util.LogLevel
import com.stormv.vpn.util.NativeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

class StormVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.stormv.vpn.START"
        const val ACTION_STOP  = "com.stormv.vpn.STOP"
        const val EXTRA_SERVER = "server_json"
        const val CHANNEL_ID   = "stormv_vpn"
        const val NOTIF_ID     = 1

        // Приложения, чей трафик идёт через TUN (split tunneling, whitelist-режим).
        // Telegram + YouTube: весь трафик через VPN.
        // Браузеры: только домены из списка "Сайты через VPN" — через VPN,
        //           остальные сайты — напрямую (маршрутизация в sing-box).
        val ROUTED_APPS = listOf(
            // Telegram
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
            "app.nicegram",
            // YouTube
            "com.google.android.youtube",
            "com.google.android.youtube.tv",
            "com.google.android.apps.youtube.music",
            // Браузеры
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.opera.browser",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.yandex.browser"
        )

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
    private var tun2socksPid: Long = -1
    private var tun2socksOutPfd: ParcelFileDescriptor? = null
    private var tunPfd: ParcelFileDescriptor? = null

    @Volatile private var stopping = false

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
                if (server.isAuto)
                    AppLogger.i("VpnService", "Запуск (Auto): ${server.displayName} [${server.serverCount} серв.]")
                else
                    AppLogger.i("VpnService", "Запуск: ${server.displayName} [${server.protocol}] ${server.host}:${server.port}")

                // ── 1. Создаём TUN интерфейс ──────────────────────────────────
                // Split tunneling: только Telegram + YouTube идут через VPN.
                // addAllowedApplication() — режим whitelist: остальные приложения
                // обходят TUN и выходят напрямую без VPN.
                val tun = Builder()
                    .setSession("StormV")
                    .addAddress("172.19.0.1", 30)
                    .addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .setMtu(8500)
                    .setBlocking(false)

                for (pkg in ROUTED_APPS) {
                    try {
                        tun.addAllowedApplication(pkg)
                    } catch (e: Exception) {
                        AppLogger.w("VpnService", "Пакет не установлен, пропущен: $pkg")
                    }
                }

                tunPfd = tun.establish() ?: throw Exception("Не удалось создать TUN интерфейс")
                val tunFd = tunPfd!!.fd
                AppLogger.i("VpnService", "TUN создан, fd=$tunFd")

                // Снимаем FD_CLOEXEC через JNI — нужно для tun2socks
                val fcntlResult = NativeUtils.clearFdCloexec(tunFd)
                AppLogger.i("VpnService", "clearFdCloexec(fd=$tunFd) = $fcntlResult")

                // ── 2. Запускаем sing-box как SOCKS5/HTTP прокси на 127.0.0.1:2080 ──
                val singBoxFile = File(applicationInfo.nativeLibraryDir, "libsingbox.so")
                if (!singBoxFile.exists()) throw Exception("sing-box не найден. Переустановите приложение.")

                val configDir = File(filesDir, "singbox").also { it.mkdirs() }
                val configFile = File(configDir, "config.json")
                val userVpnSites = SettingsRepository.vpnSites
                configFile.writeText(
                    if (server.isAuto) ConfigBuilder.applyRoutingPolicy(server.singboxConfig, userVpnSites)
                    else ConfigBuilder.build(server, userVpnSites)
                )

                AppLogger.i("VpnService", "Запуск sing-box SOCKS5 на :${ConfigBuilder.PROXY_PORT}")
                singBoxProcess = ProcessBuilder(singBoxFile.absolutePath, "run", "-c", configFile.absolutePath)
                    .redirectErrorStream(true)
                    .directory(configDir)
                    .start()

                readLogsAsync(singBoxProcess!!.inputStream, "sing-box")

                // Ждём пока sing-box поднимет порт
                Thread.sleep(1500)
                if (singBoxProcess?.isAlive == false) {
                    val out = singBoxProcess?.inputStream?.bufferedReader()?.readText()?.trim() ?: ""
                    throw Exception("sing-box упал:\n$out")
                }
                AppLogger.i("VpnService", "sing-box запущен")

                // ── 3. Запускаем tun2socks через JNI fork()+execv() ─────────
                // ProcessBuilder (Android API 28+) использует posix_spawn с
                // POSIX_SPAWN_CLOEXEC_DEFAULT и закрывает все fd в дочернем
                // процессе. fork()+execv() напрямую наследует tunFd без ограничений.
                val tun2socksFile = File(applicationInfo.nativeLibraryDir, "libtun2socks.so")
                if (!tun2socksFile.exists()) throw Exception("tun2socks не найден. Переустановите приложение.")

                AppLogger.i("VpnService", "Запуск tun2socks: fd=$tunFd → socks5://127.0.0.1:${ConfigBuilder.PROXY_PORT}")
                val forkResult = NativeUtils.startTun2socksNative(
                    tun2socksFile.absolutePath, tunFd, ConfigBuilder.PROXY_PORT
                ) ?: throw Exception("Не удалось запустить tun2socks (fork failed)")

                tun2socksPid = forkResult[0]
                val pipeReadFd = forkResult[1].toInt()
                tun2socksOutPfd = ParcelFileDescriptor.adoptFd(pipeReadFd)
                readLogsAsync(ParcelFileDescriptor.AutoCloseInputStream(tun2socksOutPfd!!), "tun2socks")

                Thread.sleep(1000)
                if (!NativeUtils.isProcessAlive(tun2socksPid)) {
                    throw Exception("tun2socks упал сразу после запуска")
                }
                AppLogger.i("VpnService", "tun2socks запущен")

                isRunning = true
                lastError = null
                onStatusChanged?.invoke(true, null)
                updateNotification("Telegram · YouTube защищены")
                AppLogger.i("VpnService", "VPN активен (split): ${server.host}:${server.port}")

                // Мониторим tun2socks через polling с delay() — единственный способ
                // сделать блок отменяемым. waitpid() блокирует JNI-поток навсегда и
                // не реагирует на vpnJob.cancel(). delay() — точка отмены корутины.
                while (isActive) {
                    delay(500)
                    if (!NativeUtils.isProcessAlive(tun2socksPid)) {
                        throw Exception("tun2socks неожиданно завершился")
                    }
                }
                // Сюда попадаем только при отмене корутины из stopVpn() — это штатный выход.

            } catch (e: CancellationException) {
                // Штатная отмена через vpnJob.cancel() из stopVpn() — не ошибка, молчим.
                throw e
            } catch (e: Exception) {
                AppLogger.e("VpnService", "Ошибка: ${e.message}")
                isRunning = false
                lastError = e.message
                onStatusChanged?.invoke(false, e.message)
                // stopVpn() убивает процессы и закрывает TUN.
                // Если не вызвать его здесь — TUN остаётся открытым, трафик идёт
                // через мёртвый VPN и интернет "не работает" даже при статусе "отключено".
                stopVpn()
            }
        }
    }

    private fun readLogsAsync(stream: InputStream, tag: String) {
        scope.launch {
            stream.bufferedReader().forEachLine { line ->
                val level = when {
                    line.contains("error", ignoreCase = true) -> LogLevel.ERROR
                    line.contains("warn",  ignoreCase = true) -> LogLevel.WARNING
                    line.contains("debug", ignoreCase = true) -> LogLevel.DEBUG
                    else                                       -> LogLevel.INFO
                }
                AppLogger.write(level, tag, line)
            }
        }
    }

    private fun stopVpn() {
        if (stopping) return
        stopping = true
        AppLogger.i("VpnService", "Остановка VPN...")
        vpnJob?.cancel()
        if (tun2socksPid > 0) {
            NativeUtils.killProcess(tun2socksPid)
            tun2socksPid = -1
        }
        tun2socksOutPfd?.close()
        tun2socksOutPfd = null
        singBoxProcess?.destroyForcibly()
        singBoxProcess?.waitFor(3, TimeUnit.SECONDS)
        singBoxProcess = null
        tunPfd?.close()
        tunPfd = null
        isRunning = false
        lastError = null
        onStatusChanged?.invoke(false, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        stopping = false
        AppLogger.i("VpnService", "VPN остановлен")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ── Notifications ──────────────────────────────────────────────────────────

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
