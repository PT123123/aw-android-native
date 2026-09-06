# aw-android —— 用 just 驱动构建与安装
#
# 常用命令：
#   just                列出全部命令
#   just build          编译 debug APK（复用 jniLibs 里已编好的 libaw_server.so，跳过 Rust 重编）
#   just install        adb 安装 debug APK（默认装手机，按型号自动识别）
#   just install phone  指定装到手机
#   just install tab    指定装到平板
#   just install-all    两台都装
#   just run            编译 + 安装 + 启动
#   just kotlinc        只快速校验 Kotlin/资源改动（不重建 .so、离线）
#   just build-release  编 release APK（自动 versionName/versionCode +1）
#   just sign-release   用 android.jks 签名（需先准备 keystore 与 JKS_* 环境变量）
#   just install-release adb 安装已签名的 release APK
#
# 说明：
#   - 本机用 Gradle 8.1 二进制（非 gradlew，因 Git Bash 下 gradlew 会 ClassNotFound），JDK 17。
#   - preBuild 依赖 cargoBuild（Rust 重编），耗时且非交互 shell 里易卡 openssl-sys；
#     因此 build/install/run 统一 `-x cargoBuild`，复用 jniLibs 下已有的 .so。
#     若要真正重编 Rust 库，请先 `make -C aw-server-rust android` 或单独跑 cargoBuild。

# ---- 工具链路径（按需修改）----
# 以下三个导出给 gradle / adb / sign_apk.sh 使用
export JAVA_HOME   := "/c/Users/ted/Tools/jdk-17/zulu17.68.203-ca-jdk17.0.20.1-win_x64"
export ANDROID_HOME := "/c/Users/ted/AppData/Local/Android/Sdk"
# Gradle JVM 走本机 HTTP 代理（大文件会被代理掐断，但 Gradle 依赖体积极小，正常）
export GRADLE_OPTS := "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10809 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10809"

GRADLE      := "/c/Users/ted/.gradle/wrapper/dists/gradle-8.1-bin/2eyty4r6kz6fpakefpk52nbbm/gradle-8.1/bin/gradle"
export ADB        := "/c/Users/ted/AppData/Local/Android/Sdk/platform-tools/adb"

DEBUG_APK   := "mobile/build/outputs/apk/debug/mobile-debug.apk"
UNSIGNED_APK := "mobile/build/outputs/apk/release/mobile-release-unsigned.apk"
RELEASE_APK := "mobile/build/outputs/apk/release/mobile-release.apk"

# 默认：列出命令
default:
    @just --list

# 编译 debug APK（跳过 Rust 重编，复用已有 .so）
build:
    {{GRADLE}} :mobile:assembleDebug -x cargoBuild

# adb 安装 debug APK（-r 覆盖安装；默认装手机，`just install phone|tab` 指定设备）
# 动态识别设备：型号含 pad/tablet 或 TB 开头视为平板，Justfile 不写死序列号
# 顶层不执行反引号，避免 parse 时脚本 exit 1 炸掉所有命令
export PHONE_SERIAL := ""
export TAB_SERIAL := ""

install device="phone":
    @case "{{device}}" in phone|tab) ;; *) echo "用法: just install [phone|tab]"; exit 1;; esac; \
    serial=$(bash scripts/pick_device.sh {{device}}); \
    test -n "$serial" || (echo "未检测到{{if device == "tab" { "平板" } else { "手机" } }}设备（按型号 pad/tablet/TB 识别平板），请确认已连接 adb"; exit 1); \
    {{ADB}} -s "$serial" install -r "{{DEBUG_APK}}"

# 两台都装
install-all: (install "phone") (install "tab")

# 编译 + 安装 + 启动到红米手机
run: build (install "phone")
    @serial=$(bash scripts/pick_device.sh phone); \
    {{ADB}} -s "$serial" shell am start -n net.activitywatch.android.debug/net.activitywatch.android.MainActivity

# 只快速校验 Kotlin/资源改动（不重建 .so、离线，依赖已缓存）
kotlinc:
    {{GRADLE}} :mobile:compileDebugKotlin -x cargoBuild --offline

# 编 release APK（versionName/versionCode 自动 +1，未签名）
build-release:
    bash scripts/bump_version.sh
    {{GRADLE}} :mobile:assembleRelease -x cargoBuild

# 用 android.jks 签名 release APK
# 前置：需在项目根放解密后的 android.jks，并导出 JKS_STOREPASS / JKS_KEYPASS
sign-release:
    bash scripts/sign_apk.sh "{{UNSIGNED_APK}}" "{{RELEASE_APK}}"

# adb 安装已签名 release APK
install-release:
    {{ADB}} install -r "{{RELEASE_APK}}"
