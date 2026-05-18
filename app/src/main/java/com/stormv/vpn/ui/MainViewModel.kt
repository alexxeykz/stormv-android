package com.stormv.vpn.ui

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stormv.vpn.data.ServerRepository
import com.stormv.vpn.data.SettingsRepository
import com.stormv.vpn.model.ServerConfig
import com.stormv.vpn.service.StormVpnService
import com.stormv.vpn.util.AppLogger
import com.stormv.vpn.util.ConfigBuilder
import com.stormv.vpn.util.PingUtil
import com.stormv.vpn.util.SubscriptionManager
import com.stormv.vpn.util.UpdateInfo
import com.stormv.vpn.util.UpdateManager
import com.stormv.vpn.util.UrlParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import com.google.gson.JsonParser

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
enum class AppHealth { UNKNOWN, OK, DOWN }

data class MainUiState(
    val servers: List<ServerConfig> = emptyList(),
    val selectedServer: ServerConfig? = null,
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val errorMessage: String? = null,
    val pingResults: Map<String, String> = emptyMap(),
    val activeServerTag: String? = null,
    val telegramHealth: AppHealth = AppHealth.UNKNOWN,
    val youtubeHealth: AppHealth = AppHealth.UNKNOWN,
    val updateInfo: UpdateInfo? = null,
    val updateDownloadProgress: Int = -1,
    val isRefreshingSubscription: Boolean = false,
    val subscriptionUrl: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val gson = Gson()
    private var pollJob: Job? = null
    private var healthJob: Job? = null
    private var healthFailCount = 0
    @Volatile private var isReconnecting = false

    init {
        loadServers()
        _state.value = _state.value.copy(subscriptionUrl = SettingsRepository.subscriptionUrl)
        checkForUpdate()
        autoRefreshSubscriptionIfNeeded()
        StormVpnService.onStatusChanged = { running, error ->
            val newStatus = if (running) VpnStatus.CONNECTED
                           else if (error != null) VpnStatus.ERROR
                           else VpnStatus.DISCONNECTED
            _state.value = _state.value.copy(
                status = newStatus,
                errorMessage = error,
                activeServerTag = if (!running) null else _state.value.activeServerTag
            )
            if (running) {
                // Clash API polling только в Auto-режиме (urltest)
                if (_state.value.selectedServer?.isAuto == true) {
                    startPollingActiveServer()
                }
                startHealthMonitoring()
            } else {
                pollJob?.cancel()
                pollJob = null
                healthJob?.cancel()
                healthJob = null
                isReconnecting = false
                _state.value = _state.value.copy(
                    activeServerTag = null,
                    telegramHealth = AppHealth.UNKNOWN,
                    youtubeHealth = AppHealth.UNKNOWN
                )
            }
        }
    }

    private fun loadServers() {
        viewModelScope.launch {
            val all = ServerRepository.loadAll()
            // Auto server goes first, then subscription servers, then manual
            val sorted = all.sortedWith(compareByDescending<ServerConfig> { it.isAuto }.thenBy { it.name })
            _state.value = _state.value.copy(
                servers = sorted,
                selectedServer = sorted.firstOrNull { it.isAuto } ?: sorted.firstOrNull()
            )
            pingAll(sorted)
        }
    }

    fun pingAll(servers: List<ServerConfig> = _state.value.servers) {
        viewModelScope.launch {
            val pingable = servers.filter { !it.isAuto && it.host.isNotBlank() }
            _state.value = _state.value.copy(
                pingResults = pingable.associate { it.id to "..." }
            )
            val results = pingable.map { server ->
                async {
                    val ms = PingUtil.ping(server.host, server.port)
                    server.id to (if (ms != null) "$ms ms" else "—")
                }
            }.awaitAll().toMap()
            _state.value = _state.value.copy(pingResults = results)
        }
    }

    fun selectServer(server: ServerConfig) {
        _state.value = _state.value.copy(selectedServer = server)
    }

    fun addServerFromUrl(url: String): Boolean {
        val server = UrlParser.parse(url) ?: return false
        ServerRepository.add(server)
        val updated = ServerRepository.loadAll().filter { !it.isAuto }
        _state.value = _state.value.copy(
            servers = updated,
            selectedServer = _state.value.selectedServer ?: server
        )
        AppLogger.i("UI", "Добавлен сервер: ${server.displayName}")
        return true
    }

    fun addServerFromClipboard(clipText: String): Boolean = addServerFromUrl(clipText)

    fun addSubscription(url: String, onResult: (Int, String?) -> Unit) {
        viewModelScope.launch {
            val result = SubscriptionManager.fetch(url)
            result.onSuccess { servers ->
                SettingsRepository.subscriptionUrl = url
                _state.value = _state.value.copy(subscriptionUrl = url)
                applySubscriptionServers(servers)
                val count = servers.firstOrNull { it.isAuto }?.serverCount ?: servers.size
                AppLogger.i("UI", "Подписка: $count серверов")
                onResult(count, null)
            }.onFailure { e ->
                AppLogger.e("UI", "Ошибка подписки: ${e.message}")
                onResult(0, e.message)
            }
        }
    }

    fun refreshSubscription() {
        val url = SettingsRepository.subscriptionUrl
        if (url.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshingSubscription = true)
            val result = SubscriptionManager.fetch(url)
            result.onSuccess { servers ->
                applySubscriptionServers(servers)
                SettingsRepository.lastSubscriptionUpdate = System.currentTimeMillis()
                val count = servers.firstOrNull { it.isAuto }?.serverCount ?: servers.size
                AppLogger.i("UI", "Подписка обновлена: $count серверов")
            }.onFailure { e ->
                AppLogger.e("UI", "Ошибка обновления подписки: ${e.message}")
            }
            _state.value = _state.value.copy(isRefreshingSubscription = false)
        }
    }

    private fun autoRefreshSubscriptionIfNeeded() {
        val url = SettingsRepository.subscriptionUrl
        if (url.isBlank()) return
        val elapsed = System.currentTimeMillis() - SettingsRepository.lastSubscriptionUpdate
        if (elapsed < 12 * 60 * 60 * 1000L) return
        viewModelScope.launch {
            val result = SubscriptionManager.fetch(url)
            result.onSuccess { servers ->
                applySubscriptionServers(servers)
                SettingsRepository.lastSubscriptionUpdate = System.currentTimeMillis()
                AppLogger.i("UI", "Авто-обновление подписки: ${servers.size} серверов")
            }.onFailure { e ->
                AppLogger.e("UI", "Авто-обновление подписки не удалось: ${e.message}")
            }
        }
    }

    private fun applySubscriptionServers(servers: List<com.stormv.vpn.model.ServerConfig>) {
        val autoServer = servers.firstOrNull { it.isAuto }
        val newSubTags = servers.filter { !it.isAuto }.map { it.displayName }.toSet()
        val manual = ServerRepository.loadAll()
            .filter { !it.isAuto && !it.isSubscription && it.displayName !in newSubTags }
        val newList = if (autoServer != null) {
            listOf(autoServer) + servers.filter { !it.isAuto } + manual
        } else {
            manual + servers
        }
        ServerRepository.saveAll(newList)
        val sorted = newList.sortedWith(compareByDescending<ServerConfig> { it.isAuto }.thenBy { it.name })
        _state.value = _state.value.copy(
            servers = sorted,
            // Сохраняем текущий выбор; при первом добавлении подписки — выбираем Auto
            selectedServer = _state.value.selectedServer?.let { cur ->
                sorted.firstOrNull { it.id == cur.id }
            } ?: sorted.firstOrNull { it.isAuto } ?: sorted.firstOrNull()
        )
    }

    fun removeServer(server: ServerConfig) {
        if (server.isAuto) return // Auto сервер нельзя удалить напрямую — управляется подпиской
        if (_state.value.status == VpnStatus.CONNECTED &&
            _state.value.selectedServer?.id == server.id) {
            disconnect()
        }
        ServerRepository.remove(server.id)
        val updated = ServerRepository.loadAll()
            .sortedWith(compareByDescending<ServerConfig> { it.isAuto }.thenBy { it.name })
        _state.value = _state.value.copy(
            servers = updated,
            selectedServer = if (_state.value.selectedServer?.id == server.id)
                updated.firstOrNull { it.isAuto } ?: updated.firstOrNull()
            else _state.value.selectedServer
        )
    }

    fun toggleConnection(vpnPermLauncher: ActivityResultLauncher<Intent>) {
        val st = _state.value
        when (st.status) {
            VpnStatus.CONNECTED, VpnStatus.CONNECTING -> disconnect()
            else -> connect(vpnPermLauncher)
        }
    }

    fun connect(vpnPermLauncher: ActivityResultLauncher<Intent>) {
        val server = _state.value.selectedServer ?: return
        val permIntent = VpnService.prepare(getApplication())
        if (permIntent != null) {
            vpnPermLauncher.launch(permIntent)
            return
        }
        startVpnService(server)
    }

    fun onVpnPermissionGranted() {
        val server = _state.value.selectedServer ?: return
        startVpnService(server)
    }

    private fun startVpnService(server: ServerConfig) {
        val label = when {
            server.isAuto -> "Auto [${server.serverCount} серв.]"
            else -> server.displayName
        }
        AppLogger.i("UI", "Подключение → $label")
        _state.value = _state.value.copy(status = VpnStatus.CONNECTING, errorMessage = null)
        val intent = Intent(getApplication(), StormVpnService::class.java).apply {
            action = StormVpnService.ACTION_START
            putExtra(StormVpnService.EXTRA_SERVER, gson.toJson(server))
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun disconnect() {
        AppLogger.i("UI", "Отключение...")
        pollJob?.cancel()
        pollJob = null
        healthJob?.cancel()
        healthJob = null
        isReconnecting = false
        val intent = Intent(getApplication(), StormVpnService::class.java).apply {
            action = StormVpnService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
        _state.value = _state.value.copy(
            status = VpnStatus.DISCONNECTED,
            errorMessage = null,
            activeServerTag = null,
            telegramHealth = AppHealth.UNKNOWN,
            youtubeHealth = AppHealth.UNKNOWN
        )
    }

    private fun startHealthMonitoring() {
        healthJob?.cancel()
        healthFailCount = 0
        healthJob = viewModelScope.launch {
            delay(15_000) // дать VPN стабилизироваться
            while (true) {
                val (tgOk, ytOk) = checkAppHealth()
                _state.value = _state.value.copy(
                    telegramHealth = if (tgOk) AppHealth.OK else AppHealth.DOWN,
                    youtubeHealth  = if (ytOk) AppHealth.OK else AppHealth.DOWN
                )
                val tgSign = if (tgOk) "✓" else "✗"
                val ytSign = if (ytOk) "✓" else "✗"
                AppLogger.i("Health", "Telegram=$tgOk YouTube=$ytOk")
                StormVpnService.onRequestNotificationUpdate?.invoke("Telegram $tgSign  YouTube $ytSign")
                if (!tgOk && !ytOk) {
                    healthFailCount++
                    AppLogger.w("Health", "Telegram+YouTube недоступны (попытка $healthFailCount/2)")
                    if (healthFailCount >= 2) {
                        healthFailCount = 0
                        triggerReconnect()
                    }
                } else {
                    healthFailCount = 0
                }
                delay(30_000)
            }
        }
    }

    private suspend fun checkAppHealth(): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        // Проверяем через SOCKS5 прокси (sing-box) с реальной загрузкой данных —
        // HEAD только проверяет TCP+TLS handshake, но видео/сообщения могут не идти.
        // GET с чтением тела доказывает что VPN реально передаёт контент.
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", ConfigBuilder.PROXY_PORT))

        fun testWithData(urlStr: String, minBytes: Int = 256): Boolean = runCatching {
            val conn = URL(urlStr).openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code !in 200..499) return@runCatching false
            var totalRead = 0
            val buf = ByteArray(4096)
            val stream = conn.inputStream
            while (totalRead < minBytes * 2) {
                val n = stream.read(buf)
                if (n < 0) break
                totalRead += n
            }
            conn.disconnect()
            totalRead >= minBytes
        }.getOrElse { false }

        // Telegram: favicon.ico (~1.5 KB PNG) — реальная передача бинарных данных
        val tgOk = testWithData("https://telegram.org/favicon.ico")
        // YouTube: миниатюра видео (~5 KB JPEG) — реальный медиаконтент
        val ytOk = testWithData("https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg")
        tgOk to ytOk
    }

    private fun triggerReconnect() {
        if (isReconnecting) return
        isReconnecting = true
        val server = _state.value.selectedServer ?: run { isReconnecting = false; return }
        AppLogger.w("Health", "Переподключение (Telegram+YouTube недоступны)...")
        viewModelScope.launch {
            val stopIntent = Intent(getApplication(), StormVpnService::class.java).apply {
                action = StormVpnService.ACTION_STOP
            }
            getApplication<Application>().startService(stopIntent)
            delay(2500)
            startVpnService(server)
            isReconnecting = false
        }
    }

    private fun startPollingActiveServer() {
        pollJob?.cancel()
        val autoServer = _state.value.selectedServer?.takeIf { it.isAuto } ?: return
        val urlTestTag = extractUrlTestTag(autoServer.singboxConfig)
        pollJob = viewModelScope.launch {
            delay(2000)
            while (true) {
                val tag = fetchActiveServerTag(urlTestTag)
                if (tag != null && tag != _state.value.activeServerTag) {
                    _state.value = _state.value.copy(activeServerTag = tag)
                    AppLogger.i("UI", "Активный сервер (urltest): $tag")
                }
                delay(3000)
            }
        }
    }

    private fun extractUrlTestTag(singboxConfig: String): String = runCatching {
        JsonParser.parseString(singboxConfig).asJsonObject
            .getAsJsonArray("outbounds")
            ?.firstOrNull { it.isJsonObject && it.asJsonObject.get("type")?.asString == "urltest" }
            ?.asJsonObject?.get("tag")?.asString ?: "auto"
    }.getOrDefault("auto")

    private suspend fun fetchActiveServerTag(urlTestTag: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("http://127.0.0.1:${ConfigBuilder.CLASH_API_PORT}/proxies/$urlTestTag")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val json = conn.inputStream.bufferedReader().readText()
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = Gson().fromJson(json, type)
            map["now"] as? String
        }.getOrNull()
    }

    // ── Обновления ────────────────────────────────────────────────────────────

    private fun checkForUpdate() {
        viewModelScope.launch {
            val info = UpdateManager.checkForUpdate()
            if (info != null) {
                _state.value = _state.value.copy(updateInfo = info)
                AppLogger.i("Update", "Доступна версия ${info.versionName}")
            }
        }
    }

    fun dismissUpdate() {
        _state.value = _state.value.copy(updateInfo = null)
    }

    fun downloadUpdate(context: android.content.Context) {
        val info = _state.value.updateInfo ?: return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(updateDownloadProgress = 0)
                UpdateManager.downloadAndInstall(context, info) { progress ->
                    _state.value = _state.value.copy(updateDownloadProgress = progress)
                }
            } catch (e: Exception) {
                AppLogger.e("Update", "Ошибка: ${e.message}")
            } finally {
                _state.value = _state.value.copy(updateDownloadProgress = -1)
            }
        }
    }

    override fun onCleared() {
        StormVpnService.onStatusChanged = null
        pollJob?.cancel()
        super.onCleared()
    }
}
