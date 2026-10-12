# GameDay TV

A live TV and sports app for Android TV, built around **your IPTV service** and designed to work like YouTube TV. It's all about sports and live TV: Sports, Live and Library tabs, a program guide, a DVR, Multiview, profiles, and live scores for every game. Pick a game and it finds the channel in your lineup that's showing it.

**Install on Fire TV / Android TV:** open the [Downloader](https://www.aftvnews.com/downloader/) app and enter code **`3558070`**. It always fetches the [latest release](https://github.com/Yigder/gameday-tv/releases/latest).

**Install on a phone or tablet:** download [GameDay.apk](https://github.com/Yigder/gameday-tv/releases/latest/download/GameDay.apk) on the device and open it (allow installs from your browser when Android asks). The phone app has the same features with touch controls. It's a separate app, so it installs next to the TV app instead of replacing it.

## What's new in 1.0.1

- **New app icon:** a TV with a ball on screen and a red live dot. The TV home screen banner matches it.
- **The phone app is called GameDay TV too**, on your home screen and inside the app.
- **Cleaner logo:** the top of the app shows just the "GameDay TV" name, without the small red "GD" tile.
- **Fix:** on phones, the selected tab in the bottom bar shows its icon again (it was white on white).

## What's new in 1.0

GameDay starts over at 1.0. This version has everything from the earlier releases (the last one was 2.6), plus updates from inside the app:

- **Update from inside the app.** When a new version is out, a banner at the top of Sports says so. Choose **Update** to download it and install it over this one (Android asks once to allow GameDay TV to install apps, then asks you to confirm). Your account, profiles, library and recordings stay. **Not now** hides the banner until the next version. Settings › About › **Check for updates** checks right away. The app only asks GitHub for the latest release; nothing about you is sent.
- **Two apps:** GameDay TV for Android TV and Fire TV, and GameDay for phones and tablets. They have the same features; the phone app uses touch controls, turns the player to landscape and keeps playing in picture-in-picture.
- **Sports, Live and Library tabs**, like YouTube TV, with live video playing behind the menus.
- **Live scores** for every game, matched to the channel in your lineup that's showing it, with the best stream first.
- **Score bugs** that trail the scoreboard so they don't spoil the stream, including mini bugs for every game on NFL RedZone.
- **Program guide** with catch-up, **DVR**, **Multiview** (up to 4 screens), **profiles** and search.

**Upgrading from 2.6?** Install 1.0 over it the usual way (Downloader code or the phone link). Everything you've saved stays. After this, the app tells you about new versions itself.

## Features

- **Live scores** for NFL, NCAAF, MLB, NBA, NHL, WNBA, NCAAM, MLS, Premier League, Champions League, LaLiga and Golf (PGA Tour and DP World Tour), from ESPN's public scoreboards. Scores refresh every 15 seconds while games are live.
- **Game → channel matching.** Each game is matched to your channels by:
  - team names
  - its national broadcaster (ESPN, FOX, Prime Video, TNT, NFL Network…)
  - league packages (Sunday Ticket, League Pass, Center Ice…)
- **Score bug (ScoreBox style)** shows when a stream starts, hides after a few seconds, and comes back with OK or Info. It pops up when the score changes. It recognizes the game even when you tune to a channel by hand.
- **NFL RedZone** gets a mini score bug for every live NFL game, in a thin strip across the top (up to 8 a row). ▲ shows or hides them like a single game's bug, and a game's bug lights up when its score changes. Works in Multiview too.
- **Best streams first.** A game's channels are sorted by frame rate, then resolution (from tags like "FHD 60FPS", or what the channel actually played at earlier), within how well they match the game. The quality shows on each channel.
- **Hide scores** keeps cards, pages, alerts and the score bug spoiler-free.
- **Team pages** show the record, standing, schedule, results, team channels, Add to library and Record all games.
- **IPTV login** supports two kinds:
  - **Xtream Codes.** You get channels, the guide and catch-up. Pasted `get.php` links are converted automatically.
  - **M3U playlist**, with an optional XMLTV guide.
- **Playback** (Media3/ExoPlayer):
  - MPEG-TS and HLS, with automatic fallback to the other format.
  - Reconnects automatically when a stream drops.
  - Switches to software decoding when the TV runs out of hardware decoders.
  - Video decoding can be set to Hardware (the default) or Software separately for the full-screen player and each Multiview screen (Settings › Playback, the player's settings, or a Multiview screen's menu). It never switches on its own; a screen that can't get a hardware decoder says so.
  - Multiview screens ask adaptive (HLS) streams for 720p and at most 30 fps when the stream offers those versions.
  - Streaming stops when the app goes to the background, which frees your provider's connection.

## Remote controls

**Player**

| Button | Action |
|---|---|
| OK | Show the controls and the score (or retry after an error) |
| ◀ / ▶ (controls hidden) | Back 10 s / forward 30 s in recordings, catch-up and live streams with a rewind window |
| ◀ / ▶ (on the progress bar) | Scrub; hold to go faster |
| ▲ (controls hidden) | Live TV: show or hide the score bug (nothing else). Recordings: show the controls |
| ▼ (controls hidden) | Show the controls |
| CH+ / CH− | Next / previous channel |
| Info / Guide / Red / Green | Show or hide the score bug |
| Hold OK (controls hidden) | Live TV: Multiview, with this channel still playing and a second screen to fill |
| Hold Back | Live TV: the guide (channels and games) over the video, which keeps playing |
| Menu | Playback settings |
| Play/Pause, FF, Rewind | Pause / resume / seek |
| Back | Close the guide, hide the controls, then leave the player |

**Guide (hold Back in the player)**

| Button | Action |
|---|---|
| OK on a channel or live game | Watch it |
| Hold OK on a game | Add to Multiview (what you're watching keeps playing beside it) or watch it |
| OK on Channels / Sports | Switch between the channel list and the games |
| OK on the filter (top right) | Pick a channel group (favorites, recent, sports channels, your provider's groups) or a sport |
| Back | Close the filter list, then the guide |

**Multiview**

| Button | Action |
|---|---|
| ◀ ▲ ▼ ▶ | Move between screens. **The audio follows the highlighted screen** |
| OK | Screen menu: change channel, watch full screen, remove, add a screen, layout |
| ▲ / Info | Show the score bugs on all screens that have a game (▲ still moves up when there's a screen above) |
| Back | Close the menu; otherwise back to one screen, full screen, with the channel you were listening to |

Each Multiview screen is a separate stream, so 4 screens need 4 connections from your provider (a channel carried on from full screen keeps its one connection). The builder only lets you pick as many as your plan allows.

## On phones and tablets

The phone app has the TV app's features with touch controls:

| Where | Do this | What happens |
|---|---|---|
| Any card or channel | Tap | Live games and channels play; anything else opens its page |
| Any card or channel | Press and hold | Its menu: watch, record, Multiview, favorites, schedule |
| Sports | Pull down | Refresh scores |
| Player | Tap | Show or hide the controls |
| Player | Swipe left / right | Next / previous channel in the list |
| Player | Double-tap the left / right side | Back 10 s / forward 30 s (recordings and catch-up) |
| Player | Press and hold | Multiview with this channel |
| Player | Trophy button | Show or hide the score bug |
| Player | Home or swipe up | Keep watching in picture-in-picture |
| Multiview | Tap a screen | Hear that screen (or add a channel to an empty one) |
| Multiview | Press and hold a screen | Full screen, change channel, remove, video decoding |

After you leave the player, what you were watching keeps playing in a mini player above the tabs (Settings › Playback › Mini player: with sound, muted or off). Tap it to go back to full screen.

## Recordings

- Recordings are saved in the app's storage on the TV. Set a space limit and how long to keep them under **Settings › Recordings**.
- Each recording uses one of your provider's streams while it runs.
- On Android 12 and newer, allow **Alarms & reminders** for GameDay TV (there's a shortcut in **Settings › Recordings**) so scheduled recordings start on time.
- The TV must be on, or in a standby mode that keeps the network on.

## Accounts and privacy

- GameDay TV accounts are stored **only on the TV**. Passwords are kept as salted PBKDF2 hashes. There's no server and no email reset; a forgotten password means creating a new account.
- Your IPTV logins are encrypted with a key from the Android Keystore and sent only to your provider.
- GameDay TV doesn't host or provide content.
- Deleting an account removes its profiles, library, history and recordings from the TV.
- Use this app only with an IPTV service you're legally licensed to use.

## Building

Requirements: JDK 17+ and the Android SDK (compileSdk 37). There are two apps (product flavors) from one codebase: `tv` (Android TV / Fire TV, app id `com.gameday.tv`) and `mobile` (phones and tablets, app id `com.gameday.tv.mobile`). Build with:

```
gradlew.bat assembleTvRelease
gradlew.bat assembleMobileRelease
```

Output: `app/build/outputs/apk/tv/release/app-tv-release.apk` and `app/build/outputs/apk/mobile/release/app-mobile-release.apk`. Release builds are signed with the key from `~/.gameday-tv/keystore.properties` when it exists, otherwise with the debug key.

To run the unit tests:

```
gradlew.bat testTvDebugUnitTest
```

They cover:
- channel and golf matching
- stream quality tags (resolution, frame rate), best-stream sorting and RedZone detection
- M3U parsing
- the Xtream guide
- XMLTV times
- HLS playlists for the recorder
- password hashing
- multiview rules
- the OK-key gate
- caption languages and style
- the score delay
- multiple providers (ids, storage, combined lineups)
- update checks (version order, picking each app's APK)

### Testing without an IPTV login

`tools/mock-xtream` is a small fake Xtream Codes server. It has a few channels and a program guide with catch-up, all pointing at public sample videos. See [tools/mock-xtream/README.md](tools/mock-xtream/README.md).

## Installing with ADB

```
adb connect <tv-ip-address>
adb install -r app/build/outputs/apk/tv/release/app-tv-release.apk
```

On a phone (USB debugging on): `adb install -r app/build/outputs/apk/mobile/release/app-mobile-release.apk`.

## Project layout

```
app/src/tv/java/com/gameday/tv/
  MainActivity.kt              TV entry point, screen routing, dialogs, toasts, remote keys
app/src/mobile/java/com/gameday/tv/mobile/
  MobileActivity.kt            phone entry point, screen routing, landscape + picture-in-picture for video
  MobileMain.kt                tab bar, mini player, Sports / Live / Library
  MobilePlayer.kt, MobileMultiview.kt, MobileDetails.kt, MobileSettings.kt
  MobileAuth.kt, MobileSetup.kt, MobileComponents.kt, MobileTheme.kt
app/src/main/java/com/gameday/tv/   shared by both apps (the TV screens live here too)
  data/
    Models.kt                  channels, guide, games, accounts, recordings
    SettingsStore.kt           accounts (PBKDF2), per-account and per-profile settings, 1.x import
    Security.kt                password hashing, Keystore-encrypted secrets
    IptvSource.kt              Xtream Codes (live, guide, catch-up) + M3U
    Providers.kt               several providers per account, provider-scoped ids
    ScoreDelay.kt              delayed score history for the score bug and alerts
    Subtitles.kt               caption languages and style
    Xmltv.kt                   streaming XMLTV guide parser
    ScoresRepository.kt        ESPN scoreboards, team schedules, game summaries (stats)
    ChannelMatcher.kt          game ↔ channel matching and search
    Updates.kt                 in-app updates from GitHub releases
  dvr/
    RecordingStore.kt          recordings list + storage limits
    StreamRecorder.kt          TS and HLS stream recorder
    RecordingService.kt        foreground recording service, alarm scheduling
  ui/
    AppViewModel.kt            session, profiles, scores, provider, guide, playback, DVR, search
    MainShell.kt               top bar, tabs, live video behind the menus
    SportsTab.kt, LiveTab.kt, LibraryTab.kt, SearchScreen.kt, SettingsScreen.kt
    DisplayModes.kt            smooth motion (fastest refresh rate)
    AuthScreens.kt, OnboardingScreens.kt, ProfileScreens.kt
    SportsDetailScreens.kt     game, golf tournament and team pages
    ChannelScreen.kt           channel page: what's on and the schedule
    PlayerScreen.kt            player: YouTube TV-style for live TV, a seekable player for recordings; side panels
    SubtitleUi.kt              caption drawing, style options and preview
    MultiviewScreen.kt, MultiviewBuilderScreen.kt
    Cards.kt, ContentCards.kt, Components.kt, Menus.kt, Icons.kt, ScoreBugs.kt
    StreamPlayer.kt, DecoderBudget.kt, OkKeyGate.kt
tools/mock-xtream/             fake Xtream server for development
```
