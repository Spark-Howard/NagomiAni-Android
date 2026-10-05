package com.sparkhoward.nagomiani.ui.bangumi

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.sparkhoward.nagomiani.core.bangumi.BangumiAuth
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiPrimaryButton
import com.sparkhoward.nagomiani.ui.theme.NagomiSectionHeader
import com.sparkhoward.nagomiani.ui.theme.NagomiSegmented
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/** 追番页：未登录引导 / 登录后收藏五态列表 */
@Composable
fun BangumiScreen(
    onOpenDetail: (Int) -> Unit,
    onOpenLogin: () -> Unit,
    viewModel: BangumiViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    // 每次回到本页都重查登录态（登录页 popBackStack 返回后能看到已登录）
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshIfLoggedOut() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("追番", style = MaterialTheme.typography.titleLarge)
            }
        }

        when {
            state.loading -> item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            state.user == null -> item {
                Column(Modifier.padding(24.dp)) {
                    Text("登录 Bangumi 账号后可以：", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "· 同步收藏（想看/在看/看过…）\n· 看完自动标记看过\n· 云端番库显示已看徽章",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    NagomiPrimaryButton("登录 Bangumi", onClick = onOpenLogin)
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    ProxySection(viewModel)
                }
            }

            else -> {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = state.user?.avatar?.bestURL(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(state.user?.nickname ?: "", style = MaterialTheme.typography.titleMedium)
                            Text("@${state.user?.username}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "退出登录",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable { viewModel.logout { } },
                        )
                    }
                }
                item {
                    NagomiSegmented(
                        options = CollectionType.entries.toList(),
                        selected = state.selected,
                        label = { it.label },
                        onSelect = viewModel::selectType,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (state.collectionsLoading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                // 加载失败要显示出来（此前只在未登录分支展示，登录后失败是"无声空白"）
                state.error?.let {
                    item {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (!state.collectionsLoading && state.collections.isEmpty() && state.error == null) {
                    item {
                        Text(
                            "该分类下暂无收藏，切换上方标签看看别的分类",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                items(state.collections, key = { it.subjectId }) { collection ->
                    val subject = collection.subject
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDetail(collection.subjectId) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = subject?.images?.bestURL(),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .width(52.dp)
                                .aspectRatio(0.7f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                subject?.displayName ?: "#${collection.subjectId}",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "进度 ${collection.epStatus}" + (subject?.episodeCount?.let { "/$it" } ?: " 集"),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxySection(viewModel: BangumiViewModel) {
    val draft by viewModel.proxyDraft.collectAsState()
    val message by viewModel.proxyMessage.collectAsState()
    Column(Modifier.padding(top = 20.dp)) {
        Text("网络代理（搜索/登录不上时配置）", style = MaterialTheme.typography.titleMedium)
        Text(
            "bgm.tv 在部分网络下被 DNS 污染。填电脑上代理的局域网地址即可，" +
                "例如 192.168.1.5:7890（电脑代理需开「允许局域网连接」）。清空保存恢复直连。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = { viewModel.proxyDraft.value = it },
                placeholder = { Text("192.168.1.5:7890", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Text(
                "保存",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable { viewModel.saveProxy() }
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        message?.let {
            Text(it, fontSize = 12.sp, color = NagomiColors.watchedGreen, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * OAuth 登录页：内嵌 WebView 打开 bgm.tv 授权页。
 * 回调捕获双保险（先到先得）：
 * 1. 主路径——WebViewClient 拦截回调 URL，直接从 URL 解析授权码（不让 WebView 真去请求本机地址，
 *    彻底避开代理拦截/端口占用导致的 net::ERR_CONNECTION_REFUSED）；
 * 2. 备路径——本机 127.0.0.1:8123 回调服务（外部浏览器场景兼容）。
 * 拿到授权码后由主进程经 OkHttp（含代理）换取令牌；完成后自动关页。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val auth = remember { BangumiAuth(context.applicationContext) }
    var status by remember { mutableStateOf("正在打开授权页…") }
    val state = remember { UUID.randomUUID().toString() }
    val codeChannel = remember { kotlinx.coroutines.CompletableDeferred<String>() }
    val targetUrl = remember { mutableStateOf<String?>(null) }
    var loadedUrl by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onDone) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "返回",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text("登录 Bangumi", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            status,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (url.startsWith("http://127.0.0.1:8123")) {
                                val code = BangumiAuth.extractAuthorizationCode(url, state)
                                if (code != null && !codeChannel.isCompleted) {
                                    codeChannel.complete(code)
                                    // 不让 WebView 请求本机回环地址（代理/端口问题会 REFUSED），直接展示成功页
                                    view?.stopLoading()
                                    view?.loadDataWithBaseURL(
                                        null,
                                        "<html><head><meta charset='utf-8'></head>" +
                                            "<body style='font-family:sans-serif;text-align:center;padding-top:80px'>" +
                                            "<h2>授权成功 ✓</h2><p>请返回 NagomiAni</p></body></html>",
                                        "text/html", "utf-8", null,
                                    )
                                    return true
                                }
                                if (!codeChannel.isCompleted) status = "收到回调，正在换取令牌…"
                            }
                            return false
                        }
                    }
                }
            },
            update = { view ->
                val target = targetUrl.value
                if (target != null && loadedUrl != target) {
                    loadedUrl = target
                    view.loadUrl(target)
                }
            },
            modifier = Modifier.weight(1f),
        )
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        // 两段式登录：bgm.tv 登录后跳回授权页会丢失 redirect_uri（invalid_uri 错误的根因），
        // 所以先单独完成 bgm.tv 登录（检测 chii_auth cookie），再打开带全参数的授权页
        val cookieManager = android.webkit.CookieManager.getInstance()
        val loggedIn = cookieManager.getCookie("https://bgm.tv")?.contains("chii_auth") == true
        if (loggedIn) {
            status = "bgm.tv 已登录，正在打开授权页…"
            targetUrl.value = auth.authorizeURL(state)
        } else {
            status = "请先在下方页面登录 bgm.tv 账号，成功后自动继续授权"
            targetUrl.value = "https://bgm.tv/login"
            while (true) { // 退出页面时协程被取消，delay 处中断
                kotlinx.coroutines.delay(800)
                if (cookieManager.getCookie("https://bgm.tv")?.contains("chii_auth") == true) break
            }
            status = "登录成功，正在打开授权页…"
            targetUrl.value = auth.authorizeURL(state)
        }
        // 备路径：本机回调服务（与 WebView 拦截先到先得）
        val serverJob = launch {
            val code = auth.awaitAuthorizationCode(expectedState = state) { url ->
                if (!codeChannel.isCompleted) {
                    BangumiAuth.extractAuthorizationCode(url, state)?.let { codeChannel.complete(it) }
                }
            }
            if (code != null) codeChannel.complete(code)
        }
        try {
            val code = kotlinx.coroutines.withTimeout(360_000L) { codeChannel.await() }
            status = "登录中…"
            try {
                val session = auth.completeLogin(code)
                if (session.isLoggedIn) {
                    status = "登录成功 ✓"
                    onDone()
                } else {
                    status = "登录失败：未取得令牌，请重试"
                }
            } catch (e: Exception) {
                // 换 token 失败（网络/授权码失效等）要显示出来，不能让协程异常顶穿崩溃
                status = "登录失败：${e.message ?: "网络错误"}，请重试"
            }
        } catch (e: Exception) {
            status = "登录超时或已取消，请重试"
        } finally {
            serverJob.cancel()
        }
    }
}
