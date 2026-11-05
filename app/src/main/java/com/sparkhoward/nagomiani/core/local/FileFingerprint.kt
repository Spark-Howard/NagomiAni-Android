package com.sparkhoward.nagomiani.core.local

import java.io.InputStream
import java.security.MessageDigest

/**
 * 本地视频指纹：取文件首 N 字节（dandanplay match 约定 16MB）算 MD5 小写 hex。
 * 纯函数可 JVM 单测（limitBytes 可注入小值用小流验证）；流由调用方负责关闭。
 */
object FileFingerprint {

    /** dandanplay /api/v2/match 的指纹口径：文件首 16MB */
    const val MATCH_BYTES = 16 * 1024 * 1024

    fun md5Prefix(input: InputStream, limitBytes: Int = MATCH_BYTES): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        var remaining = limitBytes.toLong()
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            remaining -= read
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
