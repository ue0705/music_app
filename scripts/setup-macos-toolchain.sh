#!/usr/bin/env bash
# Install a self-contained build toolchain for CelloCoach on macOS (no Docker,
# no Android Studio, nothing system-wide): JDK 17 (Temurin), Android SDK
# cmdline-tools + platform-34 + build-tools 34.0.0, and Gradle 8.7.
#
# Everything goes under $CELLOCOACH_TOOLCHAIN (default ~/.cellocoach-toolchain,
# ~1.4 GB). Downloads come from the official sources and are checksum-verified
# where the source publishes one (Temurin, Gradle).
#
# Usage:
#   bash scripts/setup-macos-toolchain.sh --accept-android-sdk-licenses
#   source "${CELLOCOACH_TOOLCHAIN:-$HOME/.cellocoach-toolchain}/env.sh"
#   gradle --no-daemon assembleDebug          # or: bash scripts/deploy-macos.sh
#
# Installing SDK packages requires accepting the Android SDK License Agreement.
# The script refuses to do that on your behalf unless you pass the flag above.
# See docs/arc42/07_deployment_view.md §7.5.
set -euo pipefail

TC="${CELLOCOACH_TOOLCHAIN:-$HOME/.cellocoach-toolchain}"
CMDLINE_TOOLS_VERSION=11076708          # same as .devcontainer/Dockerfile
GRADLE_VERSION=8.7
ACCEPT=0
[ "${1:-}" = "--accept-android-sdk-licenses" ] && ACCEPT=1

case "$(uname -s)/$(uname -m)" in
  Darwin/arm64)  JDK_ARCH=aarch64 ;;
  Darwin/x86_64) JDK_ARCH=x64 ;;
  *) echo "This script is for macOS (got $(uname -s)/$(uname -m)). Use the devcontainer instead." >&2; exit 1 ;;
esac

if [ "$ACCEPT" != 1 ]; then
  cat >&2 <<'EOF'
Installing the Android SDK requires accepting the Android SDK License Agreement
(https://developer.android.com/studio/terms). Re-run with:
  bash scripts/setup-macos-toolchain.sh --accept-android-sdk-licenses
EOF
  exit 2
fi

mkdir -p "$TC/dl"
cd "$TC/dl"

echo "==> Downloading JDK 17 (Temurin, mac/$JDK_ARCH), Android cmdline-tools, Gradle $GRADLE_VERSION"
JDK_API="https://api.adoptium.net/v3"
curl -fsSL -o jdk17.json "$JDK_API/assets/latest/17/hotspot?architecture=$JDK_ARCH&image_type=jdk&os=mac&vendor=eclipse"
JDK_URL=$(python3 -I -c "import json;print(json.load(open('jdk17.json'))[0]['binary']['package']['link'])")
JDK_SHA=$(python3 -I -c "import json;print(json.load(open('jdk17.json'))[0]['binary']['package']['checksum'])")
curl -fsSL -o jdk17.tar.gz "$JDK_URL" &
curl -fsSL -o cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-mac-${CMDLINE_TOOLS_VERSION}_latest.zip" &
curl -fsSL -o gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" &
curl -fsSL -o gradle.zip.sha256 "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip.sha256" &
wait

echo "==> Verifying checksums"
[ "$(shasum -a 256 jdk17.tar.gz | cut -d' ' -f1)" = "$JDK_SHA" ] || { echo "JDK checksum mismatch" >&2; exit 1; }
[ "$(shasum -a 256 gradle.zip | cut -d' ' -f1)" = "$(cat gradle.zip.sha256)" ] || { echo "Gradle checksum mismatch" >&2; exit 1; }

echo "==> Unpacking"
rm -rf "$TC/jdk" "$TC/gradle-$GRADLE_VERSION" "$TC/android-sdk/cmdline-tools"
mkdir -p "$TC/jdk" "$TC/android-sdk/cmdline-tools"
tar -xzf jdk17.tar.gz -C "$TC/jdk"
unzip -q -o gradle.zip -d "$TC"
unzip -q -o cmdtools.zip -d "$TC/android-sdk/cmdline-tools"
mv "$TC/android-sdk/cmdline-tools/cmdline-tools" "$TC/android-sdk/cmdline-tools/latest"

JAVA_HOME="$(echo "$TC"/jdk/jdk-*/Contents/Home)"
export JAVA_HOME
SDKM="$TC/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$TC/android-sdk"

echo "==> Accepting Android SDK licenses (requested with --accept-android-sdk-licenses)"
yes | $SDKM --licenses >/dev/null 2>&1 || true
echo "==> Installing platforms;android-34 and build-tools;34.0.0"
$SDKM --install "platforms;android-34" "build-tools;34.0.0" >/dev/null

cat > "$TC/env.sh" <<EOF
# Source me: puts the CelloCoach toolchain on PATH for this shell.
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$TC/android-sdk" ANDROID_SDK_ROOT="$TC/android-sdk"
export GRADLE_USER_HOME="$TC/gradle-home"
# Keep the debug keystore stable across builds so 'adb install -r' keeps working.
export ANDROID_USER_HOME="$TC/android-user-home"
export PATH="\$JAVA_HOME/bin:$TC/gradle-$GRADLE_VERSION/bin:\$PATH"
EOF

rm -rf "$TC/dl"
echo "Done. Toolchain in $TC ($(du -sh "$TC" | cut -f1))."
echo "Next:  source $TC/env.sh && gradle --no-daemon assembleDebug"
