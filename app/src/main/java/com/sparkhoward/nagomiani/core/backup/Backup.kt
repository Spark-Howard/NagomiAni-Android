package com.sparkhoward.nagomiani.core.backup

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.store.AppStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据备份/恢复（换机迁移）：观看历史 + 用户设置。
 * 导出 = cacheDir/backup/ 落 JSON → FileProvider URI → 系统分享面板；
 * 导入 = 文件选择器给的 Uri 读流解析 → 历史按 lastWatchedAt 新者胜合并、设置逐项回写。
 * 不含 resume/knownShows（合规版遗留数据）与 skippedUpdate（设备本地语义）。
 */
object Backup {

    @Serializable
    data class Data(
        val version: Int = 1,
        val exportedAt: Long,
        val history: Map<String, AppStore.HistoryRecord> = emptyMap(),
        // 设置显式字段（新增设置时在此扩列，旧备份解析到缺省 null 即跳过）
        val autoplayNext: Boolean? = null,
        val danmakuEnabled: Boolean? = null,
        val danmakuFontSize: Double? = null,
        val danmakuColorMode: String? = null,
        val danmakuCustomColor: Int? = null,
        val danmakuOpacity: Double? = null,
        val networkProxy: String? = null,
        val dailyAiringEnabled: Boolean? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** 生成备份文件并拉起分享；返回给用户的结果文案 */
    suspend fun export(context: Context): String {
        val store = AppGraph.store
        val data = Data(
            exportedAt = System.currentTimeMillis(),
            history = store.historyOnce(),
            autoplayNext = store.autoplayNext.first(),
            danmakuEnabled = store.danmakuEnabled.first(),
            danmakuFontSize = store.danmakuFontSize.first(),
            danmakuColorMode = store.danmakuColorMode.first(),
            danmakuCustomColor = store.danmakuCustomColor.first(),
            danmakuOpacity = store.danmakuOpacity.first(),
            networkProxy = store.networkProxyRaw.first(),
            dailyAiringEnabled = store.dailyAiringEnabled.first(),
        )
        val dir = File(context.cacheDir, "backup").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // 只留最新一份
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "NagomiAni备份_$stamp.json")
        file.writeText(json.encodeToString(data))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri) // 部分机型的分享面板不透传 EXTRA_STREAM 的读授权
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 从 Application context 启动必须带 NEW_TASK，且 chooser 包装 Intent 不继承内层的 flags
        val chooser = Intent.createChooser(send, "分享备份文件")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        return "已生成备份：${data.history.size} 条观看记录 + 设置"
    }

    /** 解析并恢复所选备份；返回给用户的结果文案（历史合并、设置回写） */
    suspend fun import(context: Context, uri: Uri): String {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return "无法读取所选文件"
        val data = try {
            json.decodeFromString<Data>(text)
        } catch (e: Exception) {
            return "备份文件格式不对，请选择本应用导出的 JSON"
        }
        val store = AppGraph.store
        val total = store.mergeImportedHistory(data.history)
        data.autoplayNext?.let { store.setAutoplayNext(it) }
        data.danmakuEnabled?.let { store.setDanmakuEnabled(it) }
        data.danmakuFontSize?.let { store.setDanmakuFontSize(it) }
        data.danmakuColorMode?.let { store.setDanmakuColorMode(it) }
        data.danmakuCustomColor?.let { store.setDanmakuCustomColor(it) }
        data.danmakuOpacity?.let { store.setDanmakuOpacity(it) }
        data.networkProxy?.let { store.setNetworkProxy(it) }
        data.dailyAiringEnabled?.let { store.setDailyAiringEnabled(it) }
        return "已恢复 ${data.history.size} 条观看记录与设置（本机现有 $total 条）"
    }
}
