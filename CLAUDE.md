# GameDay TV

Live TV and sports app for Android TV / Fire TV, plus a phone and tablet app, built around the user's own IPTV
service (Xtream Codes or M3U). YouTube TV-style UI, ESPN live scores, game → channel matching, DVR, Multiview and
profiles. Sports and live TV only (no On Demand / VOD). No backend: accounts, profiles and settings live on the device.

## Stack
- Kotlin, Jetpack Compose + Compose for TV (`tv-material`), Material 3 (phone app only), Media3 ExoPlayer (HLS + OkHttp
  data source), OkHttp, Coil 3, coroutines. JSON is parsed by hand with `org.json` / `JsonReader`: no serialization
  libraries, no Room, no DI, no Navigation-Compose.
- JDK 17+, compileSdk 37, targetSdk 36, minSdk 26. Namespace `com.gameday.tv`. AGP 9 with built-in Kotlin.
- Single Gradle module `:app`, two product flavors (dimension `device`):
  - `tv` (default): app id `com.gameday.tv`, label "GameDay TV", leanback launcher.
  - `mobile`: app id `com.gameday.tv.mobile`, label "GameDay", Material 3 touch UI (`mobileImplementation` only).

## Commands (Windows: use `.\gradlew.bat`)
- Release APKs: `assembleTvRelease` → `app/build/outputs/apk/tv/release/app-tv-release.apk`;
  `assembleMobileRelease` → `app/build/outputs/apk/mobile/release/app-mobile-release.apk`
- Debug: `assembleTvDebug assembleMobileDebug`
- Unit tests: `testTvDebugUnitTest` (they only touch shared code); one class: `--tests "com.gameday.tv.data.ChannelMatcherTest"`
- Lint: `lintTvDebug lintMobileDebug`
- After changing shared code, compile both flavors: `compileTvDebugKotlin compileMobileDebugKotlin`.
- There's no `local.properties`: set `$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"` before Gradle.
- Install: `adb install -r <apk>` (adb is in `%LOCALAPPDATA%\Android\Sdk\platform-tools`).
- Mock IPTV server for development without a real login: `java tools/mock-xtream/MockXtream.java`, then
  `adb reverse tcp:8085 tcp:8085`. Credentials and endpoints: `tools/mock-xtream/README.md`.

## Map
- `app/src/main/java/com/gameday/tv/`: shared by both apps
  - `data/`: models, `SettingsStore` (accounts, per-account and per-profile prefs, 1.x import), `Security` (PBKDF2,
    Keystore-encrypted secrets), `IptvSource` (Xtream + M3U), `Providers` (multi-provider id scoping), `ScoresRepository`
    (ESPN), `ChannelMatcher`, `ScoreDelay`, `StreamQuality`, `Xmltv`, `Subtitles`, `Http` (shared OkHttp client),
    `Device` ("TV" / "device" wording)
  - `dvr/`: `RecordingService` (foreground service + alarms), `StreamRecorder` (TS/HLS), `RecordingStore`
  - `ui/`: `AppViewModel` (everything: navigation, session, scores, providers, guide, playback, Multiview, DVR, search),
    `AppModels` (`Screen`, `Tab`, `PlayRequest`, `AppDialog`), `StreamPlayer` (`StreamController`), `Menus` (long-press
    menus as `AppDialog`s), `ScoreBugs`, `Cards`/`ContentCards` (artwork), and all the TV screens
- `app/src/tv/`: `MainActivity` (screen routing, remote keys) and the leanback manifest (features, banner)
- `app/src/mobile/java/com/gameday/tv/mobile/`: the phone UI. `MobileActivity` (routing, landscape + picture-in-picture
  for video screens), `MobileMain` (tab bar, mini player, Sports/Live/Library), `MobilePlayer`, `MobileMultiview`,
  `MobileDetails`, `MobileSettings`, `MobileAuth`, `MobileSetup`, `MobileComponents`, `MobileTheme`
- `app/src/test/`: JUnit 4 unit tests
- `tools/mock-xtream/`: fake Xtream panel and M3U playlist

## Architecture
- **Navigation and state.** One `AppViewModel` owns a hand-rolled back stack of `Screen`s plus the current `Tab` (Sports,
  Live, Library; Sports is `Tab.FIRST`). Each activity renders `vm.screen` with a `when`. Screens take `vm` and call its
  methods (`navigate`, `back`, `replace`, `selectTab`, `openGame`, …). State is Compose `mutableStateOf` on the view
  model. `Screen.key` is used for dedupe and per-screen focus memory (`focusMemory` / `restoreFocusFor`) on TV.
- **Persistence.** SharedPreferences in three layers: `AccountStore` (accounts, password hashes), per-account
  (`prefsName(accountId)`: providers) and per-profile (`prefsName(accountId, profileId)`: teams, library, history,
  playback prefs). Recordings metadata is `recordings.json` in `filesDir`.
- **Multiple providers.** `Providers.kt` prefixes every id (`"<prefix>~<id>"`) and unprefixes ids passed back in. The
  first provider keeps an empty prefix so older favorites, history and recordings still match. Mind this whenever channel
  ids cross the data/UI boundary.
