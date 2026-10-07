# GameDay TV

A live TV and sports app for Android TV, built around **your IPTV service** and designed to work like YouTube TV. It's all about sports and live TV: Sports, Live and Library tabs, a program guide, a DVR, Multiview, profiles, and live scores for every game. Pick a game and it finds the channel in your lineup that's showing it.

**Install on Fire TV / Android TV:** open the [Downloader](https://www.aftvnews.com/downloader/) app and enter code **`3558070`**. It always fetches the [latest release](https://github.com/Yigder/gameday-tv/releases/latest).

There's also a **phone and tablet app** (GameDay) with the same features and touch controls. It's a separate app, so it installs next to the TV app instead of replacing it.

## What's new in 2.6

- **GameDay for phones and tablets.** A new app built from the same code as the TV app: Sports, Live and Library tabs at the bottom, game and team pages, the guide as a list of what's on now and next, recordings, Multiview, profiles and Settings. Sign in with the same kind of account (accounts stay on each device). The player turns to landscape, and keeps playing in picture-in-picture when you leave the app. See [On phones and tablets](#on-phones-and-tablets).

## What's new in 2.5

- **RedZone card in Live now.** While NFL games are live, Sports › Live now starts with an NFL RedZone card that plays your best RedZone channel.
- **NFL RedZone score bugs.** On a RedZone channel, every live NFL game gets a mini score bug in a thin strip across the top (up to 8 a row, so the picture stays clear). ▲ shows or hides them, like a single game's bug. A game's bug lights up when its score changes. Works in Multiview too.
- **Best streams first.** A game's channels are sorted by frame rate, then resolution, within how well they match the game. Quality comes from tags in the channel name ("FHD 60FPS", "720p60", "4K") and, after you've watched a channel, from what it actually played at. "Watch" starts the best one, and the quality (e.g. "1080p · 60 fps") shows on each channel.
- **Real frame rate in Video stats.** Frame rate is now counted from the frames actually shown, so it appears on IPTV channels that don't report one (most of them).

## What's new in 2.4

- **Just sports and live TV.** The On Demand tab is gone, along with everything that went with it: add-ons, TorBox, trailers, add-on subtitles, your provider's movies and shows, and saved movies and shows in the Library. Search covers games, teams, channels and what's on TV. Add-on links and the TorBox key saved on the TV are deleted.
- **No more Home tab.** The app opens on Sports, which now also has the "Connect your TV provider" banner and the ready-made **Watch in Multiview** row. Back from another tab returns to Sports.
- **Recordings player:** title at the bottom, a full-width progress bar (◀ ▶ scrub, faster when held), and a row of buttons: restart, play/pause, subtitles, audio, and More (speed, picture size, settings, video stats). The clock and "Ends at" sit in the top-right corner.
- **Live TV player:**
  - A **LIVE** button: red while you're watching live; grey when you're behind (paused, rewound, or in catch-up), and pressing it jumps back to live.
  - A **catch-up** button on channels with catch-up: start the current program over, or replay an earlier one from the provider's archive.
  - Back / forward are YouTube TV's circular arrows with the seconds inside (10 and 30). They also work on live streams that have a rewind window.
  - Fewer buttons: LIVE, back / play / forward, catch-up, game stats and captions. Record, Multiview, favorite, video stats and settings are under **More**.
  - **Hold OK for Multiview, like TiviMate:** the channel you're watching keeps playing (no reconnect) and the picker opens for a second screen. The Multiview button under More still opens the Multiview builder.
  - **Hold Back for the guide:** channels with what's on now, and Live now / Coming up game cards, over the video, which keeps playing. Each has one filter (a channel group, or a sport). OK on a channel or live game switches to it; hold OK on a game to add it to Multiview next to what you're watching.
- **Video decoding is Hardware or Software only.** The Automatic setting (which switched streams to software on its own) is gone, along with the learned decoder limit; Automatic reads as Hardware.
- **Multiview screens keep playing** when you add a screen, change the layout or come back from full screen, instead of every screen reconnecting. The channel name no longer covers the bottom of each screen (a screen's menu shows it). ▲ shows every screen's score bug, and Back goes back to one screen (the one you're listening to) full screen.
  - **Score delay slider** in the live player's settings (and Settings › Sports): ◀ ▶ moves it 5 seconds at a time, from off to 3 minutes, so the score bug can match the stream you're watching.
- **Watch the live preview full screen:** on Sports and Live, move up onto the video playing at the top and press OK. Up from the video goes to the tab you're on.
- **Video stats** in every player: resolution, frame rate, video and audio codecs, decoder, bitrates, connection speed, buffer and dropped frames. It's under More in the live and recordings players, and in each Multiview screen's menu.
- **Back to the top:** on Sports and Live, Back from further down the page goes back to the top first.
- **Smoother scrolling:** rows glide up as well as down.
- **Sports header:** with no game highlighted, it shows the channel playing and what's on (from the guide).
- **Golf:** a tournament is only in "Live now" while a round is being played (not overnight or once the day's play is complete), and golf channels only show its score bug then. Tournament cards show the tour's logo.
- **Fixes:**
  - Back closes menus, player panels and pages with one press (it used to take two).
  - Up / Down in a long-press menu stays in the menu instead of jumping to the games and shows behind it, and closing a menu returns to the card it was opened from.
  - Going back to a row lands on the card you left there, even after the row scrolled off screen (it used to pick whichever card lined up).

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
  - Start over (catch-up), with Record and Multiview under More.
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
- **NFL RedZone** gets a mini score bug for every live NFL game, in a thin strip across the top (up to 8 a row). ▲ shows or hides them like a single game's bug, and a game's bug lights up when its score changes. Works in Multiview too.
- **Best streams first.** A game's channels are sorted by frame rate, then resolution (from tags like "FHD 60FPS", or what the channel actually played at earlier), within how well they match the game. The quality shows on each channel.- **Hide scores** keeps cards, pages, alerts and the score bug spoiler-free.
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
