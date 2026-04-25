package com.stormv.vpn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stormv.vpn.model.ServerConfig
import com.stormv.vpn.ui.theme.*
import com.stormv.vpn.util.EncryptionHelper

@Composable
fun MainScreen(
    state: MainUiState,
    onConnect: () -> Unit,
    onSelectServer: (ServerConfig) -> Unit,
    onRemoveServer: (ServerConfig) -> Unit,
    onAddServer: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SVBgDeep)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── Логотип ──────────────────────────────────────────────────────────
        LogoHeader(onOpenSettings = onOpenSettings, onOpenLogs = onOpenLogs)

        // ── Список серверов ──────────────────────────────────────────────────
        ServerListCard(
            servers = state.servers,
            selectedServer = state.selectedServer,
            pingResults = state.pingResults,
            activeServerTag = state.activeServerTag,
            onSelect = onSelectServer,
            onRemove = onRemoveServer,
            onAdd = onAddServer,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
        )

        // ── Кнопка подключения + статус ──────────────────────────────────────
        ConnectCard(
            state = state,
            onConnect = onConnect,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        )
    }
}

// ── Logo ─────────────────────────────────────────────────────────────────────

@Composable
private fun LogoHeader(onOpenSettings: () -> Unit = {}, onOpenLogs: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 0.dp)
    ) {
        Row(modifier = Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = onOpenLogs) {
                Icon(
                    imageVector = Icons.Filled.List,
                    contentDescription = "Логи",
                    tint = SVTextSecondary
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Настройки",
                    tint = SVTextSecondary
                )
            }
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp)
    ) {
        // Логотип SV
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(100.dp)
                .shadow(elevation = 0.dp, shape = RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradientBrush(
                        colors = listOf(Color(0xFF1A1A2E), Color(0xFF16213E))
                    )
                )
        ) {
            // Свечение
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(SVPurple.copy(alpha = 0.3f), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )
            Text(
                text = "SV",
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                style = LocalTextStyle.current.copy(
                    brush = Brush.linearGradientBrush(
                        colors = listOf(SVPurple, SVPurpleLight, SVBlue)
                    )
                )
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "StormV",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = SVTextPrimary
        )
        Text(
            text = "Безопасный VPN",
            fontSize = 13.sp,
            color = SVTextSecondary
        )
    }
}

// ── Server List ───────────────────────────────────────────────────────────────

