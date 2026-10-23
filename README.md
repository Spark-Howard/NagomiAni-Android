# NagomiAnime — NagomiAni 安卓版（核心版）

macOS 桌面应用 [NagomiAni](https://github.com/Spark-Howard/NagomiAni) 的 Android 移植版。
技术栈：**Kotlin + Jetpack Compose (Material 3) + Media3/ExoPlayer**，minSdk 26 / targetSdk 35。

## 功能（核心版）

| 模块 | 说明 |
|---|---|
| 搜索 | Bangumi 条目搜索（动漫过滤）＋「新番放送（近一周 · 未来一周）」周历（放送表随"今天"逐日滚动：窗口为今天 ±7 天、日期升序，**一天一页横滑翻页**——打开自动定位今天，左右滑看前一天/后一天的更新，离开今天后右下角「回到今天」一键返回；刷新栏常驻顶部；天头带真实日期，过去/今天/未来三级配色；每天 3 列海报网格） |
| 条目详情 | 头图/评分/排名/标签/infobox/简介；我的收藏五态（想看/看过/在看/搁置/抛弃）；**收藏评分与短评**（5 星 = 1-10 分 + 短评，同步 Bangumi）；**分集标记**（格子点选弹确认防误触、批量全部看过或清除、未开播置灰、头部显示「看到第 X 集」，ep_status 只增不减自动对账）；**在线观看**跨片源搜索与分集点播 |
| 在线片源 | MacCMS V10 采集站协议（内置量子/极速/爱坤/暴风 4 站＋用户自加站点）；`$$$`/`#`/`$` 剧集文法、HTML 实体双重转义解码、动漫分类过滤、多线路（换线路保留进度） |
| 播放器 | ExoPlayer 全屏播放；断点续播（<15s 不续、距尾 30s 视为看完、5s 节流落盘）；看完自动同步 Bangumi（90% 或 EOF，≥300s，seek 后 10s 不判定，未收藏自动补「在看」再标记，离线队列 200 条补同步）；95%/EOF 连播征询条（点「看下一集」才切换）；播放中底栏可切线路；播放手势竖屏/全屏一致（作用于视频区：单击控制条、双击播放/暂停、横拖滑进度、长按 3× 快进、右/左半侧上下滑调音量/亮度）；迷你小窗（唯一迷你形态：控制条按钮退出页面继续看——单指拖动移位、双指捏合缩放；点画面呼出控制钮 2s 自动隐藏：关闭右上、播放/暂停居中、最大化右下，暂停时中央显示三角；回桌面自动切系统画中画保留到桌面，回 App 自动还原）；截图分享卡（控制条相机按钮定格当前画面：PixelCopy 取视频表面干净帧＋弹幕层快照，自动裁黑边；弹层里「分享卡/纯净截图」两种样式、带/不带弹幕开关、分享卡可附一句话配文，樱花浅色卡带番名/集数/进度条/水印；一键保存相册（API 29+ 免权限）或系统分享）；线路测速与断流自动换线（多线路会话进入时后台探测各线路直链 TTFB，竖屏面板标 ⚡最快+延迟、全屏线路 ⚡ 前缀；播放出错或连续缓冲 ≥10s 自动换下一条线路并保留进度，提示条告知，换遍全部线路才显示错误 UI；手动切线重新武装自动兜底，换集复位） |
| 弹幕 | dandanplay 开放 API（SHA256 签名、番名+集号匹配「宁缺毋滥」）；Choreographer 逐帧 Canvas 渲染（与 mac 版同管线语义：墙钟插值 + 锚点陈旧钳制 1.5s + 暂停完全冻结）；车道算法常量与 mac 版一致（滚动 12s / 停留 5s / 上方 75% 区域）；设置弹层（开关/字号/颜色三模式+色板/不透明度，即时生效并持久化） |
| 番库 | 继续观看（resume 聚合、一番一卡、进度条，点击续播）；云端番库（收藏的片源、新集计数、已看徽章、分集点播、移除）；更新提醒（WorkManager 每日比对片源集数，新集发系统通知点按回番库，页头开关可关；已通知基线与已看基线独立，同一批新集只提醒一次）；片源健康（站点 API 并发测速：延迟/可达性/⚡最快标记，首次展开自动测、手动可刷新，结果本机持久化；详情页每个片源行同挂延迟徽章；自加站点的添加/删除入口也在此——AppStore 接口此前已有但一直无 UI） |
| 追番 | Bangumi OAuth 登录（内嵌 WebView + 本机 127.0.0.1:8123 回调，复用 mac 版注册的 client）；收藏五态列表（全量翻页、四种排序、进度条、「+1」快捷推进）；「今日更新提醒」开关（每日检查放送表 × 在看收藏，命中发通知）；页头刷新按钮手动重拉用户信息与收藏 |
| 更新 | 启动时静默检查 GitHub Releases（`releases/latest`，无新版本/失败均不打扰）；发现新 tag 弹更新框（可「下次再说」跳过该版本）；应用内下载 APK 带进度，经 FileProvider 唤起系统安装器（Android 8+ 需允许「安装未知应用」）；发布流程见下 |
| 观看历史与统计 | 番库页「历史 · 统计 ›」进入：全部看过的集按天分组（可搜索/按在看·看完筛选，点行续播）＋统计（总时长/看过番数/看完集数/观看天数、近 7 日柱状图、年度热力图、年度看番报告 TOP 番）；时长按实际播放秒数累计（seek 不计）；数据备份/恢复（导出 JSON 分享、导入新者胜合并） |
| 小部件与快捷方式 | 桌面小部件：「🌸 今日新番」（Bangumi 放送表 JST 锚定，点条目直达详情）与「🌸 观看统计」（近 7 日柱状图，点击进历史页），樱粉风格自动跟随深浅色；长按图标快捷方式直达 搜索/番库/追番 |
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
