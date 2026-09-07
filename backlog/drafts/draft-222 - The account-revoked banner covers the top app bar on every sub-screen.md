---
id: draft-222
title: "The account-revoked banner covers the top app bar on every sub-screen"
status: Draft
created_date: '2026-09-07'
labels:
  - ui
  - bug
---

## What was found

While device-verifying the new licences screen, the screen rendered with **no toolbar at all** — no
title and, more importantly, **no back arrow**. The only way off the screen was the system back
button.

It is not a defect in that screen. `MainActivity.AccountRevokedNotice` (decision-17) shows an
**indefinite** Snackbar for a revoked account, and it draws at the top of the window, exactly where
`ChronicleScaffold`'s `TopAppBar` sits. Confirmed pre-existing by opening the **Series Index Tester**,
which uses the identical scaffold: it loses its toolbar the same way, under the same banner.

Observed on the tablet (`HVA067JE`) in mock Plex mode, where the session reports a revoked account,
so the banner is always up — which is also why every device verification done in mock mode will see
this.

## Why it matters more than it looks

- **Losing the back arrow is a navigation dead end** for a user who does not know the system back
  gesture, on precisely the screens that are pushed rather than tabbed.
- It is **silent**: the semantics tree is correct and only the pixels are wrong, so no Compose test
  can see it. This is the class of bug the `android-ui` skill already lists three examples of.
- It is worst in the state where the app most needs to look trustworthy — the user has just been told
  their login expired.

## Shapes a fix could take

Not investigated in depth; listing so the ticket does not start from zero.

- Host the Snackbar in a `Scaffold`'s `snackbarHost` slot so the framework insets the content rather
  than overlaying it.
- Move it to the **bottom** of the window, which is where Material puts a Snackbar anyway, and where
  it would cover the bottom nav rather than the toolbar. Note the player sheet also lives down there.
- Keep it at the top but pad the nav host by its height while it is showing.

## Worth checking at the same time

Whether the banner also covers anything on the four bottom-nav tabs, which have no toolbar of their
own — the home screen looked fine, but that was not examined deliberately.
