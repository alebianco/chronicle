---
id: doc-r2-review-guide
title: R2 review guide
type: reference
created_date: '2026-09-05'
---

# R2 review guide

> **Point-in-time snapshot, 2026-09-05.** Written after the adversarial review pass against branch
> `integration/r2-review`. Two things in it have since moved: **the Fetch2 mirror has shipped** (Fetch2 is now
> vendored at `libs/fetch2-mirror`), and **Compose was adopted** ([[decision-22]]), which
> this guide predates and does not cover. The gate figures below are frozen at the moment of
> writing — re-run `./verify.sh` for current numbers. The task list has also grown; see the roundup
> in the m-2 milestone rather than treating the count here as current.

**What this is.** Everything in milestone `m-2` that was waiting for you as of 2026-09-05, what it
does, and how to check it.

**State of the gate at the time of writing:** `./verify.sh` green on all 6 stages — ktlint, 1334
unit tests, 0 failures, coverage ratchet (39.77% aggregate, all 23 packages at or above their
floor), debug APK, lint, release compile.

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

## The tasks waiting on you (30 as of 2026-09-05)

Grouped by what kind of judgement each needs. Each task file's closing notes say the same thing in
more detail — this is the map, not a replacement.

### A. Product choices — defaults, thresholds, orderings

These work correctly; the question is whether the choice suits how your household listens.

| Task | The choice | How to check |
|---|---|---|
| **Continue Listening shelf** | Which books appear, how many, and that `lastViewedAt` is the right sort key | Home screen. Is the top-left book the one you'd reach for? |
| **Per-book speed** | That a book keeps its own speed **forever** once set, and the preset values offered | Set 1.5× on one book, play another — the second should be at your global default |
| **Sleep timer** | The durations offered, the end-of-chapter option, and re-arming on resume | Start a 5-min timer, let it expire, press play — it should re-arm to **5 min**, not the seconds left |
| **Search** | Fuzziness threshold (4 chars), tier weights ordering results, grouping | Search a title with a typo; search a narrator; search 3 characters (prefix-only by design) |
| **Skip silence** | Thresholds **tuned by ear** — the task's own criterion asks for recorded reasoning | Play a narrated passage with skip-silence on. Does it clip the starts of words? |
| **Narrator & series** | That a refresh seeds the index (`1 + N` requests, not one per book), and how partial coverage is worded | Browse → narrator/series facets. `unknownCount` must be honest about books not yet synced |
| **Series index** | Eight built-in `titleSort` patterns, their **order** (most specific first), and hundredths scaling so a novella sits at 1.5 | Books in a series should sort 1, 1.5, 2 — not alphabetically |
| **Rule config + tester** | The `series-index-rules.json` format, and the tester UI's layout | Settings → series index tester. Type a title, see every rule's verdict |

### B. Visual — a screen changed and only you can see it

| Task | What changed | How to check |
|---|---|---|
| **Chapter progress** | Player wording: `6h 12m` for a span, `32:10` inside a chapter, never `47:12:33/52:04:11` | Open a long book. Every duration should read like a human wrote it |
| **Bookmarks** | The add flow, note editor, and jump-back | Add a bookmark mid-chapter, add a note, leave, come back |
| **Library usability** | List view, author browse, narrator/series facets — a large surface | Library tab, switch to list view, then browse each facet |
| **Accessibility** | Six player controls have a **48dp tap area behind a 32dp icon** — no visible change, but the spacing *feels* different | Player controls. Also worth a TalkBack pass |
| **Notification** | Notification updates on chapter change | Play across a chapter boundary with the shade open |
| **Edge-to-edge** | Insets on every screen. **Portrait only, Android 15 only — landscape never checked** | Rotate every screen. This is the least-verified item here |
| **First-frame flashes** | 34 views given XML defaults. **Screenshot comparison unchecked** | Cold-start repeatedly and watch for a flash of wrong state |
| **Mini player on tablet** | **Four criteria unchecked, all visual** | Tablet, portrait and landscape, with a book playing |
| **Buffering indicator** | What buffering looks like vs paused vs loading | Play over a throttled connection |
| **Collapsing toolbar** | Scrolled content no longer draws above the toolbar | Scroll the library fast |
| **Speed popover in landscape** | Opened at peek height showing only its title bar; now expands | **Rotate to landscape**, open the speed popover |

### C. Verified by measurement — confirm it matches your experience

| Task | The claim | How to check |
|---|---|---|
| **Playback cost** | Main-thread work during playback cut; the remaining cost is layout/draw, not data | Play a **3-track, 8-chapter** book, not the easy single-track fixture, and scroll while it plays |
| **Large-library performance** | Profiling **contradicted the task's premise** — paged loading already existed, scans were already linear, indexes made no measurable difference. A criterion was retired with evidence rather than ticked | Library of 196 books should scroll and search without stutter |
| **StateFlow migration** | All LiveData gone; `postValue` banned by a build gate. Found three real bugs on the way | Everything should behave as before — this is the one to shout about if anything feels off |
| **Backend interface carve** | The ingestion seam is real but **not registered** — `sources` is empty in production | No user-visible change expected |
| **Auto browse tree** | Browse tree no longer keyed on localized strings | Android Auto browse, ideally in a non-English locale |

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

- **Android Auto seek bar** — On Android Auto the seek bar spans the whole track while the title names the current
  chapter. The most-reported Auto complaint against both major competitors, and we are well placed
  to fix it since chapters and typed offsets already exist.
- **Fetch2 mirror** — Fetch2 (downloads) is unmaintained upstream and arrives via JitPack. The decision not
  to migrate still holds; the recommendation is to mirror the artifact so an outage can't break the
  build.
- **kotlin-reflect removal** — `kotlin-reflect` ships in the APK unused, pulled in by the wrong Moshi artifact.

## Two things worth your decision, not filed

- ~~**`play-services-oss-licenses` is proprietary**~~ — **settled 2026-09-05 by decision-19.**
  Principle 7 was re-stated: the ban is on *data extraction*, not on proprietary code as such. This
  SDK sends nothing outward and renders the open-source licence list, so it now passes on its
  merits rather than sitting awkwardly. No action needed.
- **kotlin-result is barely earning its place**: 7 imports across 5 files, with `Ok`/`Err`
  constructed in exactly one, while ~6 hand-rolled sealed outcome types coexist elsewhere. Either
  adopt it more widely or scope it deliberately.

## Where we are ahead of the field

Worth knowing, because it says the expensive investments paid off:

- **Typed offset frames** — Voice#3396 is an open bug in a 3.1k-star competitor whose
  reporter is guessing "absolute vs chapter". That is exactly the bug class our value classes make
  impossible to compile.
- **Per-book speed** — open as a feature request against Audiobookshelf since January 2024.
- **Chapter-table-first resolution** — chapter extraction is fragile everywhere; Voice has three
  open issues on it.
- **Sleep timer end-of-chapter with modified speed** — upstream #101, fixed here by storing the
  chapter id rather than a computed deadline.
