---
id: cu-68
title: Sweep the remaining first-frame flashes from cu-58
status: To Do
assignee: []
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

- [ ] Every Kotlin-driven view defaults to `gone` in XML unless it should genuinely show first
- [ ] Onboarding no longer flashes "No libraries found"
- [ ] Collections shows "No books found" when appropriate
- [ ] Screenshots compared against the current baseline
