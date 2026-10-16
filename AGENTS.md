# AGENTS.md — NagomiAnime（NagomiAni Android 版）

给 AI 会话/协作者的项目上下文。功能明细以 [README.md](README.md) 为准，本文只讲「怎么在这个仓库里干活」。

## 概述

macOS 桌面应用 [NagomiAni](https://github.com/Spark-Howard/NagomiAni) 的 Android 移植版。
**Kotlin + Jetpack Compose (Material 3) + Media3/ExoPlayer**，minSdk 26 / targetSdk 35。
片源走 MacCMS V10 采集站协议，弹幕走弹弹play开放 API，追番数据同步 bgm.tv。

## 常用命令

```bash
./gradlew testDebugUnitTest   # 单元测试（kotlin.test 风格，平铺在 app/src/test/.../nagomiani/ 下）
./gradlew assembleDebug       # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # 发布包（需根目录 gitignored 的 signing.private，见 README）
```

## 代码结构

```
app/src/main/java/com/sparkhoward/nagomiani/
├── MainActivity.kt        底部导航 + 播放器路由 + 「回到播放中」入口条
├── AppGraph.kt            全局单例入口（store 等）
├── core/                  纯逻辑层，不含 UI
│   ├── Http.kt            全局 OkHttp：分层 DNS（bgm 域 DoH/边缘 IP 兜底）、应用内代理、UA 常量
│   ├── bangumi/           BangumiApi / OAuth
│   ├── dandanplay/        弹幕 API（凭据 XOR 混淆在 assets，缺失则弹幕静默停用）
│   ├── maccms/            MacCMSApi（采集站）、OnlineRepo（跨站搜索/取流）、SourceHealth（站点/线路测速）
│   ├── online/            WeeklyAggregator（搜源评分）、WeeklyCalendar（放送周历）
│   ├── store/AppStore.kt  DataStore 集中存储（绑定/续播/番库/设置/待同步队列/站点健康）
│   ├── download/          离线缓存（CacheDataSource）
│   └── notify/            追番更新提醒（WorkManager）
├── player/                PlayerViewModel/Screen（ExoPlayer）、弹幕渲染、迷你小窗、截图分享卡
└── ui/                    theme（Nagomi 樱粉组件库）、search、library、bangumi 页面
```

## 关键约定与先例（改代码前先看）

- **与 mac 版语义对齐**是硬约束：弹幕车道常量、续播策略（<15s 不续/距尾 30s 看完/5s 节流）、
  连播征询阈值等都注释了对应关系，改动需说明是否两版同步。
- **放送日/周历一律用 JST**（`ZoneId.of("Asia/Tokyo")`）：Bangumi 按日本时区编排，用本地时区会在
  跨天时段错位一天。周历语义 = 「今天起未来一周」逐日滚动（`WeeklyCalendar`），不要回退成回溯式裸星期。
- **采集站 URL 必须过 `MacCMSApi.normalizeAPIBase`** 再拼端点：内置站在 `builtinSites` 里是裸域名，
  直接拼 `?ac=list` 会打到站点根（403/500 误判不可达，v0.3.0 踩过）。真实 API 在
  `{base}/api.php/provide/vod/?ac=list|detail`。
- **测速与播放不同域**：站点测速打 API 域（OkHttp），实际播放走 CDN 域（ExoPlayer +
  DefaultHttpDataSource，带 Referer 防盗链头）。给线路做探测要带 `item.httpHeaders/userAgent`。
- **AppStore 范式**：结构化数据用 `@Serializable` + JSON 字符串键（参考 `lastNotified` /
  `SiteHealthEntry` 的 merge 写法）；可测的纯逻辑抽成 object（参考 `DanmakuLayout` 的 measure 注入、
  `SourceHealth` 的纯函数分区），测试用 kotlin.test 平铺断言。
- **PlayerViewModel 是 Activity 级**（路由退出/小窗后仍存活）：大资源必须随页面销毁释放
  （先例：`dismissShareSheet` 在 PlayerScreen onDispose 调用）；Compose 可观察状态用
  `mutableStateOf`（`exoPlayer` 字段有黑屏相关注释，动它之前先读）。
- **换线/自动兜底**：`applyRoute` 保留暂停态（openMedia 会强制 playWhenReady）并复位卡顿看门狗；
  自动兜底上限 routes-1 次，手动切线重新武装，换集复位。
- **取视频帧**：PlayerView 默认 SurfaceView，用 PixelCopy（minSdk 26 恰好支持）+ `videoSize` 算
  FIT 内容区裁黑边；弹幕叠加用 `DanmakuOverlayView.snapshotOverlay()`（View.draw 复用 onDraw）。
- **分享/保存图片**：FileProvider authority `${applicationId}.fileprovider`，新增目录要同步在
  `res/xml/file_paths.xml` 加 path（先例：`share/`）；存相册 API 29+ MediaStore 免权限，
  26-28 需 WRITE_EXTERNAL_STORAGE（manifest 已声明 maxSdkVersion=28）。
- **凭据永不进仓库**：`DanmakuCredentials.private`、`signing.private`、`*.keystore` 均 gitignored，
  构建期任务生成混淆产物。`.kotlin/`、`.zcode/` 也是 gitignore 的本地目录。

## 发布流程（应用内更新的分发渠道）

1. `app/build.gradle.kts`：`versionCode` +1，`versionName` 改为 `x.y.z`
2. `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
3. 提交推送后 `gh release create vX.Y.Z --draft --notes-file ... <apk>`：
   **tag 必须与 versionName 对应**（`releases/latest` 靠它比较版本），草稿审完手动 Publish，
   发布后老用户才会收到应用内更新提示（签名不一致会导致要求卸载重装，勿换签名）。

## 当前状态

- 最新版本 **v0.3.0**（versionCode 4）：截图分享卡、片源健康面板、自加站点管理、
  线路测速与断流自动换线、周历语义修复、测速地址修复及若干边界修复。
- 单元测试 78 项，全部纯 JVM（无 robolectric），新增可测逻辑请照 `SourceHealthTest` 风格补测试。
