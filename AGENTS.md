# AGENTS.md — NagomiAnime（NagomiAni Android 合规版）

给 AI 会话/协作者的项目上下文。功能明细以 [README.md](README.md) 为准，本文只讲「怎么在这个仓库里干活」。

## 概述

macOS 桌面应用 [NagomiAni](https://github.com/Spark-Howard/NagomiAni) 的 Android 移植版。
**Kotlin + Jetpack Compose (Material 3) + Media3/ExoPlayer**，minSdk 26 / targetSdk 35。
**只维护本 Android 仓库；鸿蒙端已搁置、不在维护范围**（用户指示，勿发起/合入鸿蒙相关工作）。

## 常用命令

```bash
./gradlew testDebugUnitTest   # 单元测试（kotlin.test 风格，平铺在 app/src/test/.../nagomiani/ 下）
./gradlew assembleDebug       # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # 发布包（需根目录 gitignored 的 signing.private，见 README）
```

## 代码结构

```
app/src/main/java/com/sparkhoward/nagomiani/
├── MainActivity.kt        底部导航（搜索/番库/追番/缓存）+ 播放器/详情路由 + 小部件 deeplink + 更新弹窗
├── NagomiApp.kt           AppGraph 全局单例入口 + 通知渠道 + 代理设置生效（OkHttp/WebView/Coil）
├── core/                  纯逻辑层，不含 UI
│   ├── Http.kt            全局 OkHttp：分层 DNS（bgm 域 DoH/边缘 IP 兜底）、应用内代理、UA 常量
│   ├── backup/            Backup（观看历史+设置导出/导入，新者胜合并）
│   ├── bangumi/           BangumiApi（v0 + legacy）/ OAuth（WebView + 127.0.0.1:8123 回调）
│   ├── dandanplay/        弹幕 API（凭据 XOR 混淆在 assets）——播放链路消费
│   ├── danmaku/           DanmakuLayout 车道算法 + HtmlEntities——播放链路消费
│   ├── history/           HistoryStats（观看统计纯函数：汇总/近7日/热力图/年度报告/时长格式化）
│   ├── backup/            Backup（历史+设置导出/导入）
│   ├── matching/          集号匹配——播放链路消费
│   ├── similarity/        标题相似度——片源评分消费
│   ├── maccms/            MacCMSApi（采集站协议）、OnlineRepo（跨站搜索/取流）、SourceHealth（站点/线路测速）
│   ├── model/             BangumiModels + OnlineModels（含弹幕模型）
│   ├── notify/            EpisodeNotify（云端番库更新提醒）+ DailyAiringNotifier（今日放送提醒）
│   ├── online/            WeeklyCalendar（放送周历 ±7 天窗口、日期升序、初始定位今天）、WeeklyAggregator、SourceSearchCache
│   ├── store/AppStore.kt  DataStore 集中存储（设置/绑定/续播/云端番库/观看历史/测速/待同步队列）
│   ├── download/          离线缓存（CacheDataSource + 前台服务）
│   └── update/            UpdateCenter（GitHub Releases 应用内更新）
├── player/                PlayerViewModel/Screen（ExoPlayer）、弹幕渲染、迷你小窗、截图分享卡
└── ui/
    ├── theme/             Nagomi 樱粉组件库（胶囊/卡片/分段选择器/徽章）
    ├── search/            搜索+周历、条目详情（收藏五态/评分短评/分集标记/在线观看）
    ├── library/           番库（继续观看/云端番库/片源健康面板）
    ├── history/           观看历史+统计双分页（番库页进入的二级页）
    ├── widget/            桌面小部件（今日新番/观看统计 + route deeplink 通道）
    └── bangumi/           追番页（登录/收藏五态列表/更新提醒开关）
```

## 关键约定与先例（改代码前先看）

- **与 mac 版语义对齐**是硬约束（历史先例：弹幕车道常量、续播策略、连播征询阈值）；
  改动需说明是否两版同步。
- **Bangumi v0 无删除条目收藏端点**：详情页「移除收藏」只做引导（网页端深链 / 改「抛弃」），
  不要发注定失败的请求假装删除；空体 POST 是 upsert（未收藏条目反而会新建「在看」）。
- **收藏写入口红线**（`CollectionPayloadTest` 契约测试把守）：`rate=0` 是删除评分语义、
  省略字段是"不动"、清短评必须显式传空串、`vol_status` 仅书籍类目（动画误发可能 400）——
  动 `CollectionModifyPayload` 相关写入口前先看该测试。
- **集数标记**：`markEpisodes` type=2 标记 / 0 取消，批量 300/批防请求体过大；ep_status 对账
  **只增不减**（`syncEpStatus`，不覆盖用户手动回退的进度）。
- **放送日/周历一律用 JST**（`ZoneId.of("Asia/Tokyo")`）：Bangumi 按日本时区编排，用本地时区会在
  跨天时段错位一天。周历语义 = 「近 7 天 + 未来 7 天」窗口（±7 天、日期升序、UI 初始滚动定位今天，
  `WeeklyCalendar`），不要回退成回溯式裸星期或只显示未来的单向窗口。
  搜索页展示为**两天一档**（`twoDaySections`：今天+明天成档为初始定位）。
- **AppStore 范式**：结构化数据用 `@Serializable` + JSON 字符串键；可测的纯逻辑抽成 object
  （参考 `DanmakuLayout`/`HistoryStats`/`WeeklyCalendar` 的 measure 注入/纯函数分区），
  测试用 kotlin.test 平铺断言。**DataStore 的 `edit{}` 块内绝不能再读同一个文件**（持锁重入）——
  先 `first()` 读出再进 `edit{}` 写入（`recordWatchProgress`/`seedHistoryIfNeeded` 注释有说明）。
- **详情页是 ON_RESUME 静默刷新先例**：`SubjectDetailScreen` 用 LifecycleEventObserver 在
  回前台时调 `viewModel.refreshCollectionOf`（首个 ON_RESUME 跳过防重复），网页端改动后回 App 跟上。
- **凭据永不进仓库**：`DanmakuCredentials.private`、`signing.private`、`*.keystore` 均 gitignored，
  构建期任务生成混淆产物。`.kotlin/`、`.zcode/` 也是 gitignore 的本地目录。
- **小部件色值是双份维护**：Compose 侧 `NagomiColors`（Theme.kt）与 XML 侧
  `res/values/widget_colors.xml` + `values-night/widget_colors.xml` 同值各一份（Compose 常量
  XML 引用不到）——改主题色必须两边同步。小部件 deeplink 统一走 `WidgetIntents.route()`
  （route extra 由 MainActivity 解析；今日新番的"今天"一律 JST 锚定，与周历同口径）。

## 发布流程（应用内更新的分发渠道）

1. `app/build.gradle.kts`：`versionCode` +1，`versionName` 改为 `x.y.z`
2. `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
3. 提交推送后 `gh release create vX.Y.Z --draft --notes-file ... <apk>`：
   **tag 必须与 versionName 对应**（`releases/latest` 靠它比较版本），草稿审完手动 Publish，
   发布后老用户才会收到应用内更新提示（签名不一致会导致要求卸载重装，勿换签名）。

## 当前状态

- 最新发布 **v0.3.0**（截图分享卡、片源测速与健康面板、断流自动换线、周历语义修复）。
- v0.4.0 待发版内容（在 v0.3.0 全量功能之上）：
  观看历史+统计（番库页「历史 · 统计 ›」入口 + `core/history/` + `player.history.json` 本地积累 +
  年度热力图 + 数据备份 `core/backup/Backup`）、
  桌面小部件 ×2 与长按快捷方式（`ui/widget/` + `WidgetIntents.route` deeplink）、
  今日更新提醒（`core/notify/DailyAiringNotifier`：放送表 × 在看收藏，JST 日期基线每日一次）、
  集数标记 + 收藏评分短评（详情页）、收藏列表增强（全量翻页/排序/进度条/+1）。
- 单元测试 92 项，全部纯 JVM（无 robolectric），新增可测逻辑请照 `HistoryStatsTest`/
  `SourceHealthTest` 风格补测试。
