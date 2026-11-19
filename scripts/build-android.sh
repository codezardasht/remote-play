#!/usr/bin/env bash
# Build the Android / Google TV app and (optionally) install it over ADB.
#
#   scripts/build-android.sh                 # build signed release APK
#   scripts/build-android.sh 192.168.1.50    # build, then install on that TV / device
#
# Tools are expected where scripts set them up once:
#   JDK 17          ~/.local/android-tools/jdk17
#   protoc          ~/.local/android-tools/protoc/bin
#   python venv     ~/.local/android-tools/venv   (protobuf, for nanopb)
#   Android SDK/NDK ~/Android/Sdk                 (written to android/local.properties)
# Override any of them with JAVA_HOME / ANDROID_HOME / TOOLS.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${TOOLS:-$HOME/.local/android-tools}"
export JAVA_HOME="${JAVA_HOME_17:-$TOOLS/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$TOOLS/venv/bin:$TOOLS/protoc/bin:$JAVA_HOME/bin:$PATH"

cd "$ROOT/android"
./gradlew assembleRelease --no-daemon "${@:2}"

APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
echo "APK: $APK"

if [ $# -ge 1 ] && [ -n "$1" ]; then
	DEV="$1"
	case "$DEV" in *:*) ;; *) DEV="$DEV:5555" ;; esac
	adb connect "$DEV" >/dev/null
	adb -s "$DEV" install -r "$APK"
	adb -s "$DEV" shell monkey -p com.metallic.chiaki -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 || true
	echo "Installed and launched on $DEV"
fi
