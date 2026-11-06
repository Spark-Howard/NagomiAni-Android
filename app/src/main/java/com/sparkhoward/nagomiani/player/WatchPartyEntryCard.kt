package com.sparkhoward.nagomiani.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sparkhoward.nagomiani.core.watchparty.RoomClient
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiPillAction

/**
 * 一起看全局入口卡（搜索页/番库页顶部常驻）——朋友不用进播放器就能输房间码/点附近房间：
 * 未入房 = 一行入口「一起看 · 和朋友同步看同一集」+「创建 / 加入」；
 * 已入房 = 房间状态（guest 附「房主在看第 N 集」）+ 退出。
 * 加入/创建复用 PlayerViewModel（Activity 作用域，与播放器同实例，进播放器后无缝接管同步）。
 */
@Composable
fun WatchPartyEntryCard(modifier: Modifier = Modifier) {
    // 与 PlayerScreen 同一个 Activity 级实例（不能按导航目的地作用域，否则播放器拿不到会话）
    val activityOwner = LocalContext.current as ViewModelStoreOwner
    val viewModel: PlayerViewModel = viewModel(viewModelStoreOwner = activityOwner)
    val party by viewModel.partyState.collectAsState()
    val hostEpisode by viewModel.partyHostEpisode.collectAsState()
    var showJoinDialog by remember { mutableStateOf(false) }

    if (party.phase == RoomClient.Phase.JOINED) {
        Row(
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Groups, null, tint = NagomiColors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "一起看 · 房间 ${party.roomCode}" + (if (party.role == RoomClient.Role.HOST) "（你是房主）" else ""),
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                Text(
                    when {
                        party.role == RoomClient.Role.GUEST && hostEpisode != null ->
                            "房主在看第 $hostEpisode 集 · 播放同一集即自动同步"
                        party.role == RoomClient.Role.HOST -> "开播后朋友自动跟随你的进度"
                        else -> "播放同一集即自动同步"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
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
        return
    }

    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .clickable { showJoinDialog = true }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Groups, null, tint = NagomiColors.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("一起看 · 和朋友同步看同一集", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(
                "输房间码加入，或创建房间邀请朋友（同一 WiFi 自动发现）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NagomiPillAction("进入", Icons.Filled.Groups) { showJoinDialog = true }
    }

    if (showJoinDialog) {
        WatchPartyJoinDialog(viewModel = viewModel, onDismiss = { showJoinDialog = false })
    }
}

/** 输码/附近房间/创建，三合一加入对话框（未进播放器的浅入口） */
@Composable
private fun WatchPartyJoinDialog(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val party by viewModel.partyState.collectAsState()
    val nearbyRooms by viewModel.nearbyRooms.collectAsState()
    val savedNick by viewModel.store.watchPartyNick.collectAsState(initial = "")
    val savedServer by viewModel.store.watchPartyServer.collectAsState(initial = RoomClient.DEFAULT_RELAY_URL)
    var nick by remember(savedNick) { mutableStateOf(savedNick) }
    var server by remember(savedServer) { mutableStateOf(savedServer) }
    var code by remember { mutableStateOf("") }

    // 对话框打开期间扫描附近房间；关闭即停
    LaunchedEffect(Unit) { viewModel.startNearbyDiscovery() }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopNearbyDiscovery() }
    }

    // 入房成功即收（弹层里能看到房间详情）
    LaunchedEffect(party.phase) {
        if (party.phase == RoomClient.Phase.JOINED) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("一起看") },
        text = {
            Column {
                if (party.phase == RoomClient.Phase.FAILED || party.phase == RoomClient.Phase.RECONNECTING) {
                    Text(
                        party.error ?: "连接失败",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
                OutlinedTextField(
                    value = nick,
                    onValueChange = { nick = it },
                    label = { Text("昵称（默认用 Bangumi 昵称）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))

                if (nearbyRooms.isNotEmpty()) {
                    Text("附近的房间（同一 WiFi 自动发现）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        items(nearbyRooms) { room ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.joinDiscoveredRoom(room, nick.ifBlank { "路人" }) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    room.roomCode,
                                    color = NagomiColors.accent,
                                    fontSize = 15.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                )
                                Text(
                                    "${room.hostNick} 的房间",
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    label = { Text("朋友的房间码（6 位）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("中继服务器（可选，异地看才需要）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.createWatchParty(server, nick.ifBlank { "路人" }) }) {
                Text("创建房间", color = NagomiColors.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    viewModel.joinWatchParty(server, code, nick.ifBlank { "路人" })
                }) {
                    Text("加入", color = NagomiColors.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}
