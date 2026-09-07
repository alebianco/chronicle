---
id: cu-183
title: Retire the deprecated LocalBroadcastManager and onBackPressed calls
status: In Review
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - maintainability
milestone: m-2
dependencies: []
priority: medium
---

## Description

Two deprecated-API tails the compiler flags on **every build**, so they are pure noise until fixed —
and warning noise is how a real warning gets missed. Found by the 2026-09-06 migration audit; neither
was tracked.

### `LocalBroadcastManager` — 5 files

Deprecated by AndroidX; the recommendation is `LiveData`/flows for in-process events, and this
project already standardised on `StateFlow` (cu-52). Sites: `CurrentlyPlayingFragment`,
`CurrentlyPlayingViewModel`, `AudiobookMediaSessionCallback`, `MediaPlayerService`,
`ActivityComponent`.

**Read cu-21 before touching this.** `ACTION_SLEEP_TIMER_CHANGE` is bidirectional over this
transport — commands travel in and ticks travel out on the *same* action — and the service must not
answer its own broadcast. That constraint has to survive the migration; it is the kind of thing a
straight mechanical replacement breaks silently.

### `Activity.onBackPressed()` — 3 call sites

`CollectionDetailsFragment` (x2) and `AudiobookDetailsFragment` call the deprecated
`requireActivity().onBackPressed()`. **`MainActivity` already uses `onBackPressedDispatcher`**, so
these three are inconsistent with the rest of the app rather than merely dated.

## Acceptance Criteria

- [x] `LocalBroadcastManager` gone from `app/src/main`, replaced with the project's flow convention
- [x] The cu-21 bidirectional sleep-timer constraint preserved, with a test that pins it
- [x] The three `onBackPressed()` calls routed through `onBackPressedDispatcher`
- [x] Both deprecation warnings gone from a clean build
- [ ] Back navigation verified **on the device** from collection details and book details — this is
      a navigation change, so it needs a human look
- [x] `./verify.sh` green

## Notes

Closing status is **In Review**, not Done: back navigation is user-visible behaviour and the last
criterion is an on-device check.

## What had already happened

**The `onBackPressed()` half was done before this task started, by the Compose migration.**
`CollectionDetailsFragment` and `AudiobookDetailsFragment` no longer exist — no `*Fragment*.kt`
remains anywhere — and their back handling went with them. Back from book details and collection
details is now Navigation Compose's `onNavigateUp`, wired at the nav host and passed into
`DetailsDestination` / `CollectionDetailsDestination`; the login flow's conditional back is a
`BackHandler` in `ChooseUserDestination`, and `MainActivity` still owns
`onBackPressedDispatcher.addCallback`. So the criterion is met, and nothing in this change
touched it.

That leaves the `LocalBroadcastManager` half, which was the real work.

## Two channels, not one

The 5 files carried **two unrelated conversations**, and separating them is most of the value:

| channel | direction | risk |
|---|---|---|
| `ACTION_SLEEP_TIMER_CHANGE` | UI ↔ service, **bidirectional on one action** | the recorded feedback-loop bug |
| `ACTION_PLAYBACK_ERROR` | service → UI, one-way | none; a transport swap |

## The bidirectional constraint is now structural, not guarded

The task said a straight mechanical replacement would break the constraint silently, and it was
right — so the design does not reproduce it. `SleepTimerBus` splits the single action into **two
one-way `SharedFlow`s**: `commands` (UI → timer, only the service collects) and `updates`
(timer → UI, only the player screen collects).

A tick can no longer arrive as a command **because it is not on that flow at all.** The
`action != SleepTimerAction.UPDATE` filter that the service receiver needed is gone, along with the
comment explaining it, because there is nothing left to filter. `SleepTimerCommand` additionally
refuses to be constructed with `UPDATE`, so the enum value that made the old loop expressible
cannot be passed at all.

`SleepTimerLogicTest`'s existing test still pins the *damage* (a zero-length fixed timer expires
immediately), which is worth keeping independently of transport; its doc comment is updated to say
the shared action is gone rather than that the service filters `UPDATE`.

## The test found a real problem in itself

`SleepTimerBusTest` is 7 tests, and writing it surfaced a trap worth recording, because it is the
exact failure mode this project's sabotage rule exists to catch.

