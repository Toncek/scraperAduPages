package com.harbor.tv.core.tvui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object HarborColors {
    val Background = Color(0xFF0B0F19)
    val Surface = Color(0xFF111827)
    val SurfaceCard = Color(0xFF1E293B)
    val SurfaceCardFocused = Color(0xFF273449)
    val SurfaceElevated = Color(0xFF162032)
    val SurfaceGlass = Color(0xE60F172A)

    val FocusCyan = Color(0xFF38BDF8)
    val FocusGlow = Color(0x4438BDF8)
    val AccentTeal = Color(0xFF2DD4BF)
    val Gold4K = Color(0xFFF59E0B)
    val QuadHdViolet = Color(0xFFC084FC)
    val SuccessGreen = Color(0xFF34D399)
    val WarningAmber = Color(0xFFFBBF24)
    val DangerRed = Color(0xFFF87171)
    val PrivatePurple = Color(0xFFA78BFA)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
    val BorderSubtle = Color(0xFF334155)
    val Scrim = Color(0xCC060911)
}

private val HarborDarkColorScheme = darkColorScheme(
    primary = HarborColors.FocusCyan,
    onPrimary = HarborColors.Background,
    secondary = HarborColors.AccentTeal,
    onSecondary = HarborColors.Background,
    tertiary = HarborColors.PrivatePurple,
    background = HarborColors.Background,
    onBackground = HarborColors.TextPrimary,
    surface = HarborColors.Surface,
    onSurface = HarborColors.TextPrimary,
    surfaceVariant = HarborColors.SurfaceCard,
    onSurfaceVariant = HarborColors.TextSecondary,
    error = HarborColors.DangerRed,
    onError = HarborColors.Background,
    outline = HarborColors.BorderSubtle
)

private val HarborTvTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold,
        color = HarborColors.TextPrimary
    ),
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        color = HarborColors.TextPrimary
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        color = HarborColors.TextPrimary
    ),
    titleLarge = TextStyle(
        fontSize = 19.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        color = HarborColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
        color = HarborColors.TextPrimary
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        color = HarborColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        color = HarborColors.TextSecondary
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = HarborColors.TextPrimary
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        color = HarborColors.TextSecondary
    )
)

@Composable
fun HarborTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HarborDarkColorScheme,
        typography = HarborTvTypography,
        content = content
    )
}
