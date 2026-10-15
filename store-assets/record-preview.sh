#!/bin/sh
# Records the app preview footage: runs ScreenshotTests/testPreviewFlow on a store simulator while
# `simctl io recordVideo` captures the screen, then writes the shot markers the test printed, as
# seconds from the start of the recording, beside the video. video.mjs cuts the preview from both.
#
# Usage: store-assets/record-preview.sh [device-name ...]   (run capture-ios.sh --build first)
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DERIVED=${GALLR_STORE_DERIVED_DATA:-"$ROOT/build/store-derived-data"}
CACHE="$HOME/Library/Caches/tools.fastlane"
OUT="$ROOT/store-assets/captures/preview"
LOCALES=${GALLR_STORE_LOCALES:-"ko en-US"}
if [ "$#" -eq 0 ]; then set -- "Gallr Store iPhone 17 Pro Max" "Gallr Store iPad Pro 13 M5"; fi
failures=0

XCTESTRUN=$(ls "$DERIVED"/Build/Products/*.xctestrun 2>/dev/null | head -n 1)
if [ -z "$XCTESTRUN" ]; then
  echo "no test build in $DERIVED; run capture-ios.sh --build first" >&2
  exit 1
fi
APP="$DERIVED/Build/Products/Debug-iphonesimulator/iosApp.app"

udid_for() {
  xcrun simctl list devices available -j | python3 -c '
import json, sys
for runtime, items in json.load(sys.stdin)["devices"].items():
    for item in items:
        if item["name"] == sys.argv[1]:
            print(item["udid"]); sys.exit(0)
sys.exit("no simulator named " + sys.argv[1])
' "$1"
}

for device in "$@"; do
  udid=$(udid_for "$device")
  xcrun simctl boot "$udid" 2>/dev/null || true
  xcrun simctl bootstatus "$udid" -b >/dev/null
  xcrun simctl ui "$udid" appearance light
  xcrun simctl status_bar "$udid" override --time 9:41 --dataNetwork wifi --wifiMode active \
    --wifiBars 3 --cellularMode active --cellularBars 4 --batteryState discharging --batteryLevel 100
  xcrun simctl location "$udid" set 37.5372,127.0005

  for locale in $LOCALES; do
    xcrun simctl uninstall "$udid" com.gallr.app 2>/dev/null || true
    xcrun simctl install "$udid" "$APP"
    xcrun simctl privacy "$udid" grant location com.gallr.app
    mkdir -p "$CACHE"
    printf '%s' "$locale" > "$CACHE/language.txt"

    dest="$OUT/$locale/$device"
    mkdir -p "$dest"
    log="$dest/test.log"
    rm -f "$dest/recording.mov"

    xcrun simctl io "$udid" recordVideo --codec h264 --force "$dest/recording.mov" 2> "$dest/record.log" &
    recorder=$!
    # The recorder prints "Recording started" once frames flow; time zero is that moment. A recorder
    # left over from an interrupted run holds the device ("Host recording is already in progress"):
    # restart the simulator to release it.
    waited=0
    until grep -q "Recording started" "$dest/record.log" 2>/dev/null; do
      if ! kill -0 "$recorder" 2>/dev/null || [ "$waited" -gt 300 ]; then
        echo "recorder did not start on $device ($locale):" >&2
        cat "$dest/record.log" >&2
        exit 1
      fi
      sleep 0.1
      waited=$((waited + 1))
    done
    start=$(python3 -c 'import time; print(time.time())')

    xcodebuild test-without-building -xctestrun "$XCTESTRUN" -destination "id=$udid" \
      -only-testing:iosAppUITests/ScreenshotTests/testPreviewFlow > "$log" 2>&1 \
      || { echo "testPreviewFlow failed on $device ($locale); see $log" >&2; failures=$((failures + 1)); }

    kill -INT "$recorder" 2>/dev/null || true
    wait "$recorder" || true

    python3 - "$log" "$start" > "$dest/marks.json" <<'PY'
import json, re, sys
log, start = sys.argv[1], float(sys.argv[2])
marks = {}
for line in open(log, encoding="utf-8", errors="replace"):
    match = re.search(r"PREVIEW_MARK (\w+) ([\d.]+)", line)
    if match and match.group(1) not in marks:
        marks[match.group(1)] = round(float(match.group(2)) - start, 3)
print(json.dumps(marks, indent=2))
PY
    echo "$device ($locale): $(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$dest/marks.json") marks in $dest"
  done
done

if [ "$failures" -gt 0 ]; then
  echo "$failures preview recordings failed; video.mjs would cut incomplete footage" >&2
  exit 1
fi
