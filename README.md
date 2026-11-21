# Genshin Tracker

An offline-first JavaFX desktop tracker for Windows 10 and 11. It records when the Genshin Impact process is open, keeps a local ledger of earned Primogems, and tracks objectives and tasks.

## Run from source

Install a 64-bit JDK 21, set `JAVA_HOME` to its location, and open PowerShell in the project directory.

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd javafx:run
```

In IntelliJ IDEA, open `pom.xml` as a project, select JDK 21 as both Project SDK and Maven runner JRE, and run the Maven goal `javafx:run`. You can also launch `tracker.Main` as an Application configuration using this module's classpath. Java 21 LTS is the build and packaging baseline so contributors and the bundled runtime use the same Java and JavaFX generation.

The Maven wrapper downloads its pinned Maven distribution on first use. Maven also downloads the dependencies once. The app itself works offline. On Linux x64, use `./mvnw javafx:run` in a graphical session to use the offline features. Game process detection requires Windows.

## Package on Windows

Install a full Windows JDK 21. For the MSI, install WiX Toolset 3.14 and put the directory containing `candle.exe` and `light.exe` on `PATH`. From the project root run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\packaging\build-windows.ps1
```

The app image and MSI are written to `target\windows`. Install the MSI and open **Genshin Tracker** from Start. To build a portable image without WiX, add `-AppImageOnly` to the command and open `target\windows\Genshin Tracker\Genshin Tracker.exe`. Keep the whole app-image folder together. Both forms bundle the runtime and use a graphical launcher. The MSI produced by the script is unsigned.

## Existing session data

The tracker uses `%LOCALAPPDATA%\GenshinTracker\genshin_tracker.db` and writes rotating logs beside it. The non-Windows fallback is `~/.genshin-tracker`. To override storage for testing, supply the VM option `-Dtracker.dataDir=<directory>`.

At launch, the tracker looks for a legacy `genshin_tracker.db` in the launch directory and then the user's home directory. Alternatively, set the VM option `-Dtracker.legacyDb=<file>` or use **Settings → Import legacy sessions**. Migration expects `play_sessions(id, start_at, last_seen_at, end_at)` with epoch-millisecond timestamps. An external file is read in place and its sessions are copied in one transaction. Re-import skips identical intervals. Legacy rows already in the app-data database are migrated in place with IDs preserved. Interrupted sessions close at the last confirmed observation. Overlapping historical intervals are preserved in Sessions and counted once in aggregate playtime.

## Tracking and objectives

- The tracker polls `tasklist.exe` for `GenshinImpact.exe` every five seconds. Consistent observations extend the session. A time gap over 16 seconds or a backward clock change closes at the previous observation. Game exit closes at the previous confirmed timestamp. The tracker starts a new interval after a restart.
- Analytics buckets durations by local date and local hour. Repeated daylight-saving hours accrue both occurrences. Weeks begin on Monday. Average playtime per day includes days without playtime since the first tracked date. The Windows tray keeps polling when the window is closed. **Exit completely** stops monitoring.
- Each objective has its own initial baseline. New shared entries flow to eligible active objectives of that resource. Starting amounts and individual adjustments affect just that objective. Reset creates a new baseline without removing audit entries. Completing or archiving stops new accrual. Reversals and downward import revisions still correct prior allocations. Reopening resumes new accrual. A deadline is inclusive and restricts shared or automatic accrual based on the activity date.
- **Manual** accepts shared manual events. **Automatic** accepts confirmed Playtime or imported Primogem totals. **Mixed** accepts both sources when supported by the resource. You can add custom resource and unit names. Editing the resource starts a new baseline and keeps the original entries in the audit history.
- Primogems shows earned income. You can reverse a manual entry and then log the corrected amount. Objective adjustments are separate from the income ledger. To-do tasks support pending/completed filters, priority, due dates, and categories.

## HoYoLAB and CSV fallback

Automatic HoYoLAB synchronization is unavailable. HoYoLAB's Traveler's Diary announcement describes income and earnings sources for the last three months at launch. HoYoLAB's tool overview describes monthly income and source details. These are descriptions of the signed-in user feature. The sources reviewed did not establish a supported third-party developer API, authorized sign-in, daily record contract, character identifier, or current historical coverage guarantee for this integration. The app uses local files and never requests HoYoLAB credentials. [Traveler's Diary announcement](https://www.hoyolab.com/article/1221566) · [HoYoLAB tools overview](https://www.hoyolab.com/article/1680746) · [HoYoverse account security guidance](https://genshin.hoyoverse.com/en/news/detail/28606)

Use **Primogems → Import daily CSV** to load dates for which you have actual daily earned totals. Monthly sums cannot be converted into daily records by the tracker. Create a UTF-8 CSV with a `date` and `total` header plus an optional `category`. For example:

```csv
date,total,category
2026-03-01,160,
2026-03-02,240,Events
```

Provide one whole, nonnegative total per day. Category applies to the full total in that row. Leave it blank when no single category applies. Choose one local account label per tracker database. Confirm the same label whenever importing more files. Re-importing an identical day adds zero. A revised day adds or subtracts the difference. Missing dates stay as they were, and Settings and Primogems distinguish local import from automatic sync. Primogems shows the number of imported dates and the range between the earliest and latest covered dates.

If a CSV date already has manual income, review that day's entries and explicitly select **Replace manual entries on imported dates** only when the CSV covers the entire day. Replacement reverses the manual income, carries over existing objective credit up to the confirmed amount, and applies excess newly confirmed income to eligible objectives. Subsequent corrections to an imported day use a revised CSV total. Shared or imported dates before an objective's current eligibility date leave its progress unchanged. When income time is known only by date, eligibility is determined at the end of that local day.

## Architecture and tests

`tracker.ui` contains the JavaFX views and Windows tray bridge. `tracker.service` owns process monitoring, per-user storage, instance locking and interval aggregation. `tracker.db` manages SQLite repositories, a versioned schema, and the transactional resource ledger. `tracker.integration` defines the daily-income provider interface and the local CSV implementation. Maven copies runtime dependencies to `target/lib`.

Run `mvnw.cmd clean verify` for database, playtime, objective, import and task tests. The opt-in desktop smoke test requires a graphical desktop and can be run with `mvnw.cmd -Dtracker.uiSmoke=true -Dtest=DesktopSmokeTest test` or `./mvnw -Dtracker.uiSmoke=true -Dtest=DesktopSmokeTest test` on Linux.
