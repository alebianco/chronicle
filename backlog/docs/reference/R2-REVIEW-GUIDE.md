# R2 review guide

**What this is.** Everything in milestone `m-2` that is waiting for you, what it does, and how to
check it. Written 2026-09-05, after the adversarial review pass, against branch
`integration/r2-review` (28 commits ahead of `feature/agentic-dev`, linear, no merges).

**State of the gate:** `./verify.sh` green on all 6 stages — ktlint, **1334 unit tests, 0
failures**, coverage ratchet (39.77% aggregate, all 23 packages at or above their floor), debug APK,
lint, release compile.

**Why there is so much to review.** The rule in CLAUDE.md is *"can a machine prove this was right?"*
Bug fixes with a failing-then-passing test go straight to `Done`; anything that changed a screen or
made a product choice waits for you. That routed **30 tasks** here and **24** to `Done`. Nothing in
this list is blocked on more work — each is blocked on a judgement only you can make.

---

## How to get the app in front of you

```bash
./plex-session.sh status            # which mode is the tablet in right now
./plex-session.sh backup            # capture the real session first, once
./plex-session.sh real              # restore the real ANTARES login (196 books)
./plex-session.sh mock              # fixture pack, no credentials
```

The debug package is `io.github.mattpvaughn.chronicle.debug` — the `.debug` suffix matters in every
`adb` line, and the activity class does **not** move with it:

```bash
adb connect 192.168.1.95:5555
adb shell am start -n io.github.mattpvaughn.chronicle.debug/io.github.mattpvaughn.chronicle.application.MainActivity
```

Two screens sit behind a bottom-nav tab that `adb shell input tap` cannot reach (the nav bar sits
under the system bars), so they have debug hooks:

```bash
# ... MainActivity --ez show_browse true          # browse/facets
# ... MainActivity --el play_book <id>            # start playback without tapping
```

---

## The 30 tasks waiting on you

Grouped by what kind of judgement each needs. Each task file's closing notes say the same thing in
more detail — this is the map, not a replacement.

### A. Product choices — defaults, thresholds, orderings

These work correctly; the question is whether the choice suits how your household listens.

| Task | The choice | How to check |
|---|---|---|
| **cu-18** Continue Listening shelf | Which books appear, how many, and that `lastViewedAt` is the right sort key | Home screen. Is the top-left book the one you'd reach for? |
| **cu-20** Per-book speed | That a book keeps its own speed **forever** once set, and the preset values offered | Set 1.5× on one book, play another — the second should be at your global default |
| **cu-21** Sleep timer | The durations offered, the end-of-chapter option, and re-arming on resume | Start a 5-min timer, let it expire, press play — it should re-arm to **5 min**, not the seconds left |
| **cu-25** Search | Fuzziness threshold (4 chars), tier weights ordering results, grouping | Search a title with a typo; search a narrator; search 3 characters (prefix-only by design) |
| **cu-88** Skip silence | Thresholds **tuned by ear** — the task's own criterion asks for recorded reasoning | Play a narrated passage with skip-silence on. Does it clip the starts of words? |
| **cu-143 / cu-145** Narrator & series | That a refresh seeds the index (`1 + N` requests, not one per book), and how partial coverage is worded | Browse → narrator/series facets. `unknownCount` must be honest about books not yet synced |
| **cu-146 / cu-147 / cu-155** Series index | Eight built-in `titleSort` patterns, their **order** (most specific first), and hundredths scaling so a novella sits at 1.5 | Books in a series should sort 1, 1.5, 2 — not alphabetically |
| **cu-148 / cu-151** Rule config + tester | The `series-index-rules.json` format, and the tester UI's layout | Settings → series index tester. Type a title, see every rule's verdict |

### B. Visual — a screen changed and only you can see it

| Task | What changed | How to check |
|---|---|---|
| **cu-19** Chapter progress | Player wording: `6h 12m` for a span, `32:10` inside a chapter, never `47:12:33/52:04:11` | Open a long book. Every duration should read like a human wrote it |
| **cu-22** Bookmarks | The add flow, note editor, and jump-back | Add a bookmark mid-chapter, add a note, leave, come back |
| **cu-24** Library usability | List view, author browse, narrator/series facets — a large surface | Library tab, switch to list view, then browse each facet |
| **cu-47** Accessibility | Six player controls have a **48dp tap area behind a 32dp icon** — no visible change, but the spacing *feels* different | Player controls. Also worth a TalkBack pass |
| **cu-50** Notification | Notification updates on chapter change | Play across a chapter boundary with the shade open |
| **cu-63** Edge-to-edge | Insets on every screen. **Portrait only, Android 15 only — landscape never checked** | Rotate every screen. This is the least-verified item here |
| **cu-68** First-frame flashes | 34 views given XML defaults. **Screenshot comparison unchecked** | Cold-start repeatedly and watch for a flash of wrong state |
| **cu-74** Mini player on tablet | **Four criteria unchecked, all visual** | Tablet, portrait and landscape, with a book playing |
| **cu-95** Buffering indicator | What buffering looks like vs paused vs loading | Play over a throttled connection |
| **cu-105** Collapsing toolbar | Scrolled content no longer draws above the toolbar | Scroll the library fast |
| **cu-142** Speed popover in landscape | Opened at peek height showing only its title bar; now expands | **Rotate to landscape**, open the speed popover |

