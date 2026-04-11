package com.stormv.vpn.ui

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.stormv.vpn.data.ServerRepository
import com.stormv.vpn.model.ServerConfig
import com.stormv.vpn.service.StormVpnService
import com.stormv.vpn.util.AppLogger
import com.stormv.vpn.util.PingUtil
import com.stormv.vpn.util.SubscriptionManager
import com.stormv.vpn.util.UrlParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class MainUiState(
    val servers: List<ServerConfig> = emptyList(),
    val selectedServer: ServerConfig? = null,
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val errorMessage: String? = null,
    val pingResults: Map<String, String> = emptyMap(), // id → "45 ms" / "—" / "..."
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val gson = Gson()

    init {
        loadServers()
        // Слушаем статус VPN сервиса
        StormVpnService.onStatusChanged = { running, error ->
            _state.value = _state.value.copy(
                status = if (running) VpnStatus.CONNECTED
                         else if (error != null) VpnStatus.ERROR
                         else VpnStatus.DISCONNECTED,
                errorMessage = error
            )
        }
    }

    private fun loadServers() {
        viewModelScope.launch {
            val servers = ServerRepository.loadAll()
            _state.value = _state.value.copy(
                servers = servers,
                selectedServer = servers.firstOrNull()
            )
            pingAll(servers)
        }
    }

    fun pingAll(servers: List<ServerConfig> = _state.value.servers) {
        viewModelScope.launch {
            // Показываем "..." сразу
            _state.value = _state.value.copy(
                pingResults = servers.associate { it.id to "..." }
            )
            // Пингуем параллельно
            val results = servers.map { server ->
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
        val updated = ServerRepository.loadAll()
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
                servers.forEach { ServerRepository.add(it) }
                val updated = ServerRepository.loadAll()
                _state.value = _state.value.copy(
                    servers = updated,
                    selectedServer = _state.value.selectedServer ?: servers.firstOrNull()
                )
                AppLogger.i("UI", "Подписка: добавлено ${servers.size} серверов")
                onResult(servers.size, null)
            }.onFailure { e ->
                AppLogger.e("UI", "Ошибка подписки: ${e.message}")
                onResult(0, e.message)
            }
        }
    }

    fun removeServer(server: ServerConfig) {
        if (_state.value.status == VpnStatus.CONNECTED &&
            _state.value.selectedServer?.id == server.id) {
            disconnect()
        }
        ServerRepository.remove(server.id)
        val updated = ServerRepository.loadAll()
        _state.value = _state.value.copy(
            servers = updated,
            selectedServer = if (_state.value.selectedServer?.id == server.id)
                updated.firstOrNull() else _state.value.selectedServer
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

        // Проверяем разрешение VPN
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
        AppLogger.i("UI", "Подключение → ${server.displayName} [${server.protocol}]")
        _state.value = _state.value.copy(status = VpnStatus.CONNECTING, errorMessage = null)
        val intent = Intent(getApplication(), StormVpnService::class.java).apply {
            action = StormVpnService.ACTION_START
            putExtra(StormVpnService.EXTRA_SERVER, gson.toJson(server))
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun disconnect() {
        AppLogger.i("UI", "Отключение...")
        val intent = Intent(getApplication(), StormVpnService::class.java).apply {
            action = StormVpnService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
        _state.value = _state.value.copy(status = VpnStatus.DISCONNECTED, errorMessage = null)
    }

    override fun onCleared() {
        StormVpnService.onStatusChanged = null
        super.onCleared()
    }
}
