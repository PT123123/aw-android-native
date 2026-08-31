aw-android（增强版 / Native UI Fork）
=====================================

[![Build](https://github.com/PT123123/aw-android/workflows/Build/badge.svg)](https://github.com/PT123123/aw-android/actions)

这是 [ActivityWatch/aw-android](https://github.com/ActivityWatch/aw-android) 的一个增强分支，目标是在 Android 上提供一个**原生、快速、可离线**的 ActivityWatch 客户端。

相比上游（主要通过 WebView 加载 WebUI），本分支做了大量工作：

- 用 **Kotlin + ViewBinding（MVVM）** 重写了核心界面，摆脱对 WebView 的依赖；
- 内置 **Inbox 快速笔记**，支持 Markdown、置顶、历史、回收站；
- 新增 **标签 Day 时间线**、**多日统计**、**按天活动浏览** 等原生页面；
- 用原生 Kotlin 重写 **局域网同步（LAN Sync）**，移除了原先的 Flutter 依赖；
- 集成 Firebase Analytics / Crashlytics，修复了 SQLite 崩溃与 JNI 内存安全问题；
- 完善构建体系：Makefile 预编译 Rust、国内镜像加速、Android 15 的 16KB 页对齐，以及**原生 Windows 构建**支持。

当前版本：`0.13.0`（versionCode 35）。

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
| **Activity** | 按天浏览活动数据，支持前一天 / 后一天 / 回到今天 / 刷新。 |
| **Sync (LAN)** | 局域网同步页（原生重写），见下文。 |
| **Settings** | 仍通过内嵌 WebUI 提供（`WebUIFragment`），作为原生页面的补充。 |

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

---

## 架构

- **内嵌服务器**：应用通过 JNI 启动 [`aw-server-rust`](https://github.com/PT123123/aw-server-rust)（本仓库的 `aw-server-rust` 子模块，亦为定制分支），监听 `127.0.0.1:5600`。`RustInterface` 负责启动与生命周期管理。
- **数据采集**：`UsageStatsWatcher`（基于 UsageStats）与 `ChromeWatcher` 采集应用 / 浏览器使用数据并以心跳上报。
- **原生页面数据流**：原生 Fragment 通过 `common/` 下的 API 客户端（`AwApiClient` 等）调用本地服务器；`inbox/` 使用独立的本地 API 与 Room 缓存。
- **子模块定制点**：`aw-server-rust` 分支集成了 `aw-inbox-rust`（Inbox 服务）、`aw-sync-rust`（局域网同步）、CORS 放开（便于局域网访问），并做了 JNI 内存安全、SQLite 崩溃修复、日志系统完善等加固。

---

## 构建

构建本应用需要先编译 `aw-server-rust`（`./aw-server-rust`）与 `aw-webui`（`./aw-server-rust/aw-webui`）。

如果还没有初始化子模块：`git submodule update --init --recursive`。

> **提示**
> 如果不想折腾 Rust 环境，可以从 [aw-server-rust 的 CI 产物](https://github.com/ActivityWatch/aw-server-rust/actions/workflows/build.yml) 下载 jniLibs，手动放进 `mobile/src/main/jniLibs`，跳过下面编译 Rust 的步骤。

### 构建 aw-server-rust

需要安装 Rust（通过 rustup）。然后：

```
export ANDROID_NDK_HOME=`pwd`/aw-server-rust/NDK  # 指向你的 NDK
pushd aw-server-rust && ./install-ndk.sh; popd    # 配置并（如缺失）安装 NDK
env RELEASE=false make aw-server-rust             # RELEASE=true 为发布模式（更慢、更难调试）
```

> **提示**
> 若未设置 `ANDROID_NDK_HOME`，`install-ndk.sh` 会把 NDK 下载到 `aw-server-rust/NDK`。若 NDK 已在别处（如 Arch 的 `/opt/android-ndk/`），可建一个软链接指向它。

### 构建 aw-webui

需要较新的 node/npm，然后执行 `make aw-webui`。

### 组装应用

`aw-server-rust` 与 `aw-webui` 都构建好后，即可像普通 Android 应用一样构建（Android Studio 或 `./gradlew :mobile:assembleDebug`）。

### 在 Windows 上构建

支持两种方式，二者都需要先初始化子模块（`git submodule update --init --recursive`）。

**方式 A — 原生 PowerShell（推荐）。** 一个自包含脚本驱动整条流水线（webui → Rust 交叉编译 → jniLibs → Gradle），无需 WSL：

```powershell
# 在仓库根目录执行
powershell -ExecutionPolicy Bypass -File scripts\win\build.ps1
# 发布构建 + 安装到已连接设备：
powershell -ExecutionPolicy Bypass -File scripts\win\build.ps1 -BuildType release -Install
```

前置依赖（装一次即可）：JDK 17、Android SDK + NDK r25c、Rust（`rustup`）、Node.js/npm，以及 [Strawberry Perl](https://strawberryperl.com/)（用于编译 vendored OpenSSL）。脚本会自动安装 `cargo-ndk` 并用它完成 Rust → Android 交叉编译。

**方式 B — Git Bash / MSYS2（复用 `make` 流程）。** 若偏好与 Linux 相同的流程，安装 [MSYS2](https://www.msys2.org/)（或 Git for Windows），然后在该 shell 里运行常规 `make` 目标。`Makefile`、`install-ndk.sh`、`compile-android.sh` 会探测 Windows 宿主并使用 `windows-x86_64` NDK 工具链。先设置 `ANDROID_NDK_HOME`（或让 `install-ndk.sh` 在 `%LOCALAPPDATA%\Android\Sdk\ndk` 下查找 NDK）。

Windows 上的注意事项 / 常见坑：

- `local.properties` 里的 `sdk.dir` 用正斜杠（或转义反斜杠）；构建脚本会自动写好。
- 编译 vendored OpenSSL 需要 `PATH` 里有 Perl。
- 长路径：建议开启 Windows 长路径支持，或把仓库放在较短的路径（如 `C:\src\aw-android`），因为 Rust 构建会产生很深的目录树。
- 确保 NDK 版本与 `mobile/build.gradle` 的 `ndkVersion` 一致，当前为 `25.2.9519653`（r25c）。

### 发布

制作发布版：打一个签名 tag 并推送到 GitHub：

```sh
git tag -s v0.1.0
git push origin refs/tags/v0.1.0
```

这会触发 GitHub Actions 工作流：构建应用、上传到 GitHub Releases，并发布到 Play Store（含 `./fastlane/metadata/android` 中的元数据）。

---

## 更多信息

- 上游主仓库：[ActivityWatch/activitywatch](https://github.com/ActivityWatch/activitywatch)
- 上游 Android 仓库：[ActivityWatch/aw-android](https://github.com/ActivityWatch/aw-android)
- 服务器（本分支定制）：[PT123123/aw-server-rust](https://github.com/PT123123/aw-server-rust)
