#!/bin/bash
export PATH="$HOME/AppData/Local/Android/Sdk/ndk/25.2.9519653/toolchains/llvm/prebuilt/windows-x86_64/bin:$PATH"
echo "--- armv7a ---"
armv7a-linux-androideabi24-clang --version 2>&1 | head -3
echo "--- aarch64 ---"
aarch64-linux-android24-clang --version 2>&1 | head -3
