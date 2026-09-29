#!/usr/bin/env bash
# On-device smoke test. Usage: scripts/smoke-test.sh <adb-serial> [apk]
# Installs the launcher, makes it the home app, drives the main flows with adb input,
# then runs a monkey stress pass. Exits non-zero on any failure or crash/ANR.
#
# Physical devices (serial not "emulator-*") run in SAFE mode: no lock-screen / stay-awake
# changes, no package removal, no monkey (random taps could toggle quick settings), and the
# original home app and dark-mode setting are restored at the end.
set -uo pipefail

SERIAL=${1:?usage: smoke-test.sh <adb-serial> [apk]}
APK=${2:-app/build/outputs/apk/debug/app-debug.apk}
PKG=dev.minimal.launcher
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb -s $SERIAL"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
FAILS=0
SAFE=0; [[ "$SERIAL" == emulator-* ]] || SAFE=1

pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILS=$((FAILS + 1)); }

dump() { $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB shell cat /sdcard/ui.xml > "$TMP/ui.xml"; }
has_id() { dump; grep -q "resource-id=\"$PKG:id/$1\"" "$TMP/ui.xml"; }
has_text() { dump; grep -q "text=\"$1\"" "$TMP/ui.xml"; }
has_desc() { dump; grep -q "content-desc=\"$1\"" "$TMP/ui.xml"; }
# Centre of the first node whose text matches exactly.
center_of() {
  dump
  python3 - "$TMP/ui.xml" "$1" <<'EOF'
import re, sys
xml, text = open(sys.argv[1]).read(), sys.argv[2]
# Match visible text first, then content description (dock icons have no text).
for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    if m.group(1) == text or (not m.group(1) and m.group(2) == text):
        x1, y1, x2, y2 = map(int, m.groups()[2:])
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
EOF
}
top_pkg() { $ADB shell dumpsys activity activities | grep -m1 topResumedActivity | sed -E 's/.* u0 ([^/]+)\/.*/\1/'; }
screen() { read -r W H < <($ADB shell wm size | tail -1 | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/'); }
# Home/back use real navigation gestures when the device is in gesture-nav mode.
go_home() {
  if [ "$NAV" = 2 ]; then $ADB shell input swipe $((W / 2)) $((H - 3)) $((W / 2)) $((H * 6 / 10)) 120
  else $ADB shell input keyevent KEYCODE_HOME; fi
  # The gesture's recents animation can take a while on a busy device; wait for home.
  for _ in 1 2 3 4 5 6 7 8 9 10; do sleep 0.5; [ "$(top_pkg)" = "$PKG" ] && break; done
  sleep 0.7
}
go_back() {
  if [ "$NAV" = 2 ]; then $ADB shell input swipe 2 $((H / 2)) $((W * 4 / 10)) $((H / 2)) 150
  else $ADB shell input keyevent KEYCODE_BACK; fi
  sleep 1
}
swipe_up() { $ADB shell input swipe $((W / 2)) $((H * 7 / 10)) $((W / 2)) $((H * 3 / 10)) 150; sleep 1; }
swipe_down() { $ADB shell input swipe $((W / 2)) $((H * 3 / 10)) $((W / 2)) $((H * 7 / 10)) 150; sleep 1; }
# Opens the drawer and types into search. Taps the field first so text never goes astray
# while the window is still regaining focus (e.g. right after the shade closes).
# Search sits at the bottom and slides up with the keyboard, so only tap it (after the keyboard
# has settled) when it isn't already focused; tapping early could hit a keyboard key instead.
search_for() {
  swipe_up; sleep 1.2
  dump
  if ! grep -o "resource-id=\"$PKG:id/search\"[^>]*" "$TMP/ui.xml" | grep -q 'focused="true"'; then
    read -r SX SY < <(center_of_id search)
    [ -n "${SX:-}" ] && $ADB shell input tap "$SX" "$SY" && sleep 1
  fi
  $ADB shell input text "$1"; sleep 1
}
center_of_id() {
  dump
  python3 - "$TMP/ui.xml" "$PKG:id/$1" <<'PY'
import re, sys
xml, rid = open(sys.argv[1]).read(), sys.argv[2]
m = re.search(r'resource-id="%s"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(rid), xml)
if m:
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
PY
}

echo "== $SERIAL (API $($ADB shell getprop ro.build.version.sdk | tr -d '\r'), $($ADB shell getprop ro.product.manufacturer | tr -d '\r') $($ADB shell getprop ro.product.model | tr -d '\r'))$([ $SAFE = 1 ] && echo ' [SAFE mode]')"
$ADB install -r "$APK" >/dev/null || { echo "install failed"; exit 1; }
$ADB shell pm clear $PKG >/dev/null   # fresh prefs; a previous monkey run may have toggled settings
ORIG_HOME=$($ADB shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r')
ORIG_NIGHT=$($ADB shell cmd uimode night | sed -E 's/.*: //' | tr -d '\r')
restore() {
  rm -rf "$TMP"
  [ $SAFE = 1 ] || return 0
  $ADB shell cmd uimode night "$ORIG_NIGHT" >/dev/null 2>&1
  if [ -n "$ORIG_HOME" ] && [[ "$ORIG_HOME" != $PKG/* ]]; then
    $ADB shell cmd role add-role-holder android.app.role.HOME "${ORIG_HOME%%/*}" 0 >/dev/null 2>&1
    $ADB shell cmd package set-home-activity "$ORIG_HOME" >/dev/null 2>&1
  fi
  $ADB shell pm clear $PKG >/dev/null 2>&1
  $ADB shell input keyevent KEYCODE_HOME
  echo "  INFO  restored home=$ORIG_HOME night=$ORIG_NIGHT"
}
trap restore EXIT
$ADB shell cmd role add-role-holder android.app.role.HOME $PKG 0 >/dev/null 2>&1
$ADB shell cmd package set-home-activity $PKG/.HomeActivity >/dev/null 2>&1
if [ $SAFE = 0 ]; then
  $ADB shell locksettings set-disabled true >/dev/null 2>&1
  $ADB shell svc power stayon true
  $ADB shell wm dismiss-keyguard
fi
$ADB shell input keyevent KEYCODE_WAKEUP
$ADB shell am force-stop $PKG
$ADB logcat -c
screen
NAV=$($ADB shell settings get secure navigation_mode | tr -d '\r')
echo "  INFO  navigation: $([ "$NAV" = 2 ] && echo gestures || echo buttons)"
go_home; sleep 2

[ "$(top_pkg)" = "$PKG" ] && pass "launcher is home" || fail "launcher is not home ($(top_pkg))"
has_id clock && ! has_id search && pass "home screen shown" || fail "home screen not shown"

# Drawer open / back / home
swipe_up
has_id search && pass "swipe up opens drawer" || fail "swipe up did not open drawer"
go_back   # first back dismisses the keyboard
has_id search && go_back
! has_id search && pass "back closes drawer" || fail "back did not close drawer"
swipe_up; go_home
! has_id search && pass "home closes drawer" || fail "home did not close drawer"

# Search + Enter launches top result
search_for "setting"
has_text "Settings" && pass "search finds Settings" || fail "search did not find Settings"
$ADB shell input keyevent KEYCODE_ENTER; sleep 2
[ "$(top_pkg)" = "com.android.settings" ] && pass "enter launches top result" || fail "enter launched $(top_pkg)"
go_home
[ "$(top_pkg)" = "$PKG" ] && ! has_id search && pass "returning home shows home, not drawer" || fail "stale drawer after return"

# No-results state
search_for "zzqqxx"
has_id no_results && pass "no-results message" || fail "no-results message missing"
go_home

# Pin to home via long-press menu
search_for "settings"
read -r X Y < <(center_of "Settings")
if [ -n "${X:-}" ]; then
  $ADB shell input swipe "$X" "$Y" "$X" "$Y" 800; sleep 1
  read -r MX MY < <(center_of "Add to home")
  if [ -n "${MX:-}" ]; then $ADB shell input tap "$MX" "$MY"; sleep 1; fi
  go_home
  has_desc "Settings" && pass "pinned app appears in dock" || fail "pinned app missing from dock"
  read -r FX FY < <(center_of "Settings")
  [ -n "${FX:-}" ] && $ADB shell input tap "$FX" "$FY"; sleep 2
  [ "$(top_pkg)" = "com.android.settings" ] && pass "favorite launches app" || fail "favorite launched $(top_pkg)"
  go_home
else
  fail "Settings not found to pin"
fi

# Swipe down expands notification shade
swipe_down; sleep 1
$ADB shell dumpsys window | grep -q "mCurrentFocus=.*NotificationShade" && pass "swipe down opens shade" || fail "swipe down did not open shade"
go_home

# Package changes update the list live: remove Clock for this user, then restore it
TARGET=com.google.android.deskclock
if [ $SAFE = 1 ]; then
  echo "  SKIP  package change test (SAFE mode)"
elif $ADB shell pm list packages | grep -q "$TARGET"; then
  search_for "clock"
  has_text "Clock" && pass "Clock listed before removal" || fail "Clock not listed"
  $ADB shell pm uninstall -k --user 0 $TARGET >/dev/null; sleep 2
  ! has_text "Clock" && pass "uninstalled app disappears live" || fail "uninstalled app still listed"
  $ADB shell cmd package install-existing --user 0 $TARGET >/dev/null; sleep 2
  has_text "Clock" && pass "installed app appears live" || fail "installed app missing"
  go_home
else
  echo "  SKIP  package change test ($TARGET not installed)"
fi

# Dark/light switch recreates the activity; it must come back intact
$ADB shell cmd uimode night yes >/dev/null; sleep 2
has_id clock && pass "survives dark mode switch" || fail "broken after dark mode switch"
swipe_up; has_id search && pass "drawer works after mode switch" || fail "drawer broken after mode switch"
$ADB shell cmd uimode night no >/dev/null; sleep 2; go_home

# Process death: home must come straight back
$ADB shell am kill $PKG; $ADB shell am force-stop $PKG; go_home; sleep 1
[ "$(top_pkg)" = "$PKG" ] && has_id clock && pass "recovers after process death" || fail "did not recover after process death"

# Cold start time
$ADB shell am force-stop $PKG
START=$($ADB shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME | grep TotalTime | tr -dc 0-9)
echo "  INFO  cold start ${START}ms"

# Monkey stress (restricted to the launcher package)
if [ $SAFE = 1 ]; then
  echo "  SKIP  monkey (SAFE mode)"
else
  echo "  ....  monkey"
  $ADB shell monkey -p $PKG --throttle 20 --pct-syskeys 0 --pct-appswitch 0 -s 42 -v 3000 > "$TMP/monkey.txt" 2>&1
  grep -q "Monkey finished" "$TMP/monkey.txt" && pass "monkey 3000 events" || { fail "monkey aborted"; tail -20 "$TMP/monkey.txt"; }
  go_home
fi

# Crash / ANR scan
$ADB logcat -d > "$TMP/logcat.txt"
if grep -E "FATAL EXCEPTION|ANR in $PKG" -A 20 "$TMP/logcat.txt" | grep -q "$PKG"; then
  fail "crash or ANR in logcat"; grep -E "FATAL EXCEPTION|ANR in" -A 20 "$TMP/logcat.txt" | head -40
else
  pass "no crashes or ANRs"
fi
grep -E "StrictMode policy violation" -A 3 "$TMP/logcat.txt" | grep "$PKG" | sort | uniq -c | head -5

echo "== $SERIAL: $FAILS failure(s)"
exit $FAILS
