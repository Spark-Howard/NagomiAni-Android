package com.sparkhoward.nagomiani.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparkhoward.nagomiani.core.watchparty.RoomClient
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.minimumTouchTarget

/** 面板底色（与弹幕设置/分享弹层同款深色半透明） */
private val PanelBg = Color(0xD9141014)
private val LabelColor = Color.White.copy(alpha = 0.65f)
private val ErrorColor = Color(0xFFFF7A8E)

/**
 * 一起看弹层（播放器竖屏面板入口，非模态覆盖层同款视觉）：
 * 未入房 = 昵称/服务器 + 创建房间 / 输码加入；
 * 已入房 = 房间码（复制分享）+ 成员 + 聊天（同时以弹幕样式飘过画面）+ 退出。
 * 房主权威：guest 的暂停/拖动以房主为准（面板内有明示）。
 */
@Composable
fun WatchPartySheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val party by viewModel.partyState.collectAsState()
    val chat by viewModel.partyChat.collectAsState()
    val savedNick by viewModel.store.watchPartyNick.collectAsState(initial = "")
    val savedServer by viewModel.store.watchPartyServer.collectAsState(initial = RoomClient.DEFAULT_RELAY_URL)
    var nick by remember(savedNick) { mutableStateOf(savedNick) }
    var server by remember(savedServer) { mutableStateOf(savedServer) }
    var code by remember { mutableStateOf("") }
    var chatInput by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier
            .fillMaxWidth()
            .background(PanelBg, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // 标题行：标题 + 完成键
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("一起看", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "完成",
                color = NagomiColors.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp),
            )
        }

        when (party.phase) {
            RoomClient.Phase.JOINED -> {
                // 房间行：房间码（点击复制）+ 身份
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "房间 ${party.roomCode}",
                        color = NagomiColors.accent,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                clipboard.setText(AnnotatedString(party.roomCode))
                                viewModel.showStatus("房间码已复制，去分享吧")
                            }
                            .padding(vertical = 4.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.Icon(
                        Icons.Filled.ContentCopy,
                        "复制房间码",
                        tint = LabelColor,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                clipboard.setText(AnnotatedString(party.roomCode))
                                viewModel.showStatus("房间码已复制，去分享吧")
                            },
                    )
                    Spacer(Modifier.width(10.dp))
                    // 系统分享：朋友点开文本按提示输码，不必自己找入口
                    val context = androidx.compose.ui.platform.LocalContext.current
                    androidx.compose.material3.Icon(
                        Icons.Filled.Share,
                        "分享房间",
                        tint = NagomiColors.accent,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        android.content.Intent.EXTRA_TEXT,
                                        "来 NagomiAni 一起看！房间码 ${party.roomCode}\n打开 App → 搜索/番库页「一起看」卡片 → 输入房间码加入",
                                    )
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "分享房间"))
                            },
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (party.role == RoomClient.Role.HOST) "房主" else "成员",
                        color = LabelColor,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    if (party.role == RoomClient.Role.HOST) "你的播放进度就是大家的进度"
                    else "跟随房主播放：播放/暂停/拖动以房主为准",
                    color = LabelColor,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )

                // 成员胶囊（横滑）
                if (party.members.isNotEmpty()) {
                    Row(
                        Modifier
                            .padding(top = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        party.members.forEach { member ->
                            val isSelf = member.id == party.selfId
                            val isHost = member.id == party.hostId
                            Text(
                                buildString {
                                    append(member.nick)
                                    if (isHost) append(" · 房主")
                                    if (isSelf) append("（我）")
                                },
                                color = if (isSelf) NagomiColors.accent else Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                // 聊天列表（同时以弹幕样式飘过画面，见 PlayerScreen 合并逻辑）
                val listState = rememberLazyListState()
                LaunchedEffect(chat.size) {
                    if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                ) {
                    items(chat) { entry ->
                        Text(
                            "${entry.nick}：${entry.text}",
                            color = if (entry.isMine) NagomiColors.accent else Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }

                // 聊天输入 + 发送
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PartyField(
                        value = chatInput,
                        onValueChange = { chatInput = it },
                        hint = "说点什么…（会以弹幕飘过）",
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Icon(
                        Icons.Filled.Send,
                        "发送",
                        tint = NagomiColors.accent,
                        modifier = Modifier
                            .size(28.dp)
                            .minimumTouchTarget()
                            .clickable {
                                viewModel.sendPartyChat(chatInput)
                                chatInput = ""
                            }
                            .padding(6.dp),
                    )
                }

                // 退出房间
                Row(Modifier.padding(top = 8.dp)) {
                    com.sparkhoward.nagomiani.ui.theme.NagomiTextAction(
                        "退出房间",
                        Icons.Filled.Logout,
                        tint = ErrorColor,
                    ) { viewModel.leaveWatchParty("已退出一起看") }
                }
            }

            else -> {
                party.error?.let { err ->
                    Text(err, color = ErrorColor, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }

                PartyField(
                    value = nick,
                    onValueChange = { nick = it },
                    hint = "昵称（默认用 Bangumi 昵称）",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )

                PartyField(
                    value = server,
                    onValueChange = { server = it },
                    hint = "自定义服务器（可选：mqtt:// 或 ws://，留空用公共节点）",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )

                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 创建房间：留空地址=本机开局（局域网）；填了=走自建中继（可异地）
                    Text(
                        "创建房间",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(NagomiColors.accent, RoundedCornerShape(999.dp))
                            .clickable {
                                viewModel.createWatchParty(server, nick.ifBlank { "路人" })
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    PartyField(
                        value = code,
                        onValueChange = { code = it.uppercase() },
                        hint = "朋友给的房间码",
                        modifier = Modifier.weight(1f),
                        capitalization = KeyboardCapitalization.Characters,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "加入",
                        color = NagomiColors.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .minimumTouchTarget()
                            .clickable {
                                viewModel.joinWatchParty(server, code, nick.ifBlank { "路人" })
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }

                Text(
                    "输房间码即可同看，同一 WiFi、异地都行，无需任何配置（走免费公共节点）。有自建服务器时在地址栏填 mqtt:// 或 ws:// 地址。",
                    color = LabelColor,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** 深色面板上的圆角输入框（BasicTextField + 占位符） */
@Composable
private fun PartyField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Unspecified,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
        keyboardOptions = KeyboardOptions(capitalization = capitalization),
        cursorBrush = SolidColor(NagomiColors.accent),
        decorationBox = { inner ->
            Box(
                Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) Text(hint, color = LabelColor, fontSize = 14.sp, maxLines = 1)
                inner()
            }
        },
        modifier = modifier,
    )
}

/** 面板「一起看」行的状态摘要（PortraitPanel 用） */
fun partyStatusText(state: RoomClient.ConnectionState): String = when (state.phase) {
    RoomClient.Phase.JOINED ->
        if (state.role == RoomClient.Role.HOST) {
            "${state.roomCode} · 房主 · ${state.members.size}人"
        } else {
            "${state.roomCode} · 成员 · ${state.members.size}人"
        }
    RoomClient.Phase.CONNECTING -> "连接中…"
    RoomClient.Phase.RECONNECTING -> state.error ?: "重连中…"
    RoomClient.Phase.FAILED -> state.error ?: "连接失败"
    RoomClient.Phase.IDLE -> "未开始 · 创建或加入房间码"
}
