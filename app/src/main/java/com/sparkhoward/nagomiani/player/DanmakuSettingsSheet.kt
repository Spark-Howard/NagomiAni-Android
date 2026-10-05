package com.sparkhoward.nagomiani.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparkhoward.nagomiani.ui.theme.NagomiColors

private val palette = listOf(
    0xFFFFFFu, 0xEC6A88u, 0xFF4D4Du, 0xFFA640u, 0xFFE14Du,
    0x6EE77Au, 0x4DD8E7u, 0x5B8CFFu, 0xB56CFFu, 0x2A2A2Au,
)

/** 半透明面板上使用的浅色文字（面板底色为深色半透明，主题色在此不可读） */
private val LabelColor = Color.White.copy(alpha = 0.65f)

/**
 * 弹幕设置半透明列表（非模态覆盖层，从底部滑出）：
 * 视频与弹幕不受任何影响（无遮罩变暗）；点列表外任意处或「完成」收起。
 * 弹幕开关 / 字号 / 颜色（三模式+色板）/ 不透明度 / 状态 / 重新获取。改动即时回调持久化。
 */
@Composable
fun DanmakuSettingsSheet(
    controller: DanmakuController,
    settings: DanmakuSettings,
    onUpdate: ((DanmakuSettings) -> DanmakuSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by controller.state.collectAsState()
    var showRefetchHint by remember { mutableStateOf(false) }
    val enabled = settings.enabled
    val fontSize = settings.fontSize
    val colorMode = settings.colorMode
    val customColor = settings.customColor
    val opacity = settings.opacity

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xD9141014), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // 标题行：弹幕开关 + 完成键
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("弹幕设置", color = Color.White, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { onUpdate { it.copy(enabled = !it.enabled) } },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = NagomiColors.accent,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.25f),
                    uncheckedThumbColor = Color.White,
                ),
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "完成",
                color = NagomiColors.accent,
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp),
            )
        }

        settingRow("字号") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Slider(
                    value = fontSize,
                    onValueChange = { value ->
                        val rounded = kotlin.math.round(value).toInt().toFloat()
                        onUpdate { it.copy(fontSize = rounded) }
                    },
                    valueRange = 8f..28f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = NagomiColors.accent,
                        activeTrackColor = NagomiColors.accent,
                    ),
                    modifier = Modifier.weight(1f).height(26.dp),
                )
                Text(
                    "${fontSize.toInt()}",
                    fontSize = 13.sp,
                    color = if (enabled) NagomiColors.accent else LabelColor,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        settingRow("颜色") {
            Column {
                Row {
                    DanmakuSettings.ColorMode.entries.forEach { mode ->
                        Text(
                            mode.label,
                            color = if (colorMode == mode) NagomiColors.accent else LabelColor,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable { onUpdate { it.copy(colorMode = mode) } }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                if (colorMode == DanmakuSettings.ColorMode.CUSTOM) {
                    Row(Modifier.padding(top = 4.dp)) {
                        palette.forEach { c ->
                            androidx.compose.foundation.Canvas(Modifier
                                .padding(end = 8.dp)
                                .size(20.dp)
                                .clickable { onUpdate { s -> s.copy(customColor = c) } }) {
                                drawCircle(
                                    color = Color(
                                        ((c shr 16) and 0xFFu).toInt(),
                                        ((c shr 8) and 0xFFu).toInt(),
                                        (c and 0xFFu).toInt(),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        settingRow("不透明度") {
            Row {
                listOf(0.3f to "30%", 0.6f to "60%", 1.0f to "100%").forEach { (value, label) ->
                    Text(
                        label,
                        color = if (opacity == value) NagomiColors.accent else LabelColor,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { onUpdate { it.copy(opacity = value) } }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }

        settingRow("状态") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.statusText, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                Text(
                    "重新获取",
                    color = NagomiColors.accent,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { showRefetchHint = true },
                )
            }
        }

        Text(
            "弹幕来自弹弹play：在线番按剧名+集号自动匹配，找不到不显示（宁缺毋滥）。" + if (showRefetchHint) "（回到本集重新进入播放器即重新获取）" else "",
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun settingRow(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, fontSize = 11.sp, color = LabelColor, modifier = Modifier.padding(bottom = 2.dp))
        content()
    }
}
