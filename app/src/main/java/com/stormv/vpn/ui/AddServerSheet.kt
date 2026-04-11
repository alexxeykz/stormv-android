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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stormv.vpn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerSheet(
    onAdd: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
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
            // Заголовок
            Text(
                text = "Добавить сервер",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = SVTextPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "VLESS · VMess · SS · Trojan · Hysteria2 · TUIC · WireGuard",
                fontSize = 12.sp,
                color = SVPurple
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Поле ввода URL
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = "" },
                placeholder = {
                    Text(
                        "vless://, ss://, trojan://...",
                        color = SVTextSecondary.copy(alpha = 0.5f)
                    )
                },
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

            // Кнопки действий
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Вставить из буфера
                OutlinedButton(
                    onClick = {
                        val text = clipboard.getText()?.text?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            url = text
                            error = ""
                        }
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

                // Очистить
                IconButton(
                    onClick = { url = ""; error = "" },
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.CenterVertically)
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Очистить",
                        tint = SVTextSecondary
                    )
                }
            }

            // Ошибка
            if (error.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = error,
                    fontSize = 12.sp,
                    color = SVError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Кнопки Отмена / Добавить
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SVTextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SVTextSecondary.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("Отмена")
                }

                Button(
                    onClick = {
                        if (url.isBlank()) {
                            error = "Введите ссылку"
                            return@Button
                        }
                        val ok = onAdd(url.trim())
                        if (ok) onDismiss()
                        else error = "Не удалось распознать ссылку.\nПроверьте формат и протокол."
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SVPurple),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("Добавить", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
