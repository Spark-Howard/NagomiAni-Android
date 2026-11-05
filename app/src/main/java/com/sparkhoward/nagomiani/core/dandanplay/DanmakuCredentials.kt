package com.sparkhoward.nagomiani.core.dandanplay

import android.content.Context

/** 内置弹幕凭据（assets/danmaku-credentials.bin，XOR 0x5A，构建期生成；缺失则弹幕整体停用） */
object DanmakuCredentialsHolder {
    fun load(context: Context): Pair<String, String>? = runCatching {
        val raw = context.assets.open("danmaku-credentials.bin").use { it.readBytes() }
            .map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        val text = String(raw, Charsets.UTF_8)
        val lines = text.split('\n').filter { it.isNotBlank() }
        if (lines.size >= 2) lines[0].trim() to lines[1].trim() else null
    }.getOrNull()
}
