package com.sparkhoward.nagomiani.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Nagomi 樱粉主题：色值与 mac 版 Theme.swift 一致。
 * 播放器画面区域永远纯黑（影院），粉色只出现在控件上。
 */
object NagomiColors {
    val accent = Color(0xFFEC6A88)
    val accentDark = Color(0xFFF292AA)
    val accentSoft = Color(0x1AEC6A88)      // 10% 透明粉底
    val pageBackgroundLight = Color(0xFFFFF7F9)
    val pageBackgroundDark = Color(0xFF20181B)
    val cardLight = Color(0xFFFFFFFF)
    val cardDark = Color(0xFF2A2124)
    val hairlineLight = Color(0x26EC6A88)   // 15% 粉描边
    val hairlineDark = Color(0x33EC6A88)
    val textPrimaryLight = Color(0xFF2A2124)
    val textPrimaryDark = Color(0xFFF5EDEF)
    val textSecondaryLight = Color(0xFF8A7580)
    val textSecondaryDark = Color(0xFFB39DA8)
    val playerBackground = Color.Black
    val watchedGreen = Color(0xFF34A853)
    val errorOrange = Color(0xFFE8833A)
}

private val LightScheme = lightColorScheme(
    primary = NagomiColors.accent,
    onPrimary = Color.White,
    primaryContainer = NagomiColors.accentSoft,
    onPrimaryContainer = NagomiColors.accent,
    secondary = NagomiColors.accentDark,
    background = NagomiColors.pageBackgroundLight,
    onBackground = NagomiColors.textPrimaryLight,
    surface = NagomiColors.cardLight,
    onSurface = NagomiColors.textPrimaryLight,
    surfaceVariant = NagomiColors.cardLight,
    onSurfaceVariant = NagomiColors.textSecondaryLight,
    outline = NagomiColors.hairlineLight,
    error = Color(0xFFD3455B),
)

private val DarkScheme = darkColorScheme(
    primary = NagomiColors.accentDark,
    onPrimary = Color(0xFF3A1420),
    primaryContainer = Color(0x33EC6A88),
    onPrimaryContainer = NagomiColors.accentDark,
    secondary = NagomiColors.accent,
    background = NagomiColors.pageBackgroundDark,
    onBackground = NagomiColors.textPrimaryDark,
    surface = NagomiColors.cardDark,
    onSurface = NagomiColors.textPrimaryDark,
    surfaceVariant = NagomiColors.cardDark,
    onSurfaceVariant = NagomiColors.textSecondaryDark,
    outline = NagomiColors.hairlineDark,
    error = Color(0xFFFF7A8E),
)

@Composable
fun NagomiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = NagomiTypography,
        content = content,
    )
}

val NagomiTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)
