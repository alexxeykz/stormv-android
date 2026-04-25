package com.stormv.vpn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stormv.vpn.ui.AddServerSheet
import com.stormv.vpn.ui.LogScreen
import com.stormv.vpn.ui.MainScreen
import com.stormv.vpn.ui.MainViewModel
import com.stormv.vpn.ui.SettingsScreen
import com.stormv.vpn.ui.theme.StormVTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) vm.onVpnPermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Обработка intent-ссылок (vless://, ss://, ...)
        handleIntent(intent)

        setContent {
            StormVTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                var showAddSheet by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }
                var showLogs by remember { mutableStateOf(false) }

                when {
                    showSettings -> SettingsScreen(onBack = { showSettings = false })
                    showLogs     -> LogScreen(onBack = { showLogs = false })
                    else -> MainScreen(
                        state = state,
                        onConnect = { vm.toggleConnection(vpnPermLauncher) },
                        onSelectServer = { vm.selectServer(it) },
                        onRemoveServer = { vm.removeServer(it) },
                        onAddServer = { showAddSheet = true },
                        onOpenSettings = { showSettings = true },
                        onOpenLogs = { showLogs = true },
                        onDownloadUpdate = { vm.downloadUpdate(this@MainActivity) },
                        onDismissUpdate = { vm.dismissUpdate() },
                    )
                }

                if (showAddSheet) {
                    AddServerSheet(
                        onAdd = { url -> vm.addServerFromUrl(url) },
                        onAddSubscription = { url, cb -> vm.addSubscription(url, cb) },
                        onDismiss = { showAddSheet = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (data.isNotBlank()) vm.addServerFromUrl(data)
    }
}
