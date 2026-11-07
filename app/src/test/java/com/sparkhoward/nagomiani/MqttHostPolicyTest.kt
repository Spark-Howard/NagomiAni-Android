package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.watchparty.MqttHostPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class MqttHostPolicyTest {

    @Test
    fun hostHintAlwaysSelf() {
        // 创建方：无论在场表里谁自荐，恒自任（凭稳定 id 断线重连夺回）
        assertEquals(
            "me",
            MqttHostPolicy.resolveHost(liveClaimMinId = "aaa", minMemberId = "aaa", selfId = "me", hostHint = true),
        )
        assertEquals(
            "me",
            MqttHostPolicy.resolveHost(liveClaimMinId = null, minMemberId = null, selfId = "me", hostHint = true),
        )
    }

    @Test
    fun liveClaimBeatsFallback() {
        assertEquals(
            "claimant",
            MqttHostPolicy.resolveHost(liveClaimMinId = "claimant", minMemberId = "me", selfId = "me", hostHint = false),
        )
    }

    @Test
    fun takeoverIncludesSelfInMinId() {
        // 关键不变量：无人自荐时，接任者 = 含自己在内的最小 id——
        // 排除自己会让两成员互指对方（双方都认为「房主是别人」），永远无人接任；
        // 含自己则双方算出同一结果（谁是更小 id 谁接任），恰好一人接任
        assertEquals(
            "a",
            MqttHostPolicy.resolveHost(liveClaimMinId = null, minMemberId = "b", selfId = "a", hostHint = false),
        )
        // 成员 a、自己 b：算出的都是 a（一致收敛），自己不接任
        assertEquals(
            "a",
            MqttHostPolicy.resolveHost(liveClaimMinId = null, minMemberId = "a", selfId = "b", hostHint = false),
        )
    }

    @Test
    fun emptyTableFallsBackToSelf() {
        assertEquals(
            "me",
            MqttHostPolicy.resolveHost(liveClaimMinId = null, minMemberId = null, selfId = "me", hostHint = false),
        )
    }
}
