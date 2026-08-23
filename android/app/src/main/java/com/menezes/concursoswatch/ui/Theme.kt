package com.menezes.concursoswatch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AppBg = Color(0xFF070A0F)
val AppPanel = Color(0xFF11161D)
val AppPanel2 = Color(0xFF171B24)
val AppPurple = Color(0xFFB05CFF)
val AppPurple2 = Color(0xFF6A23BE)
val AppText = Color(0xFFF5F6FA)
val AppMuted = Color(0xFFA4A8B4)
val AppGreen = Color(0xFF52D664)
val AppRed = Color(0xFFFF6B6B)

private val Scheme = darkColorScheme(
    primary = AppPurple,
    onPrimary = Color.White,
    secondary = AppPurple2,
    background = AppBg,
    onBackground = AppText,
    surface = AppPanel,
    onSurface = AppText,
    surfaceVariant = AppPanel2,
    onSurfaceVariant = AppMuted,
    error = AppRed,
)

@Composable
fun ConcursosWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
