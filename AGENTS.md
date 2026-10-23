# AGENTS.md — NagomiAnime（NagomiAni Android 合规版）

给 AI 会话/协作者的项目上下文。功能明细以 [README.md](README.md) 为准，本文只讲「怎么在这个仓库里干活」。

## 概述

macOS 桌面应用 [NagomiAni](https://github.com/Spark-Howard/NagomiAni) 的 Android 移植版（**合规版**）。
**Kotlin + Jetpack Compose (Material 3)**，minSdk 26 / targetSdk 35。
数据全部来自 bgm.tv 官方开放 API（元数据/OAuth/收藏/分集）。
**只维护本 Android 仓库；鸿蒙端不在维护范围**（用户指示，勿发起/合入鸿蒙相关工作）。

> **红线：本仓库不得引入任何盗版片源相关功能。** 采集站（MacCMS）聚合、在线播放、离线缓存、
> 依赖盗源播放的弹幕渲染/断点续播/截图分享等已于合规版整体删除——历史代码只在 v0.3.0 及更早的
> tag 里，不要把它们移植回来；片源类需求请引导到用户自担责的私有分支。

## 常用命令

```bash
./gradlew testDebugUnitTest   # 单元测试（kotlin.test 风格，平铺在 app/src/test/.../nagomiani/ 下）
./gradlew assembleDebug       # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # 发布包（需根目录 gitignored 的 signing.private，见 README）
```

## 代码结构

```
app/src/main/java/com/sparkhoward/nagomiani/
├── MainActivity.kt        底部导航（搜索/历史/追番）+ 详情/登录路由 + 应用内更新弹窗
├── NagomiApp.kt           AppGraph 全局单例入口 + 代理设置生效（OkHttp/WebView/Coil）
├── core/                  纯逻辑层，不含 UI
│   ├── Http.kt            全局 OkHttp：分层 DNS（bgm 域 DoH/边缘 IP 兜底）、应用内代理、UA 常量
│   ├── bangumi/           BangumiApi（v0 + legacy）/ OAuth（WebView + 127.0.0.1:8123 回调）
│   ├── dandanplay/        弹幕 API（凭据 XOR 混淆在 assets）——纯逻辑保留，当前无 UI 消费方
│   ├── danmaku/           DanmakuLayout 车道算法 + HtmlEntities——纯逻辑保留，当前无 UI 消费方
│   ├── matching/          集号匹配——纯逻辑保留
│   ├── similarity/        标题相似度——纯逻辑保留
│   ├── history/           HistoryStats（观看统计纯函数：汇总/近7日/年度报告/时长格式化）
│   ├── model/             BangumiModels（条目/收藏/分集）+ DanmakuModels
│   ├── online/            WeeklyCalendar（放送周历：±7 天窗口、日期升序、初始定位今天）
│   ├── store/AppStore.kt  DataStore 集中存储（设置/代理/更新/观看历史；片源相关键已删）
│   └── update/            UpdateCenter（GitHub Releases 应用内更新）
└── ui/
    ├── theme/             Nagomi 樱粉组件库（胶囊/卡片/分段选择器/徽章/Markdown）
    ├── search/            搜索+周历、条目详情（收藏五态管理）
    ├── history/           观看历史+统计双分页（底部 Tab 直达；在线播放已移除，仅本地记录展示）
    ├── widget/            桌面小部件（今日新番列表/观看统计 + route deeplink 通道）
    └── bangumi/           追番页（登录/收藏五态列表）
```

## 关键约定与先例（改代码前先看）

- **与 mac 版语义对齐**是硬约束（历史先例：弹幕车道常量、续播策略、连播征询阈值）；
  改动需说明是否两版同步。合规版删除播放链路后，剩余功能（周历语义/收藏五态/取消收藏引导）
  仍保持与 mac 版同类功能的语义一致。
- **Bangumi v0 无删除条目收藏端点**：`DELETE /v0/users/-/collections/{subject_id}` 的官方 PR
  （bangumi/server#556）因服务端软删除未实现被搁置，维护者建议用「抛弃」代替；空体 POST 走
  upsert（未收藏条目反而会新建「在看」）。详情页「移除收藏」只做引导弹窗（说明限制 +
  深链 `https://bgm.tv/subject/{id}` 去网页端删）；**mac 版 `removeCollection` 的空体 POST
  实为无效空操作**（本地假成功、服务端收藏还在），勿照抄，待官方软删除落地再两版同步补真删除。
- **放送日/周历一律用 JST**（`ZoneId.of("Asia/Tokyo")`）：Bangumi 按日本时区编排，用本地时区会在
  跨天时段错位一天。周历语义 = 「近 7 天 + 未来 7 天」窗口（±7 天、日期升序、UI 初始滚动定位今天，
  `WeeklyCalendar`），不要回退成回溯式裸星期或只显示未来的单向窗口。
- **AppStore 范式**：结构化数据用 `@Serializable` + JSON 字符串键；可测的纯逻辑抽成 object
  （参考 `DanmakuLayout`/`HistoryStats`/`WeeklyCalendar`），测试用 kotlin.test 平铺断言。
  **DataStore 的 `edit{}` 块内绝不能再读同一个文件**（持锁重入）——先 `first()` 读出再进 `edit{}`
  写入（`seedHistoryIfNeeded` 的注释里有说明）。
- **详情页是 ON_RESUME 静默刷新先例**：`SubjectDetailScreen` 用 LifecycleEventObserver 在
  回前台时调 `refreshCollectionOf`（首个 ON_RESUME 跳过防重复），网页端改动后回 App 徽章跟上。
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
   合规版首个版本建议在 release notes 里明确说明「移除在线片源功能」。

## 当前状态

- 合规版改造已完成（待发版，建议 versionName 0.4.0）：
  **删除** `core/maccms`、`core/online/WeeklyAggregator`+`SourceSearchCache`、`core/download`、
  `core/notify`、`player/`（播放器/弹幕渲染/小窗/分享卡）、`ui/library`、`ui/cache`、
  `core/model/OnlineModels`（片源模型，弹幕模型拆到 `core/model/DanmakuModels`）；
  AppStore 删除片源站点/健康/云端番库/绑定表/待同步队列/更新提醒区块；
  Manifest 移除前台服务/通知/存储/开机广播权限与下载服务。
- 底部导航现为 **搜索 / 历史 / 追番** 三个 Tab；详情页仅剩条目信息+收藏管理；
  历史页点行不再续播（提示「在线片源功能已下线」）。
- 桌面小部件（今日新番/观看统计）与长按快捷方式（三 Tab）已就位：经典 RemoteViews、
  樱粉双模式色值（`ui/widget/` + `res/values[-night]/widget_colors.xml`）、
  `WidgetIntents.route` deeplink（MainActivity singleTop + onCreate/onNewIntent 解析）。
- 短期四件套已就位（待发版）：**今日更新提醒**（`core/notify/DailyAiringNotifier`：WorkManager 24h
  + 启动补查，calendar × 在看收藏按 subject id 交叉，JST 日期串基线每日一次，POST_NOTIFICATIONS
  运行时申请）；**收藏列表增强**（追番页 allCollections 全量翻页 + 四种排序 + 进度条）；
  **年度热力图**（`HistoryStats.dailySeconds/heatLevel`，色阶 0-4，年份选择器在热力图卡、与报告卡共用）；
  **数据备份**（`core/backup/Backup`：历史+设置导出 JSON 分享、导入新者胜合并，`file_paths.xml`
  有 backup cache-path）。
- 集数标记与评分短评已就位（待发版）：详情页分集格子（`markEpisodes` type=2 标记/0 取消，
  批量 300/批防请求体过大），**ep_status 对账只增不减**（`syncEpStatus`，不覆盖用户手动回退）；
  评分短评 payload 红线（rate=0 是删评分、空串才是清短评、null 才是省略、vol_status 书籍专用）
  由 `CollectionPayloadTest` 契约测试把守——动 `CollectionModifyPayload` 相关写入口前先看它。
- dandanplay/DanmakuLayout/MediaMatching/TitleSimilarity/HtmlEntities 为纯逻辑保留
  （有测试覆盖，未来本地播放合法复用），当前无 UI 消费方。
- 单元测试 47 项，全部纯 JVM（无 robolectric），新增可测逻辑请照 `HistoryStatsTest`/
  `WeeklyCalendarTest` 风格补测试。