Both buses use `replay = 0`. A first draft subscribed with `backgroundScope.launch` and then called
`advanceUntilIdle()` before asserting. Three tests failed outright — and the two that mattered,
the "a tick never arrives as a command" pair, **passed vacuously on an empty list**. They would
have stayed green with the feedback loop fully reinstated.

Measured rather than guessed, with a throwaway probe:

```
PROBE subscribed=true
PROBE after publish, before advance: seen=[]
PROBE after advanceUntilIdle: seen=[]     <- advanceUntilIdle does NOT deliver
PROBE after yield:            seen=[SleepTimerUpdate(remainingMillis=0, isActive=true)]
```

So `advanceUntilIdle` does not resume a `backgroundScope` collector of a `SharedFlow`; `yield()`
does. The recorder now awaits `onSubscription` before anything is emitted, and tests call a
`settle()` helper instead of `advanceUntilIdle`. Both facts are written into the helper's KDoc so
the next person does not re-derive them. This is also why the buses expose `SharedFlow` rather than
`Flow`: `onSubscription` is a `SharedFlow` extension, and honestly testing a `replay = 0` channel
depends on it.

**Sabotage-verified** — reinstating the loop by having `publish()` also emit onto `_commands`:

```
7 tests completed, 1 failed
  ✗ a published update never arrives on the command flow
```

Restored in a separate call, and the suite is 7/7 again.

## Also fixed on the way

`MainActivity`'s receiver held a pure diagnosis → string-resource mapping (404/503/401, else the
raw text) that had **no test**, because it sat inside an `onReceive` needing a `Context`, an
`Intent` and an activity. Extracted as `explainPlaybackError`, now covered by
`PlaybackErrorExplanationTest` (6 tests, including that an unrecognised failure is shown verbatim
rather than flattened to a generic message, and that a blank diagnosis falls back to the unknown
string as the missing-extra case used to).

The receiver's `else -> throw NoWhenBranchMatchedException` went with it: a receiver could be handed
an intent for another action and had to re-check the one it filtered on. A typed flow cannot
deliver the wrong kind of thing.

## Retired

- `implementation(libs.localbroadcastmanager)` and its catalogue entries
- `AppModule.provideBroadcastManager` — the replacements are `@Singleton` classes with `@Inject`
  constructors, so they need no provider at all
- Six now-dead intent constants: `ACTION_SLEEP_TIMER_CHANGE`, `ARG_SLEEP_TIMER_ACTION`,
  `ARG_SLEEP_TIMER_DURATION_MILLIS`, `ARG_SLEEP_TIMER_IS_ACTIVE`, `ACTION_PLAYBACK_ERROR`,
  `PLAYBACK_ERROR_MESSAGE`
- Two `BroadcastReceiver` objects and the `DisposableEffect` register/unregister pair

The `ARG_SLEEP_TIMER_IS_ACTIVE` reasoning — that an end-of-chapter timer is active with 0
remaining, so inferring active-ness from the duration shows it as off — moved onto
`SleepTimerUpdate`, where the value now lives. It is covered by
`an end-of-chapter tick keeps isActive true with nothing remaining`.

The two ViewModel tests got *simpler*: a mocked `LocalBroadcastManager` became a real
`SleepTimerBus()`, since the replacement is a plain injectable class with no framework in it.

## What self-review caught — a bug this change introduced

Worth recording, because the first version was green on all 8 stages and still wrong.

**`replay = 0` on `updates` could make an armed end-of-chapter timer read as inactive.**
`SleepTimerLogic.tick` returns `SleepTimerEffect.None` for `EndOfChapter` on every intervening
second — it has no countdown to report — so such a timer publishes **only** when armed and when it
expires. With no replay and a now-lifecycle-scoped collector, a subscriber arriving in between gets
nothing and will get nothing until the chapter ends: player sheet re-expanded, or the activity
recreated under memory pressure, which resets the ViewModel's `_isSleepTimerActive` to `false`.
The button reads unlit and the chooser offers durations instead of a cancel, while the timer is
armed and about to pause playback.

That is the exact failure `ARG_SLEEP_TIMER_IS_ACTIVE` was introduced to prevent, reappearing
through subscription timing rather than through inference. A fixed-duration timer self-heals within
a second (its paused arm keeps publishing), which is what would have made this easy to miss on a
device.

