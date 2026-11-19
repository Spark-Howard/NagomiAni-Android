package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.watchparty.PartyEndpoints
import com.sparkhoward.nagomiani.core.watchparty.PartyWire
import com.sparkhoward.nagomiani.core.watchparty.PartyWireCodec
import com.sparkhoward.nagomiani.core.watchparty.PresenceTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresenceTableTest {

    private val table = PresenceTable(ttlMs = 12_000)

    // MARK: - 成员快照（TTL 过期即出局）

    @Test
    fun membersWithinTtlAreListedSortedById() {
        table.record("b", "乙", hostClaim = false, rttMs = null, nowMs = 1_000)
        table.record("a", "甲", hostClaim = true, rttMs = null, nowMs = 2_000)
        table.record("c", "丙", hostClaim = false, rttMs = 40, nowMs = 3_000)
        val members = table.members(nowMs = 4_000)
        assertEquals(listOf("a", "b", "c"), members.map { it.id })
        assertEquals("甲", members.first().nick)
        assertEquals(40L, members[2].rttMs)
        assertNull(members[0].rttMs) // 房主锚点无延迟
    }

    @Test
    fun staleMembersExpireSilently() {
        table.record("a", "甲", hostClaim = true, rttMs = null, nowMs = 0)
        table.record("b", "乙", hostClaim = false, rttMs = null, nowMs = 1_000)
        // a 心跳停在 0，12s TTL 后只剩 b
        assertEquals(listOf("b"), table.members(nowMs = 13_000).map { it.id })
        assertNull(table.resolveHost(nowMs = 13_000))
    }

    @Test
    fun freshHeartbeatRefreshesMembership() {
        table.record("a", "甲", hostClaim = true, rttMs = null, nowMs = 0)
        table.record("a", "甲改", hostClaim = true, rttMs = null, nowMs = 10_000)
        val members = table.members(nowMs = 20_000)
        assertEquals(1, members.size)
        assertEquals("甲改", members.first().nick)
    }

    // MARK: - RTT（成员测得的「到房主」延迟随在场广播）

    @Test
    fun rttUpdatesInPlaceWithoutTouchingOtherFields() {
        table.record("a", "甲", hostClaim = false, rttMs = null, nowMs = 0)
        table.updateRtt("a", rttMs = 23, nowMs = 1_000)
        assertEquals(23L, table.members(nowMs = 2_000).single().rttMs)
        assertEquals("甲", table.members(nowMs = 2_000).single().nick)
        // 续期：updateRtt 也刷新 lastSeen（TTL 内）
        assertEquals(23L, table.members(nowMs = 12_500).single().rttMs)
    }

    @Test
    fun rttOfUnknownMemberIsNull() {
        assertNull(table.rttOf("ghost"))
        table.record("a", "甲", hostClaim = false, rttMs = null, nowMs = 0)
        assertNull(table.rttOf("a"))
    }

    // MARK: - 房主选举（TTL 内自荐者取最小 id）

    @Test
    fun hostIsSmallestIdAmongClaims() {
        table.record("mm", "后到房主", hostClaim = true, rttMs = null, nowMs = 0)
        table.record("aa", "先到房主", hostClaim = true, rttMs = null, nowMs = 0)
        assertEquals("aa", table.resolveHost(nowMs = 1_000))
    }

    @Test
    fun hostClaimExpiresAndNextTakesOver() {
        table.record("zz", "原房主", hostClaim = true, rttMs = null, nowMs = 0)
        table.record("kk", "成员", hostClaim = false, rttMs = null, nowMs = 2_000)
        assertEquals("zz", table.resolveHost(nowMs = 5_000))
        // 原房主断线（心跳停摆）后自荐过期：无人自荐 → 本机应补位
        assertNull(table.resolveHost(nowMs = 13_000))
    }

    @Test
    fun takeoverKeepsStableWhileNewClaimerHeartbeats() {
        table.record("zz", "原房主", hostClaim = true, rttMs = null, nowMs = 0)
        table.record("kk", "接任", hostClaim = true, rttMs = null, nowMs = 13_000)
        table.record("kk", "接任", hostClaim = true, rttMs = null, nowMs = 16_000)
        assertEquals("kk", table.resolveHost(nowMs = 17_000))
        assertEquals("kk", table.resolveHost(nowMs = 19_000))
    }

    @Test
    fun removeDropsImmediately() {
        table.record("a", "甲", hostClaim = true, rttMs = null, nowMs = 0)
        table.remove("a")
        assertTrue(table.members(nowMs = 1).isEmpty())
        assertNull(table.resolveHost(nowMs = 1))
    }

    // MARK: - Presence wire 往返（rttMs 随心跳广播）

    @Test
    fun presenceWireRoundTripCarriesRtt() {
        val encoded = PartyWireCodec.encode(PartyWire.Presence(nick = "火", host = true, rttMs = 37, from = "abc"))
        assertTrue(encoded.contains("\"rttMs\":37"), encoded)
        val decoded = PartyWireCodec.decode(encoded)
        assertEquals(PartyWire.Presence(nick = "火", host = true, rttMs = 37, from = "abc"), decoded)
    }

    @Test
    fun addressedPingPongRoundTrip() {
        // 定址 ping/pong（guest 量「到房主」延迟；时间戳回声无时钟偏差）
        val ping = PartyWireCodec.encode(PartyWire.Ping(at = 123L, from = "guest1", to = "host1"))
        val decodedPing = PartyWireCodec.decode(ping)
        assertEquals(PartyWire.Ping(at = 123L, from = "guest1", to = "host1"), decodedPing)
        val pong = PartyWireCodec.encode(PartyWire.Pong(at = 123L, from = "host1", to = "guest1"))
        assertEquals(PartyWire.Pong(at = 123L, from = "host1", to = "guest1"), PartyWireCodec.decode(pong))
    }
}

class PartyEndpointsTest {

    private val base = listOf("cn-wss", "global-wss", "cn-tcp", "global-tcp")

    @Test
    fun sameRoomAlwaysSameOrder() {
        // 核心不变量：同房间所有设备算出同一顺序（否则房间分裂到不同 broker 互不可见）
        for (room in listOf("AA8NQU", "TE5T7X", "ZZZZ99", "AABBCC")) {
            val a = PartyEndpoints.ordered(base, room)
            val b = PartyEndpoints.ordered(base, room)
            assertEquals(a, b, "room=$room")
            assertEquals(base.toSet(), a.toSet(), "rotation must keep all nodes")
        }
    }

    @Test
    fun rotationSpreadsPrimaryAcrossNodes() {
        // 不同房间的主节点应分布开（负载均衡），且首元素 = |hash| % n 的预期节点
        val primaries = (0 until 32).map { PartyEndpoints.ordered(base, "ROOM$it").first() }
        assertTrue(primaries.toSet().size > 1, "primaries should vary: $primaries")
        for (i in 0 until 32) {
            val room = "ROOM$i"
            val expected = base[((room.hashCode() % base.size) + base.size) % base.size]
            assertEquals(expected, PartyEndpoints.ordered(base, room).first())
        }
    }

    @Test
    fun singleNodeListUnchanged() {
        assertEquals(listOf("only"), PartyEndpoints.ordered(listOf("only"), "ANY"))
    }
}
