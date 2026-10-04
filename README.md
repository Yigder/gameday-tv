# GameDay TV

A live TV and sports app for Android TV, built around **your IPTV service** and designed to work like YouTube TV. It has Home, Sports, Live and Library tabs, a program guide, a DVR, Multiview, movies and shows, profiles, and live scores for every game. Pick a game and it finds the channel in your lineup that's showing it.

**Install on Fire TV / Android TV:** open the [Downloader](https://www.aftvnews.com/downloader/) app and enter code **`3558070`**. It always fetches the [latest release](https://github.com/Yigder/gameday-tv/releases/latest).

## What's new in 2.3

- **Subtitles from subtitle add-ons** (OpenSubtitles, SubMaker and other Stremio-compatible ones) for On Demand movies and shows, plus subtitles that sources list themselves. Pick one in the player's settings (Menu, or the gear): your language is listed first, and it's picked automatically when subtitles are on. Settings › Add-ons has a one-tap "Add OpenSubtitles" when no subtitle add-on is installed.
- **Subtitle style:** text size, color, edge (outline / drop shadow), background box, font, bold and position, with a live preview. It applies to every kind of subtitle, including live TV captions. Set it in Settings › Playback or from the player. Add-on subtitles can also be shifted earlier or later (Timing).
- **On Demand search:** its own search screen (the Search chip on On Demand, or the search icon while on that tab). Suggestions and results update as you type, closest titles first, and popular titles show before you type.
- **A Search (Enter) key** on both search keyboards jumps straight to the results.
- **Player button size:** Small, Medium (the new default, smaller than before) or Large, in Settings › Playback or the player's settings. Applies to live TV, movies and shows.

## What's new in 2.2

- **On Demand tab** for movies and shows from **Stremio-compatible add-ons** (the same ones Nuvio uses: Cinemeta for catalogs, Comet, Torrentio, MediaFusion, AIOStreams… for sources).
  - Catalog rows, "See all" with genres, title pages with seasons and episodes, a source picker (best quality and ready-to-play first), resume, autoplay of the next episode, and "Your list".
  - Add an add-on by pasting its link, or **from your phone**: the add-on screen shows a QR code for a page on your network where you paste it.
  - Add-on links can hold your settings (like a debrid key), so they're stored encrypted.
- **TorBox**, like Nuvio: add your API key (Settings › Add-ons & TorBox) and torrent sources play from TorBox's servers. Sources TorBox already has are marked ⚡ and sorted first.
- **More than one IPTV provider.** Add several Xtream logins or M3U playlists; their channels, guides, catch-up, movies and shows are combined. Each can be edited, turned off or removed.
- **Home works like YouTube TV's:** Top picks, Continue watching, On now, Live sports, several ready-made Multiviews (your teams, top games, close games, each league, sports networks, your channels), Recordings, add-on rows, and your provider's movies and shows. Team schedules and finals stay in Sports.
- **Live TV behind the menus.** What you were watching keeps playing at the top of Home, Sports and Live, and resting on a live channel or game previews it. Going back into the player is instant. Choose with sound, muted or off (Settings › Playback).
- **Score delay.** The score bug and score alerts trail the live scoreboard by 1 minute (adjustable) so they match the stream instead of spoiling it.
- **Player:** Up shows or hides only the score bug. The bug moves below the clock while the controls are up.
- **Sports:** PGA Tour and DP World Tour are one "Golf" sport; tournaments sit in the Live / Upcoming / Final rows instead of their own row; more room at the top.
- **Smoother:** the header only redraws itself on focus moves, cards no longer change size when focused, and the menus use the TV's fastest refresh rate (up to 120 Hz) when it has one.
- **Fixes:**
  - Choosing a Live guide category no longer jumps back to Home.
  - Up/Left return to where you were (the card you left in a row, the open Settings section or Library shelf, the chosen filter) instead of whatever lines up.
  - Holding OK on a card opens its menu and keeps it open, instead of starting playback.

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

- **Live scores** for NFL, NCAAF, MLB, NBA, NHL, WNBA, NCAAM, MLS, Premier League, Champions League, LaLiga and Golf (PGA Tour and DP World Tour), from ESPN's public scoreboards. Scores refresh every 15 seconds while games are live.
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
  - Video decoding can be set to Automatic, Hardware or Software separately for the full-screen player and each Multiview screen (Settings › Playback, the player's settings, or a Multiview screen's menu).
  - Streaming stops when the app goes to the background, which frees your provider's connection.

## Remote controls

**Player**

| Button | Action |
|---|---|
| OK | Show the controls and the score (or retry after an error) |
| ◀ / ▶ (controls hidden) | Back 10 s / forward 30 s in movies and recordings |
| ▲ (controls hidden) | Show or hide the score bug (nothing else) |
| ▼ (controls hidden) | Show the controls |
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
- Your IPTV logins, add-on links and TorBox key are encrypted with a key from the Android Keystore. Each is sent only to the service it belongs to.
- "Send from your phone" runs a small web page on your home network only while that screen is open, at a random address, and takes one entry.
- GameDay TV doesn't host or provide content. Add-ons are made by others; only stream content you have the rights to watch.
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
- add-on manifests, catalogs, details and sources
- subtitles (SubRip, WebVTT, SSA/ASS, encodings, languages, timing) and search ranking
- TorBox responses and picking the right file from a season pack
- the score delay
- multiple providers (ids, storage, combined lineups)

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
    Providers.kt               several providers per account, provider-scoped ids
    Addons.kt                  Stremio add-on protocol: manifests, catalogs, details, sources
    TorBox.kt                  TorBox: cache check, add torrent, pick file, stream link
    ScoreDelay.kt              delayed score history for the score bug and alerts
    Subtitles.kt               subtitle add-ons, subtitle file parsing, languages, style
    Xmltv.kt                   streaming XMLTV guide parser
    ScoresRepository.kt        ESPN scoreboards, team schedules, game summaries (stats)
    ChannelMatcher.kt          game ↔ channel matching and search
  dvr/
    RecordingStore.kt          recordings list + storage limits
    StreamRecorder.kt          TS and HLS stream recorder
    RecordingService.kt        foreground recording service, alarm scheduling
  ui/
    AppViewModel.kt            session, profiles, scores, provider, guide, VOD, playback, DVR, search
    MainShell.kt               top bar, tabs, live video behind the menus
    HomeTab.kt, SportsTab.kt, LiveTab.kt, LibraryTab.kt, SearchScreen.kt, SettingsScreen.kt
    OnDemandScreens.kt         On Demand tab, title pages, source picker, add-on and TorBox setup
    AddonsModel.kt             add-on and TorBox state for an account
    PhoneInput.kt              "send from your phone" page and QR code
    DisplayModes.kt            smooth motion (fastest refresh rate)
    AuthScreens.kt, OnboardingScreens.kt, ProfileScreens.kt
    SportsDetailScreens.kt     game, golf tournament and team pages
    MediaDetailScreens.kt      channel, movie, show and browse pages
    PlayerScreen.kt            YouTube TV-style player, stats and settings panels
    SubtitleUi.kt              subtitle drawing, style options and preview
    OnDemandSearch.kt          On Demand search with suggestions as you type
    MultiviewScreen.kt, MultiviewBuilderScreen.kt
    Cards.kt, ContentCards.kt, Components.kt, Menus.kt, Icons.kt, ScoreBugs.kt
    StreamPlayer.kt, DecoderBudget.kt, OkKeyGate.kt
tools/mock-xtream/             fake Xtream server for development
```