- **Sports.** `ScoresRepository` polls ESPN public scoreboards (every 15 s while games are live). `ChannelMatcher` maps a
  `Game` to channels by team names, national broadcasters and league packages; golf is a `Tournament`. `ScoreDelay`
  makes the score bug and alerts trail the scoreboard (IPTV runs 30–90 s behind) so they don't spoil the stream.
- **Playback.** `StreamController` wraps ExoPlayer: tries candidate URLs in turn (TS, then HLS), gives up on a stall after
  25 s and tries the other format, recovers from falling behind the live window, and stops when the app goes to the
  background to free the provider connection. One shared controller (`vm.mainStream`) serves the full-screen player and
  the video behind the menus (TV) / mini player (phone); each Multiview tile has its own. `DecoderBudget` holds each
  position's Hardware/Software choice; decoding never switches on its own.
- **Remote keys (TV).** `MainActivity.dispatchKeyEvent` routes through `OkKeyGate`. Long-press OK handlers must call
  `swallowRelease`. Back goes straight to `onBackPressedDispatcher`; a held Back first goes to `BackHold.action` (the
  player's guide). `KeyActivity` tells D-pad focus moves apart from Android moving focus on its own.
- **DVR.** `RecordingService` is a `specialUse` foreground service started by exact alarms (`RecordAlarmReceiver` re-arms
  on boot and package replace). Recordings are the only thing with resume points ("Continue watching", keys `rec:<id>`).

## Conventions / decisions
- Session naming: at the start of every session, rename it (`set_session_title` with `"self"`) to the version from
  `versionName` in `app/build.gradle.kts`, as `major.minor` (e.g. "2.6"). Across a bump, use a range ("2.5 - 2.6").
- The README is the user-facing changelog and feature spec (remote-key tables, phone touch-controls table). When
  behavior changes, update "What's new" and those tables. Every release bumps `versionName`/`versionCode`.
- User-facing text: plain, short sentences in YouTube TV / Nuvio terms. The README is written the same way.
- TV flavor: everything must be reachable with the D-pad, and focus behavior (where focus lands on open and on Back) is a
  feature. Check focus restore when adding screens or rows.
- Phone flavor: reuse the view model, `Menus.kt` (dialogs render as bottom sheets), artwork, score bugs and
  `StreamController` instead of duplicating logic. Text must get the light color from `MobileTheme` (Material's default
  is black outside a surface). Video screens (Player, Multiview) turn landscape, hide system bars and allow PiP.
- Shared code must never reference `MainActivity` (it's only in the tv source set); use `getLaunchIntentForPackage`.
  Device wording in shared messages comes from `Device.noun`.
- Testable logic stays free of Android/Compose types (`MultiviewRules`, `OkKeyGate`, `ScoreDelay`, parsers in `data/`).
  `android.jar` only has stubs, so tests use real `org.json` via `testImplementation`.
- Cleartext HTTP is intentional (most IPTV panels are http-only). Reuse `data/Http.kt`'s client so the User-Agent is the
  same everywhere.
- Release builds are minified (R8) and signed with `~/.gameday-tv/keystore.properties` (override with
  `-PgamedayKeystore=<path>`); without it they fall back to the debug key. Keystores and `local.properties` are gitignored.
- Use this app only with IPTV services the user is licensed for; the app hosts no content. Keep that note in the README.

## Gotchas
- Windows PowerShell 5.1: `Get-Content -Raw` / `Set-Content -Encoding utf8` read UTF-8 as ANSI and write a BOM, which
  mangled `›`, `·`, `●`, `…` in README and Kotlin files once. Use the Edit tool, or .NET `File.ReadAllText`/`WriteAllText`
  with a no-BOM `UTF8Encoding`.
- Screenshots: `adb shell screencap -p /sdcard/x.png` + `adb pull` (`exec-out >` redirection corrupts PNGs).
- Commit messages: pass multi-line messages with `git commit -F <file>`; PowerShell splits a here-string argument.

## Current status / Next steps
- **v2.6.0 (versionCode 10) is GitHub "Latest"** (2026-10-09): the phone and tablet app (PR #3). Checked on the
  owner's Pixel 10 Pro XL (wireless adb) only up to the welcome screen; sign-in, provider, playback, PiP and Multiview
  still need a hands-on pass. v2.5.1 (versionCode 9, 2026-10-04) was the previous release.
- Release flow: bump `versionName`/`versionCode`, update README "What's new", build `assembleTvRelease` and
  `assembleMobileRelease`, tag `vX.Y.0`, and attach two assets: the TV APK as `GameDayTV.apk` (Downloader code
  `3558070` fetches `releases/latest`, so keep the name) and the phone APK as `GameDay.apk` (README links to
  `releases/latest/download/GameDay.apk`). Notes end with an "Upgrading" line.
- `lintTvDebug` fails on 4 errors in `MainActivity` key handling (`RestrictedApi` on `dispatchKeyEvent`,
  `GestureBackNavigation`). The code predates 2.6; fix or baseline separately.
- The in-app logo (`ui/AuthScreens.kt` `Logo`) says "GameDay TV" on the phone too.
- Next steps: _(fill in)_
