#Requires -Version 5.1
<#
.SYNOPSIS
    Build aw-android natively on Windows (no WSL required).

.DESCRIPTION
    Mirrors the Linux `make` pipeline for Windows:
      1. Build the webui (aw-server-rust/aw-webui) for Android.
      2. Cross-compile aw-server-rust to Android .so via cargo-ndk.
      3. Place the .so files into mobile/src/main/jniLibs/<abi>/.
      4. Run Gradle to assemble the APK (or AAB with -Bundle).
      5. Optionally install onto a device with adb (-Install).

    Rust -> Android cross-compilation uses `cargo-ndk`, which is the
    maintained, cross-platform way to drive the NDK toolchain. This avoids
    having to hand-roll CC/AR/RANLIB env vars and work around the NDK's
    Windows `.cmd` launcher wrappers.

    Prerequisites (install once):
      - JDK 17                        (java)
      - Android SDK + NDK r25c        (via Android Studio / SDK Manager)
      - Rust toolchain                (rustup, cargo)
      - Node.js + npm                 (for the webui)
      - Strawberry Perl               (builds the vendored OpenSSL)
      - cargo-ndk                     (auto-installed by this script)

.PARAMETER BuildType
    debug (default) or release.

.PARAMETER Abis
    Android ABIs to build. Defaults to arm64-v8a + armeabi-v7a, which are the
    two ABIs the Gradle build requires (see mandatorySoFiles in
    mobile/build.gradle).

.PARAMETER NdkPlatform
    Android API level for the NDK toolchain (matches the repo's use of the
    *-android26-clang wrappers). Default 26.

.PARAMETER SkipWebui
    Skip rebuilding the webui (reuse an existing aw-webui/dist).

.PARAMETER SkipRust
    Skip the Rust cross-compile (reuse existing jniLibs .so files).

.PARAMETER Install
    After building, install the APK onto a connected device via adb.

.PARAMETER Bundle
    Build an .aab (app bundle) instead of an .apk.

.PARAMETER NoMirror
    Do not set the rsproxy.cn / USTC Rust mirrors (they are set by default to
    match the behaviour of the Linux scripts).

.EXAMPLE
    .\build.ps1

.EXAMPLE
    .\build.ps1 -BuildType release -Install

.EXAMPLE
    .\build.ps1 -SkipWebui -SkipRust   # quick rebuild of just the APK
#>
[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')]
    [string]$BuildType = 'debug',

    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')]
    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a'),

    [int]$NdkPlatform = 26,

    [switch]$SkipWebui,
    [switch]$SkipRust,
    [switch]$Install,
    [switch]$Bundle,
    [switch]$NoMirror
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Write-Info([string]$msg) { Write-Host "    $msg" }

function Test-Command([string]$name) {
    return [bool](Get-Command $name -ErrorAction SilentlyContinue)
}

# Run a native (non-PowerShell) command and fail fast on a non-zero exit code.
function Invoke-Native {
    param(
        [Parameter(Mandatory)][string]$Exe,
        [string[]]$CmdArgs = @(),
        [string]$WorkDir
    )
    if ($WorkDir) { Push-Location $WorkDir }
    try {
        Write-Info "$Exe $($CmdArgs -join ' ')"
        & $Exe @CmdArgs
        $code = $LASTEXITCODE
    }
    finally {
        if ($WorkDir) { Pop-Location }
    }
    if ($code -ne 0) {
        throw "Command failed (exit code $code): $Exe $($CmdArgs -join ' ')"
    }
}

function Get-AndroidSdkRoot {
    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path }
    }
    return $null
}

function Get-NdkRoot {
    param([string]$SdkRoot, [string]$PreferredVersion)
    if ($env:ANDROID_NDK_HOME -and (Test-Path $env:ANDROID_NDK_HOME)) {
        return (Resolve-Path $env:ANDROID_NDK_HOME).Path
    }
    if (-not $SdkRoot) { return $null }
    $ndkDir = Join-Path $SdkRoot 'ndk'
    if (-not (Test-Path $ndkDir)) { return $null }
    $versions = Get-ChildItem $ndkDir -Directory -ErrorAction SilentlyContinue
    # Prefer the pinned version, otherwise fall back to the newest available.
    $pinned = $versions | Where-Object { $_.Name -eq $PreferredVersion }
    if ($pinned) { return $pinned.FullName }
    $newest = $versions | Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } -Descending | Select-Object -First 1
    if ($newest) { return $newest.FullName }
    return $null
}

