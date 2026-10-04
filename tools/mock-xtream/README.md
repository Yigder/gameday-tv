# Mock Xtream server (development only)

A fake Xtream Codes panel so GameDay TV can be developed and tested without a real IPTV login.
It serves a small channel lineup with a program guide (including catch-up), movies and a show.
Streams redirect to public sample videos.

```
java tools/mock-xtream/MockXtream.java
adb reverse tcp:8085 tcp:8085
```

In the app, choose **Xtream Codes** and use:

| Field | Value |
|---|---|
| Server | `http://127.0.0.1:8085` |
| Username | `gameday` |
| Password | `mock-only-2026` |

## Second provider and add-on

- **M3U playlist** (to test several providers): `http://127.0.0.1:8085/playlist.m3u` — four channels.
- **Stremio add-on** (to test On Demand): `http://127.0.0.1:8085/addon/manifest.json`. It has a small catalog, and gives sources for its own titles and for Cinemeta's (`tt…`) titles: a plain link, a link that only plays when the add-on's request header is sent, and a torrent (to check the "needs TorBox" path). It's also a subtitle add-on: English (SubRip) and Spanish (WebVTT) test subtitles with a line every 3 seconds, for any title.

## Test app account

Used when testing account creation on a device. It's deleted afterwards (Settings › Account › Delete account).

| Field | Value |
|---|---|
| Name | `Test Viewer` |
| Email | `viewer@gameday.test` |
| Password | `gdtest7419pw` |
