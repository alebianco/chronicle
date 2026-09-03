---
id: cu-20
title: Per-book speed memory + speed/effects popover
status: Done
assignee:
  - '@claude'
created_date: '2026-07-13'
labels:
  - R2
  - comfort
milestone: m-2
dependencies: []
priority: medium
ordinal: 43000
---

## Description

Consolidated popover: speed, skip-silence, per-book override.

## Acceptance Criteria

- [x] Switching books restores each book's speed

## Implementation Notes

**Storage: a column on `Audiobook`, `BookDatabase` v9 -> v10.** `playbackSpeed REAL NOT NULL
DEFAULT 0`, where `0f` is `NO_SPEED_OVERRIDE` — a sentinel, not a speed. Chosen over a prefs map
keyed by book id: the entity already has a durable home, a map would grow unbounded and need its
own pruning, and it would force a `BACKUP_SETTING_KEYS` decision for a key whose name is not known
in advance. `Audiobook.effectiveSpeed(globalSpeed)` is the only reader, so the sentinel is
interpreted in exactly one place, and `MIN_VALID_SPEED` is pinned equal to the slider's floor by a
test — if it ever drifted below, a legitimately chosen speed would read as "no override".

**`merge` retains it in both arms.** A local-only field the network copy cannot carry gets zeroed
by a library refresh unless it is named in *both* branches — the trap `progress` already documents
(decision-16). Verified by sabotage: removing it from one arm failed exactly the two tests that
exercise that arm and left the third green, which is what proves the arms are independently
covered.

**Application: one seam, `MediaPlayerService.invalidatePlaybackParams()`.** It was already the sole
writer of `PlaybackParameters` and already ran on service start, on a player switch and on a pref
change, so resolving `book.effectiveSpeed(prefsRepo.playbackSpeed)` there covers every case with no
new call sites. The book comes from `CurrentlyPlaying` rather than the load path because the load
path publishes it *after* `player.prepare()` — reading it there would give the outgoing book.

The service collects `currentlyPlaying.book`, but mapped to `id to playbackSpeed` and
`distinctUntilChanged` first: `ProgressUpdater` republishes the book **once a second** during
playback, so collecting the book itself would call `setPlaybackParameters` at tick rate for an
unchanged value — the exact shape cu-110 was about.

**`CurrentlyPlaying.updateSpeedOverride` was added because the DB write alone was not enough.**
`ProgressUpdater` would eventually re-read the book and republish it, but its tick is gated on
`isPlaying` — so a speed changed **while paused** would not reach the player until playback
resumed. Coupling a setting's propagation to a progress tick is incidental anyway. It no-ops when
the id is not the loaded book, so a popover left open across a book change cannot write its speed
onto the wrong book (sabotage-verified).

**UI: the existing sheet became the consolidated popover** (RESEARCH_FINDINGS §3.1 rule 6 / line
120) — speed, "Just for this book", "Skip silence". Enabling the override **adopts the speed
already showing**, so the switch alone never changes how the book sounds; disabling it clears the
row. With nothing playing the switch is disabled rather than silently dropping the write.

The decision logic was extracted to `SpeedChooserState` (a pure object: what to display, whether
the switch is on/usable, where a write goes, how to snap a speed). Done partly for testability —
the coverage gate caught the Fragment as untested and this is the honest fix rather than lowering
the baseline — and partly because three interacting inputs deserve enumerable tests. `views` went
11.48% -> 15.50%.

### Live defects fixed along the way

- **The chip presets keyed on localizable text.** `android:tag="@string/playback_speed_1_0x"`, so
  a locale rendering it "1,0x" matched no branch and every preset silently became 1.0x. Tags are
  plain numbers now, parsed with `toFloatOrNull` and logged if absent. `android:value="1.0f"` was
  also present on each chip and is not a real attribute — inert, removed.
- **The listeners re-entered.** `Slider.value`, `ChipGroup.check` and `SwitchMaterial.isChecked`
  all fire their listeners on a programmatic write, so rendering state wrote it straight back, and
  via the prefs listener in a loop. One `render()`, guarded by an `isRendering` flag.
- **`Slider.setValue` throws off-grid.** Every speed this popover writes is on a step, but the
  global preference is also reachable through a settings import, which validates keys and not
  values (cu-77). `snapToStep` rounds onto the grid; a test reads `android:stepSize` from the XML
  rather than restating it, so it fails if the layout changes (sabotage-verified).

### Found but deliberately not fixed here

**The popover collapses to its title bar in landscape** — filed as **cu-142**. Found by device
verification, then reproduced on the base branch at `72e64d6`, so it is pre-existing and not from
this work; most likely from cu-58's DataBinding removal. Reordering the layout children to read
top-down was tried and does not fix it. It needs a scrolling container, which is layout work rather
than per-book-speed work. Verification for this task was therefore done in **portrait**, where the
popover renders correctly.

### Verification

`./verify.sh --format` green — **VERIFY PASSED (7 stages)**. 823 -> **845 unit tests**; coverage
32.52% -> 32.66% aggregate, with `data/model`, `features/currentlyplaying` and `views` all
ratcheting up.

On the tablet in mock Plex mode (fixture books, no credentials), portrait:

1. The Hobbit (1001) plays at `speed = 1.0 (book override = false)`.
2. Toggling "Just for this book" -> `speed = 1.0 (book override = true)` — the switch adopted the
   shown speed and did not change the sound.
3. Tapping 2.0x -> `speed = 2.0 (book override = true)`.
4. Switching to Dune (1002) after a force-stop -> `speed = 1.0 (book override = false)`; it did
   **not** inherit the override.
5. Switching back to The Hobbit -> `speed = 2.0 (book override = true)`, restored across a full
   process restart.

The speed lands 47 ms after the book publishes (`33.041` -> `33.088` in logcat), which is as early
as it can be: the book is not known before then.