@Composable
private fun ServerListCard(
    servers: List<ServerConfig>,
    selectedServer: ServerConfig?,
    pingResults: Map<String, String>,
    activeServerTag: String?,
    onSelect: (ServerConfig) -> Unit,
    onRemove: (ServerConfig) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SVBgCard),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Заголовок + кнопка +
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Серверы",
                    fontSize = 14.sp,
                    color = SVTextSecondary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onAdd) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Добавить сервер",
                        tint = SVPurple
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (servers.isEmpty()) {
                // Пустое состояние
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Dns,
                        contentDescription = null,
                        tint = SVTextSecondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Нет серверов", color = SVTextSecondary)
                    Text(
                        "Нажмите + чтобы добавить",
                        fontSize = 12.sp,
                        color = SVTextSecondary.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(servers, key = { it.id }) { server ->
                        ServerItem(
                            server = server,
                            isSelected = server.id == selectedServer?.id,
                            isActive = activeServerTag != null && server.name == activeServerTag,
                            ping = pingResults[server.id] ?: "",
                            onClick = { onSelect(server) },
                            onRemove = { onRemove(server) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerItem(
    server: ServerConfig,
    isSelected: Boolean,
    isActive: Boolean,
    ping: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) SVPurple.copy(alpha = 0.12f) else SVBgItem,
        label = "itemBg"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // Протокол-бейдж
        Box(modifier = Modifier.size(width = 48.dp, height = 32.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradientBrush(
                            colors = listOf(SVPurple.copy(alpha = 0.2f), SVBlue.copy(alpha = 0.2f))
                        )
                    )
            ) {
                Text(
                    text = if (server.isAuto) "AUTO" else server.protocol.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    style = LocalTextStyle.current.copy(
                        brush = Brush.linearGradientBrush(listOf(SVPurple, SVBlue))
                    )
                )
            }
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(SVSuccess)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = SVTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!server.isAuto) {
                Text(
                    text = EncryptionHelper.maskSensitive(server.host),
                    fontSize = 12.sp,
                    color = SVTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Пинг
        if (ping.isNotEmpty()) {
            Text(
                text = ping,
                fontSize = 11.sp,
                color = when {
                    ping == "..." -> SVTextSecondary
                    ping == "—" -> SVError.copy(alpha = 0.7f)
                    ping.contains("ms") -> {
                        val ms = ping.removeSuffix(" ms").toIntOrNull() ?: 999
                        when {
                            ms < 100 -> SVSuccess
                            ms < 300 -> SVYellow
                            else -> SVError.copy(alpha = 0.8f)
                        }
                    }
                    else -> SVTextSecondary
                },
                modifier = Modifier.padding(end = 4.dp)
            )
        }

        // Выбран
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = SVPurple,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        // Удалить
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = "Удалить",
                tint = SVTextSecondary.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── Connect Card ──────────────────────────────────────────────────────────────

@Composable
private fun ConnectCard(
    state: MainUiState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SVBgCard),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Выбранный сервер
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Сервер: ", fontSize = 13.sp, color = SVTextSecondary)
                Text(
                    text = state.selectedServer?.displayName ?: "Не выбран",
                    fontSize = 13.sp,
                    color = SVTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Большая кнопка ПОДКЛЮЧИТЬ
            val isEnabled = state.selectedServer != null && state.status != VpnStatus.CONNECTING
            Button(
                onClick = onConnect,
                enabled = isEnabled,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isEnabled)
                                Brush.horizontalGradient(listOf(SVPurple, SVBlue))
                            else
                                Brush.horizontalGradient(listOf(Color(0xFF3A3A5C), Color(0xFF3A3A5C))),
                            shape = RoundedCornerShape(28.dp)
                        )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.VpnLock,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = when (state.status) {
                                VpnStatus.CONNECTED, VpnStatus.CONNECTING -> "ОТКЛЮЧИТЬ"
                                else -> "ПОДКЛЮЧИТЬ"
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Индикатор статуса
            StatusIndicator(state.status)

            // Ошибка
            if (state.status == VpnStatus.ERROR && state.errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                ErrorBox(state.errorMessage)
            }
        }
    }
}

@Composable
private fun StatusIndicator(status: VpnStatus) {
    val dotColor by animateColorAsState(
        targetValue = when (status) {
            VpnStatus.CONNECTED   -> SVSuccess
            VpnStatus.CONNECTING  -> SVYellow
            VpnStatus.ERROR       -> SVError
            VpnStatus.DISCONNECTED -> SVTextSecondary
        },
        label = "dotColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "spin"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        // Кружок
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (status == VpnStatus.CONNECTED) dotColor.copy(alpha = alpha)
                    else dotColor
                )
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = when (status) {
                VpnStatus.CONNECTED    -> "Подключено"
                VpnStatus.CONNECTING   -> "Подключение..."
                VpnStatus.DISCONNECTED -> "Отключено"
                VpnStatus.ERROR        -> "Ошибка"
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = when (status) {
                VpnStatus.CONNECTED   -> SVSuccess
                VpnStatus.ERROR       -> SVError
                else -> SVTextSecondary
            }
        )

        // Спиннер
        if (status == VpnStatus.CONNECTING) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = SVYellow,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(spinAngle)
            )
        }
    }
}

@Composable
private fun ErrorBox(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SVError.copy(alpha = 0.15f))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = SVError,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message,
            fontSize = 12.sp,
            color = SVError,
            modifier = Modifier.weight(1f)
        )
    }
}

// ── Extension ─────────────────────────────────────────────────────────────────
fun Brush.Companion.linearGradientBrush(colors: List<Color>) =
    linearGradient(colors)

fun Brush.Companion.horizontalGradient(colors: List<Color>) =
    horizontalGradient(colors)
