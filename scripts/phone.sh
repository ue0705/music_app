#!/usr/bin/env bash
# Drive and inspect CelloCoach on a USB phone through adb, by Compose testTag.
# The app sets testTagsAsResourceId, so every TestTags.* string is a resource-id
# in `uiautomator dump`.
#
#   phone.sh dump                  every element as "resource-id | text"
#   phone.sh tap <resource-id>     tap the centre of that element
#   phone.sh taptext <text>        tap the first element whose text matches exactly
#   phone.sh text <resource-id>    text of that element and its children (<absent> if missing)
#   phone.sh scroll                swipe up once (scroll the Home list)
#   phone.sh shot <name>           screenshot -> $PHONE_OUT/<name>.png
#   phone.sh mic                   last microphone event AudioService logged for the app
#   phone.sh focus                 the focused window (which app is in front)
#
# Notes (docs/arc42/07_deployment_view.md §7.5, 11_risks_and_technical_debt.md):
#   - tap/taptext/scroll need input injection. On Xiaomi / HyperOS enable
#     「USB 偵錯（安全設定）」, then run `adb usb` once so adbd picks it up.
#   - uiautomator cannot dump while an instrumentation test is running.
set -uo pipefail

PKG=com.cellocoach
OUT="${PHONE_OUT:-${TMPDIR:-/tmp}/cellocoach-phone}"
XML="$OUT/ui.xml"
mkdir -p "$OUT"

dump() {
  [ "$(adb get-state 2>/dev/null | tr -d '\r')" = "device" ] || { echo "no authorised device (adb devices)" >&2; return 1; }
  rm -f "$XML"
  adb shell uiautomator dump /sdcard/cc_ui.xml >/dev/null 2>&1
  adb pull /sdcard/cc_ui.xml "$XML" >/dev/null 2>&1
  adb shell rm -f /sdcard/cc_ui.xml >/dev/null 2>&1
  [ -s "$XML" ] || { echo "uiautomator dump failed (is an instrumentation running?)" >&2; return 1; }
}

# centre <attr> <value>  -> "x y" of the first node whose attribute equals value
centre() {
  python3 -I - "$XML" "$1" "$2" <<'EOF'
import sys, re, xml.etree.ElementTree as ET
_, path, attr, value = sys.argv
for n in ET.parse(path).iter('node'):
    if n.get(attr) == value:
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
EOF
}

tap_by() {  # tap_by <attr> <value>
  dump || return 1
  local c; c=$(centre "$1" "$2")
  [ -n "$c" ] || { echo "not found: $2" >&2; return 1; }
  # shellcheck disable=SC2086
  adb shell input tap $c
  sleep "${TAP_WAIT:-1.5}"
}

case "${1:-}" in
  dump)
    dump || exit 1
    python3 -I - "$XML" <<'EOF'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter('node'):
    rid, txt = n.get('resource-id', ''), n.get('text', '')
    if rid or txt:
        print(f"{rid:34} | {txt}")
EOF
    ;;
  tap)     tap_by resource-id "$2" ;;
  taptext) tap_by text "$2" ;;
  text)
    dump || exit 1
    python3 -I - "$XML" "$2" <<'EOF'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter('node'):
    if n.get('resource-id') == sys.argv[2]:
        print(' '.join(t for t in (m.get('text', '') for m in n.iter('node')) if t))
        break
else:
    print('<absent>')
EOF
    ;;
  scroll)
    read -r w h < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
    adb shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 400
    sleep 1
    ;;
  shot)
    adb exec-out screencap -p > "$OUT/$2.png" && echo "$OUT/$2.png"
    ;;
  mic)
    # Each capture start/stop is logged as "rec start|update|stop|release ... pack:<pkg>".
    adb shell dumpsys audio | grep "pack:$PKG" | tail -1 | sed -E 's/riid.*src:MIC//'
    ;;
  focus)
    adb shell dumpsys window | grep mCurrentFocus | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+' | head -1
    ;;
  *)
    sed -n '2,20p' "$0" >&2
    exit 2
    ;;
esac
