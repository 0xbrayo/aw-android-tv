# ActivityWatch Android TV

An early-version ActivityWatch client for Android TV that tracks what you watch and which apps you use, storing all data locally in an ActivityWatch-compatible format.

> **Early version notice:** This app is functional and has been tested on a physical Android TV (API 31, TCL) and the Android TV emulator (API 36), but it is not yet feature-complete. Expect rough edges and missing polish. Contributions and bug reports are welcome.

---

## Features

### Now Playing Screen
Displays the currently active media session in real time:
- Track title, artist, and album
- Album art (when provided by the app)
- Live playback progress bar with elapsed and total time
- Playing / Paused indicator
- App name badge (e.g. Spotify, YouTube)

### App Usage Tracking
Monitors the foreground app on the TV every 5 seconds(heartbeats) using the Android `UsageStatsManager` API. App switches are recorded as events in the `aw-watcher-android-tv-window` bucket. Requires the **Usage Access** permission.

### Media Session Tracking
Listens to active `MediaSession`s via the Android `NotificationListenerService` and `MediaController` APIs. Any app that publishes a media session (Spotify, YouTube, Netflix, etc.) is tracked automatically:
- Heartbeats sent while playing (every 60 seconds)
- Pause and resume events recorded
- Track metadata (title, artist, album, source app) stored in the `aw-watcher-android-tv-media` bucket

Requires the **Notification Access** permission.

### Jellyfin Integration
For users running a self-hosted Jellyfin media server, a dedicated background poller hits the Jellyfin `/Sessions` API every 10 seconds and records rich metadata:
- **Movies** — title, production year, IMDb ID, genres, community rating
- **TV episodes** — series name, season/episode number, IMDb ID, genres, rating
- **Music** — title, artist, album

Playback is only recorded while the session is active (paused sessions are skipped). Events are stored in the `aw-watcher-android-tv-media` bucket alongside other media events.

The Jellyfin server URL and API key can be configured directly from the app's main screen. An API key can be generated in the Jellyfin Dashboard under **Administration → API Keys**.

### Usage Dashboard
An in-app stats screen accessible from the main screen via the **Stats** button:
- **Top apps** — up to 15 apps ranked by total usage time for the selected period, with gold / silver / bronze highlights for the top three
- **Period selector** — animated sliding tab to switch between Today, This Week, and This Month
- **Total time** — aggregate tracked time for the selected period shown in the header

### Runs in the Background
Three foreground services keep tracking alive without requiring the app to be open:

| Service | Purpose | Bucket |
|---|---|---|
| `UsageStatsWatcherService` | Foreground app tracking | `aw-watcher-android-tv-window` |
| `WatcherForegroundService` | MediaSession tracking | `aw-watcher-android-tv-media` |
| `JellyfinPollerService` | Jellyfin playback tracking | `aw-watcher-android-tv-media` |

### Auto-start on Boot
A `BroadcastReceiver` starts all services automatically after the device boots or after the app is updated, so tracking resumes without manual intervention.

### Local Data Storage
All events are stored on-device using Room (SQLite) in an ActivityWatch-compatible bucket/event schema. No data leaves the device except for the Jellyfin API calls to your own server.

---

## Permissions

| Permission | Why it's needed |
|---|---|
| Notification Access | Required to read active `MediaSession`s from other apps |
| Usage Access | Required to query which app is in the foreground |
| Internet | Required only for Jellyfin API polling |

The app walks you through granting each permission on first launch.

---

## Building & Installing

```bash
# Clone the repo
git clone https://github.com/0xbrayo/aw-android-tv.git
cd aw-android-tv

# Build a debug APK
./gradlew assembleDebug

# Install over ADB (enable ADB in Settings → Device Preferences → Developer Options)
adb connect <tv-ip>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

Minimum SDK: **API 26 (Android 8.0)**  
Target SDK: **API 36**

> **Note:** Some OEM Android TV firmware ships SQLite without the JSON1 extension (`json_extract` unavailable). This app works around that by doing all JSON parsing in Kotlin rather than in SQL queries, so it is compatible with those devices.

---

## Known Limitations

- The Jellyfin poller identifies the active device session by matching `DeviceName` to `Build.MODEL`. If multiple sessions are active it falls back to the first one in the list.
- The dashboard only shows data captured since the app was installed; there is no import of historical data.
- No data export or sync to a remote ActivityWatch server is implemented yet.
