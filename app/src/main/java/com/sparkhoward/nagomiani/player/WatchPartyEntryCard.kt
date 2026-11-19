package com.sparkhoward.nagomiani.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sparkhoward.nagomiani.core.watchparty.PartyMember
import com.sparkhoward.nagomiani.core.watchparty.RoomClient
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiPillAction

/**
 * 一起看全局入口卡（搜索页/番库页顶部常驻）。创建与加入是两个独立入口：
 * - 「创建房间」一键直达：即点即开（昵称默认 Bangumi 昵称），进入房主状态
 * - 「加入房间」打开独立对话框：输 6 位房间码 + 连接状态/失败原因
 * 已入房显示会话状态条：guest 附「房主在看《X》第 N 集」，点击直接跳详情页。
 * 两个入口共用 Activity 级 PlayerViewModel（与播放器同实例，进播放器后无缝接管同步）。
 */
@Composable
fun WatchPartyEntryCard(
    modifier: Modifier = Modifier,
    onOpenDetail: (Int) -> Unit = {},
) {
    // 与 PlayerScreen 同一个 Activity 级实例（不能按导航目的地作用域，否则播放器拿不到会话）
    val activityOwner = LocalContext.current as ViewModelStoreOwner
    val viewModel: PlayerViewModel = viewModel(viewModelStoreOwner = activityOwner)
    val party by viewModel.partyState.collectAsState()
    val hostWatching by viewModel.partyHostWatching.collectAsState()
    val savedNick by viewModel.store.watchPartyNick.collectAsState(initial = "")
    var showJoinDialog by remember { mutableStateOf(false) }

    if (party.phase == RoomClient.Phase.JOINED) {
        val watching = hostWatching // 委托属性不能 smart cast，先落地局部值
        var showMembers by remember { mutableStateOf(false) }
        // 会话状态条：房间码 + 身份 + 房主在看什么 + 成员胶囊（含每人到房主延迟）
        Column(
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .clickable(enabled = party.role == RoomClient.Role.GUEST && watching != null && watching.subjectId > 0) {
                    watching?.let { if (it.subjectId > 0) onOpenDetail(it.subjectId) }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Groups, null, tint = NagomiColors.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "一起看 · 房间 ${party.roomCode}" + (if (party.role == RoomClient.Role.HOST) "（你是房主）" else ""),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            party.role == RoomClient.Role.GUEST && watching != null ->
                                buildString {
                                    append("房主在看")
                                    watching.title?.let { append("《${it}》") }
                                    append("第 ${watching.episodeNumber} 集")
                                    if (watching.subjectId > 0) append(" · 点此前往")
                                }
                            // guest 已入房但还没收到房主的状态：明确「等开播」而不是含糊的同步说明
                            party.role == RoomClient.Role.GUEST -> "已入房，等待房主开播…"
                            party.role == RoomClient.Role.HOST -> "开播后朋友自动跟随你的进度"
                            else -> "播放同一集即自动同步"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 成员入口：点击打开成员面板（昵称/身份/每人到房主延迟）
                Text(
                    "成员 ${party.members.size}",
                    fontSize = 12.sp,
                    color = NagomiColors.accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { showMembers = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Icon(
                    Icons.Filled.Logout,
                    "退出房间",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { viewModel.leaveWatchParty("已退出一起看") }
                        .padding(4.dp),
                )
            }
            // 成员胶囊行：在线成员一览（nick + 到房主延迟），始终可见
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                party.members.take(4).forEach { member ->
                    MemberChip(
                        member = member,
                        isSelf = member.id == party.selfId,
                        isHost = member.id == party.hostId,
                    )
                }
                if (party.members.size > 4) {
                    MemberChip(member = PartyMember(id = "+", nick = "+${party.members.size - 4}"), isSelf = false, isHost = false)
                }
            }
        }
        if (showMembers) {
            WatchPartyMembersDialog(
                party = party,
                watching = watching,
                onDismiss = { showMembers = false },
                onOpenDetail = onOpenDetail,
            )
        }
        return
    }

    // 未入房：创建 / 加入两个独立入口
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Groups, null, tint = NagomiColors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("一起看 · 和朋友同步看同一集", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "同一 WiFi、异地都能用，无需任何配置",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (party.phase == RoomClient.Phase.CONNECTING) {
            Text(
                if (party.nodeCount > 1) "正在连接房间服务器（节点 ${party.nodeIndex}/${party.nodeCount}）…"
                else "正在连接房间服务器…",
                fontSize = 11.sp,
                color = NagomiColors.accent,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (party.phase == RoomClient.Phase.FAILED || party.phase == RoomClient.Phase.RECONNECTING) {
            Text(
                party.error ?: "连接失败",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Row(Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            NagomiPillAction(
                "创建房间",
                Icons.Filled.Groups,
                filled = true,
                enabled = party.phase != RoomClient.Phase.CONNECTING,
                modifier = Modifier.weight(1f),
            ) { viewModel.createWatchParty(savedNick) }
            NagomiPillAction(
                "加入房间",
                Icons.Filled.Groups,
                modifier = Modifier.weight(1f),
            ) { showJoinDialog = true }
        }
    }

    if (showJoinDialog) {
        WatchPartyJoinDialog(viewModel = viewModel, onDismiss = { showJoinDialog = false })
    }
}
