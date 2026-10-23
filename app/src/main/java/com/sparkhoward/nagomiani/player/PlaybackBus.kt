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

    /** 应用内悬浮小窗：true 时视频在根层小窗继续播放（播放器路由已退出、VM 仍存活）。
     *  离开 App（主页键）时自动切系统画中画保留到桌面（见 MainActivity.onUserLeaveHint） */
    val miniActive = MutableStateFlow(false)

    /** 系统画中画进行中（MainActivity.onPictureInPictureModeChanged 写入；
     *  PlayerScreen 据此按沉浸布局渲染：视频铺满、控制条隐藏、单击切播放/暂停） */
    val inPip = MutableStateFlow(false)

    fun launch(playback: OnlinePlayback) {
        pending.value = playback
    }
}
