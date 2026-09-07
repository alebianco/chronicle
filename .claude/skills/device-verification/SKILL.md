---
name: device-verification
description: Use when verifying a change on a real device or emulator - adb commands, mock Plex mode, plex-session.sh, screenshots, uiautomator dumps, driving the bottom navigation, debug intent hooks, or profiling. Covers the package-suffix trap and the prefs cache clobber.
---

# Device verification

**A green test suite is not device verification.** 1301 green tests once missed "No books found"
over a full library — open every tab after rewiring fragments.

## The debug package id has a `.debug` suffix

`app/build.gradle.kts` sets `applicationIdSuffix = ".debug"`, so the debug build's package is
`io.github.mattpvaughn.chronicle.debug` — but the **activity class does not move with it**. Every
component name must be fully qualified:

```
io.github.mattpvaughn.chronicle.debug/io.github.mattpvaughn.chronicle.application.MainActivity
```

Seven examples in the docs once named the unsuffixed id and had never worked as written.

The tablet lives at `192.168.1.95:5555`.

## Mock Plex mode (cu-16)

A debug build can run against the fixture pack with **no account and no live server**:

```bash
adb shell am start -n io.github.mattpvaughn.chronicle.debug/io.github.mattpvaughn.chronicle.application.MainActivity --ez mock_plex true
```

It records the flag and restarts — it must apply before `setupNetwork()`. The machinery lives in
`app/src/debug/`, with a no-op twin in `app/src/release/`, so it is not compiled into release
builds at all.

The mock serves cover art in **both** the shapes the app asks for (cu-207) — `/photo/:/transcode`,
which the *notification* builds, and the raw `/library/metadata/{id}/thumb/{version}` path the
*screens* use. Only the first was routed for a long time, so notification artwork worked while every
cover in the UI was blank; the unrouted path fell through to an **empty 200**, a bodyless success
that reads as a served request in a log where a 404 would have been obvious. If artwork looks wrong,
check which shape is being requested before assuming the loader is at fault.

The mock also serves a generated audio tone with HTTP range support (cu-64). Audio really
flows: all three track parts are fetched and decoded. **The tone is 180 s** (8 kHz mono, 2.7 MB, a
semitone step every 30 s so a log tells you where in the file playback is) — it was 5 s, which
ended within a second of starting and **silently blocked every player-open verification for three
sessions** while being misdiagnosed as a debug-hook gap (cu-115).

Seeks are still unexercised end-to-end; the 206/range path is unit-tested only.

### Getting back to a real server

**`--ez mock_plex false` does not do it** (cu-73). `MockPlexMode.enable` seeds
`accountAuthToken`/`server`/`library` into prefs, and `determineLoginState` reports
`LOGGED_IN_FULLY` whenever all three are present — so clearing the flag leaves the app "logged in"
to a dead `127.0.0.1`, with no login screen. `MockPlexMode.disable()` would clear those prefs but
is **dead code, called from nowhere**, and `onMockPlexIntent` exits the process before anything
could call it.

`pm clear` works but **destroys the login**, which matters whenever a human is not available to
sign in again. It also drops the `mock_plex` flag itself, since that lives in `chronicle_debug.xml`.

**Use `./plex-session.sh {backup|real|mock|status}`** — it captures the real session's
`Chronicle.xml`/`ChronicleAuth.xml` to `~/.chronicle-session-backup/` (outside the repo — they hold
live tokens) and restores them with the app **stopped**. Three refusals make it safe to run
unattended: `mock` will not proceed without a real backup on disk, `real` will not restore a backup
that is itself a mock session, and `backup` will not overwrite a real backup with a mock one. All
three are sabotage-verified.

**Always `status` first** — the device holds a *stale* flag from any earlier mock session.

### The prefs cache clobber

**A `SharedPreferences` file edited while the app runs is silently reverted when the process dies.**
The framework holds the map in memory and writes it back on shutdown, so the file reads correct
immediately and wrong at the next launch. That cost a real login once: `mock_plex` was set to
`false` on disk, verified, and read back as `true` on the next cold start.

So: `force-stop` **and poll until the process is actually gone** (it returns before the kill
completes) before touching `shared_prefs/`.

## Debug intent hooks

| Hook | Effect |
|---|---|
| `--ez mock_plex true` | Enable mock mode (restarts the app) |
| `--el play_book <id>` | Start playback via `playFromMediaId`, no tap coordinates needed |
| `--ez show_browse true` | Open the browse screen (cu-24) |
| `--el download_book <id>` | Trigger a download (cu-132) |
| `--es move_sync_location <dir>` | Replay a sync-location move (cu-153) |

`move_sync_location` validates the path against `externalDeviceDirs()` by **exact** match, since
`cachedMediaDir` accepts any string and a bad one fails much later as "downloads don't work".

**A hook must `post` rather than navigate immediately** — called from `onCreate`, a `commit()`
throws `FragmentManager has not been attached to a host`.

Hooks stay worth having for what a coordinate cannot survive: a different screen size, a scrolled
list.

## Driving the bottom navigation with `adb shell input tap`

