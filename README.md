# NagomiAnime — NagomiAni 安卓版（合规版）

macOS 桌面应用 [NagomiAni](https://github.com/Spark-Howard/NagomiAni) 的 Android 移植版。
技术栈：**Kotlin + Jetpack Compose (Material 3)**，minSdk 26 / targetSdk 35。

> **合规版说明**：自本版起**移除了在线片源聚合功能**（MacCMS 采集站搜索/点播/缓存/播放器/弹幕渲染/
> 断点续播/离线下载/更新提醒）。采集站属于无授权盗版源，聚合传播有著作权风险，相关模块已整体
> 删除（非隐藏）。本项目现为一个纯粹的 **Bangumi 追番助手**：仅使用 bgm.tv 官方开放 API。
> 历史版本（≤v0.3.0）的播放相关代码可在对应 tag 中找到。

## 功能（合规版）

| 模块 | 说明 |
|---|---|
| 搜索 | Bangumi 条目搜索（动漫过滤）＋「新番放送（近一周 · 未来一周）」周历（Bangumi /calendar 每周放送表随"今天"逐日向前滚动：窗口为今天 ±7 天、按日期升序，打开自动定位到今天——上滑看近几天更新过的、下滑看未来几天待放的；天头带真实日期标签，过去/今天/未来三级配色） |
| 条目详情 | 头图/评分/排名/标签/infobox/简介；我的收藏五态（想看/看过/在看/搁置/抛弃，常显当前状态徽章）；收藏评分与短评（5 星 = Bangumi 1-10 分制 + 短评，同步到服务端并回显）；分集标记（格子点选「看过」/批量全部看过或清除，未开播集置灰，ep_status 只增不减自动对账）；移除收藏（官方 API 无删除端点，弹窗说明并深链网页端，官方建议改「抛弃」代替） |
| 观看历史 | 底部 Tab 直达：历史列表（可搜索/按在看/看完筛选、按天分组，本地 `player.history.json`）＋统计（总观看时长/看过番数/看完集数/观看天数概览、近 7 日分布柱状图、年度热力图（GitHub 打卡格风格，年分切换）、年度看番报告（TOP 番））、数据备份/恢复（导出 JSON 经系统分享、导入按「新记录胜出」合并，含观看历史与设置）。在线播放已移除，历史仅作本地记录展示，不再新增记录 |
| 追番 | Bangumi OAuth 登录（内嵌 WebView + 本机 127.0.0.1:8123 回调，复用 mac 版注册的 client）；收藏五态列表（全量翻页拉取、默认/最近更新/我的评分/放送日期排序、每行观看进度条 + 部数汇总 + 「在看」行「+1」快捷推进一集）；页头刷新按钮手动重拉用户信息与收藏；「今日更新提醒」开关（每日检查放送表 × 在看收藏，命中发系统通知，Android 13+ 运行时申请通知权限） |
| 更新 | 启动时静默检查 GitHub Releases（`releases/latest`，无新版本/失败均不打扰）；发现新 tag 弹更新框（可「下次再说」跳过该版本）；应用内下载 APK 带进度，经 FileProvider 唤起系统安装器（Android 8+ 需允许「安装未知应用」）；发布流程见下 |
| 小部件与快捷方式 | 桌面小部件两个（经典 RemoteViews，樱粉风格自动跟随深浅色）：「今日新番」（4×3，Bangumi 放送表 JST 锚定，今日更新列表，点行直达条目详情、点头部进搜索页，30 分钟自动刷新 + 手动刷新，网络失败回退上次缓存）与「观看统计」（4×2，总时长/看过番数/看完集数三列 + 近 7 日柱状图，点击进历史页）；长按图标静态快捷方式直达 搜索/历史/追番 三个 Tab（singleTop + route extra 导航，冷热启动均覆盖） |
| 视觉 | 樱粉主题完整还原（accent #EC6A88、浅色 #FFF7F9 / 深色 #20181B、胶囊按钮/粉描边卡片/分段选择器/徽章） |

## 构建与安装

```bash
# 命令行（需要 JDK 17+ 与 Android SDK，local.properties 指向 sdk.dir）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk（直接 adb install 或传输安装）

./gradlew testDebugUnitTest   # 单元测试（集号正则/标题相似度/弹幕签名/实体解码/车道算法）
```

或用 Android Studio 打开本目录直接 Run。

## 发布新版本（应用内更新的分发渠道）

1. 改 `app/build.gradle.kts` 的 `versionCode`（+1）与 `versionName`（如 `0.2.0`）
2. `./gradlew assembleRelease` → 产物 `app/build/outputs/apk/release/app-release.apk`
   - 发布签名：根目录放置 gitignored 的 `signing.private`（四行：storeFile / storePassword /
     keyAlias / keyPassword，storeFile 相对路径以仓库根为基准）；缺失时回退 debug 签名
     （同机自用分发足够；正式分发建议 `keytool` 生成专用 keystore。注意：应用内更新要求
     新旧包签名一致，签名变更后老用户必须卸载重装）
3. 在 GitHub 新建 Release：tag 填 `v0.2.0`（需与 versionName 对应，否则更新检查无法比较版本），
   更新说明写在 body，附件挂上 APK

应用侧即会自动发现该 Release 并提示更新；`releases/latest` 不含 pre-release 与 draft。

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
│   ├── maccms/              MacCMSApi（采集站协议）、OnlineRepo（跨站搜索/取流装配）、HtmlEntities、SourceHealth（站点/线路测速与最快标记）
│   ├── danmaku/             DanmakuLayout（车道分配，常量同 mac 版）
│   ├── notify/              EpisodeNotify（追番更新提醒：WorkManager 每日检查 + 系统通知）
│   ├── matching/            MediaMatching（集号正则表）
│   ├── similarity/          TitleSimilarity（Dice 二元组+包含保底）
│   ├── store/               AppStore（DataStore：绑定表/续播/设置/云端番库/待同步队列）
│   └── model/               Bangumi/Online wire 模型
├── player/                  PlayerViewModel/Screen（ExoPlayer）、MiniPlayerOverlay、DanmakuOverlayView、DanmakuController、PlaybackBus、ShareCardComposer/ShareCardSheet（截图分享卡）
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
