# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

GameDay TV: an Android TV / Fire TV app (Kotlin, Jetpack Compose + Compose for TV, Media3/ExoPlayer) that plays the user's own IPTV service (Xtream Codes or M3U) with a YouTube TV-style UI, ESPN live scores, game→channel matching, DVR and Multiview. It's focused on sports and live TV (there is no On Demand / VOD). Single Gradle module `:app`, package `com.gameday.tv`. There is no backend: accounts, profiles and settings live on the device.

Two apps come from the one module as product flavors (dimension `device`): `tv` (app id `com.gameday.tv`, the default) and `mobile` (phones/tablets, app id `com.gameday.tv.mobile`, label "GameDay"). `src/main` holds everything shared — data, DVR, `AppViewModel`, the player controller, and the TV screens. `src/tv` has only `MainActivity` and the leanback manifest. `src/mobile/java/com/gameday/tv/mobile/` is the touch UI (Material 3, `mobileImplementation` only), which reuses the view model, menus (`Menus.kt` dialogs render as bottom sheets), card artwork, score bugs and `StreamController`. Shared code must not reference `MainActivity` (use `getLaunchIntentForPackage`); device wording in shared messages comes from `data/Device.kt` ("TV" / "device").

The README is the user-facing changelog and feature spec (including the remote-control key tables). When behavior changes, update the "What's new" section and the key tables there.

## Commands

Requires JDK 17+ and the Android SDK (compileSdk 37, minSdk 26).

```
gradlew.bat assembleTvRelease          # → app/build/outputs/apk/tv/release/app-tv-release.apk
gradlew.bat assembleMobileRelease      # → app/build/outputs/apk/mobile/release/app-mobile-release.apk
gradlew.bat assembleTvDebug assembleMobileDebug
gradlew.bat testTvDebugUnitTest        # all JVM unit tests (they only touch shared code)
gradlew.bat testTvDebugUnitTest --tests "com.gameday.tv.data.ChannelMatcherTest"
gradlew.bat testTvDebugUnitTest --tests "com.gameday.tv.data.M3uParserTest.someTestName"
gradlew.bat lintTvDebug lintMobileDebug
adb install -r app/build/outputs/apk/tv/release/app-tv-release.apk
```

When changing shared code, compile both flavors (`compileTvDebugKotlin compileMobileDebugKotlin`). If Gradle can't find the SDK, set `ANDROID_HOME` (e.g. `%LOCALAPPDATA%\Android\Sdk`).

Release builds are minified (R8) and signed with `~/.gameday-tv/keystore.properties` (overridable with `-PgamedayKeystore=<path>`); without that file they fall back to the debug key.

Unit tests are plain JUnit 4 under `app/src/test` (no instrumented tests). `android.jar` only has stubs, so tests use real `org.json` via `testImplementation`. Logic that needs testing is deliberately kept free of Android/Compose types (e.g. `MultiviewRules`, `OkKeyGate`, `ScoreDelay`, parsers in `data/`).

### Mock IPTV server

`tools/mock-xtream/MockXtream.java` is a single-file fake Xtream panel and M3U playlist for development without a real login:

```
java tools/mock-xtream/MockXtream.java
adb reverse tcp:8085 tcp:8085
```

Credentials and endpoints are in `tools/mock-xtream/README.md`.

## Architecture

**Navigation and state.** There is one `AppViewModel` (`ui/AppViewModel.kt`, by far the largest file). It owns a hand-rolled back stack of `Screen` objects (`ui/AppModels.kt`) plus the current `Tab` (Sports, Live, Library; Sports is `Tab.FIRST`). `MainActivity` renders `vm.screen` with a `when` over `Screen` subclasses. There is no Navigation-Compose and no DI. Screens are composables that take `vm` and call its methods (`navigate`, `back`, `replace`, `selectTab`, `openGame`, …). State is held as Compose `mutableStateOf` / `mutableStateListOf` on the ViewModel. `Screen.key` is used for dedupe and for per-screen focus memory (`focusMemory` / `restoreFocusFor`), which is how Back/Up returns focus to the card the viewer left.

