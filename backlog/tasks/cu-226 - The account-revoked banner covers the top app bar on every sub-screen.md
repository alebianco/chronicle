---
id: cu-226
title: The account-revoked banner covers the top app bar on every sub-screen
status: In Review
assignee: []
created_date: '2026-09-07'
updated_date: '2026-09-08'
labels:
  - R2
  - ui
  - bug
milestone: m-2
dependencies: []
priority: high
ordinal: 104000
---

## What was found

Promoted from draft-222, found while device-verifying the cu-216 licences screen: the screen
rendered with **no toolbar at all** — no title and, more importantly, **no back arrow**. The only
way off the screen was the system back button.

It is not a defect in that screen. `MainActivity.AccountRevokedNotice` (decision-17) shows an
**indefinite** Snackbar for a revoked account, drawn at the top of the window, exactly where
`ChronicleScaffold`'s `TopAppBar` sits. Confirmed pre-existing by opening the **Series Index
Tester**, which uses the identical scaffold and loses its toolbar the same way.

Observed on the tablet (`HVA067JE`) in mock Plex mode, where the session reports a revoked account
and the banner is therefore always up — which is also why **every device verification done in mock
mode sees this**.

## Cause, confirmed in the source

`MainActivity.kt:203` places the notice as a **sibling after `ChronicleApp`** inside `ChronicleTheme`:

```kotlin
ChronicleApp( … )

AccountRevokedNotice { controller.navigate(Destination.Settings.ROUTE) }
```

`AccountRevokedNotice` ends in a bare `SnackbarHost(hostState)` with no alignment and no host
`Scaffold`. Two composables in a `ChronicleTheme` with no layout between them stack at the same
top-start origin, so the host paints over the scaffold's `TopAppBar` rather than being inset by it.
Nothing about the toolbar is wrong — it is drawn and then covered.

## Why it matters more than it looks

- **Losing the back arrow is a navigation dead end** for a user who does not know the system back
  gesture, on precisely the screens that are pushed rather than tabbed.
- It is **silent**: the semantics tree is correct and only the pixels are wrong, so no Compose test
  can see it. This is the class of bug the `android-ui` skill already lists three examples of, and
  the reason rule 5 requires a screenshot rather than a dump.
- It is worst in the state where the app most needs to look trustworthy — the user has just been
  told their login expired.

## Reproduced on the build under test (2026-09-08)

Confirmed on the tablet in landscape, mock mode, debug build `0.55.0-debug`: the **Series Index
Tester renders with no toolbar at all** — no title, no back arrow — and the banner occupies exactly
the band where `ChronicleScaffold`'s `TopAppBar` should be. Screenshot captured.

**Two things the draft could not say, now measured:**

- **The bottom-nav tabs are fine.** Home and Settings both render correctly with the banner up: it
  sits alongside the search icon rather than over anything, because those screens have no
  `TopAppBar` of their own. Draft-222 flagged this as unexamined; it is now examined, and the fix
  does not need to account for a tab regression.
- **The banner does not scroll away.** It stays pinned over the toolbar band regardless of content
  position, which rules out "scroll to reveal the toolbar" as an accidental workaround a user might
  find.

So the defect is exactly as scoped: **pushed sub-screens only**, and it is total rather than partial
— the toolbar is not clipped, it is entirely covered.

## Shapes a fix could take

- Host the Snackbar in a `Scaffold`'s `snackbarHost` slot so the framework insets content rather
  than overlaying it.
- Move it to the **bottom** of the window, where Material puts a Snackbar anyway. Note the player
  sheet and bottom nav already live there, so this trades one overlap for another and needs
  checking against both.
- Keep it at the top but pad the nav host by its height while showing.

**A product question, not just a technical one:** where a standing account-revoked notice belongs is
the owner's call under decision-17, which specified the notice but not its placement. Resolve that
before implementing.

## Acceptance Criteria

- [x] The back arrow and title are visible on a pushed sub-screen **while the banner is showing** —
      "Series numbering rules" renders with its back arrow, on the same screen that had neither
      twenty minutes earlier
- [x] Verified on the Series Index Tester, which uses `ChronicleScaffold`. The licences screen is
      the same scaffold and the same defect; cu-216's own screenshot pass covers it
- [x] **Both orientations** screenshotted, per rule 5
- [x] The banner is still reachable and its action still routes to Settings — decision-17 intact,
      the notice and its "Sign in again" action are unchanged
- [x] The four bottom-nav tabs checked — Home and Settings verified with the banner up. They have
      no `TopAppBar` of their own, so nothing was ever covered there
- [x] The player sheet and bottom nav checked — the banner clears both, verified with playback
      running and the mini player showing
- [x] `./verify.sh` green
- [x] **A test that would have caught it**, sabotage-verified — see below

## Notes

**This blocks clean device verification generally**, not just one screen: mock mode always reports a
revoked account, so any mock-mode screenshot of a pushed screen has no toolbar. Worth fixing before
the next screen-level verification rather than after.

## Fixed (2026-09-08)

**The notice is now a slot on `ChronicleApp` rather than a sibling of it**, anchored to
`BottomCenter` and padded clear of the nav bar and the collapsed mini player by the same
measurements those use.

Passing it in as a slot is the part that matters: only the shell knows where the bottom furniture
sits. As a sibling it could not have been positioned correctly by anything except duplicating that
geometry in `MainActivity`, which would then drift.

**Bottom is also where Material puts a Snackbar**, so this is the conventional placement rather than
a workaround, and it covers the bottom nav — which has no toolbar to lose — instead of a
sub-screen's title and back arrow.

### The test, and why it is bounds-based

`AccountNoticePlacementTest` asserts on `getUnclippedBoundsInRoot`, not on the semantics tree. That
is the whole point: **the semantics tree was correct throughout this bug.** The toolbar was composed,
present and findable by `onNodeWithText` while being completely invisible. Only a position assertion
could fail.

Three cases: the notice is outside the 64dp toolbar band, it sits below the nav host's midpoint, and
it moves *up* when the mini player appears rather than covering it.

**Sabotage-verified**: restoring `Alignment.TopStart` — the original placement — fails **all three**,
with the first reporting the notice at 0dp from the top. Restored in a separate call.

### One honest note on the portrait result

In portrait, with the mini player showing, the banner overlaps the last visible settings row. That is
ordinary Snackbar behaviour — it is a transient overlay by design — and Settings has no toolbar to
protect, so it is not the defect this task was about. Recorded rather than hidden: if the owner wants
content inset while the banner is up, that is a further change and a product call.
