package com.stormv.vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stormv.vpn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerSheet(
    onAdd: (String) -> Boolean,
    onAddSubscription: (String, (Int, String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var pastedUrl by remember { mutableStateOf("") }
    val tabs = listOf("Ссылка", "Подписка")
    val clipboard = LocalClipboardManager.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SVBgCard,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Добавить сервер",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = SVTextPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Умная кнопка — определяет тип по содержимому буфера
            Button(
                onClick = {
                    val text = clipboard.getText()?.text?.trim() ?: return@Button
                    if (text.startsWith("http://") || text.startsWith("https://")) {
                        selectedTab = 1
                    } else {
                        selectedTab = 0
                    }
                    pastedUrl = text
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SVPurple),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Вставить из буфера", fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(12.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = SVBgDeep,
                contentColor = SVPurple,
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 14.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                0 -> ServerUrlTab(onAdd = onAdd, onDismiss = onDismiss, initialUrl = pastedUrl)
                1 -> SubscriptionTab(onAddSubscription = onAddSubscription, onDismiss = onDismiss, initialUrl = pastedUrl)
            }
        }
    }
}

@Composable
private fun ServerUrlTab(
    onAdd: (String) -> Boolean,
    onDismiss: () -> Unit,
    initialUrl: String = "",
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var error by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    Text(
        text = "VLESS · VMess · SS · Trojan · Hysteria2 · TUIC · WireGuard",
        fontSize = 12.sp,
        color = SVPurple
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = url,
        onValueChange = { url = it; error = "" },
        placeholder = { Text("vless://, ss://, trojan://...", color = SVTextSecondary.copy(alpha = 0.5f)) },
        label = { Text("Ссылка на сервер") },
        singleLine = false,
        maxLines = 4,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SVPurple,
            unfocusedBorderColor = SVTextSecondary.copy(alpha = 0.3f),
            focusedLabelColor = SVPurple,
            cursorColor = SVPurple,
            focusedTextColor = SVTextPrimary,
            unfocusedTextColor = SVTextPrimary,
        ),
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(10.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = {
                val text = clipboard.getText()?.text?.trim() ?: ""
                if (text.isNotEmpty()) { url = text; error = "" }
            },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SVPurple),
            border = androidx.compose.foundation.BorderStroke(1.dp, SVPurple),
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Из буфера", fontSize = 13.sp)
        }
        IconButton(
            onClick = { url = ""; error = "" },
            modifier = Modifier.size(40.dp).align(Alignment.CenterVertically)
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Очистить", tint = SVTextSecondary)
        }
    }

    if (error.isNotEmpty()) {
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = error, fontSize = 12.sp, color = SVError, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
    }

    Spacer(modifier = Modifier.height(20.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onDismiss,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SVTextSecondary),
            border = androidx.compose.foundation.BorderStroke(1.dp, SVTextSecondary.copy(alpha = 0.3f)),
            modifier = Modifier.weight(1f).height(48.dp)
        ) { Text("Отмена") }

        Button(
            onClick = {
                if (url.isBlank()) { error = "Введите ссылку"; return@Button }
                val ok = onAdd(url.trim())
                if (ok) onDismiss()
                else error = "Не удалось распознать ссылку.\nПроверьте формат и протокол."
            },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SVPurple),
            modifier = Modifier.weight(1f).height(48.dp)
        ) { Text("Добавить", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SubscriptionTab(
    onAddSubscription: (String, (Int, String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    initialUrl: String = "",
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Text(
        text = "Вставьте ссылку на подписку — все серверы добавятся автоматически",
        fontSize = 12.sp,
        color = SVTextSecondary
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = url,
        onValueChange = { url = it; error = "" },
        placeholder = { Text("http:// или https://...", color = SVTextSecondary.copy(alpha = 0.5f)) },
        label = { Text("URL подписки") },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SVPurple,
            unfocusedBorderColor = SVTextSecondary.copy(alpha = 0.3f),
            focusedLabelColor = SVPurple,
            cursorColor = SVPurple,
            focusedTextColor = SVTextPrimary,
            unfocusedTextColor = SVTextPrimary,
        ),
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(10.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = {
                val text = clipboard.getText()?.text?.trim() ?: ""
                if (text.isNotEmpty()) { url = text; error = "" }
            },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SVPurple),
            border = androidx.compose.foundation.BorderStroke(1.dp, SVPurple),
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Из буфера", fontSize = 13.sp)
        }
        IconButton(
            onClick = { url = ""; error = "" },
            modifier = Modifier.size(40.dp).align(Alignment.CenterVertically)
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Очистить", tint = SVTextSecondary)
        }
    }

    if (error.isNotEmpty()) {
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = error, fontSize = 12.sp, color = SVError, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
    }

    Spacer(modifier = Modifier.height(20.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onDismiss,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SVTextSecondary),
            border = androidx.compose.foundation.BorderStroke(1.dp, SVTextSecondary.copy(alpha = 0.3f)),
            modifier = Modifier.weight(1f).height(48.dp)
        ) { Text("Отмена") }

        Button(
            onClick = {
                if (url.isBlank()) { error = "Введите URL подписки"; return@Button }
                loading = true
                error = ""
                onAddSubscription(url.trim()) { count, err ->
                    loading = false
                    if (err != null) error = err
                    else onDismiss()
                }
            },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SVPurple),
            enabled = !loading,
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = SVTextPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Загрузить", fontWeight = FontWeight.Bold)
            }
        }
    }
}
