#!/bin/sh
# Raw iOS captures for the store set: runs ScreenshotTests on the dedicated store simulators for each
# locale and collects the PNGs into store-assets/captures/ios/<locale>/<device>/.
#
# fastlane snapshot 2.240 cannot address custom simulator names, so this drives xcodebuild directly and
# uses SnapshotHelper's own contract (language.txt in ~/Library/Caches/tools.fastlane, screenshots
# written back to the same cache).
#
# Usage: store-assets/capture-ios.sh [--build] [device-name ...]
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DERIVED=${GALLR_STORE_DERIVED_DATA:-"$ROOT/build/store-derived-data"}
CACHE="$HOME/Library/Caches/tools.fastlane"
OUT="$ROOT/store-assets/captures/ios"
LOCALES=${GALLR_STORE_LOCALES:-"ko en-US"}
SLOTS=6
failures=0
# Hannam-dong, Seoul: the densest gallery cluster, and the neighbourhood of the captured route.
LATITUDE=37.5372
LONGITUDE=127.0005

build=false
if [ "${1:-}" = "--build" ]; then build=true; shift; fi
if [ "$#" -eq 0 ]; then set -- "Gallr Store iPhone 17 Pro Max" "Gallr Store iPad Pro 13 M5"; fi

udid_for() {
  xcrun simctl list devices available -j | python3 -c '
import json, sys
name = sys.argv[1]
for runtime, items in json.load(sys.stdin)["devices"].items():
    for item in items:
        if item["name"] == name:
            print(item["udid"]); sys.exit(0)
sys.exit("no simulator named " + name + "; create it as described in store-assets/README.md")
' "$1"
}

if $build || ! ls "$DERIVED"/Build/Products/*.xctestrun >/dev/null 2>&1; then
  xcodebuild -project "$ROOT/iosApp/iosApp.xcodeproj" -scheme iosApp -configuration Debug \
    -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath "$DERIVED" \
    ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build-for-testing
fi
XCTESTRUN=$(ls "$DERIVED"/Build/Products/*.xctestrun | head -n 1)
APP="$DERIVED/Build/Products/Debug-iphonesimulator/iosApp.app"

for device in "$@"; do
  udid=$(udid_for "$device")
  xcrun simctl boot "$udid" 2>/dev/null || true
  xcrun simctl bootstatus "$udid" -b >/dev/null
  xcrun simctl ui "$udid" appearance light
  xcrun simctl status_bar "$udid" override --time 9:41 --dataNetwork wifi --wifiMode active \
    --wifiBars 3 --cellularMode active --cellularBars 4 --batteryState discharging --batteryLevel 100
  xcrun simctl location "$udid" set "$LATITUDE,$LONGITUDE"

  for locale in $LOCALES; do
    # A fresh install per locale: no saved exhibitions, no route draft, the default language.
    xcrun simctl uninstall "$udid" com.gallr.app 2>/dev/null || true
    xcrun simctl install "$udid" "$APP"
    xcrun simctl privacy "$udid" grant location com.gallr.app
    mkdir -p "$CACHE/screenshots"
    rm -f "$CACHE"/screenshots/*.png
    printf '%s' "$locale" > "$CACHE/language.txt"
    printf '%s' "$(echo "$locale" | tr '-' '_')" > "$CACHE/locale.txt"

    if ! xcodebuild test-without-building -xctestrun "$XCTESTRUN" -destination "id=$udid" \
      -only-testing:iosAppUITests/ScreenshotTests/testStoreCaptures; then
      echo "ScreenshotTests failed on $device ($locale)" >&2
      failures=$((failures + 1))
    fi

    # Replace only the captures this run produced, so a failed run keeps the last good ones.
    dest="$OUT/$locale/$device"
    mkdir -p "$dest"
    for png in "$CACHE"/screenshots/*.png; do
      [ -e "$png" ] || continue
      name=$(basename "$png" | sed "s/^$device-//")
      mv "$png" "$dest/$name"
    done
    count=$(ls "$dest"/*.png 2>/dev/null | wc -l | tr -d ' ')
    echo "$device ($locale): $count captures in $dest"
    if [ "$count" -ne "$SLOTS" ]; then
      echo "expected $SLOTS captures for $device ($locale)" >&2
      failures=$((failures + 1))
    fi
  done
done

# A partial set must never look like success: the upload lane replaces the whole live listing.
if [ "$failures" -gt 0 ]; then
  echo "$failures capture runs failed or came up short; the composed set is not complete" >&2
  exit 1
fi
