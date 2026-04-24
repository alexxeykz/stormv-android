package com.stormv.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stormv.vpn.util.AppLogger
import com.stormv.vpn.util.LogEntry
import com.stormv.vpn.util.LogLevel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val allEntries by AppLogger.flow.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var minLevel by remember { mutableStateOf(LogLevel.INFO) }
    var search by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    var levelMenuExpanded by remember { mutableStateOf(false) }

    val filtered = remember(allEntries, minLevel, search) {
        AppLogger.getFiltered(minLevel, search)
    }

    // Автопрокрутка
    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A14))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── Top Bar ──────────────────────────────────────────────────────────
        Surface(color = Color(0xFF111122), tonalElevation = 2.dp) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Назад", tint = Color(0xFF9CA3AF))
                    }
                    Text(
                        "Логи подключения",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    // Кол-во записей
                    Text(
                        "${filtered.size}",
                        fontSize = 12.sp,
                        color = Color(0xFF6B7280),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    // Автопрокрутка
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            if (autoScroll) Icons.Filled.VerticalAlignBottom
                            else Icons.Filled.VerticalAlignCenter,
                            "Автопрокрутка",
                            tint = if (autoScroll) Color(0xFF7B61FF) else Color(0xFF6B7280)
                        )
                    }
                    // Копировать
                    IconButton(onClick = {
                        val text = AppLogger.copyToString(minLevel, search)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("StormV Logs", text))
                    }) {
                        Icon(Icons.Filled.ContentCopy, "Копировать", tint = Color(0xFF6B7280))
                    }
                    // Очистить
                    IconButton(onClick = { AppLogger.clear() }) {
                        Icon(Icons.Filled.DeleteOutline, "Очистить", tint = Color(0xFF6B7280))
                    }
                }

                // Строка фильтров
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Фильтр уровня
                    Box {
                        FilterChip(
                            selected = true,
                            onClick = { levelMenuExpanded = true },
                            label = {
                                Text(
                                    when (minLevel) {
                                        LogLevel.DEBUG   -> "DEBUG+"
                                        LogLevel.INFO    -> "INFO+"
                                        LogLevel.WARNING -> "WARN+"
                                        LogLevel.ERROR   -> "ERROR"
                                    },
                                    fontSize = 12.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF7B61FF).copy(alpha = 0.2f),
                                selectedLabelColor = Color(0xFF7B61FF)
                            )
                        )
                        DropdownMenu(
                            expanded = levelMenuExpanded,
                            onDismissRequest = { levelMenuExpanded = false },
                            containerColor = Color(0xFF1E1E35)
                        ) {
                            LogLevel.entries.forEach { level ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            level.label,
                                            color = levelColor(level),
                                            fontFamily = FontFamily.Monospace
                                        )
                                    },
                                    onClick = { minLevel = level; levelMenuExpanded = false }
                                )
                            }
                        }
                    }

                    // Поиск
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Поиск...", fontSize = 12.sp, color = Color(0xFF4B5563)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF7B61FF),
                            unfocusedBorderColor = Color(0xFF374151),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF7B61FF),
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    )

                    // Прокрутить вниз
                    IconButton(onClick = {
                        coroutineScope.launch {
                            if (filtered.isNotEmpty())
                                listState.animateScrollToItem(filtered.size - 1)
                        }
                    }) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Вниз", tint = Color(0xFF6B7280))
                    }
                }
            }
        }

        // ── Log list ──────────────────────────────────────────────────────────
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Нет записей", color = Color(0xFF4B5563), fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(filtered, key = { it.id }) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Время
        Text(
            text = fmt.format(entry.time),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFF4B5563),
            modifier = Modifier.width(80.dp)
        )

        // Уровень
        Text(
            text = entry.level.label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = levelColor(entry.level),
            modifier = Modifier.width(36.dp)
        )

        // Тег
        Text(
            text = "[${entry.tag}]",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFFA78BFA),
            modifier = Modifier.width(90.dp),
            maxLines = 1
        )

        // Сообщение
        Text(
            text = entry.message,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = when (entry.level) {
                LogLevel.ERROR   -> Color(0xFFFF6B6B)
                LogLevel.WARNING -> Color(0xFFFBBF24)
                LogLevel.INFO    -> Color(0xFFE5E7EB)
                LogLevel.DEBUG   -> Color(0xFF6B7280)
            },
            modifier = Modifier.weight(1f),
            softWrap = true
        )
    }
}

private fun levelColor(level: LogLevel) = when (level) {
    LogLevel.DEBUG   -> Color(0xFF6B7280)
    LogLevel.INFO    -> Color(0xFF60A5FA)
    LogLevel.WARNING -> Color(0xFFFBBF24)
    LogLevel.ERROR   -> Color(0xFFFF4444)
}
