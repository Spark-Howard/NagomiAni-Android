package com.sparkhoward.nagomiani.core.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.dandanplay.DanmakuCredentialsHolder
import com.sparkhoward.nagomiani.core.dandanplay.DandanplayApi
import com.sparkhoward.nagomiani.core.matching.MediaMatching
import com.sparkhoward.nagomiani.core.model.OnlineEpisode
import com.sparkhoward.nagomiani.core.model.OnlinePlayback
import com.sparkhoward.nagomiani.core.store.AppStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 本地媒体库：SAF 目录扫描 + 惰性指纹匹配。点击文件时才算 16MB MD5 并请求
 * dandanplay match（有频控，不批量预扫），结果持久化到 AppStore.localItems 供复用。
 *
 * 键约定（HistoryRecord/继续观看/历史回放依赖此格式，与 "online:…" 同构）：
 * - seriesKey = "local:d<animeId>"（已匹配，继续观看一番一卡）或 "local:f<hash8>"（未匹配按文件）
 * - resumeKey = "<seriesKey>:<集号>"
 *
 * 本地番不绑定 Bangumi：markWatched 因无绑定自然跳过，观看只进本地历史/续播。
 */
object LocalMediaLibrary {

    private val VIDEO_EXTENSIONS =
        setOf("mp4", "mkv", "avi", "mov", "wmv", "m4v", "flv", "webm", "ts", "rmvb", "mpg", "mpeg", "3gp")

    data class LocalMediaItem(
        val uri: String,
        val displayName: String,
        val sizeBytes: Long,
        /** 已持久化的解析结果（null=尚未点击解析过） */
        val meta: AppStore.LocalItemMeta?,
    )

    data class State(
        val folders: List<String> = emptyList(),
        val files: List<LocalMediaItem> = emptyList(),
        val scanning: Boolean = false,
        /** 正在做指纹/匹配的文件（行内状态提示） */
        val resolvingUri: String? = null,
    )

    val state = MutableStateFlow(State())

    /** 添加目录（持久化读权限 + 落盘 + 重扫） */
    suspend fun addFolder(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        AppGraph.store.addLocalFolder(treeUri.toString())
        refresh(context)
    }

    suspend fun removeFolder(context: Context, uri: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        AppGraph.store.removeLocalFolder(uri)
        refresh(context)
    }

