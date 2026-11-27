# Change Reader – Android folder change logger

Watches a folder you choose (and every sub-folder inside it) and logs each file or folder that
gets **created, modified, saved, deleted or moved**. It keeps running in the background while you
use other apps. Each entry also records **which app was on screen** at that moment.

## Build and install
1. Open the `ChangeReaderApp` folder in **Android Studio** (Koala or newer). If Android Studio asks
   to create or upgrade the Gradle wrapper, accept.
2. Plug in your phone with USB debugging on, then press **Run ▶**.
   Or use **Build → Build APK(s)** and install `app/build/outputs/apk/debug/app-debug.apk`.

Needs Android 10 (API 29) or newer.

## First run
1. **Grant all files access.** This opens Settings. Turn on *Allow access to manage all files*.
2. **Grant usage access** (optional). This opens Settings. Turn on *Change Reader*. With this on,
   each log line shows the app that was in the foreground.
3. Choose a folder. Use **Browse…**, **Whole storage**, or type a path such as
   `/storage/emulated/0/DCIM` or `/storage/emulated/0/WhatsApp`.
4. Tap **Start watching**. A notification stays visible while watching. You can tap **Stop** in it.
5. Use any app. New entries show up live in the list.

## What gets logged
| Event | Meaning |
|---|---|
| CREATED | A new file or folder appeared |
| MODIFIED | The file is being written (repeats on the same file are limited to one every 3 seconds) |
| SAVED | The app finished writing and closed the file |
| DELETED | The file or folder was removed |
| MOVED_IN / MOVED_OUT | Renamed, or moved into or out of a watched folder |
| ATTRIB | Permissions or timestamp changed (off by default) |

Logs are saved as one CSV file per day:
`Android/data/com.rt.changereader/files/logs/changes_YYYY-MM-DD.csv`
Columns: `timestamp, event, kind, path, size_bytes, foreground_app`.
Use **Export CSV** to share today's file.

## Limits (these come from Android itself)
- **Other apps' private storage can't be watched.** This means `/data/data/<pkg>` and, on
  Android 11+, `Android/data` and `Android/obb`. Only root access gets around this. The app
  covers shared storage: Download, DCIM, Pictures, Documents, WhatsApp media, and similar folders.
- **"Foreground app" is the app on screen, not necessarily the app that wrote the file.**
  Android does not reveal which process wrote a file. Background work (backup, media scanner,
  downloads) is labelled with whatever app was on screen at that time.
- Very large folder trees (tens of thousands of sub-folders) can hit the Linux inotify
  watch limit. Pick a narrower folder if some sub-folders don't report changes.
- Some phone makers (Xiaomi, Oppo, Vivo, Samsung) stop background services aggressively.
  To prevent this, set the app's battery mode to **Unrestricted**.
- Play Store: the *All files access* and *Usage access* permissions need a policy declaration.
  If you install the app yourself (sideload), no declaration is needed.

## Code map
- `RecursiveObserver.kt`: one `FileObserver` per sub-folder. It starts watching new folders and
  stops watching deleted ones automatically.
- `WatcherService.kt`: foreground service. Limits repeated MODIFIED events and adds the
  foreground app.
- `ForegroundAppTracker.kt`: finds the app on screen using `UsageStatsManager`.
- `ChangeLog.kt`: in-memory list for the screen plus the daily CSV writer.
- `MainActivity.kt`: folder picker, permissions, start/stop, live list, export.
