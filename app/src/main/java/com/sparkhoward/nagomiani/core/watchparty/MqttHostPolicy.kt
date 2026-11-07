package com.sparkhoward.nagomiani.core.watchparty

/**
 * MQTT 无服务器模式的房主裁决（纯函数，可测）。
 * 关键不变量：**恰好一人认为自己是房主**——
 * - 创建方（hostHint）：恒自任（断线重连凭稳定 id 立即夺回，成员表无缝衔接）
 * - 在位自荐（TTL 内 presence.host=true）优先，取最小 id（防双自荐脑裂）
 * - 无人自荐（房主掉线自荐过期）→ **含自己在内的**最小 id 成员接任——
 *   含自己才保证所有成员算出同一结果、且恰好一人接任（排除自己会出现双方互指的死锁）
 */
internal object MqttHostPolicy {
    fun resolveHost(
        liveClaimMinId: String?,
        minMemberId: String?,
        selfId: String,
        hostHint: Boolean,
    ): String = when {
        hostHint -> selfId
        liveClaimMinId != null -> liveClaimMinId
        else -> minOf(minMemberId ?: selfId, selfId)
    }
}