    /** 重扫全部授权目录（合并持久化 meta；目录里已消失的文件同步丢弃其 meta）。
     *  全部目录都读不了（存储未挂载等瞬态）时跳过清理，避免误删指纹/匹配缓存 */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val store = AppGraph.store
        val folders = store.localFolders.first().sorted()
        state.value = state.value.copy(folders = folders, scanning = true)
        val metas = store.localItemsOnce()
        val files = ArrayList<LocalMediaItem>()
        var scannedAny = false
        for (folderUri in folders) {
            val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) }.getOrNull() ?: continue
            scannedAny = true
            walk(tree, files, metas)
        }
        if (scannedAny) store.retainLocalItems(files.map { it.uri }.toSet())
        state.value = state.value.copy(files = files, scanning = false)
    }

    private fun walk(dir: DocumentFile, out: MutableList<LocalMediaItem>, metas: Map<String, AppStore.LocalItemMeta>) {
        runCatching {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    walk(child, out, metas)
                } else {
                    val name = child.name ?: continue
                    if (name.substringAfterLast('.', "").lowercase() !in VIDEO_EXTENSIONS) continue
                    val uri = child.uri.toString()
                    out += LocalMediaItem(uri, name, child.length(), metas[uri])
                }
            }
        }
    }

    /**
     * 点击播放：惰性解析（指纹 → dandanplay match → 键/集号），成功返回播放参数并持久化 meta。
     * 无凭据 / 匹配失败 / 网络错误时用文件名解析集号兜底（弹幕由播放器按番名标题匹配兜底）。
     */
    suspend fun preparePlayback(context: Context, item: LocalMediaItem): OnlinePlayback? = withContext(Dispatchers.IO) {
        state.value = state.value.copy(resolvingUri = item.uri)
        try {
            val store = AppGraph.store
            var meta = item.meta ?: AppStore.LocalItemMeta(displayName = item.displayName, sizeBytes = item.sizeBytes, resumeKey = "")

            // 1. 指纹（只算一次并持久化）
            val hash = meta.hash ?: run {
                val computed = runCatching {
                    context.contentResolver.openInputStream(Uri.parse(item.uri))?.use { FileFingerprint.md5Prefix(it) }
                }.getOrNull()
                if (computed != null) meta = meta.copy(hash = computed)
                computed
            } ?: return@withContext null // 文件读不了（被移动/权限丢失）

            // 2. dandanplay 指纹匹配（凭据在且该文件尚未匹配过时；逐次点击触发，规避频控）
            if (meta.animeId == null) {
                val credentials = DanmakuCredentialsHolder.load(context)
                if (credentials != null) {
                    val api = DandanplayApi(credentials.first, credentials.second)
                    val result = api.match(meta.displayName, hash, meta.sizeBytes)
                    val best = result.matches.firstOrNull()
                    if (result.isMatched && best != null) {
                        meta = meta.copy(
                            animeTitle = best.animeTitle.ifEmpty { meta.animeTitle },
                            animeId = best.animeId.takeIf { it > 0 },
                            episodeId = best.episodeId,
                            // 集号优先从匹配到的集名解析（"第01话 …"），文件名兜底
                            episodeNumber = MediaMatching.episodeNumber(best.episodeTitle)
                                ?: MediaMatching.episodeNumber(meta.displayName)
                                ?: meta.episodeNumber,
                        )
                    }
                }
            }

            // 3. 集号与键：已匹配用匹配时解析的集号，否则文件名 → 1
            val episodeNumber = meta.episodeNumber
                .takeIf { meta.episodeId != null && it > 0 }
                ?: MediaMatching.episodeNumber(meta.displayName)
                ?: 1
            val seriesKey = when {
                meta.animeId != null -> "local:d${meta.animeId}"
                hash.isNotEmpty() -> "local:f${hash.take(8)}"
                else -> "local:u${meta.displayName.hashCode().toUInt()}"
            }
            meta = meta.copy(episodeNumber = episodeNumber, resumeKey = "$seriesKey:$episodeNumber")
            store.upsertLocalItem(item.uri, meta)

            playbackFromMeta(meta, item.uri)
        } finally {
            state.value = state.value.copy(resolvingUri = null)
        }
    }

    /** 已解析文件的回放参数（继续观看卡片 / 历史行续播用；同步查持久化 meta，不重算指纹） */
    suspend fun playbackFor(resumeKey: String): OnlinePlayback? {
        val entry = AppGraph.store.localItemsOnce().entries.firstOrNull { it.value.resumeKey == resumeKey }
            ?: return null
        return playbackFromMeta(entry.value, entry.key)
    }

    /** 同系列的已解析下一集（连播/选集用；未解析过的集返回 null，提示用户先去缓存页点一次） */
    suspend fun playbackForSeriesEpisode(seriesKey: String, episodeNumber: Int): OnlinePlayback? {
        val entry = AppGraph.store.localItemsOnce().entries
            .firstOrNull { it.value.resumeKey == "$seriesKey:$episodeNumber" }
            ?: return null
        return playbackFromMeta(entry.value, entry.key)
    }

    /** 同系列的已解析集列表（竖屏面板选集/上一集下一集按钮态用；number/title 参与 UI，键字段无意义） */
    suspend fun episodesFor(seriesKey: String): List<OnlineEpisode> =
        AppGraph.store.localItemsOnce().values
            .filter { it.resumeKey.startsWith("$seriesKey:") }
            .map { OnlineEpisode(providerID = "local", showID = seriesKey, number = it.episodeNumber, title = it.animeTitle) }
            .sortedBy { it.number }

    private fun playbackFromMeta(meta: AppStore.LocalItemMeta, uri: String): OnlinePlayback = OnlinePlayback(
        url = uri,
        displayTitle = meta.animeTitle?.let { "《$it》第 ${meta.episodeNumber} 集" } ?: meta.displayName,
        seriesKey = meta.resumeKey.substringBeforeLast(':'),
        episodeNumber = meta.episodeNumber,
        resumeKey = meta.resumeKey,
        httpHeaders = emptyMap(),
        userAgent = null,
        routes = listOf(uri),
        showTitle = meta.animeTitle,
        danmakuEpisodeId = meta.episodeId,
    )
}
