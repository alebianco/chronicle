---
id: cu-102
title: Unlock crashes the app from the book details screen
status: Done
assignee: [claude]
created_date: '2026-09-01'
labels: [R1, trust, bug]
dependencies: []
priority: high
milestone: m-1
---

## Description

Fourteen identical crashes in one listening session, every time the screen was woken with a book
details screen open. Because the crash kills the process it also killed playback, which is why it
was initially reported together with cu-103.

```
java.lang.NullPointerException: Attempt to invoke interface method
'android.view.MenuItem android.view.MenuItem.setIcon(int)' on a null object reference
  at AudiobookDetailsFragment.onCreateView$lambda$26(AudiobookDetailsFragment.kt:261)
  ...
  at MainActivity.onStart(MainActivity.kt:306)
```

## Root cause

A lifecycle mismatch between two menu owners:

- `setSupportActionBar(binding.detailsToolbar)` hands the toolbar's menu to the **activity's**
  `MenuHost`, so the menu inflated by `app:menu` in XML no longer belongs to the toolbar;
- the `MenuProvider` that repopulates it is registered at `Lifecycle.State.RESUMED`;
- a `LiveData` observer becomes active at **STARTED** and immediately replays its cached value.

Between STARTED and RESUMED the menu is empty. The stack trace shows the exact path:
`onStart` -> `LiveData.activeStateChanged` -> `MediatorLiveData.onActive` -> replay -> `findItem`
returns null -> `.setIcon` throws.

## Implementation Notes

- `menuItemOrNull` guards the lookup; `applyWatchedIcon` / `applySyncIconState` are re-applied from
  `onPrepareMenu`, because the guard **alone** would leave the icon showing the wrong state.
- The toolbar is now held in a field cleared in `onDestroyView` — the observers outlived the local
  `binding` in `onCreateView`.
- `UnguardedMenuAccessTest` covers both halves. Scoped to `.menu.findItem(...)` (a toolbar-owned
  menu) rather than every `findItem`: reading the `menu` parameter inside `onCreateMenu` is safe,
  and `HomeFragment`, `LibraryFragment` and `CollectionsFragment` all do that correctly. The first
  version of the test flagged them and was wrong.

Sabotage: reverting to an unguarded dereference fails the test.

## Acceptance Criteria

- [x] No crash on unlock from the book details screen
- [x] Icons show correct state once the menu is populated, not merely no crash
- [x] A guard test prevents the shape being reintroduced
- [ ] Confirmed on the device over a full session

## Notes

A proper `FragmentScenario` test driving STARTED -> RESUMED would be the real guard; it needs
`fragment-testing`, which is not on the classpath (see cu-33). The text scan is the interim.
