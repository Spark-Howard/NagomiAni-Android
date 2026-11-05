package com.sparkhoward.nagomiani.core.watchparty

import kotlin.math.abs
import kotlin.random.Random

/**
 * 一起看同步纯逻辑（DanmakuLayout/HistoryStats 范式：无状态、可测）。
 * 房主权威：guest 落位 = state.pos + (now - wall)（房主播放中时推算，暂停冻结），
 * 漂移分三档——死区不动 / 微调速度缓追 / 大偏直接 seek。
 */
object WatchPartySync {

    /** 死区：偏差小于此值不纠偏（频繁微调比小偏差更伤观感） */
    const val DEAD_ZONE_SECONDS = 0.8

    /** 硬对齐阈值：偏差达到此值直接 seek（顺带把微调速度还原） */
    const val SEEK_THRESHOLD_SECONDS = 3.0

    /** 微调档的最大速度修正幅度：从死区边缘线性升到该值（避免贴着死区就猛踩油门） */
    private const val MAX_SPEED_CORRECTION = 0.04

    enum class Action { NONE, ADJUST_SPEED, SEEK }

    data class Decision(val action: Action, val targetSeconds: Double = 0.0, val speed: Float = 1.0f)

    /** guest 应处的目标位置：房主 state 发出后经过的播放时间（房主暂停时位置冻结） */
    fun targetPosition(statePosSeconds: Double, stateWallMs: Long, nowMs: Long, playing: Boolean): Double {
        if (!playing) return statePosSeconds
        val elapsedMs = (nowMs - stateWallMs).coerceAtLeast(0)
        return statePosSeconds + elapsedMs / 1000.0
    }

    /** 漂移决策：drift = local - target。ahead（本地快）→ 减速，behind（本地慢）→ 加速，大偏 seek */
    fun decide(localPosSeconds: Double, targetPosSeconds: Double): Decision {
        val drift = localPosSeconds - targetPosSeconds
        val magnitude = if (abs(drift) < DEAD_ZONE_SECONDS) {
            0.0
        } else {
            val over = abs(drift) - DEAD_ZONE_SECONDS
            val span = SEEK_THRESHOLD_SECONDS - DEAD_ZONE_SECONDS
            MAX_SPEED_CORRECTION * (over / span).coerceAtMost(1.0)
        }
        return when {
            abs(drift) >= SEEK_THRESHOLD_SECONDS -> Decision(Action.SEEK, targetPosSeconds)
            magnitude == 0.0 -> Decision(Action.NONE, targetPosSeconds)
            drift > 0 -> Decision(Action.ADJUST_SPEED, targetPosSeconds, (1 - magnitude).toFloat())
            else -> Decision(Action.ADJUST_SPEED, targetPosSeconds, (1 + magnitude).toFloat())
        }
    }

    // MARK: - 房间码

    /** 房间码字符表：32 个去易混字符（0/O、1/I/L）的大写字母数字 */
    private const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

    /** 生成 6 位房间码（创建方本地生成，relay 只认路径里的码） */
    fun generateRoomCode(random: Random = Random.Default): String =
        buildString { repeat(6) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) } }

    /** 房间码规整：大写 + 去空格；不合法返回 null（加入输入框校验用） */
    fun normalizeRoomCode(raw: String): String? {
        val code = raw.trim().uppercase().replace(" ", "")
        return code.takeIf { it.length == 6 && it.all { c -> CODE_ALPHABET.contains(c) } }
    }
}