It **can** be driven, and the geometry changed in cu-206.

It is a Compose `NavigationBar` now, whose items **spread across the full width** — so the tab
centres are simply `screenWidth / visibleTabCount` apart. In landscape on the tablet (1920 wide,
three visible tabs, Collections hidden) they sit at **x≈320, 960, 1600**, at the bar's own vertical
centre rather than the screen bottom.

**`dumpsys` no longer helps.** It reports one full-screen `AndroidComposeView` instead of a view
tree, so there are no per-item bounds to read:

```bash
adb shell dumpsys activity top | grep -iE "AndroidComposeView"   # one node, whole window
adb shell wm size                                                # divide width by visible tabs
```

Take a **screenshot** for anything finer, and confirm the tap landed by comparing screens rather
than assuming.

*Historical, pre-cu-206:* `BottomNavigationView` spanned the full width while
`BottomNavigationMenuView` inside it was **centred and narrower** — on the 1200px tablet the bar was
`0–1200` while the menu was `220–979`, so a tap at x=200 or x=1000 hit the bar and did nothing.
That trap is gone, but the method it taught is not: read the geometry, do not guess it.

**Espresso genuinely does refuse** — `click()` rejects a view the system bars overlap by more than
10% (cu-54). That is a different mechanism and still stands, so instrumented tests need another
route; shell scripts do not.

## Screenshots and uiautomator dumps

`./capture-screens.sh <dir>` drives the app and screenshots the main screens. It asserts the app
was actually foregrounded, because an earlier version silently captured the launcher.

Two dump traps:

- **A zero-bounds or empty view is absent from a `uiautomator` dump entirely** — an empty
  `TextView` and an absent one look identical. **Screenshot** rather than dump when a view looks
  wrong.
- **A dump taken during playback fails** with "could not get idle state" **while leaving the
  previous file in place**, so a stale read looks like success. Pause first, `rm` the target first,
  and assert the file exists.

## Instrumented tests (cu-54)

`./verify.sh --instrumented` adds a 7th stage; `./gradlew instrumentedCheckGroupGroupDebugAndroidTest`
runs them directly. Two Gradle Managed Devices: **API 27** (the minSdk floor, which catches a new
API called without a version guard) and **API 35**, both AOSP `arm64-v8a`.

Opt-in, not in the default gate: two emulators take minutes where the unit gate takes seconds. The
suite is `LoggedInLaunchTest` — three cases against the cu-16 fixture server via `MockPlexMode`, so
no credentials and no live server. It is deliberately small; it exists to make the
Fragment/Activity/media-session layer reachable at all.

Four traps it cost to learn:

- `MockWebServer.start()` must bind `127.0.0.1` **explicitly** — an AOSP image cannot resolve
  `localhost`, and the throw lands on a background thread with an *empty* crash buffer.
- Espresso needs `hamcrest:2.2` declared for androidTest (`hamcrest-all:1.3` resolves but
  `org.hamcrest.Matchers` reaches no dex).
- `testOptions.animationsDisabled = true` is required.
- A `BottomNavigationItemView` sits under the system bars, so tab navigation is **not** covered.

**Android Auto cannot use Gradle Managed Devices** — see the `playback-and-player` skill.

## Profiling

**Profile, do not read.** Four rounds of inspection produced plausible wrong answers on cu-110;
`am profile start --sampling` named the cause at once.

Playback main-thread cost is **layout/draw at 37.7%**, not data work.

**Measure against the worst realistic input** — a single-track, 3-chapter fixture showed 1 jiffy/6 s
and looked fixed while the 3-track, 8-chapter one measured 431 jiffies/12 s.

`./measure-audio-glitches.sh` exists for audio-specific measurement.

## Mock and live modes can now be interleaved

`plex-session.sh` made mock mode stop being a one-way door. Before it existed, the two modes could
not be interleaved in one verification pass and had to be planned as separate blocks.

## RTK filters adb output (token cost, not correctness)

`adb logcat` is this project's noisiest command — 2,772 adb calls across 352 sessions, and RTK
ships **no** built-in adb handler. `.rtk/filters.toml` adds one: it drops Android framework noise
(`nativeloader`, `OpenGLRenderer`, `chatty`, `BufferQueueProducer`, …) and keeps every app line and
every `W`/`E` line, including full stack traces. Measured 13 → 4 lines on a representative sample.

Three things to know:

- **Editing `.rtk/filters.toml` invalidates its trust.** RTK pins the file by sha256, so a filter
  silently stops applying after any edit until you re-run `rtk trust -y`. A dead filter looks
  exactly like a working one — the command still succeeds, you just pay full price.
- **`./verify-rtk-filters.sh` asserts on the numbers** and fails loudly. It caught precisely that
  trust invalidation on its first run. Run it after touching the file.
- **Do not add a `[filters.gradlew]`** — RTK has a *built-in* `gradlew` handler that takes
  precedence, so a custom one is inert (verified: 45 → 44 lines, no effect).

This is purely a token optimisation. If RTK is absent the commands run unchanged, and the verifier
skips.
