# GameDay TV

An Android TV app that puts **live sports scores** next to **your IPTV service**: pick a game from the scoreboard and it finds the channels in your lineup that are showing it, then plays it full-screen with a live score bug.

## Features

- **Live scores** for NFL, NCAAF, MLB, NBA, NHL, WNBA, NCAAM, MLS, Premier League, Champions League, and LaLiga, from ESPN's public scoreboards. Scores refresh every 15 seconds while games are live and every 60 seconds otherwise.
- **Golf: PGA Tour and DP World Tour.** Tournament cards show the top of the leaderboard. Each tournament page has a full leaderboard (position with ties, total, today, thru) next to the matching channels: the event's listed broadcaster (e.g. Golf Channel, ESPN+), the tour's usual carriers (Sky Sports Golf for the DP World Tour), and any event-specific channels ("PGA TOUR LIVE: Bank of Utah"). While you watch, a leaderboard bug sits in the corner and flashes when the leader changes.
- **My Sports & Teams** (★ My Sports on Home):
  - Choose which sports you follow. Unfollowed sports disappear from Home and Multiview and aren't fetched.
  - Star favorite teams from any league (searchable, including all college teams). Starred teams get a ★ My Teams row and filter on Home and a ★ on their games. When one of your teams scores, a score alert pops up wherever you are in the app.
  - You can also star either team from a game page.
- **Multiview in 1, 2, 3, 4, or 1+3 layouts** (like TiviMate). Each screen has its own stream and score bug, and one screen plays audio. Adding a channel grows the layout one screen at a time. To pick a channel, the sidebar lists your sports. Choose a sport to see each live or upcoming game with its best streams, then that sport's channels. The sidebar also has Live now, ★ My Teams, Recent channels, your provider's categories, and search.
- **ScoreBox-style score bug.** When a stream starts, the score shows for a few seconds and then gets out of the way. Press **OK** or **Info** on the remote to bring it back. It also pops up by itself when the score changes. Prefer it always on? Change it in Account.
- **IPTV login** with Xtream Codes (server, username, password) or an M3U playlist URL. Pasted Xtream `get.php` playlist links are converted to an Xtream login automatically.
- **Game → channel matching.** Each game is matched against your channels by team names, its national broadcaster (ESPN, FOX, Prime Video, TNT, NFL Network…), and league packages (Sunday Ticket, League Pass, Center Ice…).
- **Player** (Media3/ExoPlayer) supports MPEG-TS and HLS:
  - If a stream fails or stalls, it falls back to the other format automatically.
  - Streaming stops when the app goes to the background, which frees your provider's connection slot.
- **On-screen score bug** that flashes when the score changes. It also recognizes the game when you tune to a channel by hand (e.g. "NFL 03: Steelers @ Browns", or the network that's airing it).
- **Channel browser** with categories, a "Sports only" filter, and search.
- Scores-only mode if you don't want to sign in.

## Remote controls (player)

| Button | Action |
|---|---|
| ▲ / CH+ | Next channel |
| ▼ / CH− | Previous channel |
| ◀ | Live scores panel (pick a game or tournament to jump to its channels) |
| ▶ / Menu | Channel list panel (also has a Multiview button) |
| OK | Show the score bug and channel info (or retry after an error) |
| Info / Guide / Red / Green | Show or hide the score bug |
| Hold OK | Add this channel to Multiview and open it |
| Play/Pause | Pause / resume at the live edge |
| Back | Close panel / exit player |

## Multiview

There are several ways to open it: the **⊞ Multiview** button on Home, Channels, or a game page; **holding OK** on any channel; or holding OK in the player.

| Button | Action |
|---|---|
| ◀ ▲ ▼ ▶ | Move between screens |
| OK on an empty screen | Pick a channel (sidebar: sport → game → stream) |
| OK on a screen | Give that screen the audio |
| OK again | Watch that screen full screen (▲▼ there cycles through the multiview channels; Back returns to the grid) |
| Info | Show the score bugs on all screens |
| Hold OK / Menu | Screen options: change channel, full screen, remove, **add a screen**, and **layout** (1, 2, 3, 4, 1+3) |

Layouts keep their channels when you switch: going from 4 to 2 hides screens 3–4 and keeps screens 1–2 playing.

Each screen is a separate stream from your provider, so **4 screens need 4 connections**. If your plan allows fewer, Multiview shows a warning and the extra screens fail with an "access denied" error. Small screens are capped at 720p when the stream offers multiple qualities. Many Android TV devices can decode only 2–4 video streams at once; if a screen shows "no free video decoder", use fewer screens.

## Building

Requirements: JDK 17+ and the Android SDK (compileSdk 37). You can open the folder in Android Studio, or run:

```
gradlew.bat assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. It's signed with the debug key so it can be sideloaded directly. Set up your own signing config before distributing it.

Run the unit tests (channel matching, golf matching, M3U parsing, multiview layout rules) with `gradlew.bat testDebugUnitTest`.

## Installing on an Android TV / Google TV / Fire TV

**With ADB** (enable Developer options → USB/Network debugging on the TV):

```
adb connect <tv-ip-address>
adb install -r app/build/outputs/apk/release/app-release.apk
```

**Without a computer:** upload the APK somewhere you can reach it (e.g. Google Drive), then install it with an app like *Downloader* or *Send Files to TV*. You'll need to allow "Install unknown apps" for that app.

GameDay TV appears in the TV launcher's app row.

## Notes

- Your IPTV login is stored only on the TV (app-private storage, excluded from backups). It is sent only to the IPTV server you enter.
- Some providers only accept certain User-Agents. If yours does, change it under **Account → User-Agent**.
- Channel matching depends on how your provider names channels. If a game shows no matches, use **Search channels** on the game page.
- Use this app only with an IPTV service you're legally licensed to use.

## Project layout

```
app/src/main/java/com/gameday/tv/
  MainActivity.kt            entry point + screen routing
  data/
    Models.kt                channels, games, accounts
    Http.kt                  shared OkHttp client (User-Agent, redirects)
    IptvSource.kt            Xtream Codes API + M3U parser
    ScoresRepository.kt      ESPN scoreboard client + league list
    ChannelMatcher.kt        game ↔ channel scoring, search, sports-category detection
    SettingsStore.kt         on-device settings
  ui/
    AppViewModel.kt          navigation, score polling, IPTV state, playback, multiview slots
    HomeScreen.kt            scoreboard (Live Now / league & tour filters)
    GameScreen.kt            game detail + shared "Watch Live" channel list
    TournamentScreen.kt      golf leaderboard + matched channels
    ChannelsScreen.kt        category/search channel browser
    StreamPlayer.kt          ExoPlayer wrapper: TS/HLS fallback, stall detection, lifecycle
    PlayerScreen.kt          full-screen player, side panels
    MultiviewScreen.kt       2x2 multiview grid + channel picker
    ScoreBugs.kt             game score bug and golf leaderboard bug
    LoginScreen.kt, AccountScreen.kt, Components.kt, GameCard.kt, Format.kt
```
