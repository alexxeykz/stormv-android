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
import com.stormv.vpn.model.ServerConfig
import com.stormv.vpn.service.StormVpnService
import com.stormv.vpn.util.AppLogger
import com.stormv.vpn.util.ConfigBuilder
import com.stormv.vpn.util.PingUtil
import com.stormv.vpn.util.SubscriptionManager
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
import java.net.URL

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class MainUiState(
    val servers: List<ServerConfig> = emptyList(),
    val selectedServer: ServerConfig? = null,
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val errorMessage: String? = null,
    val pingResults: Map<String, String> = emptyMap(),
    val activeServerTag: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val gson = Gson()
    private var pollJob: Job? = null

    init {
        loadServers()
        StormVpnService.onStatusChanged = { running, error ->
            val newStatus = if (running) VpnStatus.CONNECTED
                           else if (error != null) VpnStatus.ERROR
                           else VpnStatus.DISCONNECTED
            _state.value = _state.value.copy(
                status = newStatus,
                errorMessage = error,
                activeServerTag = if (!running) null else _state.value.activeServerTag
            )
            if (running && _state.value.selectedServer?.isSubscription == true) {
                startPollingActiveServer()
            } else if (!running) {
                pollJob?.cancel()
                pollJob = null
                _state.value = _state.value.copy(activeServerTag = null)
            }
        }
    }

    private fun loadServers() {
        viewModelScope.launch {
            val all = ServerRepository.loadAll()
            // isAuto servers are hidden from the UI list
            val visible = all.filter { !it.isAuto }
            _state.value = _state.value.copy(
                servers = visible,
                selectedServer = visible.firstOrNull()
            )
            pingAll(visible)
        }
    }

    fun pingAll(servers: List<ServerConfig> = _state.value.servers) {
        viewModelScope.launch {
            val pingable = servers.filter { !it.isAuto }
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
                val autoServer = servers.firstOrNull { it.isAuto }
                // Keep manual (non-subscription) servers, replace subscription ones
                val manual = ServerRepository.loadAll().filter { !it.isAuto && !it.isSubscription }
                val newList = if (autoServer != null) {
                    listOf(autoServer) + servers.filter { !it.isAuto } + manual
                } else {
                    manual + servers
                }
                ServerRepository.saveAll(newList)
                val visible = newList.filter { !it.isAuto }
                _state.value = _state.value.copy(
                    servers = visible,
                    selectedServer = visible.firstOrNull { it.isSubscription }
                        ?: _state.value.selectedServer
                        ?: visible.firstOrNull()
                )
                val count = autoServer?.serverCount ?: servers.size
                AppLogger.i("UI", "Подписка: $count серверов")
                onResult(count, null)
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
        val updated = ServerRepository.loadAll().filter { !it.isAuto }
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
        // Subscription servers → use the hidden auto config (urltest)
        val serverToUse = if (server.isSubscription) {
            ServerRepository.loadAll().firstOrNull { it.isAuto } ?: server
        } else {
            server
        }
        val label = if (serverToUse.isAuto) "Auto [${serverToUse.serverCount} серв.]"
                    else serverToUse.displayName
        AppLogger.i("UI", "Подключение → $label")
        _state.value = _state.value.copy(status = VpnStatus.CONNECTING, errorMessage = null)
        val intent = Intent(getApplication(), StormVpnService::class.java).apply {
            action = StormVpnService.ACTION_START
            putExtra(StormVpnService.EXTRA_SERVER, gson.toJson(serverToUse))
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun disconnect() {
        AppLogger.i("UI", "Отключение...")
        pollJob?.cancel()
        pollJob = null
        val intent = Intent(getApplication(), StormVpnService::class.java).apply {
            action = StormVpnService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
        _state.value = _state.value.copy(
            status = VpnStatus.DISCONNECTED,
            errorMessage = null,
            activeServerTag = null
        )
    }

    private fun startPollingActiveServer() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            delay(2000) // wait for sing-box to start
            while (true) {
                val tag = fetchActiveServerTag()
                if (tag != null && tag != _state.value.activeServerTag) {
                    _state.value = _state.value.copy(activeServerTag = tag)
                    AppLogger.i("UI", "Активный сервер: $tag")
                }
                delay(3000)
            }
        }
    }

    private suspend fun fetchActiveServerTag(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("http://127.0.0.1:${ConfigBuilder.CLASH_API_PORT}/proxies/auto")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val json = conn.inputStream.bufferedReader().readText()
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = Gson().fromJson(json, type)
            map["now"] as? String
        }.getOrNull()
    }

    override fun onCleared() {
        StormVpnService.onStatusChanged = null
        pollJob?.cancel()
        super.onCleared()
    }
}
