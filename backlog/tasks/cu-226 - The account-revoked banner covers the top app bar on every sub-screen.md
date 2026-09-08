---
id: cu-226
title: "The account-revoked banner covers the top app bar on every sub-screen"
status: To Do
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

- [ ] The back arrow and title are visible on a pushed sub-screen **while the banner is showing**
- [ ] Verified on the licences screen **and** the Series Index Tester — both use `ChronicleScaffold`
- [ ] **Both orientations**, screenshotted, per rule 5 — the tablet is landscape-native
- [ ] The banner is still reachable and its action still routes to Settings (decision-17 intact)
- [ ] The four bottom-nav tabs checked for what the banner now covers there — draft-222 noted Home
      "looked fine" but was not examined deliberately
- [ ] The player sheet and bottom nav checked if the fix moves the banner down
- [ ] `./verify.sh` green

## Notes

**This blocks clean device verification generally**, not just one screen: mock mode always reports a
revoked account, so any mock-mode screenshot of a pushed screen has no toolbar. Worth fixing before
the next screen-level verification rather than after.