### C. Verified by measurement — confirm it matches your experience

| Task | The claim | How to check |
|---|---|---|
| **cu-104 / cu-117 / cu-140** Playback cost | Main-thread work during playback cut; the remaining cost is layout/draw, not data | Play a **3-track, 8-chapter** book, not the easy single-track fixture, and scroll while it plays |
| **cu-51** Large-library performance | Profiling **contradicted the task's premise** — paged loading already existed, scans were already linear, indexes made no measurable difference. A criterion was retired with evidence rather than ticked | Library of 196 books should scroll and search without stutter |
| **cu-52** StateFlow migration | All LiveData gone; `postValue` banned by a build gate. Found three real bugs on the way | Everything should behave as before — this is the one to shout about if anything feels off |
| **cu-33** Backend interface carve | The ingestion seam is real but **not registered** — `sources` is empty in production | No user-visible change expected |
| **cu-99** Auto browse tree | Browse tree no longer keyed on localized strings | Android Auto browse, ideally in a non-English locale |

---

## What the adversarial pass found and fixed

Four defects, each verified against a primary source and each pinned by a test that was
**sabotage-verified** — the fix was reverted to confirm the test actually fails.

1. **Authors were being filed as series.** `Mood` is not a series-only field: Audnexus writes series
   as `"Series: <name>"` but *also* writes bare author names into the same field, gated on a
   server-side preference. Plex returns moods alphabetically, so "Brandon Sanderson" arrived before
   "Series: Mistborn" and won. Verified in Audnexus's own `update_tools.py`. **This only reproduces
   on servers with that preference on**, which is why fixtures written to match our code never
   showed it. *If your library has books filed under an author's name as a series, this is why —
   worth a look after the next refresh.*
2. **Embedded cover art was parsed into heap** on every media item. An audiobook is one very large
   file with a single artwork frame, and we fetch covers from Plex anyway. This is the root cause of
   two still-open upstream OOM reports on multi-GB books.
3. **Pausing did not save your position.** The per-second progress tick is gated on `isPlaying`, so
   pausing stopped it — the saved position was whatever the previous tick caught, and Plex was never
   told you paused. A pause from the lock screen or a headset had no flush at all. Same defect that
   cost an Audiobookshelf user "an hour of listening".
4. **Two dead null-checks from the StateFlow migration** — one meant the player's loading spinner
   could never appear, the other made the library filter menu toggle to `false` instead of
   inverting.

**Investigated and deliberately not "fixed":** upstream's #67 (Android Auto disabled poisoning the
player state machine). The mechanism does not exist here. Recorded so it is not rediscovered.

**A research claim that was wrong** and would have caused harm: that python-plexapi uses a batched
`/library/metadata/{id1},{id2}` route "in four places". It does not — those are `id=` query params
to different endpoints, one of them a write. CLAUDE.md now records the negative result.

---

## Three new tasks filed from the review

- **cu-165** — On Android Auto the seek bar spans the whole track while the title names the current
  chapter. The most-reported Auto complaint against both major competitors, and we are well placed
  to fix it since chapters and typed offsets already exist.
- **cu-166** — Fetch2 (downloads) is unmaintained upstream and arrives via JitPack. The decision not
  to migrate still holds; the recommendation is to mirror the artifact so an outage can't break the
  build.
- **cu-167** — `kotlin-reflect` ships in the APK unused, pulled in by the wrong Moshi artifact.

## Two things worth your decision, not filed

- **`play-services-oss-licenses` is proprietary** (Android SDK licence, not Apache-2.0), which sits
  awkwardly against principle 7's "no proprietary SDKs". It renders the open-source licence list.
  Replaceable with a generated static list — but that's a product call.
- **kotlin-result is barely earning its place**: 7 imports across 5 files, with `Ok`/`Err`
  constructed in exactly one, while ~6 hand-rolled sealed outcome types coexist elsewhere. Either
  adopt it more widely or scope it deliberately.

## Where we are ahead of the field

Worth knowing, because it says the expensive investments paid off:

- **Typed offset frames** (cu-136) — Voice#3396 is an open bug in a 3.1k-star competitor whose
  reporter is guessing "absolute vs chapter". That is exactly the bug class our value classes make
  impossible to compile.
- **Per-book speed** — open as a feature request against Audiobookshelf since January 2024.
- **Chapter-table-first resolution** — chapter extraction is fragile everywhere; Voice has three
  open issues on it.
- **Sleep timer end-of-chapter with modified speed** — upstream #101, fixed here by storing the
  chapter id rather than a computed deadline.