# ---------------------------------------------------------------------------
# Resolve repo layout
# ---------------------------------------------------------------------------
$RepoRoot   = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ServerRust = Join-Path $RepoRoot 'aw-server-rust'
$WebuiDir   = Join-Path $ServerRust 'aw-webui'
$MobileDir  = Join-Path $RepoRoot 'mobile'
$JniLibsDir = Join-Path $MobileDir 'src\main\jniLibs'
$NdkVersionPinned = '25.2.9519653'   # r25c, matches mobile/build.gradle

Write-Host "aw-android Windows build" -ForegroundColor Cyan
Write-Info "Repo root : $RepoRoot"
Write-Info "Build type: $BuildType"
Write-Info "ABIs      : $($Abis -join ', ')"

# ---------------------------------------------------------------------------
# Prerequisite checks
# ---------------------------------------------------------------------------
Write-Step "Checking prerequisites"

foreach ($tool in @('java', 'cargo', 'rustup', 'node', 'npm')) {
    if (-not (Test-Command $tool)) {
        throw "Required tool '$tool' not found on PATH. Please install it first."
    }
}

# openssl is vendored and compiled from source, which needs Perl on Windows.
if (-not $SkipRust -and -not (Test-Command 'perl')) {
    throw ("Perl not found on PATH. It is required to build the vendored OpenSSL " +
           "for Android. Install Strawberry Perl (https://strawberryperl.com/) and re-run.")
}

$SdkRoot = Get-AndroidSdkRoot
if (-not $SdkRoot) {
    throw ("Android SDK not found. Set ANDROID_HOME, or install Android Studio. " +
           "Expected e.g. $env:LOCALAPPDATA\Android\Sdk")
}
Write-Ok "Android SDK: $SdkRoot"

$NdkRoot = Get-NdkRoot -SdkRoot $SdkRoot -PreferredVersion $NdkVersionPinned
if (-not $NdkRoot) {
    throw ("Android NDK not found under '$SdkRoot\ndk'. Install it via the SDK Manager " +
           "(preferred: ndk;$NdkVersionPinned) or set ANDROID_NDK_HOME.")
}
Write-Ok "Android NDK: $NdkRoot"
$env:ANDROID_NDK_HOME = $NdkRoot

# Ensure local.properties points at the SDK (Gradle reads sdk.dir from here).
# Use forward slashes: Gradle's properties parser treats backslashes as escapes.
$LocalProps = Join-Path $RepoRoot 'local.properties'
$sdkForProps = $SdkRoot -replace '\\', '/'
Set-Content -Path $LocalProps -Value "sdk.dir=$sdkForProps" -Encoding ASCII
Write-Ok "Wrote local.properties (sdk.dir)"

# Rust targets for the selected ABIs.
$abiToTarget = @{
    'arm64-v8a'   = 'aarch64-linux-android'
    'armeabi-v7a' = 'armv7-linux-androideabi'
    'x86'         = 'i686-linux-android'
    'x86_64'      = 'x86_64-linux-android'
}
$targets = @($Abis | ForEach-Object { $abiToTarget[$_] })
Invoke-Native 'rustup' (@('target', 'add') + $targets)

# cargo-ndk drives the NDK toolchain for us.
if (-not (Test-Command 'cargo-ndk')) {
    Write-Info "cargo-ndk not found; installing (cargo install cargo-ndk) ..."
    Invoke-Native 'cargo' @('install', 'cargo-ndk')
}

# Optional Rust mirrors (match the Linux scripts). Can be disabled with -NoMirror.
if (-not $NoMirror) {
    $env:RUSTUP_DIST_SERVER = 'https://rsproxy.cn'
    $env:RUSTUP_UPDATE_ROOT = 'https://rsproxy.cn/rustup'
    $env:CARGO_REGISTRIES_CRATES_IO_PROTOCOL = 'sparse'
}

