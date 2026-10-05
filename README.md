# NagomiAnime — NagomiAni 安卓版（核心版）

macOS 桌面应用 [NagomiAni](https://github.com/Spark-Howard/NagomiAni) 的 Android 移植版。
技术栈：**Kotlin + Jetpack Compose (Material 3) + Media3/ExoPlayer**，minSdk 26 / targetSdk 35。

## 功能（核心版）

| 模块 | 说明 |
|---|---|
| 搜索 | Bangumi 条目搜索（动漫过滤）＋「最近更新（过去一周）」周历（今天在前，含空档日） |
| 条目详情 | 头图/评分/排名/标签/infobox/简介；我的收藏五态（想看/看过/在看/搁置/抛弃）；**在线观看**跨片源搜索与分集点播 |
| 在线片源 | MacCMS V10 采集站协议（内置量子/极速/爱坤/暴风 4 站＋用户自加站点）；`$$$`/`#`/`$` 剧集文法、HTML 实体双重转义解码、动漫分类过滤、多线路（换线路保留进度） |
| 播放器 | ExoPlayer 全屏播放；断点续播（<15s 不续、距尾 30s 视为看完、5s 节流落盘）；看完自动同步 Bangumi（95% 或 EOF，≥300s，seek 后 10s 不判定，离线队列 200 条补同步）；95%/EOF 连播征询条（点「看下一集」才切换）；播放中底栏可切线路 |
| 弹幕 | dandanplay 开放 API（SHA256 签名、番名+集号匹配「宁缺毋滥」）；Choreographer 逐帧 Canvas 渲染（与 mac 版同管线语义：墙钟插值 + 锚点陈旧钳制 1.5s + 暂停完全冻结）；车道算法常量与 mac 版一致（滚动 12s / 停留 5s / 上方 75% 区域）；设置弹层（开关/字号/颜色三模式+色板/不透明度，即时生效并持久化） |
| 番库 | 继续观看（resume 聚合、一番一卡、进度条，点击续播）；云端番库（收藏的片源、新集计数、已看徽章、分集点播、移除） |
| 追番 | Bangumi OAuth 登录（内嵌 WebView + 本机 127.0.0.1:8123 回调，复用 mac 版注册的 client）；收藏五态列表 |
| 视觉 | 樱粉主题完整还原（accent #EC6A88、浅色 #FFF7F9 / 深色 #20181B、胶囊按钮/粉描边卡片/分段选择器/徽章） |

## 构建与安装

```bash
# 命令行（需要 JDK 17+ 与 Android SDK，local.properties 指向 sdk.dir）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk（直接 adb install 或传输安装）

./gradlew testDebugUnitTest   # 单元测试（集号正则/标题相似度/弹幕签名/实体解码/车道算法）
```

或用 Android Studio 打开本目录直接 Run。

## 弹幕凭据（弹弹play）

真实凭据**永不进仓库**：仓库根目录放置 gitignored 的 `DanmakuCredentials.private`
（两行：第一行 AppId、第二行 AppSecret），构建期任务 `generateDanmakuCredentials`
将其 XOR 0x5A 混淆后写入 `app/src/main/assets/danmaku-credentials.bin`，应用启动时解码
（与 mac 版 pack.sh 同构）。文件缺失时构建照常，弹幕功能静默停用。
凭据泄露时到弹弹play 开放平台重置 AppSecret 并重新构建即可。

## 代码结构

```
app/src/main/java/com/sparkhoward/nagomiani/
├── MainActivity.kt          底部导航（搜索/番库/追番）＋播放器路由＋「回到播放中」提示条
├── core/
│   ├── bangumi/             BangumiApi（v0+legacy 端点/UA/重试/宽松解析）、BangumiAuth（OAuth+token 存储+回调服务）
│   ├── dandanplay/          DandanplayApi（签名/搜索/匹配/弹幕拉取）
│   ├── maccms/              MacCMSApi（采集站协议）、OnlineRepo（跨站搜索/取流装配）、HtmlEntities
│   ├── danmaku/             DanmakuLayout（车道分配，常量同 mac 版）
│   ├── matching/            MediaMatching（集号正则表）
│   ├── similarity/          TitleSimilarity（Dice 二元组+包含保底）
│   ├── store/               AppStore（DataStore：绑定表/续播/设置/云端番库/待同步队列）
│   └── model/               Bangumi/Online wire 模型
├── player/                  PlayerViewModel/Screen（ExoPlayer）、DanmakuOverlayView、DanmakuController、PlaybackBus
└── ui/                      theme（Nagomi 主题与组件库）、search、library、bangumi 页面
```

## 本轮未做（后续版本）

本地 SAF 媒体库、离线缓存下载（Media3 CacheDataSource）、bgm.tv 私信聊天/好友、
条目详情的角色/制作/讨论/评论 Tab、弹幕本地文件指纹匹配（16MB MD5）、标题翻译器。

## 已知取舍

- 弹幕时间轴以播放器位置为锚（与 mac 版一致），ExoPlayer 的 position 粒度为帧级插值
- OAuth 回调主路径为 WebViewClient 拦截（从回调 URL 直接解析授权码，不依赖 WebView 请求
  `127.0.0.1:8123`）；本机端口监听仅作外部浏览器场景的备用，若未来失效可换 App Links
- 「重新获取弹幕」当前为提示占位，需重进本集触发（后续可直连 controller.refetch）

## 网络环境说明（DNS 污染）

bgm.tv 整域（api/bgm/lain）托管在 Cloudflare，部分国内网络会把它 DNS 污染成无关 IP
（mac 版靠 macOS 系统代理存活）。安卓版的对策分三层：

1. **应用内代理**（追番页登录卡片）：OkHttp、WebView（OAuth 授权页）、Coil 封面全部生效；
   电脑代理开「允许局域网连接」后填其局域网地址（如 `192.168.1.5:7890`）即可，清空保存恢复直连
2. **DoH 兜底**：bgm 系域名直连 1.1.1.1 解析（失败熔断 5 分钟）
3. **边缘 IP 兜底**：DoH 不可用时直连 zone 分配的 Cloudflare 边缘 IP（SNI 路由）

被污染但未封 IP 的网络会自动恢复；IP 也被封的网络请用方案 1。
