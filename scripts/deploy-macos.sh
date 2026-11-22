#!/usr/bin/env bash
# Build the debug APK with the macOS toolchain and install it on the USB phone.
#
# Prereqs:
#   - bash scripts/setup-macos-toolchain.sh --accept-android-sdk-licenses  (once)
#   - adb on PATH (e.g. brew install --cask android-platform-tools)
#   - phone: USB debugging on, this Mac authorised (adb devices shows "device")
#
# Env overrides:
#   CELLOCOACH_TOOLCHAIN  toolchain dir (default ~/.cellocoach-toolchain)
#   SKIP_BUILD=1          install the existing APK without rebuilding
#   WITH_TEST_APK=1       also build + install the androidTest APK
#
# Xiaomi / HyperOS shows an "install via USB" confirmation on the phone for every
# install; the script retries a few times so someone can tap 繼續安裝. A signature
# mismatch (an APK built on another machine is installed) cannot be fixed by -r:
# see docs/arc42/07_deployment_view.md §7.5 before uninstalling (it wipes app data).
set -euo pipefail
cd "$(dirname "$0")/.."

TC="${CELLOCOACH_TOOLCHAIN:-$HOME/.cellocoach-toolchain}"
APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
PKG=com.cellocoach

command -v adb >/dev/null || { echo "adb not found (brew install --cask android-platform-tools)" >&2; exit 1; }
[ "$(adb get-state 2>/dev/null | tr -d '\r')" = "device" ] || {
  adb devices -l
  echo "No authorised device. Enable USB debugging and accept the RSA prompt on the phone." >&2
  exit 1
}

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  [ -f "$TC/env.sh" ] || { echo "No toolchain at $TC — run scripts/setup-macos-toolchain.sh first." >&2; exit 1; }
  # shellcheck disable=SC1091
  source "$TC/env.sh"
  tasks="assembleDebug"
  [ "${WITH_TEST_APK:-0}" = "1" ] && tasks="$tasks assembleDebugAndroidTest"
  echo "==> gradle $tasks"
  gradle --no-daemon --console=plain $tasks
fi

install_apk() {  # retry: HyperOS cancels the install if nobody confirms on the phone in time
  local apk=$1 out
  for i in 1 2 3; do
    out=$(adb install -r -t "$apk" 2>&1 | tail -1)
    echo "    try $i: $out"
    case "$out" in
      *Success*) return 0 ;;
      *INSTALL_FAILED_USER_RESTRICTED*) echo "    -> tap 繼續安裝 / Install on the phone"; sleep 3 ;;
      *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
        echo "Signature differs from the installed app. Back up its data first (see §7.5), then: adb uninstall $PKG" >&2
        return 1 ;;
      *) return 1 ;;
    esac
  done
  return 1
}

echo "==> Installing $APK"
install_apk "$APK"
if [ "${WITH_TEST_APK:-0}" = "1" ]; then
  echo "==> Installing $TEST_APK"
  install_apk "$TEST_APK"
fi

echo "==> Launching $PKG"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
echo "Done."
