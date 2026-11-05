package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.watchparty.PartyWire
import com.sparkhoward.nagomiani.core.watchparty.PartyWireCodec
import com.sparkhoward.nagomiani.core.watchparty.WatchPartySync
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartySyncTest {

    // MARK: - targetPosition（guest 落位推算）

    @Test
    fun targetPositionAdvancesWithWallClockWhenPlaying() {
        // state 声明 pos=100、1.5s 前发出 → 目标 = 101.5
        val target = WatchPartySync.targetPosition(
            statePosSeconds = 100.0,
            stateWallMs = 1_000_000,
            nowMs = 1_001_500,
            playing = true,
        )
        assertEquals(101.5, target, 0.001)
    }

    @Test
    fun targetPositionFrozenWhenHostPaused() {
        val target = WatchPartySync.targetPosition(100.0, 1_000_000, 1_050_000, playing = false)
        assertEquals(100.0, target)
    }

    @Test
    fun targetPositionClampsFutureWallClock() {
        // wall 在未来（时钟偏差/乱序）：不外推负时间，直接返回 state 位置
        val target = WatchPartySync.targetPosition(100.0, 2_000_000, 1_999_000, playing = true)
        assertEquals(100.0, target, 0.001)
    }

    // MARK: - decide（漂移三档）

    @Test
    fun decideNoneWithinDeadZone() {
        val d = WatchPartySync.decide(localPosSeconds = 100.5, targetPosSeconds = 100.0)
        assertEquals(WatchPartySync.Action.NONE, d.action)
        assertEquals(1.0f, d.speed)
    }

    @Test
    fun decideSeekBeyondThreshold() {
        val ahead = WatchPartySync.decide(localPosSeconds = 105.0, targetPosSeconds = 100.0)
        assertEquals(WatchPartySync.Action.SEEK, ahead.action)
        assertEquals(100.0, ahead.targetSeconds, 0.001)

        val behind = WatchPartySync.decide(localPosSeconds = 90.0, targetPosSeconds = 100.0)
        assertEquals(WatchPartySync.Action.SEEK, behind.action)
        assertEquals(100.0, behind.targetSeconds, 0.001)
    }

    @Test
    fun decideSlowsDownWhenLocalAhead() {
        // 本地快 1.5s：微调减速，速度在 (0.96, 1.0) 区间且随偏差增大而变小
        val near = WatchPartySync.decide(localPosSeconds = 101.0, targetPosSeconds = 100.0)
        val far = WatchPartySync.decide(localPosSeconds = 102.8, targetPosSeconds = 100.0)
        assertEquals(WatchPartySync.Action.ADJUST_SPEED, near.action)
        assertEquals(WatchPartySync.Action.ADJUST_SPEED, far.action)
        assertTrue(near.speed < 1.0f && near.speed > 0.96f, "near=${near.speed}")
        assertTrue(far.speed < near.speed && far.speed >= 0.96f, "far=${far.speed}")
    }

    @Test
    fun decideSpeedsUpWhenLocalBehind() {
        // 本地慢 1.5s：微调加速，与对称超前偏差速度互为镜像
        val near = WatchPartySync.decide(localPosSeconds = 99.0, targetPosSeconds = 100.0)
        assertEquals(WatchPartySync.Action.ADJUST_SPEED, near.action)
        assertTrue(near.speed > 1.0f && near.speed <= 1.04f, "speed=${near.speed}")
        val mirrorAhead = WatchPartySync.decide(localPosSeconds = 101.0, targetPosSeconds = 100.0)
        assertEquals(1.0f + (1.0f - mirrorAhead.speed), near.speed, 0.0001f)
    }

    @Test
    fun decideAtSeekThresholdBoundary() {
        // 恰好 3.0s 触发 seek；3.0s 减一丝仍是微调
        val at = WatchPartySync.decide(localPosSeconds = 103.0, targetPosSeconds = 100.0)
        assertEquals(WatchPartySync.Action.SEEK, at.action)
        val justBelow = WatchPartySync.decide(localPosSeconds = 102.99, targetPosSeconds = 100.0)
        assertEquals(WatchPartySync.Action.ADJUST_SPEED, justBelow.action)
    }

    // MARK: - 房间码

    @Test
    fun roomCodeIsSixCharsFromUnambiguousAlphabet() {
        repeat(50) {
            val code = WatchPartySync.generateRoomCode(Random(42 + it))
            assertEquals(6, code.length)
            assertTrue(code.all { it in "23456789ABCDEFGHJKMNPQRSTUVWXYZ" }, "code=$code")
        }
    }

    @Test
    fun roomCodeGenerationIsSeededReproducible() {
        assertEquals(WatchPartySync.generateRoomCode(Random(7)), WatchPartySync.generateRoomCode(Random(7)))
        assertNotEquals(WatchPartySync.generateRoomCode(Random(1)), WatchPartySync.generateRoomCode(Random(2)))
    }

    @Test
    fun roomCodeNormalizationAcceptsLowercaseAndSpaces() {
        assertEquals("AB2CD9", WatchPartySync.normalizeRoomCode("ab2 cd9"))
        assertNull(WatchPartySync.normalizeRoomCode("AB2CD"))    // 5 位
        assertNull(WatchPartySync.normalizeRoomCode("AB2CD90"))  // 7 位
        assertNull(WatchPartySync.normalizeRoomCode("AB2CDO"))   // O 不在字符表
        assertNull(WatchPartySync.normalizeRoomCode("AB2CD1"))   // 1 不在字符表
        assertNull(WatchPartySync.normalizeRoomCode(""))
    }

    // MARK: - wire 编解码

    @Test
    fun wireRoundTripKeepsAllFields() {
        val state = PartyWire.State(
            playing = true,
            pos = 812.4,
            wall = 1_699_260_000_000,
            epoch = 3,
            episodeNumber = 5,
            subjectId = 428714,
        )
        val decoded = PartyWireCodec.decode(PartyWireCodec.encode(state))
        assertIs<PartyWire.State>(decoded)
        assertEquals(state, decoded)

        val chat = PartyWireCodec.decode("""{"t":"chat","nick":"火","text":"来了","from":"abc"}""")
        assertIs<PartyWire.Chat>(chat)
        assertEquals("火", chat.nick)
        assertEquals("来了", chat.text)
    }

    @Test
    fun wireDecodeDropsGarbageSilently() {
        assertNull(PartyWireCodec.decode("not json"))
        assertNull(PartyWireCodec.decode("""{"t":"unknown"}"""))
        // relay 附带的 from 字段被忽略（ignoreUnknownKeys）
        val state = PartyWireCodec.decode("""{"t":"state","playing":false,"pos":1.0,"wall":1,"from":"x"}""")
        assertIs<PartyWire.State>(state)
        assertEquals(1.0, state.pos, 0.0)
    }

    @Test
    fun wireTypeDiscriminatorIsT() {
        // 与 relay 的 JSON 约定（classDiscriminator="t"）锁定
        val encoded = PartyWireCodec.encode(PartyWire.Ping(at = 123L))
        assertTrue(encoded.contains("\"t\":\"ping\""), encoded)
        assertTrue(encoded.contains("\"at\":123"), encoded)
    }
}
