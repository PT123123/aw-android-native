aw-android
==========

[![GitHub Actions badge](https://github.com/ActivityWatch/aw-android/workflows/Build/badge.svg)](https://github.com/ActivityWatch/aw-android/actions)
[![Play Store ratings](https://PlayBadges.pavi2410.me/badge/ratings?id=net.activitywatch.android&country=us)](https://play.google.com/store/apps/details?id=net.activitywatch.android)

A very work-in-progress ActivityWatch app for Android.

Available on Google Play:

<a title="Get it on Google Play" href="https://play.google.com/store/apps/details?id=net.activitywatch.android">
    <img src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png" width="240px"/>
</a>


## Usage

Install the APK from the Play Store or from the [GitHub releases](https://github.com/ActivityWatch/aw-android/releases).

### For Oculus Quest

> **Note** 
> At some point a Quest system upgrade broke the ability to allow ActivityWatch access to usage stats. This can be fixed by manually assigning the needed permission using adb: `adb shell appops set net.activitywatch.android android:get_usage_stats allow`

It's available [on SideQuest](https://sidequestvr.com/#/app/201). 


## Building

To build this app you first need to build aw-server-rust (`./aw-server-rust`) and aw-webui (`./aw-server-rust/aw-webui`).

If you haven't already, initialize the submodules with: `git submodule update --init --recursive`

### Building aw-server-rust

> **Note**
> If you don't want to go through the hassle of getting Rust up and running, you can download the jniLibs from [aw-server-rust CI artifacts](https://github.com/ActivityWatch/aw-server-rust/actions/workflows/build.yml) and place them in `mobile/src/main/jniLibs` manually instead of following this section.

To build aw-server-rust you need to have Rust nightly installed (with rustup). Then you can build it with:

```
export ANDROID_NDK_HOME=`pwd`/aw-server-rust/NDK  # The path to your NDK
pushd aw-server-rust && ./install-ndk.sh; popd    # This configures the NDK for use with Rust, and installs the NDK if missing
env RELEASE=false make aw-server-rust             # Set RELEASE=true to build in release mode (slower build, harder to debug)
```

> **Note**
> The Android NDK will be downloaded by `install-ndk.sh` to `aw-server-rust/NDK` if `ANDROID_NDK_HOME` not set. You can create a symlink pointing to the real location if you already have it elsewhere (such as /opt/android-ndk/ on Arch Linux).

### Building aw-webui

To build aw-webui you need a recent version of node/npm installed. You can then build it with `make aw-webui`.

### Putting it all together

Once both aw-server-rust and aw-webui is built, you can build the Android app as any other Android app using Android Studio.

### Building on Windows

Two ways are supported. Both require the submodules to be initialized first
(`git submodule update --init --recursive`).

**Option A — native PowerShell (recommended).** A self-contained script drives
the whole pipeline (webui → Rust cross-compile → jniLibs → Gradle) without WSL:

```powershell
# from the repo root
powershell -ExecutionPolicy Bypass -File scripts\win\build.ps1
# release build + install onto a connected device:
powershell -ExecutionPolicy Bypass -File scripts\win\build.ps1 -BuildType release -Install
```

Prerequisites (install once): JDK 17, Android SDK + NDK r25c, Rust (`rustup`),
Node.js/npm, and [Strawberry Perl](https://strawberryperl.com/) (needed to build
the vendored OpenSSL). The script installs `cargo-ndk` automatically and uses it
for the Rust → Android cross-compilation.

**Option B — Git Bash / MSYS2 (reuses the `make` pipeline).** If you prefer the
same flow as Linux, install [MSYS2](https://www.msys2.org/) (or Git for Windows),
then run the usual `make` targets from that shell. The `Makefile`,
`install-ndk.sh` and `compile-android.sh` detect the Windows host and use the
`windows-x86_64` NDK toolchain. Set `ANDROID_NDK_HOME` (or let
`install-ndk.sh` find the NDK under `%LOCALAPPDATA%\Android\Sdk\ndk`) first.

Notes / common pitfalls on Windows:

- Use forward slashes (or escaped backslashes) for `sdk.dir` in
  `local.properties`; the build script writes it for you.
- Building the vendored OpenSSL requires Perl on `PATH`.
- Long paths: enable Windows long-path support or keep the repo in a short path
  (e.g. `C:\src\aw-android`), as Rust builds produce deep directory trees.
- Ensure the NDK version matches `mobile/build.gradle` (`ndkVersion`), currently
  `25.2.9519653` (r25c).

### Making a release

To make a release, make a signed tag and push it to GitHub:

```sh
git tag -s v0.1.0
git push origin refs/tags/v0.1.0
```

This will trigger a GitHub Actions workflow which will build the app and upload it to GitHub releases, and deploy it to the Play Store (including the metadata in `./fastlane/metadata/android`).

## More info

For more info, check out the main [ActivityWatch repo](https://github.com/ActivityWatch/activitywatch).
