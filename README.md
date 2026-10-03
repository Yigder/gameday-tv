# GameDay TV

A live TV and sports app for Android TV, built around **your IPTV service** and designed to work like YouTube TV. It has Home, Sports, Live and Library tabs, a program guide, a DVR, Multiview, movies and shows, profiles, and live scores for every game. Pick a game and it finds the channel in your lineup that's showing it.

**Install on Fire TV / Android TV:** open the [Downloader](https://www.aftvnews.com/downloader/) app and enter code **`3558070`**. It always fetches the [latest release](https://github.com/Yigder/gameday-tv/releases/latest).

## What's new in 2.0

The whole app was rebuilt to look and work like YouTube TV:

- **GameDay TV accounts and profiles.** Create an account with your email and a password. Up to 6 people can each have a profile, with their own teams, library, history and settings. "Who's watching?" appears at start when there's more than one profile.
- **Home** has a header that shows details of whatever you highlight, with rows underneath:
  - Continue watching
  - Live sports
  - Ready-made Multiviews
  - Your teams
  - Your channels
  - Coming up
  - Recordings
  - New movies
  - Shows
  - Final scores
- **Sports tab:** filter by league or "Your teams", with Live, Upcoming and Final rows, plus the league's channels and teams.
- **Live tab:** a program guide grid with channels down the side and programs across time.
  - Filters: Sports, All channels, Favorites, Recently watched, and your provider's categories.
  - Scroll back to replay past shows on channels with catch-up. Scroll ahead to schedule recordings.
- **Library:** recordings, scheduled recordings, continue watching, your teams, saved movies and shows, and favorite channels.
- **DVR:**
  - Record a game, a show, or a channel for a set time.
  - Turn on "Record all games" for a team.
  - Game recordings keep going while the game runs long.
  - Recordings are saved on the TV, with a storage limit and auto-delete.
- **Movies and shows** from your provider (Xtream VOD and series). Includes resume, seasons and episodes, and autoplay of the next episode.
- **Player** works like YouTube TV:
  - Round buttons along the bottom, with a progress bar for the program that's on.
  - Start over (catch-up), Record and Multiview.
  - Stats: box score, scoring plays and leaders.
  - Captions, audio track, quality, picture size and stream format.
  - A channel or episode strip underneath.
- **Build a multiview:** tick up to 4 games or channels, the way YouTube TV does it. Also opens from any game's menu.
- **Search** with an on-screen keyboard covers games, teams, channels, what's on TV, movies and shows.
- **Settings:** account, profiles, TV provider, sports and spoilers ("Hide scores"), live guide categories, playback, recordings, and about.
- **Long-press menus** on every card offer watch, record, Multiview, favorite, and the team or channel page.

Upgrading from 1.x? Create your account, and setup offers to reuse the IPTV login and sports picks already saved on the TV.

## Features

- **Live scores** for NFL, NCAAF, MLB, NBA, NHL, WNBA, NCAAM, MLS, Premier League, Champions League, LaLiga, PGA Tour and DP World Tour, from ESPN's public scoreboards. Scores refresh every 15 seconds while games are live.
- **Game → channel matching.** Each game is matched to your channels by:
  - team names
  - its national broadcaster (ESPN, FOX, Prime Video, TNT, NFL Network…)
  - league packages (Sunday Ticket, League Pass, Center Ice…)
- **Score bug (ScoreBox style)** shows when a stream starts, hides after a few seconds, and comes back with OK or Info. It pops up when the score changes. It recognizes the game even when you tune to a channel by hand.
- **Hide scores** keeps cards, pages, alerts and the score bug spoiler-free.
- **Team pages** show the record, standing, schedule, results, team channels, Add to library and Record all games.
- **IPTV login** supports two kinds:
  - **Xtream Codes.** You get channels, the guide, catch-up, movies and shows. Pasted `get.php` links are converted automatically.
  - **M3U playlist**, with an optional XMLTV guide.
- **Playback** (Media3/ExoPlayer):
  - MPEG-TS and HLS, with automatic fallback to the other format.
  - Reconnects automatically when a stream drops.
  - Switches to software decoding when the TV runs out of hardware decoders.
  - Streaming stops when the app goes to the background, which frees your provider's connection.

## Remote controls

**Player**

| Button | Action |
|---|---|
| OK | Show the controls and the score (or retry after an error) |
| ◀ / ▶ (controls hidden) | Back 10 s / forward 30 s in movies and recordings |
| ▲ / ▼ (controls hidden) | Show the controls. Can be set to change channel in Settings › Playback |
| CH+ / CH− | Next / previous channel |
| Info / Guide / Red / Green | Show or hide the score bug |
| Hold OK | Multiview with this channel |
| Menu | Playback settings |
| Play/Pause, FF, Rewind | Pause / resume / seek |
| Back | Hide the controls, then leave the player |

**Multiview**

| Button | Action |
|---|---|
| ◀ ▲ ▼ ▶ | Move between screens. **The audio follows the highlighted screen** |
| OK | Screen menu: change channel, watch full screen, remove, add a screen, layout |
| Info | Show the score bugs on all screens |
| Back | Close the menu / leave Multiview |

Each Multiview screen is a separate stream, so 4 screens need 4 connections from your provider. The builder only lets you pick as many as your plan allows.

## Recordings

- Recordings are saved in the app's storage on the TV. Set a space limit and how long to keep them under **Settings › Recordings**.
- Each recording uses one of your provider's streams while it runs.
- On Android 12 and newer, allow **Alarms & reminders** for GameDay TV (there's a shortcut in **Settings › Recordings**) so scheduled recordings start on time.
- The TV must be on, or in a standby mode that keeps the network on.

## Accounts and privacy

- GameDay TV accounts are stored **only on the TV**. Passwords are kept as salted PBKDF2 hashes. There's no server and no email reset; a forgotten password means creating a new account.
- Your IPTV login is encrypted with a key from the Android Keystore and is sent only to your provider.
- Deleting an account removes its profiles, library, history and recordings from the TV.
- Use this app only with an IPTV service you're legally licensed to use.

## Building

Requirements: JDK 17+ and the Android SDK (compileSdk 37). Build with:

```
gradlew.bat assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. Release builds are signed with the key from `~/.gameday-tv/keystore.properties` when it exists, otherwise with the debug key.

To run the unit tests:

```
gradlew.bat testDebugUnitTest
```

They cover:
- channel and golf matching
- M3U parsing
- the Xtream guide
- XMLTV times
- HLS playlists for the recorder
- password hashing
- multiview rules
- the OK-key gate

### Testing without an IPTV login

`tools/mock-xtream` is a small fake Xtream Codes server. It has a few channels, a program guide with catch-up, movies and a show, all pointing at public sample videos. See [tools/mock-xtream/README.md](tools/mock-xtream/README.md).

## Installing with ADB

```
adb connect <tv-ip-address>
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Project layout

```
app/src/main/java/com/gameday/tv/
  MainActivity.kt              entry point, screen routing, dialogs, toasts
  data/
    Models.kt                  channels, guide, movies & shows, games, accounts, recordings
    SettingsStore.kt           accounts (PBKDF2), per-account and per-profile settings, 1.x import
    Security.kt                password hashing, Keystore-encrypted secrets
    IptvSource.kt              Xtream Codes (live, guide, catch-up, VOD, series) + M3U
    Xmltv.kt                   streaming XMLTV guide parser
    ScoresRepository.kt        ESPN scoreboards, team schedules, game summaries (stats)
    ChannelMatcher.kt          game ↔ channel matching and search
  dvr/
    RecordingStore.kt          recordings list + storage limits
    StreamRecorder.kt          TS and HLS stream recorder
    RecordingService.kt        foreground recording service, alarm scheduling
  ui/
    AppViewModel.kt            session, profiles, scores, provider, guide, VOD, playback, DVR, search
    MainShell.kt               top bar + Home / Sports / Live / Library tabs
    HomeTab.kt, SportsTab.kt, LiveTab.kt, LibraryTab.kt, SearchScreen.kt, SettingsScreen.kt
    AuthScreens.kt, OnboardingScreens.kt, ProfileScreens.kt
    SportsDetailScreens.kt     game, golf tournament and team pages
    MediaDetailScreens.kt      channel, movie, show and browse pages
    PlayerScreen.kt            YouTube TV-style player, stats and settings panels
    MultiviewScreen.kt, MultiviewBuilderScreen.kt
    Cards.kt, ContentCards.kt, Components.kt, Menus.kt, Icons.kt, ScoreBugs.kt
    StreamPlayer.kt, DecoderBudget.kt, OkKeyGate.kt
tools/mock-xtream/             fake Xtream server for development
```
