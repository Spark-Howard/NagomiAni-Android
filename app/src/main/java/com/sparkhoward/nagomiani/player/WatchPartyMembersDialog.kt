package com.sparkhoward.nagomiani.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparkhoward.nagomiani.core.watchparty.PartyMember
import com.sparkhoward.nagomiani.core.watchparty.RoomClient
import com.sparkhoward.nagomiani.ui.theme.NagomiColors

/** 成员胶囊：昵称 + 到房主延迟（房主标身份；自己高亮） */
@Composable
internal fun MemberChip(member: PartyMember, isSelf: Boolean, isHost: Boolean) {
    val label = buildString {
        append(member.nick)
        if (isHost) append(" · 房主")
        if (isSelf) append("（我）")
    }
    val rtt = member.rttMs
    Text(
        text = if (rtt != null) "$label ${rtt}ms" else label,
        fontSize = 11.sp,
        fontWeight = if (isSelf) FontWeight.SemiBold else FontWeight.Normal,
        color = if (isSelf) NagomiColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * 成员面板：房间全员一览——昵称/身份/「到房主」延迟，附房主正在看的内容
 * （guest 可点跳详情页）。任意时刻从首页卡片或播放器弹层打开。
 */
@Composable
internal fun WatchPartyMembersDialog(
    party: RoomClient.ConnectionState,
    watching: PlayerViewModel.PartyHostWatching?,
    onDismiss: () -> Unit,
    onOpenDetail: (Int) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("房间成员（${party.members.size}）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 房主在看（有信息时置顶展示）
                if (watching != null) {
                    Text(
                        buildString {
                            append("房主正在看")
                            watching.title?.let { append("《${watching.title}》") }
                            append("第 ${watching.episodeNumber} 集")
                        },
                        fontSize = 13.sp,
                        color = NagomiColors.accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = watching.subjectId > 0) {
                                if (watching.subjectId > 0) {
                                    onDismiss()
                                    onOpenDetail(watching.subjectId)
                                }
                            }
                            .background(NagomiColors.accent.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                party.members.forEach { member ->
                    val isSelf = member.id == party.selfId
                    val isHost = member.id == party.hostId
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildString {
                                append(member.nick)
                                if (isHost) append(" · 房主")
                                if (isSelf) append("（我）")
                            },
                            fontSize = 14.sp,
                            fontWeight = if (isSelf || isHost) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelf) NagomiColors.accent else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when {
                                isHost -> "锚点"
                                member.rttMs != null -> "到房主 ${member.rttMs}ms"
                                else -> "测量中…"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "延迟 = 该成员到房主的消息往返（10s 自动刷新）；房主是同步锚点，无延迟值",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
