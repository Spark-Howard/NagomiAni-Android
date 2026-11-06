package com.sparkhoward.nagomiani.core.watchparty

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * 一起看局域网模式：房主手机内嵌的最小 WS 中继——与 server/index.js 完全相同的转发语义
 * （首位成员=房主；hello/ping 单播消费；state/chat/sync 原样广播并附 from；8KB/16 人限额）。
 * RoomClient 仍走 `ws://127.0.0.1:<port>/room/<code>` 连本机，对播放同步链路完全透明：
 * 连自建中继还是连房主手机，客户端代码无差别。对外广播（NSD）由 [PartyDiscovery] 负责。
 * 退出房间/清 ViewModel 即 [stop]；连接回调都在 selector 线程，成员表用同步块保护。
 */
class LocalRoomServer(val roomCode: String, port: Int = 0) : WebSocketServer(InetSocketAddress(port)) {

    private class Member(val socket: WebSocket, val nick: String)

    private val lock = Any()
    private val members = LinkedHashMap<String, Member>()
    private var hostId: String? = null

    /** 成功握手（路径对上）的连接先挂起等 hello；路径不对直接拒 */
    private val pending = HashMap<WebSocket, String>()

    override fun onStart() {
        connectionLostTimeout = 30 // 协议层心跳：30s 无响应断开（客户端会自动重连）
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val path = handshake.resourceDescriptor
        if (path != "/room/$roomCode") {
            conn.close(4001, "bad room path")
            return
        }
        pending[conn] = newId()
    }

    override fun onMessage(conn: WebSocket, message: String) {
        if (message.length > MAX_MESSAGE_BYTES) {
            conn.close(4009, "message too large")
            return
        }
        val msg = PartyWireCodec.decode(message) ?: return
        val id = pending[conn]
        if (id != null) {
            // 首条必须是 hello
            if (msg !is PartyWire.Hello) return
            pending.remove(conn)
            val member = Member(conn, msg.nick.take(24))
            val first: Boolean
            synchronized(lock) {
                first = members.isEmpty()
                if (members.size >= MAX_MEMBERS && !members.containsKey(id)) {
                    conn.close(4002, "room full")
                    return
                }
                members[id] = member
                if (first) hostId = id
            }
            send(conn, PartyWire.Welcome(you = id, host = synchronized(lock) { hostId ?: id }, members = snapshot()))
            broadcastToOthers(conn, PartyWire.Members(host = synchronized(lock) { hostId ?: id }, members = snapshot()))
            return
        }
        when (msg) {
            is PartyWire.Ping -> send(conn, PartyWire.Pong(at = msg.at))
            is PartyWire.State -> broadcastToOthers(conn, msg.copy(from = senderId(conn)))
            is PartyWire.Chat -> broadcastToOthers(conn, msg.copy(from = senderId(conn)))
            is PartyWire.Sync -> broadcastToOthers(conn, msg.copy(from = senderId(conn)))
            else -> Unit // hello 重复/未知类型不接受
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val removed = synchronized(lock) {
            pending.remove(conn)
            val it = members.entries.firstOrNull { it.value.socket === conn } ?: return
            members.remove(it.key)
            it.key
        }
        val hostNow: String? = synchronized(lock) {
            if (hostId == removed) hostId = members.keys.firstOrNull()
            hostId
        }
        broadcastToAll(PartyWire.Members(host = hostNow ?: "", members = snapshot()))
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        conn?.let { onClose(it, 1006, "error", true) }
    }

    // MARK: - 内部工具

    private fun newId(): String = "m${System.nanoTime().toString(36)}${(0..999).random()}"

    private fun snapshot(): List<PartyMember> = synchronized(lock) {
        members.map { (id, m) -> PartyMember(id, m.nick) }
    }

    private fun send(socket: WebSocket, msg: PartyWire) {
        runCatching { socket.send(PartyWireCodec.encode(msg)) }
    }

    private fun broadcastToAll(msg: PartyWire) {
        val sockets = synchronized(lock) { members.values.map { it.socket } }
        sockets.forEach { send(it, msg) }
    }

    private fun broadcastToOthers(except: WebSocket, msg: PartyWire) {
        val sockets = synchronized(lock) { members.values.map { it.socket } }
        sockets.filter { it !== except }.forEach { send(it, msg) }
    }

    /** relay 的转发协议：广播消息补 from=<发送者 id>（字段在 wire 模型上，见 WatchPartyMessages） */
    private fun senderId(conn: WebSocket): String =
        synchronized(lock) { members.entries.firstOrNull { it.value.socket === conn }?.key } ?: ""

    companion object {
        const val MAX_MEMBERS = 16
        const val MAX_MESSAGE_BYTES = 8 * 1024

        /** 挑一个空闲端口：Java-WebSocket 不回填 port=0 的实际绑定值，调用方需先选定端口 */
        fun pickFreePort(): Int = java.net.ServerSocket(0).use { it.localPort }
    }
}
