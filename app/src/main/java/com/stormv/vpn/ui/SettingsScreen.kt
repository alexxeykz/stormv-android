package com.stormv.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stormv.vpn.data.SettingsRepository
import com.stormv.vpn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var dnsPrimary by remember { mutableStateOf(SettingsRepository.dnsPrimary) }
    var dnsSecondary by remember { mutableStateOf(SettingsRepository.dnsSecondary) }
    var autoConnect by remember { mutableStateOf(SettingsRepository.autoConnectOnStart) }
    var bypassText by remember {
        mutableStateOf(SettingsRepository.bypassList.joinToString("\n"))
    }
    var vpnSitesText by remember {
        mutableStateOf(SettingsRepository.vpnSites.joinToString("\n"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SVBgDeep)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── Top Bar ──────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = SVTextPrimary)
            }
            Text(
                text = "Настройки",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = SVTextPrimary,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            IconButton(onClick = {
                dnsPrimary = "8.8.8.8"
                dnsSecondary = "8.8.4.4"
                autoConnect = false
                vpnSitesText = ""
                bypassText = "192.168.0.0/16\n10.0.0.0/8\n172.16.0.0/12"
            }) {
                Icon(Icons.Filled.RestartAlt, contentDescription = "Сбросить", tint = SVTextSecondary)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── DNS ──────────────────────────────────────────────────────────
            SettingsCard(title = "DNS серверы") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = dnsPrimary,
                        onValueChange = { dnsPrimary = it },
                        label = { Text("Основной") },
                        singleLine = true,
                        colors = svTextFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = dnsSecondary,
                        onValueChange = { dnsSecondary = it },
                        label = { Text("Запасной") },
                        singleLine = true,
                        colors = svTextFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Автоподключение ───────────────────────────────────────────────
            SettingsCard(title = "Подключение") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Автоподключение при запуске",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = SVTextPrimary
                        )
                        Text(
                            "Подключаться к последнему серверу",
                            fontSize = 12.sp,
                            color = SVTextSecondary
                        )
                    }
                    Switch(
                        checked = autoConnect,
                        onCheckedChange = { autoConnect = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = SVPurple,
                            uncheckedThumbColor = SVTextSecondary,
                            uncheckedTrackColor = SVBgItem,
                        )
                    )
                }
            }

            // ── Сайты через VPN ──────────────────────────────────────────────
            SettingsCard(title = "Сайты через VPN (в браузере)") {
                Text(
                    "Домены, которые браузер будет открывать через VPN.",
                    fontSize = 12.sp,
                    color = SVTextSecondary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Text(
                    "Один домен на строку, без https://  Пример: instagram.com",
                    fontSize = 11.sp,
                    color = SVTextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = vpnSitesText,
                    onValueChange = { vpnSitesText = it },
                    colors = svTextFieldColors(),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    placeholder = {
                        Text(
                            "instagram.com\nfacebook.com\ntwitter.com",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = SVTextSecondary.copy(alpha = 0.3f)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            }

            // ── Bypass ────────────────────────────────────────────────────────
            SettingsCard(title = "Bypass — не через VPN") {
                Text(
                    "Каждый CIDR или IP с новой строки",
                    fontSize = 12.sp,
                    color = SVTextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = bypassText,
                    onValueChange = { bypassText = it },
                    colors = svTextFieldColors(),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── Кнопка Сохранить ─────────────────────────────────────────────────
        Button(
            onClick = {
                SettingsRepository.dnsPrimary = dnsPrimary.trim()
                SettingsRepository.dnsSecondary = dnsSecondary.trim()
                SettingsRepository.autoConnectOnStart = autoConnect
                SettingsRepository.vpnSites = vpnSitesText
                    .split("\n")
                    .map { it.trim()
                        .removePrefix("https://")
                        .removePrefix("http://")
                        .trimEnd('/')
                    }
                    .filter { it.isNotBlank() }
                SettingsRepository.bypassList = bypassText
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                onBack()
            },
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SVPurple),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(52.dp)
        ) {
            Text("Сохранить", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SVBgCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 13.sp,
                color = SVTextSecondary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
private fun svTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = SVPurple,
    unfocusedBorderColor = SVTextSecondary.copy(alpha = 0.3f),
    focusedLabelColor = SVPurple,
    cursorColor = SVPurple,
    focusedTextColor = SVTextPrimary,
    unfocusedTextColor = SVTextPrimary,
)
