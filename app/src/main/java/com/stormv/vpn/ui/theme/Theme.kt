package com.stormv.vpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── StormV Brand Colors ──────────────────────────────────────────────────────
val SVPurple      = Color(0xFF7B61FF)
val SVPurpleLight = Color(0xFFA78BFA)
val SVBlue        = Color(0xFF60A5FA)
val SVBgDeep      = Color(0xFF0D0D1A)
val SVBgCard      = Color(0xFF161628)
val SVBgItem      = Color(0xFF1E1E35)
val SVSuccess     = Color(0xFF4ADE80)
val SVError       = Color(0xFFFF4444)
val SVTextPrimary = Color(0xFFFFFFFF)
val SVTextSecondary = Color(0xFF9CA3AF)
val SVYellow      = Color(0xFFFBBF24)

private val DarkColorScheme = darkColorScheme(
    primary           = SVPurple,
    onPrimary         = Color.White,
    primaryContainer  = Color(0xFF2D1F6E),
    secondary         = SVBlue,
    onSecondary       = Color.White,
    background        = SVBgDeep,
    onBackground      = SVTextPrimary,
    surface           = SVBgCard,
    onSurface         = SVTextPrimary,
    surfaceVariant    = SVBgItem,
    onSurfaceVariant  = SVTextSecondary,
    error             = SVError,
    onError           = Color.White,
)

@Composable
fun StormVTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
