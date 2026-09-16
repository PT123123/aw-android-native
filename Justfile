# aw-android —— 用 just + PowerShell 驱动构建与安装
#
# 常用命令：
#   just                列出全部命令
#   just build          编译 debug APK（自动 patch +1；复用已编好的 libaw_server.so，跳过 Rust 重编）
#   just install        adb 安装 debug APK（默认装手机，按型号自动识别）
#   just install phone  指定装到手机
#   just install tab    指定装到平板
#   just install-all    两台都装
#   just run            编译 + 安装 + 启动
#   just kotlinc        只快速校验 Kotlin/资源改动（不重建 .so、离线）
#   just build-release  编 release APK（自动 versionName/versionCode +1）
#   just install-release adb 安装已签名的 release APK
#
# 说明：
#   - Windows 上配方统一用 pwsh（PowerShell 7）执行，辅助脚本为 scripts/*.ps1；
#     旧 scripts/*.sh 保留（Gradle 的 buildApk/buildBundle/bumpVersion 任务与 Linux CI 仍引用）。
#   - 正式签名走 mobile/build.gradle 的 signingConfigs.release：材料是仓库根的
#     keystore.properties + aw-release.p12（都不入 git；缺失时 release 包不签名、只出 -unsigned）。
#     旧的 scripts/sign_apk.sh 是上游 Play 流程残留（依赖 age 解密的 android.jks），本项目已不用。
#   - preBuild 依赖 cargoBuild（Rust 重编），耗时；因此 build/install/run 统一 -x cargoBuild，
#     复用 mobile/build/rustJniLibs 下已有的 .so。重编 Rust：gradle :mobile:cargoBuildArm64。

set windows-shell := ["pwsh.exe", "-NoLogo", "-NoProfile", "-Command"]

# ---- 工具链路径（按需修改）----
export JAVA_HOME    := "C:/Users/ted/Tools/jdk-17/zulu17.68.203-ca-jdk17.0.20.1-win_x64"
export ANDROID_HOME := "C:/Users/ted/AppData/Local/Android/Sdk"
# Gradle JVM 走本机 HTTP 代理（大文件会被代理掐断，但 Gradle 依赖体积极小，正常）
export GRADLE_OPTS  := "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10809 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10809"

GRADLE       := "C:/Users/ted/.gradle/wrapper/dists/gradle-8.1-bin/2eyty4r6kz6fpakefpk52nbbm/gradle-8.1/bin/gradle.bat"
export ADB   := "C:/Users/ted/AppData/Local/Android/Sdk/platform-tools/adb.exe"

DEBUG_APK    := "mobile/build/outputs/apk/debug/mobile-debug.apk"
RELEASE_APK  := "mobile/build/outputs/apk/release/mobile-release.apk"
DIST_APK     := "dist/aw-android.apk"
# 目标设备不在线时的等待秒数（HyperOS 息屏 / USB 挂起会让 adb 短暂返回空列表）
ADB_WAIT_DEV := env_var_or_default("ADB_WAIT_DEV", "10")

# 默认：列出命令
default:
    @just --list

# 每次 build 都递增 versionName 的 patch 与 versionCode，这样「手机上是哪一版」一眼可查。
# 编译 debug APK（先 +1 patch 号；.so 缺失时先编 Rust）
build:
    @pwsh -NoLogo -NoProfile -File scripts/bump_version.ps1; exit $LASTEXITCODE
    @if (-not (Test-Path "mobile/build/rustJniLibs/android/arm64-v8a/libaw_server.so")) { \
        Write-Host "==> .so 缺失，先编 Rust..."; \
        {{GRADLE}} :mobile:cargoBuild; \
    }
    {{GRADLE}} :mobile:assembleDebug -x cargoBuild

