---
id: cu-56
title: Restore or delete the hollow AudiobookDetailsViewModel tests
status: Done
assignee: [claude]
created_date: '2026-08-30'
labels: [R0, agentic]
dependencies: []
priority: high
---

## Description

Found while evaluating mutation testing. `AudiobookDetailsViewModelTest` contains **5 `@Test` methods
whose bodies are entirely commented out** — not just the assertions, the whole body. They call no
production code and assert nothing, so they pass unconditionally and can never fail.

Dormant since `6ef9787` (v0.42.1).

Why this matters beyond tidiness:

- They are 5 of the suite's 19 tests. Any "19 tests passing" claim overstates real verification by ~26%.
- They execute `AudiobookDetailsViewModel` construction in `setUp`, so they contribute *coverage*
  while verifying nothing — the exact failure mode the cu-3 ratchet cannot detect, since coverage
  measures execution, not assertion.
- This is precisely the class of problem mutation testing exists to surface.

Two honest options:

1. **Restore them** — uncomment, fix against the current `AudiobookDetailsViewModel` API (they predate
   several refactors, so expect compile errors and changed collaborators), and confirm each fails when
   the behaviour under test is broken.
2. **Delete them** — if restoring is more work than writing fresh tests, delete and let cu-44 cover
   this ViewModel properly. A deleted test is honest; a hollow one is not.

Prefer (1) if the intent is still valid — the commented bodies document what was meant to be verified
(transport controls called on play, jump-to-chapter, cache-button state machine). Prefer (2) if the API
has moved too far.

Either way the outcome must be that no test in the repo passes unconditionally. Note deleting them will
*lower* the coverage baseline; that is a correct, deliberate drop — use `./coverage-ratchet.sh --update`
and say so in the commit message.

## Implementation Notes

### Decision: delete, not restore

Checked the commented-out bodies against the current `AudiobookDetailsViewModel` before deciding. The
API they targeted is gone:

- `viewModel.play()` **no longer exists** — playback entry is now `pausePlayButtonClicked()`.
- `jumpToChapter()` now takes `(offset, trackId, hasUserConfirmation)` and shows a confirmation menu
  unless confirmed.
- `showBottomSheet` became `bottomChooserState`; `CachedFileManager` became `ICachedFileManager`.
- The constructor grew from 6 collaborators to 9 (adds `PlexConfig`, `PrefsRepo`, `PlexMediaService`).
- `cacheStatus` is now a derived `DoubleLiveData` over `cachedFileManager.activeBookDownloads`, so it
  cannot be asserted the way the old code did.

"Restoring" would have meant writing entirely new tests while pretending to revive old ones. Deleted
instead, and the **intent** preserved as [[cu-59]] — the five behaviours mapped onto the current API,
dependent on cu-44 for the ViewModel test infrastructure.

### A second hollow test found

Scanning the whole test tree for `@Test` methods with no live statements turned up one more:
`TrackListStateManagerTest.updatePosition()` was **entirely empty** — no body at all, in the file I had
previously described as substantive. Worth noting it is the same test I sabotaged during cu-3 to prove
the gate bites; it failed then only because the injected assertion gave it something to fail on.

Filled it in rather than deleting, since `updatePosition` is real logic sitting right there:
- asserts index and progress are set;
- a second test asserts the bounds check rejects an out-of-range track index.

Verified both bite: replacing the bounds check with `if (false)` fails the new test.

### Result

- Test count 19 → 14, **all of which now genuinely verify something.** The drop is the point: 5 of the
  removed tests could never fail.
- Coverage 3.76% → 3.75% on deletion (a real, deliberate drop, recorded via `--update` rather than left
  to the ratchet's jitter tolerance, which would have silently absorbed it), then → **3.77%** once the
  `updatePosition` tests landed. Net rise despite deleting 5 tests.
- Whole test tree scanned; **no test in the repo passes unconditionally.**

### Follow-up

- [[cu-59]] (drafts) — cover `AudiobookDetailsViewModel`'s cache/playback behaviour properly, after cu-44.

## Acceptance Criteria

- [x] No `@Test` method in the repo has a fully commented-out body
- [x] Any retained test verified to fail when the behaviour it covers is broken
- [x] Coverage baseline adjusted deliberately if tests are removed
