aw-android（增强版 / Native UI Fork）
=====================================

[![Build](https://github.com/PT123123/aw-android/workflows/Build/badge.svg)](https://github.com/PT123123/aw-android/actions)

这是 [ActivityWatch/aw-android](https://github.com/ActivityWatch/aw-android) 的一个增强分支，目标是在 Android 上提供一个**原生、快速、可离线**的 ActivityWatch 客户端。

相比上游（主要通过 WebView 加载 WebUI），本分支做了大量工作：

- 用 **Kotlin + ViewBinding（MVVM）** 重写了核心界面，摆脱对 WebView 的依赖；
- 内置 **Inbox 快速笔记**，支持 Markdown、置顶、历史、回收站；
- 新增 **标签 Day 时间线**、**多日统计**、**按天活动浏览**等原生页面；
- 用原生 Kotlin 重写 **局域网同步（LAN Sync）**，移除了原先的 Flutter 依赖；
- 新增三个**原生桌面小部件**（今日屏幕使用、今日碎片、日历），数据直读系统 UsageStats，支持快捷方式一键添加与尺寸自适应；
- 集成 Firebase Analytics / Crashlytics，修复了 SQLite 崩溃与 JNI 内存安全问题；
- 完善构建体系：以 **Gradle 为唯一顶层编排**（`rust-android-gradle` 插件编译 Rust 原生库），国内镜像加速、Android 15 的 16KB 页对齐，以及**原生 Windows 构建**支持；
- **已移除内嵌 WebUI（aw-webui）**：不再通过 WebView 加载仪表盘，应用界面完全原生。

当前版本：`0.13.26`（versionCode 61）。

---

## 仓库与分支

本仓库（`PT123123/aw-android-native`）是开发主仓库，原 [`PT123123/aw-android`](https://github.com/PT123123/aw-android) 保留为上游参照：

| Remote | 地址（SSH） | 用途 |
| --- | --- | --- |
| `origin` | `git@github.com:PT123123/aw-android-native.git` | **主仓库**，日常开发与推送目标，默认分支 `main` |
| `upstream` | `git@github.com:PT123123/aw-android.git` | 原仓库（`feature/native-ui` 分支），仅作历史参照 / 对比，不再推送 |

- 本地开发分支为 `feature/native-ui`，推送目标为 `origin/main`（`git push` 即推到 `origin/main`）。
- 同步上游改动：`git fetch upstream && git merge upstream/feature/native-ui`（或按需 cherry-pick）。

---

## 功能特性

### 原生 UI（Kotlin + ViewBinding）

应用主界面由侧边抽屉导航，默认进入原生 Inbox 页面。各页面均为原生 Fragment（`ViewModel` + `ViewBinding` + Kotlin 协程），直接调用内嵌 Rust 服务器的本地 REST API，响应快、可离线。

| 页面 | 说明 |
| --- | --- |
| **Inbox（收件箱 / 快速笔记）** | 应用首页。快速记录笔记，支持搜索、置顶、撤销删除、剪贴板粘贴；编辑器为 BottomSheet，内置 Markdown 渲染与常用文本操作。 |
| **Inbox 设置** | 笔记相关偏好（如抽屉边缘手势区域等）。 |
| **回收站** | 已删除笔记的查看与恢复。 |
| **标签 Day** | 时间线选择 + 打标签，提供 Details / Summary 两种视图，支持新增与管理标签、按未打标签筛选。 |
| **统计** | 多日统计（移植自 `statspage.cpp`），含自绘图表（环形图 / 横向条形图 / 每小时柱状图 / 统计曲线），支持日期范围选择与导出。 |
| **Sync (LAN)** | 局域网同步页（原生重写），见下文。 |

> 说明：上游的 **Activity** 与 **Settings** 页面此前通过内嵌 WebUI（WebView）提供；本分支已彻底移除 WebUI 构建与对应导航入口，这两个页面当前暂未提供（使用数据仍由内嵌 Rust 服务器采集与存储）。

### Inbox 快速笔记

Inbox 是本分支的核心功能之一，背后由 [`aw-server-rust` 子模块](#架构) 中的 `aw-inbox-rust` 提供服务：

- 快速捕获想法，支持 **Markdown** 编辑与渲染（基于 Markwon）；
- **置顶**、**搜索**、**历史记录**、**回收站**与撤销删除；
- 本地使用 **Room** 数据库缓存（`InboxDatabase` / `InboxDao`），配合本地 API 提升打开速度与离线体验。

### 局域网同步（LAN Sync）

原生重写的同步页面（取代了此前的 Flutter 实现），数据来自本机 Rust 服务器的 `/api/0/sync` 接口，包含三个可折叠面板：

- **配对与设备**：发现并配对局域网内的其他 ActivityWatch 设备；
- **设置**：同步相关配置；
- **显示报文**：查看同步请求 / 响应明细，便于排查。

同步能力由子模块中的 `aw-sync-rust` 提供。

### 桌面小部件（App Widgets）

三个**纯原生**（`RemoteViews`）桌面小部件，数据直读系统 `UsageStatsManager`，**不依赖应用进程存活，也不依赖内嵌 Rust 服务器**——广播拉起进程后即可更新。

| 小部件 | 内容 | 点击跳转 |
| --- | --- | --- |
| **今日屏幕使用** | 今日屏幕使用时长（并集口径，见下）+ 常用应用图例（配色圆点 + 应用名 · 时长）；未授予「使用情况访问」时显示授权引导 | 活动 · 概览 |
| **今日碎片** | 当天 24 时 × 每时 5 分钟的点阵热力（288 格，画成一张位图推送）+ 推断的起床/入睡时间 | 活动 · 碎片 |
| **日历** | 当月月历（周起始日跟随系统地区设置），今天用主题色高亮 | 活动 · 趋势 |

- **两个口径别混**：「屏幕时长」总量走**事件并集**（`UsageEvents` 里前后台会话压成互不重叠的段，同一时刻只算一次），与系统设置的「屏幕使用时间」一致；逐包 `totalTimeInForeground` 会在多个包同时处于前台（桌面、小窗、系统组件）时**重复计时**，实测同一天逐包求和 8h48m / 事件并集 7h24m，虚高约 20%，因此只用来出逐包榜单（与系统「应用使用时长」列表同源）。「今日碎片」的点阵与总时长由 `dashboard/DayFragments` 单点提供，碎片页 Tab 与小部件逐格一致。
- **一键添加**：长按应用图标弹出的快捷方式（`res/xml/shortcuts.xml`）里有「添加屏幕使用小部件 / 添加碎片小部件 / 添加日历小部件」，点击后由 `AppWidgetManager.requestPinAppWidget` 拉起系统弹窗确认；桌面不支持钉住时退回引导提示。
- **刷新时机**：系统按 `updatePeriodMillis`（30 分钟）广播 `APPWIDGET_UPDATE`，应用 `onResume` 时也会顺带刷新一次。UsageStats 查询是 binder 调用，统一丢到 `WidgetUpdater` 的单线程池执行，并配合 `goAsync()` 保证广播不被系统提前回收。
- **尺寸自适应**（`onAppWidgetOptionsChanged` / `onUpdate` 均按当前实例尺寸重算，同一桌面可同时存在多个不同尺寸的实例）：
  - **屏幕使用**：按上报尺寸分档——微缩档隐藏标题行、只留大字时长；紧凑/窄宽档不显示图例；宽幅档显示 top5、其余 top3，窄宽档隐藏「更新于」。字号不写死，交给 `TextView` 的 `autoSizeTextType` 按实际空间自动缩放。
  - **今日碎片**：微缩档（高 <75dp）隐藏标题行与统计行只留点阵；底部**常驻**「起床 … · 入睡 …」，不按高度分档——默认落位 3×2 只有 2 格高，按高度卡会永久看不见；推断不出作息时写「没起 / 没睡」，不留空行。窄宽档（2×2）把这一行折成「起床 …」/「入睡 …」两行（单行会被 ellipsize 吃掉后半段），同时标题让位，但**右上角「更新于」常驻**——标题走 `INVISIBLE` 占位，收缩时更新时间既不换位置也不消失。点阵位图按实例实际 dp 尺寸 × 密度重画（上限 480×260px，RemoteViews 走 Binder 单次事务约 1MB，不能按大部件的原始像素出图），够高时底部标 0/6/12/18 时刻度，格子小于 2px 时退化成方块避免糊成一团。
  - **日历**：档位只决定内边距、标题文案（「9月」/「2026年9月」）与今天的高亮形状；字号一律按实际尺寸以 **dp** 反推（`sp` 会被系统字体缩放放大，导致两位数折行）。高度按比例分配：标题约占 14%、星期表头约 10%、其余全部留给日期行，并额外压 6% 余量以抵消启动器上报尺寸与实际可视区的偏差；日期网格与日期行都用 `layout_weight` 等分实际高度，因此「宽而矮」的 3×2 也不会裁掉最后一行，也不会在底部留白。
- **跳转正确性**：每个「打开主界面」的 `PendingIntent` 都必须带各自唯一的 `action`——系统判定两条 PendingIntent 是否相同只比对 `requestCode` + `Intent.filterEquals()`（action / data / type / component / categories），**完全不看 extras**；否则多个小部件/通知会互相覆盖 `open_target`，点击后落到默认页面。

---

## 架构

- **内嵌服务器**：应用通过 JNI 启动 [`aw-server-rust`](https://github.com/PT123123/aw-server-rust)（本仓库的 `aw-server-rust` 子模块，亦为定制分支），监听 `127.0.0.1:5600`。`RustInterface` 负责启动与生命周期管理。
- **数据采集**：`UsageStatsWatcher`（基于 UsageStats）与 `ChromeWatcher` 采集应用 / 浏览器使用数据并以心跳上报。
- **原生页面数据流**：原生 Fragment 通过 `common/` 下的 API 客户端（`AwApiClient` 等）调用本地服务器；`inbox/` 使用独立的本地 API 与 Room 缓存。
- **桌面小部件**：`widget/` 下三个 `AppWidgetProvider`（屏幕使用 / 今日碎片 / 日历）、统一刷新入口 `WidgetUpdater`，以及系统口径数据源 `ScreenTimeStats`（总量走 `UsageEvents` 并集，逐包明细走 `queryUsageStats`，与仪表盘「概览」共用）；碎片的单日扫描在 `dashboard/DayFragments`，碎片页 Tab 与小部件共用；见[上文](#桌面小部件app-widgets)。
- **子模块定制点**：`aw-server-rust` 分支集成了 `aw-inbox-rust`（Inbox 服务）、`aw-sync-rust`（局域网同步）、CORS 放开（便于局域网访问），并做了 JNI 内存安全、SQLite 崩溃修复、日志系统完善等加固。

---

## 构建

构建本应用需要先编译 `aw-server-rust`（`./aw-server-rust`）。

如果还没有初始化子模块：`git submodule update --init --recursive`。

> **提示**
> 如果不想折腾 Rust 环境，可以从 [aw-server-rust 的 CI 产物](https://github.com/ActivityWatch/aw-server-rust/actions/workflows/build.yml) 下载 jniLibs，手动放进 `mobile/src/main/jniLibs`，跳过下面编译 Rust 的步骤。

### 构建 aw-server-rust

需要安装 Rust（通过 rustup）。然后：

```
export ANDROID_NDK_HOME=`pwd`/aw-server-rust/NDK  # 指向你的 NDK
pushd aw-server-rust && ./install-ndk.sh; popd    # 配置并（如缺失）安装 NDK
./gradlew :mobile:cargoBuild             # 编 Rust 原生库（Gradle 自动触发，debug/release 共用 release profile）
```

> **提示**
> 若未设置 `ANDROID_NDK_HOME`，`install-ndk.sh` 会把 NDK 下载到 `aw-server-rust/NDK`。若 NDK 已在别处（如 Arch 的 `/opt/android-ndk/`），可建一个软链接指向它。

### 组装应用

`aw-server-rust` 构建好后，即可像普通 Android 应用一样构建（Android Studio 或 `./gradlew :mobile:assembleDebug`）。

### 在 Windows 上构建

顶层构建完全由 **Gradle** 驱动——编排层不再使用 `make`/`cmake`/`ninja`（已删除 `Makefile`）。先初始化子模块：
`git submodule update --init --recursive`。

**推荐方式 — 任意 shell 下使用 `./gradlew`（PowerShell / Git Bash / cmd）。**
Gradle 是唯一编排者：它通过 `rust-android-gradle` 插件交叉编译 Rust 原生库（`cargoBuild`，在 `preBuild` 前自动执行），并打包 APK/AAB。示例：

```sh
./gradlew build                 # debug APK（等价于旧的 make build）
./gradlew buildApk             # release APK  -> dist/aw-android.apk（用 signingConfigs.release 正式签名；缺 keystore.properties 则输出未签名包）
./gradlew buildBundle          # release AAB  -> dist/aw-android.aab
./gradlew install              # 通过 adb 安装 debug APK
./gradlew :mobile:cargoBuild   # 仅编译 Rust .so
```

在 Windows 上，`rust-android-gradle` 插件需要 MSYS2 的 perl 来编译 vendored OpenSSL：它会自动设置
`OPENSSL_SRC_PERL=C:/msys64/usr/bin/perl.exe`（仅 Windows）。MSYS2 perl 优于 Git for Windows 的 perl（缺模块）
和 Strawberry Perl（被 OpenSSL 的 Configure 拒绝）。

**备选 — `scripts\win\build.ps1`。** 一个自包含 PowerShell 脚本，直接用 `cargo-ndk` 编译 Rust（而非 Gradle 插件），
然后调用 `gradlew.bat`。适合想自己预编译 Rust 的场景。该脚本**不使用 Makefile**（已删除）。

Windows 上的注意事项 / 常见坑：

- `local.properties` 里的 `sdk.dir` 用正斜杠（或转义反斜杠）；构建脚本 / 插件会自动写好。
- 编译 vendored OpenSSL 需要 `PATH` 里有 Perl（推荐 MSYS2）。
- 长路径：建议开启 Windows 长路径支持，或把仓库放在较短的路径（如 `C:\src\aw-android`），因为 Rust 构建会产生很深的目录树。
- 确保 NDK 版本与 `mobile/build.gradle` 的 `ndkVersion` 一致，当前为 `25.2.9519653`（r25c）。

### 发布

本项目**不使用 CI**：release 包在本机出，传到 GitHub Release，给朋友一个固定直链直接下载安装（侧载，不上架 Play）。

```sh
just release    # 版本 +1 → assembleRelease（正式签名）→ 整理 dist/aw-android.apk（固定名）+ 签名/包名自检
git add -A && git commit -m "release: v0.13.26" && git push
just publish    # gh release create v0.13.26，上传 dist/aw-android.apk（需先 gh auth login）
```

给朋友的永久下载直链（永远指向最新一版，所以资产名固定为 `aw-android.apk`）：

```
https://github.com/PT123123/aw-android-native/releases/latest/download/aw-android.apk
```

- 包名 `dev.pt123123.awandroid`（改自上游 `net.activitywatch.android`，避免与官方 ActivityWatch 同包名不同签名而装不上）；debug 变体带 `.debug` 后缀，可与 release 版共存。
- 签名材料在仓库根：`keystore.properties` + `aw-release.p12`，**都不入 git**。缺失时 release 包退化为未签名（刻意不回退 debug 签名——debug key 换台机器就变，会让已安装的人无法覆盖升级）。
- **keystore 丢了 = 所有已安装设备以后都无法覆盖升级**，只能卸载重装。keystore 与口令请存在仓库之外的密钥目录里做好备份（两者都不入库）。
- 每次发版 `versionCode` 必须递增：覆盖安装时 Android 会拒装 versionCode 未增的包（`INSTALL_FAILED_VERSION_DOWNGRADE`），`just release` 已内置 +1。

> 上游遗留、本项目不再使用：`.github/workflows/build.yml`（需 ubicloud runner + Play 密钥，已删除）、`scripts/sign_apk.sh`、`android.jks.age`、`fastlane/`（Play 流程）。

---

## 更多信息

- 上游主仓库：[ActivityWatch/activitywatch](https://github.com/ActivityWatch/activitywatch)
- 上游 Android 仓库：[ActivityWatch/aw-android](https://github.com/ActivityWatch/aw-android)
- 服务器（本分支定制）：[PT123123/aw-server-rust](https://github.com/PT123123/aw-server-rust)
