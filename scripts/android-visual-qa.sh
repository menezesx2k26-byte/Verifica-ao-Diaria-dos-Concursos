#!/usr/bin/env bash
set -euo pipefail

PKG="com.menezes.concursoswatch.dev"
ACTIVITY="$PKG/com.menezes.concursoswatch.MainActivity"

adb wait-for-device
gradle -p android :app:connectedDebugAndroidTest --stacktrace
mkdir -p visual-qa

adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

# Seed a bundle that already satisfies the Android dashboard contract.
for file in manifest.json index.html dashboard.css; do
  adb push "dashboard-fixture/$file" "/data/local/tmp/cw-$file" >/dev/null
  adb shell chmod 644 "/data/local/tmp/cw-$file"
done
adb shell "run-as $PKG mkdir -p files/dashboard/current"
adb shell "run-as $PKG cp /data/local/tmp/cw-manifest.json files/dashboard/current/manifest.json"
adb shell "run-as $PKG cp /data/local/tmp/cw-index.html files/dashboard/current/index.html"
adb shell "run-as $PKG cp /data/local/tmp/cw-dashboard.css files/dashboard/current/dashboard.css"

adb shell am force-stop "$PKG"
adb shell am start -n "$ACTIVITY"
sleep 8
adb exec-out screencap -p > visual-qa/01-home-dynamic.png

# No cache + no network must show native Compose fallback, never a broken WebView.
adb shell am force-stop "$PKG"
adb shell "run-as $PKG rm -rf files/dashboard"
adb shell svc wifi disable || true
adb shell svc data disable || true
adb shell settings put global airplane_mode_on 1 || true
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null || true
adb shell am start -n "$ACTIVITY"
sleep 6
adb exec-out screencap -p > visual-qa/02-home-offline-fallback.png

adb shell settings put global airplane_mode_on 0 || true
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null || true
adb shell svc wifi enable || true
adb shell svc data enable || true
sleep 2

# Pixel 2 emulator: five equally spaced bottom navigation items.
adb shell input tap 324 1740
sleep 2
adb exec-out screencap -p > visual-qa/03-alertas.png

adb shell input tap 540 1740
sleep 2
adb exec-out screencap -p > visual-qa/04-concursos.png

adb shell input tap 756 1740
sleep 2
adb exec-out screencap -p > visual-qa/05-salvos.png

adb shell input tap 972 1740
sleep 2
adb exec-out screencap -p > visual-qa/06-ajustes.png

adb shell uiautomator dump /sdcard/window.xml || true
adb pull /sdcard/window.xml visual-qa/window.xml || true
