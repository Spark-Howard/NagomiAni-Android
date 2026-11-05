package com.sparkhoward.nagomiani.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import com.sparkhoward.nagomiani.ui.theme.minimumTouchTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import com.sparkhoward.nagomiani.ui.theme.NagomiTextAction

private val palette = listOf(
    0xFFFFFFu, 0xEC6A88u, 0xFF4D4Du, 0xFFA640u, 0xFFE14Du,
    0x6EE77Au, 0x4DD8E7u, 0x5B8CFFu, 0xB56CFFu, 0x2A2A2Au,
)

/** 半透明面板上使用的浅色文字（面板底色为深色半透明，主题色在此不可读） */
private val LabelColor = Color.White.copy(alpha = 0.65f)

/**
 * 弹幕设置半透明列表（非模态覆盖层，从底部滑出）：
 * 视频与弹幕不受任何影响（无遮罩变暗）；点列表外任意处或「完成」收起。
 * 弹幕开关 / 字号 / 颜色（三模式+色板）/ 不透明度 / 状态 / 重新获取（忽略缓存重拉本集）。
 * 改动即时回调持久化；「重新获取」后的匹配/拉取进度由状态行的 statusText 实时反馈。
 */
@Composable
fun DanmakuSettingsSheet(
    controller: DanmakuController,
    settings: DanmakuSettings,
    onUpdate: ((DanmakuSettings) -> DanmakuSettings) -> Unit,
    onRefetch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by controller.state.collectAsState()
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
                    modifier = Modifier.weight(1f).heightIn(min = 26.dp),
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
                                .minimumTouchTarget()
                                .clickable { onUpdate { it.copy(colorMode = mode) } }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                if (colorMode == DanmakuSettings.ColorMode.CUSTOM) {
                    Row(Modifier.padding(top = 4.dp)) {
                        palette.forEach { c ->
                            androidx.compose.foundation.Canvas(Modifier
                                .minimumTouchTarget()
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
                            .minimumTouchTarget()
                            .clickable { onUpdate { it.copy(opacity = value) } }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }

        settingRow("分层") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LayerToggle("滚动", settings.showScroll) { onUpdate { it.copy(showScroll = !it.showScroll) } }
                LayerToggle("顶部", settings.showTop) { onUpdate { it.copy(showTop = !it.showTop) } }
                LayerToggle("底部", settings.showBottom) { onUpdate { it.copy(showBottom = !it.showBottom) } }
            }
        }

        settingRow("密度") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(0.25f to "25%", 0.5f to "50%", 1.0f to "100%").forEach { (value, label) ->
                    Text(
                        label,
                        color = if (settings.density == value) NagomiColors.accent else LabelColor,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .minimumTouchTarget()
                            .clickable { onUpdate { it.copy(density = value) } }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("刷屏番可降低密度", fontSize = 10.sp, color = LabelColor)
            }
        }

        // 屏蔽词：命中弹幕文本（不分大小写）即不显示，改动即时重过滤不重拉
        settingRow("屏蔽词") {
            val keywords = settings.blockKeywords
            if (keywords.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    keywords.sorted().forEach { keyword ->
                        Text(
                            "$keyword ×",
                            color = NagomiColors.accent,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .background(NagomiColors.accent.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                                .minimumTouchTarget()
                                .clickable { onUpdate { it.copy(blockKeywords = it.blockKeywords - keyword) } }
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Row(
                Modifier.padding(top = if (keywords.isEmpty()) 0.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var input by remember { mutableStateOf("") }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
                Text(
                    "添加",
                    color = NagomiColors.accent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .minimumTouchTarget()
                        .clickable {
                            val word = input.trim()
                            if (word.isNotEmpty()) {
                                onUpdate { it.copy(blockKeywords = it.blockKeywords + word) }
                                input = ""
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        settingRow("状态") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.statusText, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                NagomiTextAction(
                    "重新获取",
                    Icons.Filled.Refresh,
                    fontSize = 12.sp,
                ) { onRefetch() }
            }
        }

        Text(
            "弹幕来自弹弹play：在线番按剧名+集号自动匹配，找不到不显示（宁缺毋滥）。",
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

/** 分层开关胶囊：亮粉=显示该层弹幕，灰=隐藏（与颜色/密度选项同一视觉语言） */
@Composable
private fun LayerToggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    Text(
        label,
        color = if (checked) NagomiColors.accent else LabelColor,
        fontSize = 13.sp,
        modifier = Modifier
            .padding(end = 6.dp)
            .background(
                if (checked) NagomiColors.accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(999.dp),
            )
            .minimumTouchTarget()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 3.dp),
    )
}
