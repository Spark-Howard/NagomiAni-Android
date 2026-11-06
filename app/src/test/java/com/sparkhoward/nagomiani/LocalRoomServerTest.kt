package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.watchparty.LocalRoomServer
import com.sparkhoward.nagomiani.core.watchparty.PartyWire
import com.sparkhoward.nagomiani.core.watchparty.PartyWireCodec
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 局域网本机中继的纯 JVM 端到端测试：起真服务、连真客户端，与 server/index.js
 * 的转发语义对齐（首位成员=房主、state 广播附 from、ping→pong、离开同步成员表）。
 */
class LocalRoomServerTest {

    private val server = LocalRoomServer("TEST23", LocalRoomServer.pickFreePort()).also { it.start() }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun url(): String = "ws://127.0.0.1:${server.address.port}/room/TEST23"

    private class TestClient(url: URI, private val inbox: LinkedBlockingQueue<String>) : WebSocketClient(url) {
        var closedCode = -1
        override fun onOpen(handshake: ServerHandshake) = Unit
        override fun onMessage(message: String) {
            inbox.put(message)
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            closedCode = code
        }

        override fun onError(ex: Exception) = Unit
    }

    private fun connect(nick: String): Pair<TestClient, LinkedBlockingQueue<String>> {
        val inbox = LinkedBlockingQueue<String>()
        val client = TestClient(URI(url()), inbox)
        client.connectBlocking(3, TimeUnit.SECONDS)
        client.send(PartyWireCodec.encode(PartyWire.Hello(room = "TEST23", nick = nick)))
        return client to inbox
    }

    private fun next(inbox: LinkedBlockingQueue<String>): PartyWire =
        PartyWireCodec.decode(inbox.poll(3, TimeUnit.SECONDS) ?: error("timeout waiting message"))
            ?: error("undecodable message")

    @Test
    fun firstJoinerBecomesHostAndSecondSeesTwoMembers() {
        val (host, hostInbox) = connect("房主")
        val welcome = next(hostInbox) as PartyWire.Welcome
        assertEquals(welcome.you, welcome.host)
        assertEquals(1, welcome.members.size)

        val (guest, guestInbox) = connect("客人")
        val gw = next(guestInbox) as PartyWire.Welcome
        assertEquals(welcome.you, gw.host) // 房主是首位成员
        assertEquals(2, gw.members.size)

        val hostUpdate = next(hostInbox) as PartyWire.Members
        assertEquals(2, hostUpdate.members.size)

        host.close()
        guest.close()
    }

    @Test
    fun stateIsBroadcastWithFromTag() {
        val (host, hostInbox) = connect("房主")
        val hw = next(hostInbox) as PartyWire.Welcome
        val (guest, guestInbox) = connect("客人")
        next(guestInbox) // welcome
        next(hostInbox) // members

        host.send(
            PartyWireCodec.encode(
                PartyWire.State(playing = true, pos = 812.4, wall = 123L, epoch = 3, episodeNumber = 5),
            ),
        )
        val raw = guestInbox.poll(3, TimeUnit.SECONDS) ?: error("timeout waiting state")
        assertTrue(raw.contains("\"from\""), raw) // relay 同款：广播附发送者 id
        val received = PartyWireCodec.decode(raw) as PartyWire.State
        assertEquals(812.4, received.pos, 0.0)
        assertEquals(3, received.epoch)
        assertEquals(hw.you, received.from) // from = 房主 id

        guest.close()
        host.close()
    }

    @Test
    fun pingAnswersPongAndChatIsForwarded() {
        val (host, hostInbox) = connect("房主")
        next(hostInbox)
        val (guest, guestInbox) = connect("客人")
        next(guestInbox)
        next(hostInbox)

        guest.send(PartyWireCodec.encode(PartyWire.Chat(nick = "客人", text = "这段太好看了")))
        val chat = next(hostInbox) as PartyWire.Chat
        assertEquals("这段太好看了", chat.text)

        host.send(PartyWireCodec.encode(PartyWire.Ping(at = 42L)))
        val pong = next(hostInbox) as PartyWire.Pong
        assertEquals(42L, pong.at)

        guest.close()
        host.close()
    }

    @Test
    fun leaveUpdatesMembersOnHost() {
        val (host, hostInbox) = connect("房主")
        val hw = next(hostInbox) as PartyWire.Welcome
        val (guest, _) = connect("客人")
        next(hostInbox) // members(2)

        guest.closeBlocking()
        val afterLeave = next(hostInbox) as PartyWire.Members
        assertEquals(1, afterLeave.members.size)
        assertEquals(hw.you, afterLeave.host) // 房主不变（房主未走）

        host.close()
    }

    @Test
    fun badPathIsRejectedWith4001() {
        val inbox = LinkedBlockingQueue<String>()
        val client = object : WebSocketClient(URI("ws://127.0.0.1:${server.address.port}/nope")) {
            override fun onOpen(handshake: ServerHandshake) = Unit
            override fun onMessage(message: String) = inbox.put(message)
            override fun onClose(code: Int, reason: String, remote: Boolean) {
                inbox.put("__closed_$code")
            }

            override fun onError(ex: Exception) = Unit
        }
        client.connectBlocking(3, TimeUnit.SECONDS)
        val closed = inbox.poll(3, TimeUnit.SECONDS) ?: error("no close event")
        assertTrue(closed.startsWith("__closed_"), closed)
        assertEquals(4001, closed.removePrefix("__closed_").toInt())
    }
}
