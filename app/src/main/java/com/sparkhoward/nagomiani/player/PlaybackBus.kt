package com.sparkhoward.nagomiani.player

import com.sparkhoward.nagomiani.core.model.OnlinePlayback
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 播放会话总线：点播入口把参数放进 [pending]，导航到播放器路由后由 PlayerViewModel 取用。
 * pending 保留最后一次播放参数（不清空）——离开播放器页后可随时"回到播放中"
 * （重新进入时 ViewModel 按 resumeKey 去重/续播）。
 */
object PlaybackBus {
    /** 当前/待播放参数（进播放器页读取） */
    val pending = MutableStateFlow<OnlinePlayback?>(null)

    /** 播放器是否正在播放（其他页面据此显示"回到播放中"入口；切离页面自动暂停） */
    val isPlaying = MutableStateFlow(false)

    fun launch(playback: OnlinePlayback) {
        pending.value = playback
    }
}
