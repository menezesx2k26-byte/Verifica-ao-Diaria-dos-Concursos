package com.menezes.concursoswatch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppBg = Color(0xFF090A0F)
val AppPanel = Color(0xFF11131A)
val AppPanel2 = Color(0xFF181B24)
val AppPanel3 = Color(0xFF202430)
val AppPurple = Color(0xFFA970FF)
val AppPurple2 = Color(0xFF7E46D9)
val AppPurpleSoft = Color(0xFF241733)
val AppText = Color(0xFFF7F7FB)
val AppMuted = Color(0xFF9CA1AE)
val AppMuted2 = Color(0xFF737987)
val AppGreen = Color(0xFF63D48A)
val AppAmber = Color(0xFFFFBE68)
val AppRed = Color(0xFFFF7070)
val AppDivider = Color(0xFF252936)

private val Scheme = darkColorScheme(
    primary = AppPurple,
    onPrimary = Color(0xFF13091F),
    primaryContainer = AppPurpleSoft,
    onPrimaryContainer = AppText,
    secondary = AppPurple2,
    background = AppBg,
    onBackground = AppText,
    surface = AppPanel,
    onSurface = AppText,
    surfaceVariant = AppPanel2,
    onSurfaceVariant = AppMuted,
    outline = AppDivider,
    error = AppRed,
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
)

@Composable
fun ConcursosWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = AppTypography,
        content = content,
    )
}