Fixed with `replay = 1` on `_updates` only — an update *is* state, carrying the whole timer state
rather than a delta, so re-delivering the latest is idempotent. `_commands` stays at `replay = 0`,
because a command is an event and a returning screen must not re-issue one. The asymmetry is
commented at the declaration. Sabotage-verified: reverting to `replay = 0` fails two tests
(`a late collector IS given the latest timer state`, `only the latest state is replayed, not the
whole countdown`).

**Two comments asserted things that were not true.**

- `command()`'s KDoc claimed it suspends so a `CANCEL` cannot be dropped. Measured against
  coroutines 1.11.0: `emit` on a `replay = 0` flow with **no** subscriber returns immediately and
  discards. It suspends only for a slow subscriber with a full buffer. This is parity with
  `sendBroadcast` and no receiver, and is safe today because the service subscribes in `onCreate`
  before the chooser is reachable — but the note now says so, and names Auto/a widget/a shortcut as
  the entry points that would silently drop a command.
- `PlayerDestination`'s KDoc said the `DisposableEffect` was STARTED-scoped. It was keyed on
  `context`, so it was **composition**-scoped and outlived `onStop`. That claim was inherited from
  the pre-existing comment and copied forward. The new collection is genuinely STARTED — a real
  tightening that stops per-second work on a backgrounded sheet, and the change that exposed the
  replay gap above. Both halves are now stated.

**One missed reuse.** The hand-rolled `LaunchedEffect` + `repeatOnLifecycle` block was
`EventEffect`'s body minus the `Event` unwrap. Extracted as `CollectEffect` beside it in
`util/compose/EventEffects.kt`, which is the argument `FlowCollect.kt`'s own KDoc makes for why
these are helpers "rather than 121 hand-written blocks" — the plausible wrong forms are subtly
broken rather than obviously so.

Two stale comments left by the deletion also corrected: `SleepTimer`'s `UPDATE ->` branch no longer
claims "the service filters it" (nothing does; `SleepTimerCommand` cannot carry it, so the branch
is now unreachable from production and kept only for exhaustiveness), and `broadcastUpdate`'s
unused-parameter note no longer overstates its option value — `publish` passes `UPDATE` at the only
call site, so removing it is a change to `SleepTimerBroadcaster`, not to the override.

Reviewed and **not** changed: `extraBufferCapacity = 1` + `DROP_OLDEST` on `updates` is right —
`DROP_OLDEST` keeps the *newest*, so an expiry survives a burst, and expiry publishes regardless.

## Verification

`./verify.sh` green, 8 stages. 1669 tests pass. Coverage **rose 53.31% → 53.48% (+0.17%)**, with
`features/player` 40.68% → 42.09% and `application` 19.87% → 20.37% — the extraction is why.

Deprecation warnings: a clean `compileDebugKotlin --rerun-tasks` now emits **none**. No
`LocalBroadcastManager` reference remains in `app/src`; the only mentions are prose in the new
files explaining what they replaced.

## Still open — why this is In Review

**The on-device back-navigation and sleep-timer check has not been done.** The only device
currently on adb is `HVA067JE`, which is the owner's phone and must not be used; the tablet at
`192.168.1.95:5555` did not answer `adb connect`.

What needs a human look when a device is available, given this change moved the sleep timer's
transport:

1. Set a fixed sleep timer, confirm the countdown ticks in the player and the button lights.
2. Set an **end-of-chapter** timer — confirm it reads as active with no countdown, and that the
   chooser offers a cancel rather than durations. This is the case the old loop corrupted.
3. Confirm the timer does not fire early, and specifically not a second after being set.
4. Cancel a timer twice in a row; the second must not be swallowed.
5. Background the player mid-countdown and return; the readout must resume without a burst of
   stale ticks.
6. Back navigation from book details and collection details (the original criterion).
7. **Arm an end-of-chapter timer, then force-stop and relaunch the app** (or trigger an activity
   recreation) and re-open the player. The button must still read active and the chooser must offer
   a cancel. This is the case the `replay = 0` bug broke, and it is not covered by items 2 or 5 —
   item 5 backgrounds mid-*countdown*, which self-heals.
