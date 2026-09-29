# Minimal

A native, lightweight Android home launcher for Android 13+ (API 33–36).

- **Home screen:** clock, date, your next upcoming events (3 visible, scroll for more; from every calendar on the phone), and a dock of up to 5 favourite app icons.
- **Swipe up:** app drawer, an icon grid over a translucent tint, with search at the bottom for one-handed use. Enter launches the top match.
- **Swipe down:** notification shade.
- **Long-press:** Wallpaper, Launcher settings and Android settings.
- **Calendar:** tap the date for a 90-day agenda, and tap any event for its details (repeat rule, next dates, location, Join button for Teams/Zoom/Meet, people). Optional reminders have a default time and sound per calendar (Settings → Calendar & reminders).
- **Long-press an app:** add to the dock, App info, Hide, Uninstall. Long-press a dock icon to move it left or right, or remove it.
- **Work-profile apps** are included, and the list updates live on install or uninstall.
- **Size:** release APK about 330 KB. The only dependency is AndroidX RecyclerView. No widgets, no theming engine.

## Build

```sh
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # search-ranking unit tests
```

Requires JDK 17+. Android Studio's bundled JDK works:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

Release builds are signed with the debug key so they can be sideloaded. Set up a real signing config before publishing.

## Install and set as home

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

Then open **Minimal settings** (in your app list) and tap **Default home app**.

## Test

```sh
scripts/smoke-test.sh <adb-serial> [apk]
```

Drives the main flows with real input on a device or emulator:

- gestures (it uses gesture navigation when the device is in that mode)
- search and Enter-to-launch
- pinning
- the notification shade
- live package changes
- dark-mode recreation
- process death
- a 3,000-event monkey run
- a crash/ANR scan

Physical phones run in a **safe mode**: no lock-screen changes, no package removal and no monkey. Your original home app and dark-mode setting are restored afterwards.

## Search ranking

Best first:

1. exact name
2. name prefix
3. word prefix ("maps" finds Google Maps; camelCase splits, so "tube" finds YouTube)
4. prefixes across words ("goo ma")
5. initials ("ytm" finds YouTube Music)
6. substring
7. fuzzy subsequence

Matching ignores case, accents and punctuation. Within a tier, your most-launched apps come first. See `app/src/main/java/dev/minimal/launcher/search/Search.kt`.

## Layout

```
app/src/main/java/dev/minimal/launcher/
  HomeActivity.kt     home screen + drawer (one window, no activity switch)
  SwipeLayout.kt      vertical drag + long-press detection
  AppRepository.kt    launchable apps across profiles, live updates
  IconCache.kt        off-main-thread icon rendering, LRU cache
  Search.kt           pure-Kotlin ranking (unit tested)
  Actions.kt          launching and system intents, all failure-safe
  SettingsActivity.kt the few settings there are
```

App icon: Material Icons "apps" (round), Apache License 2.0. Source is in `art/`.
