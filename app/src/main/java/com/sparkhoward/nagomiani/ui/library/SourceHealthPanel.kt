package com.sparkhoward.nagomiani.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparkhoward.nagomiani.core.maccms.SourceHealth
import com.sparkhoward.nagomiani.core.store.AppStore
import com.sparkhoward.nagomiani.ui.theme.NagomiBadge
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiSecondaryButton
import kotlinx.coroutines.delay

/** 延迟徽章：测速结果 → 「213ms / 1.2s」粉色；不可达橙色；未测灰。番库面板与详情页源行共用 */
@Composable
fun LatencyBadge(entry: AppStore.SiteHealthEntry?, modifier: Modifier = Modifier) {
    when {
        entry == null -> NagomiBadge("未测", MaterialTheme.colorScheme.onSurfaceVariant, modifier)
        !entry.ok -> NagomiBadge("不可达", NagomiColors.errorOrange, modifier)
        else -> NagomiBadge(SourceHealth.formatLatency(entry.latencyMs), NagomiColors.accent, modifier)
    }
}

/**
 * 片源健康区块（番库页）：站点延迟/可达性测速 + 自加站点管理
 * （AppStore 的 addUserSite/removeUserSite 早已就绪，这里补上 UI）。
 * 默认收起；首次展开且从未测过时自动测一次，之后手动「测速」刷新。结果持久化在本机。
 */
@Composable
fun SourceHealthPanel(
    rows: List<LibraryViewModel.SiteRow>,
    health: Map<String, AppStore.SiteHealthEntry>,
    probing: Boolean,
    message: String?,
    onProbe: () -> Unit,
    onAddSite: (String) -> Unit,
    onRemoveSite: (LibraryViewModel.SiteRow) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var newSite by rememberSaveable { mutableStateOf("") }
    val fastest = SourceHealth.fastestHost(health)

    // 首次展开且无任何记录：自动测一次（之后由「测速」按钮手动刷新）
    LaunchedEffect(expanded) {
        if (expanded && health.isEmpty() && !probing) {
            onProbe()
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // 标题行（仿 NagomiSectionHeader 视觉 + 测速/展开）
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 16.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
            Icon(
                Icons.Filled.Speed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp).size(16.dp),
            )
            Text(
                "片源健康",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 6.dp).weight(1f),
            )
            if (probing) {
                CircularProgressIndicator(
                    color = NagomiColors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
            } else {
                Text(
                    "测速",
                    color = NagomiColors.accent,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onProbe() }.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            rows.forEach { row ->
                val entry = health[row.providerID]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.label,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (row.providerID == fastest && entry?.ok == true) {
                        NagomiBadge("⚡最快", NagomiColors.accent)
                        Spacer(Modifier.width(6.dp))
                    }
                    LatencyBadge(entry)
                    if (row.isUser) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "删除站点",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onRemoveSite(row) },
                        )
                    }
                }
            }

            // 添加站点（内置 4 站不可删；自加站可删）
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newSite,
                    onValueChange = { newSite = it },
                    placeholder = { Text("粘贴站点地址，添加自加片源", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NagomiColors.accent,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.weight(1f).height(52.dp),
                )
                Spacer(Modifier.width(8.dp))
                NagomiSecondaryButton("添加", enabled = newSite.isNotBlank()) {
                    onAddSite(newSite)
                    newSite = ""
                }
            }
            message?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = if (it.startsWith("已")) NagomiColors.watchedGreen else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                "测速为站点 API 延迟，与播放 CDN 实际速度不完全一致；记录仅保存在本机。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