# ---------------------------------------------------------------------------
# 1. webui
# ---------------------------------------------------------------------------
if (-not $SkipWebui) {
    Write-Step "Building webui (aw-webui) for Android"
    if (-not (Test-Path (Join-Path $WebuiDir 'node_modules'))) {
        Invoke-Native 'npm' @('ci') -WorkDir $WebuiDir
    }
    # prebuild: copy logo assets expected by the build
    $staticDir = Join-Path $WebuiDir 'static'
    New-Item -ItemType Directory -Force -Path $staticDir | Out-Null
    foreach ($ext in @('png', 'svg')) {
        $src = Join-Path $WebuiDir "media\logo\logo.$ext"
        if (Test-Path $src) { Copy-Item $src (Join-Path $staticDir "logo.$ext") -Force }
    }
    Invoke-Native 'npm' @('run', 'build', '--', '--os=android') -WorkDir $WebuiDir
    Write-Ok "webui built -> $(Join-Path $WebuiDir 'dist')"
}
else {
    Write-Step "Skipping webui build (-SkipWebui)"
}

# ---------------------------------------------------------------------------
# 2. Rust cross-compile (cargo-ndk)
# ---------------------------------------------------------------------------
if (-not $SkipRust) {
    Write-Step "Cross-compiling aw-server-rust with cargo-ndk"

    # NDK r25+ ships libunwind, but some crates still ask for -lgcc. Provide a
    # libgcc.a linker script shim next to each libunwind.a (same as Linux script).
    Get-ChildItem -Path $NdkRoot -Recurse -Filter 'libunwind.a' -ErrorAction SilentlyContinue |
        ForEach-Object {
            $shim = Join-Path $_.DirectoryName 'libgcc.a'
            Set-Content -Path $shim -Value 'INPUT(-lunwind)' -Encoding ASCII -NoNewline
        }

    # Keep debug symbols and align to 16 KiB pages (Android 15 requirement).
    $env:RUSTFLAGS = '-C debuginfo=2 -Awarnings -C link-arg=-z -C link-arg=max-page-size=16384'

    $cargoArgs = @('ndk', '--platform', "$NdkPlatform")
    foreach ($a in $Abis) { $cargoArgs += @('-t', $a) }
    $cargoArgs += @('-o', $JniLibsDir, 'build', '-p', 'aw-server', '--lib')
    if ($BuildType -eq 'release') { $cargoArgs += '--release' }

    Invoke-Native 'cargo' $cargoArgs -WorkDir $ServerRust
    Write-Ok "Rust build complete; .so files written under $JniLibsDir"
}
else {
    Write-Step "Skipping Rust build (-SkipRust)"
}

# Verify the .so files the Gradle build requires are present.
foreach ($a in $Abis) {
    $so = Join-Path $JniLibsDir "$a\libaw_server.so"
    if (-not (Test-Path $so)) {
        throw "Expected $so but it is missing. Run without -SkipRust to build it."
    }
}

# ---------------------------------------------------------------------------
# 3. Gradle assemble
# ---------------------------------------------------------------------------
Write-Step "Assembling the app with Gradle"
$gradlew = Join-Path $RepoRoot 'gradlew.bat'
if (-not (Test-Path $gradlew)) { throw "gradlew.bat not found at $gradlew" }

$capitalised = (Get-Culture).TextInfo.ToTitleCase($BuildType)   # Debug / Release
$gradleTask = if ($Bundle) { ":mobile:bundle$capitalised" } else { ":mobile:assemble$capitalised" }
Invoke-Native $gradlew @($gradleTask, '--stacktrace') -WorkDir $RepoRoot

# ---------------------------------------------------------------------------
# 4. Locate & (optionally) install the artifact
# ---------------------------------------------------------------------------
$outRoot = if ($Bundle) { Join-Path $MobileDir 'build\outputs\bundle' }
           else         { Join-Path $MobileDir 'build\outputs\apk' }
$artifact = Get-ChildItem -Path (Join-Path $outRoot $BuildType) -Include '*.apk', '*.aab' -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 1

if ($artifact) {
    Write-Step "Build succeeded"
    Write-Ok "Artifact: $($artifact.FullName)"

    if ($Install) {
        Write-Step "Installing onto device via adb"
        $adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
        if (-not (Test-Path $adb)) { $adb = 'adb' }
        Invoke-Native $adb @('install', '-r', $artifact.FullName)
        Write-Ok "Installed."
    }
}
else {
    throw "Build finished but no artifact was found under $outRoot"
}

Write-Host "`nDone." -ForegroundColor Green
