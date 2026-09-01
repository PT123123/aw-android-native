#!/usr/bin/env bash
# Bump the Android app version before each release build.
#
#   versionName "X.Y.Z"  -> patch Z + 1   (e.g. 0.13.0 -> 0.13.1)
#   versionCode  N       -> N + 1          (e.g. 35 -> 36)
#
# This is what makes "release 每次编译都 +0.01" work. It edits mobile/build.gradle
# in place, so the bump persists across builds (next release build starts from the
# already-bumped version). The Makefile calls this automatically before assembling a
# release APK/bundle (only when RELEASE=1); you can also run it by hand before a
# direct `./gradlew :mobile:assembleRelease`.
#
# Usage: bash scripts/bump_version.sh [path/to/build.gradle]
set -euo pipefail

F="${1:-mobile/build.gradle}"
if [ ! -f "$F" ]; then
  echo "version file not found: $F" >&2
  exit 1
fi

# --- read current values ---
vname=$(grep -oE 'versionName "[0-9]+\.[0-9]+\.[0-9]+"' "$F" | head -1 | sed -E 's/versionName "([^"]+)"/\1/')
vcode=$(grep -oE 'versionCode [0-9]+' "$F" | head -1 | sed -E 's/versionCode ([0-9]+)/\1/')

if [ -z "$vname" ] || [ -z "$vcode" ]; then
  echo "could not find versionName/versionCode in $F" >&2
  exit 1
fi

maj=$(echo "$vname" | cut -d. -f1)
min=$(echo "$vname" | cut -d. -f2)
pat=$(echo "$vname" | cut -d. -f3)

pat=$((pat + 1))
nvname="$maj.$min.$pat"
nvcode=$((vcode + 1))

# --- write back in place ---
sed -i -E "s/versionName \"[0-9]+\.[0-9]+\.[0-9]+\"/versionName \"$nvname\"/" "$F"
sed -i -E "s/versionCode [0-9]+/versionCode $nvcode/" "$F"

echo "bumped $F : versionName $vname -> $nvname ; versionCode $vcode -> $nvcode"