# 动态识别设备：型号含 pad/tablet 或 TB 开头视为平板，Justfile 不写死序列号。
# 目标设备不在线时：adb 完全没设备就等 ADB_WAIT_DEV 秒；有别的设备在线则立刻放弃。
# **绝不拿空 serial 去装**，否则 adb 会把 APK 装到另一台唯一在线的设备上。
# adb 安装 debug APK（-r 覆盖安装；默认装手机，`just install phone|tab` 指定设备）
install device="phone":
    @if (@("phone", "tab") -notcontains "{{device}}") { Write-Host "用法: just install [phone|tab]"; exit 1 }; \
    $target = if ("{{device}}" -eq "tab") { "平板" } else { "手机" }; \
    $serial = pwsh -NoLogo -NoProfile -File scripts/pick_device.ps1 -Kind "{{device}}" -WaitSecs {{ADB_WAIT_DEV}}; \
    if (-not $serial) { \
        Write-Host "==> 未检测到 $target 设备（判定规则：型号含 pad/tablet 或以 TB 开头算平板）。当前 adb 设备："; \
        pwsh -NoLogo -NoProfile -File scripts/pick_device.ps1 -List; \
        exit 1; \
    }; \
    pwsh -NoLogo -NoProfile -File scripts/adb_install.ps1 -Apk "{{DEBUG_APK}}" -Serial "$serial"; exit $LASTEXITCODE

# 两台都装
install-all: (install "phone") (install "tab")

# 依赖 build / install 均已中止式校验，这里再兜一次，避免 -s "" 落到别的设备上
# 编译 + 安装 + 启动到手机
run: build (install "phone")
    @$serial = pwsh -NoLogo -NoProfile -File scripts/pick_device.ps1 -Kind phone; \
    if (-not $serial) { Write-Host "==> 未检测到手机设备"; pwsh -NoLogo -NoProfile -File scripts/pick_device.ps1 -List; exit 1 }; \
    & "{{ADB}}" -s "$serial" shell am start -n net.activitywatch.android.debug/net.activitywatch.android.MainActivity; exit $LASTEXITCODE

# 只快速校验 Kotlin/资源改动（不重建 .so、离线，依赖已缓存）
kotlinc:
    {{GRADLE}} :mobile:compileDebugKotlin -x cargoBuild --offline

# 清理 Kotlin 构建产物（保留 Rust .so，避免 checkRequiredSoFiles 失败）
clean:
    {{GRADLE}} :mobile:clean

# 注：release 用 keystore.properties 里的 aw-release.p12 正式签名（signingConfigs.release），
# 所以 assembleRelease 直接产出「已签名」的 mobile-release.apk；没配 keystore 时才是 -unsigned。
# 编 release APK（versionName/versionCode 自动 +1）
build-release:
    @pwsh -NoLogo -NoProfile -File scripts/bump_version.ps1; exit $LASTEXITCODE
    {{GRADLE}} :mobile:assembleRelease -x cargoBuild

# 出一个「可发布」的包：版本 +1 → assembleRelease（正式签名）→ 整理成 dist/aw-android.apk（固定名）
# → apksigner 自检（拒收 debug 证书）→ 打印给朋友的永久直链与发布命令。
# 发布前记着把版本 bump 提交并 push（publish 会检查，不 push 会拒绝）。
release:
    @pwsh -NoLogo -NoProfile -File scripts/bump_version.ps1; exit $LASTEXITCODE
    {{GRADLE}} :mobile:assembleRelease -x cargoBuild
    @pwsh -NoLogo -NoProfile -File scripts/make_release.ps1; exit $LASTEXITCODE

# 上传 dist/aw-android.apk 到 GitHub Release（自动打 tag v<versionName>；需 gh auth login + 已 push）
publish:
    @pwsh -NoLogo -NoProfile -File scripts/publish_release.ps1; exit $LASTEXITCODE

# 安装 release APK（原生 adb install -r 覆盖安装）
install-release:
    & "{{ADB}}" install -r "{{RELEASE_APK}}"; exit $LASTEXITCODE