**Persistence.** `data/SettingsStore.kt` uses SharedPreferences in three layers: `AccountStore` (accounts, PBKDF2 password hashes), per-account settings (`prefsName(accountId)`, which holds providers), and per-profile settings (`prefsName(accountId, profileId)`, which holds teams, library, history and playback prefs). `LegacySettings` reads 1.x settings for import. Secrets (IPTV logins) are encrypted with an Android Keystore key (`data/Security.kt`). Recordings metadata is `recordings.json` in `filesDir`. JSON is parsed with `org.json` / `JsonReader` by hand: there are no serialization libraries and no reflection-kept model classes.

**Multiple providers.** An account can have several IPTV providers. `data/Providers.kt` wraps each `IptvSource` so every returned id is prefixed (`"<prefix>~<id>"`) and ids passed back in are unprefixed. The first provider keeps an empty prefix so pre-multi-provider favorites, history and recordings still match. Keep this scoping in mind whenever channel ids cross the data/UI boundary.

**Sports.** `ScoresRepository` polls ESPN public scoreboards (every 15 s while games are live). `ChannelMatcher` maps a `Game` to lineup channels by team names, national broadcasters and league packages. Golf (PGA and DP World Tour) is modeled as `Tournament`, separate from `Game`. `ScoreDelay` keeps score history so the score bug and alerts trail the scoreboard (IPTV runs 30–90 s behind) and don't spoil the stream.

**Playback.** `ui/StreamPlayer.kt` wraps ExoPlayer for IPTV. It tries candidate URLs in turn (TS, then HLS), detects stalls (gives up after 25 s and tries the alternate format), recovers from falling behind the live window, and releases or pauses when the app is backgrounded to free the provider connection. The same controller is used by the full-screen player, each Multiview tile, and the live video behind the menus (`MainShell`, rendered via a TextureView so it can fade). `PlayRequest` (Live / Catchup / Rec) is what the player shows. `DecoderBudget` holds each stream position's decoding choice (Hardware or Software, per device). Decoding never switches automatically: a hardware decoder failure shows an error telling the viewer to use fewer screens or Software.

**Remote/key handling.** `MainActivity.dispatchKeyEvent` routes through `OkKeyGate`. Long-press OK handlers must call `swallowRelease` so the key release doesn't click whatever newly opened UI gained focus. Back is sent straight to `onBackPressedDispatcher` (Compose would otherwise turn it into a focus "Exit"); a held Back first goes to `BackHold.action`, which the player sets to open its guide. `KeyActivity` (next to `OkKeyGate`) tracks recent D-pad presses to tell user focus moves apart from Android moving focus on its own.

**DVR.** `dvr/RecordingService` is a `specialUse` foreground service scheduled by exact alarms (`RecordAlarmReceiver` also re-arms on boot and package replace). `StreamRecorder` records TS and HLS. `RecordingStore` enforces the storage limit and auto-delete. Recordings are the only thing with resume points ("Continue watching", keys `rec:<id>`).

## Conventions

- Session naming: at the start of every session in this repo, rename the session (Claude Code desktop: `set_session_title` with `"self"`) to the app version it works on, from `versionName` in `app/build.gradle.kts`, as `major.minor` (e.g. "2.4"). If a session spans a version bump, use a range ("2.4 - 2.5").

- The `tv` flavor is a TV app: everything must be reachable with the D-pad, and focus behavior (where focus lands on open and on Back) is treated as a feature. Check focus restore when adding screens or rows.
- User-facing text is plain, short sentences in YouTube TV / Nuvio terms. The README is written the same way.
- Cleartext HTTP is intentionally enabled (most IPTV panels are http-only). `data/Http.kt` holds the shared OkHttp client, so the User-Agent is the same everywhere; reuse it rather than creating new clients.
