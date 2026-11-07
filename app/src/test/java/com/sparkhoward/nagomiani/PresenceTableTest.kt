package com.sparkhoward.nagomiani

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
        table.record("b", "乙", hostClaim = false, nowMs = 1_000)
        table.record("a", "甲", hostClaim = true, nowMs = 2_000)
        table.record("c", "丙", hostClaim = false, nowMs = 3_000)
        val members = table.members(nowMs = 4_000)
        assertEquals(listOf("a", "b", "c"), members.map { it.id })
        assertEquals("甲", members.first().nick)
    }

    @Test
    fun staleMembersExpireSilently() {
        table.record("a", "甲", hostClaim = true, nowMs = 0)
        table.record("b", "乙", hostClaim = false, nowMs = 1_000)
        // a 心跳停在 0，12s TTL 后只剩 b
        assertEquals(listOf("b"), table.members(nowMs = 13_000).map { it.id })
        assertNull(table.resolveHost(nowMs = 13_000))
    }

    @Test
    fun freshHeartbeatRefreshesMembership() {
        table.record("a", "甲", hostClaim = true, nowMs = 0)
        table.record("a", "甲改", hostClaim = true, nowMs = 10_000)
        val members = table.members(nowMs = 20_000)
        assertEquals(1, members.size)
        assertEquals("甲改", members.first().nick)
    }

    // MARK: - 房主选举（TTL 内自荐者取最小 id）

    @Test
    fun hostIsSmallestIdAmongClaims() {
        table.record("mm", "后到房主", hostClaim = true, nowMs = 0)
        table.record("aa", "先到房主", hostClaim = true, nowMs = 0)
        assertEquals("aa", table.resolveHost(nowMs = 1_000))
    }

    @Test
    fun hostClaimExpiresAndNextTakesOver() {
        table.record("zz", "原房主", hostClaim = true, nowMs = 0)
        table.record("kk", "成员", hostClaim = false, nowMs = 2_000)
        assertEquals("zz", table.resolveHost(nowMs = 5_000))
        // 原房主断线（心跳停摆）后自荐过期：无人自荐 → 本机应补位
        assertNull(table.resolveHost(nowMs = 13_000))
    }

    @Test
    fun takeoverKeepsStableWhileNewClaimerHeartbeats() {
        table.record("zz", "原房主", hostClaim = true, nowMs = 0)
        table.record("kk", "接任", hostClaim = true, nowMs = 13_000)
        table.record("kk", "接任", hostClaim = true, nowMs = 16_000)
        assertEquals("kk", table.resolveHost(nowMs = 17_000))
        assertEquals("kk", table.resolveHost(nowMs = 19_000))
    }

    @Test
    fun removeDropsImmediately() {
        table.record("a", "甲", hostClaim = true, nowMs = 0)
        table.remove("a")
        assertTrue(table.members(nowMs = 1).isEmpty())
        assertNull(table.resolveHost(nowMs = 1))
    }

    // MARK: - Presence wire 往返

    @Test
    fun presenceWireRoundTrip() {
        val encoded = PartyWireCodec.encode(PartyWire.Presence(nick = "火", host = true, from = "abc123"))
        assertTrue(encoded.contains("\"t\":\"presence\""), encoded)
        val decoded = PartyWireCodec.decode(encoded)
        assertEquals(PartyWire.Presence(nick = "火", host = true, from = "abc123"), decoded)
    }
}
