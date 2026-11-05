package com.sparkhoward.nagomiani.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sparkhoward.nagomiani.AppGraph

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

/** AMOLED 纯黑：保留樱粉强调色，背景/表面压到纯黑与近黑（OLED 省电） */
private val AmoledScheme = DarkScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF0E0A0C),
    surfaceVariant = Color(0xFF171114),
)

@Composable
fun NagomiTheme(content: @Composable () -> Unit) {
    // 应用内主题覆盖（设置中心）：跟随系统 / 手动浅色 / 手动深色，深色下可选 AMOLED 纯黑。
    // 桌面小部件不读此设置——RemoteViews 用 XML 资源、始终跟随系统深浅色（双份维护约定不变）
    val mode by AppGraph.store.themeMode.collectAsState(initial = "system")
    val amoled by AppGraph.store.themeAmoled.collectAsState(initial = false)
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = when {
            dark && amoled -> AmoledScheme
            dark -> DarkScheme
            else -> LightScheme
        },
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
