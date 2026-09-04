---
id: cu-68
title: Sweep the remaining first-frame flashes from cu-58
status: Done
assignee:
  - '@claude'
created_date: '2026-08-31'
labels: [R2, ui]
dependencies: []
priority: medium
milestone: m-2
---

## Description

Found in the R0-close adversarial review. cu-58 fixed this defect class in `037d5aa`, but **only for
`fragment_home` and `fragment_library`** — the four parallel-agent batches and the final commit were
never revisited.

DataBinding evaluated every expression once at initial bind; ViewBinding does not. A view whose
visibility is now driven only from Kotlin holds its XML default until the first LiveData emission, so
it flashes over the content for a frame or longer.

Still affected (~13 views), worst first:

- **`onboarding_plex_choose_library.xml` `no_libraries_found`** — its `loadingStatus` is a
  `DoubleLiveData`, a cold `MediatorLiveData` with no value until a source emits *after* registration.
  "No libraries found" is painted over the screen until the connection state arrives. The choose-server
  and choose-user equivalents have the same shape but are shorter-lived (their status is seeded
  `LOADING`).
- **`activity_main.xml` `bottom_nav` and `currently_playing_container`** — driven by `isLoggedIn`,
  backed by an unseeded `MutableLiveData`. The bottom nav and mini-player flash over the
  login/onboarding screen.
- **`fragment_collections.xml` `offline_mode_container` and `no_books_message`** — driven by a
  `QuadLiveDataAsync` computing on `Dispatchers.IO`, so no value at registration. Its sibling
  `swipeToRefresh` correctly defaults to `gone`, which is what makes the inconsistency visible.

Fix is `android:visibility="gone"` in XML on each, completing what `037d5aa` started.

### Also worth folding in

`CollectionsFragment.kt:92-95` faithfully transcribes a **pre-existing** bug: `offlineModeContainer`
(match_parent, elevation 1dp) fully covers `noBooksMessage`, so "No books found" is never seen there.
`LibraryFragment.kt:82-88` already fixes this by gating on `offline` vs `!offline`; the collections
sibling was missed. Not a conversion regression, but the same screen and the same fix.

Verify with `./capture-screens.sh` — the automated gate cannot see any of this.

## Acceptance Criteria

- [x] Every Kotlin-driven view defaults to `gone` in XML unless it should genuinely show first
- [x] Onboarding no longer flashes "No libraries found"
- [x] Collections shows "No books found" when appropriate
- [ ] Screenshots compared against the current baseline

## Implementation Notes

**34 views, found by scanning rather than by the list.** The task named ~13; a mechanical sweep for
"Kotlin sets this view's visibility, XML declares no default" found 34 once the search was widened
to every layout rather than the ones already known. Each got `android:visibility="gone"` plus a
`tools:visibility="visible"` so the layout preview still shows something.

Every one is an error, empty, loading or logged-in state — none is true before the first emission.
The loading spinners were checked individually rather than swept: a spinner *is* the initial state
on a screen that loads, so `gone` would be wrong there.

**The guard is the deliverable.** `FirstFrameFlashTest` re-runs the same scan and fails on any
Kotlin-driven view with no XML default, attributing a layout to its *own* fragment by name — the
id `no_books_message` appears in four layouts driven by different classes, and attributing one
screen's Kotlin to another's XML would flag views that are perfectly correct.

**It also guards the mirror risk**, which matters more than it sounds: a view defaulted to `gone`
that nothing ever un-hides is *permanently* invisible, which is worse than a one-frame flash. The
orphan check caught two real cases — `user_list` and `no_users_found` — where my scan had defaulted
them `gone` while reporting no writer. They *are* driven, through a local `tempBinding` rather than
`binding`, so the detector was too narrow; it matches `\w*[Bb]inding` now, which also brings those
views under the guard.

**Verification**

- `./verify.sh --format` green, 7 stages. **1142 unit tests**, 0 failures.
- **Device-verified on the tablet, both directions.** After `pm clear`, the login screen shows
  none of `bottom_nav`, `currently_playing_container`, `no_libraries_found` or `no_servers_found` —
  the flash is gone. And in mock mode the home shelves, nav, swipe-refresh and mini player all
  render once populated, so nothing was hidden permanently.
- The mini player specifically was checked to *reappear* when playback starts, since it is the view
  a wrong default would most visibly break.
