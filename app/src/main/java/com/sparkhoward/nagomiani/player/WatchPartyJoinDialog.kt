package com.sparkhoward.nagomiani.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparkhoward.nagomiani.core.watchparty.RoomClient
import com.sparkhoward.nagomiani.ui.theme.NagomiColors

/**
 * 「加入房间」独立对话框：昵称（默认 Bangumi 昵称）+ 6 位房间码，连接状态全程可见
 * （节点切换/失败原因就地展示）。与「创建房间」分属两个入口（见 WatchPartyEntryCard）。
 */
@Composable
internal fun WatchPartyJoinDialog(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val party by viewModel.partyState.collectAsState()
    val savedNick by viewModel.store.watchPartyNick.collectAsState(initial = "")
    var nick by remember(savedNick) { mutableStateOf(savedNick) }
    var code by remember { mutableStateOf("") }
    val connecting = party.phase == RoomClient.Phase.CONNECTING

    // 入房成功即收（会话状态回到入口卡/播放器弹层展示）
    LaunchedEffect(party.phase) {
        if (party.phase == RoomClient.Phase.JOINED) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入房间") },
        text = {
            Column {
                if (connecting) {
                    Text(
                        if (party.nodeCount > 1) "正在连接房间服务器（节点 ${party.nodeIndex}/${party.nodeCount}）…"
                        else "正在连接房间服务器…",
                        color = NagomiColors.accent,
                        fontSize = 12.sp,
                    )
                }
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
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    label = { Text("朋友的房间码（6 位）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !connecting, onClick = {
                viewModel.joinWatchParty(code, nick.ifBlank { "路人" })
            }) {
                Text(
                    if (connecting) "连接中…" else "加入",
                    color = if (connecting) MaterialTheme.colorScheme.onSurfaceVariant else NagomiColors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
