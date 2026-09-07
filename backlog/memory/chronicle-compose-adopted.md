---
name: chronicle-compose-adopted
description: Compose accepted (decision-22) for Chronicle; the two gotchas that made a green test suite sit over a visibly broken screen
metadata:
  type: project
---

Chronicle adopted Compose on 2026-09-06 (decision-22, Accepted; POC in cu-181) and **finished the
migration on 2026-09-07** (cu-206). Screens moved one per task — cu-187 (Collections), then cu-188
ordered by bug density, player first — with ViewBinding and Compose side by side until the
navigation shell went last. **As of cu-206 there are no layouts, no Fragments and no ViewBinding.**

**Two gotchas cost a debug cycle each, and neither is catchable by a test** — both suites were green
and the semantics tree correct while the screen was visibly wrong:

1. `MaterialTheme` **defines** `colorScheme.background` but paints nothing — that is `Surface`'s or
   `Scaffold`'s job. A bare `Box` lets the window colour through: the screen renders `#121212` with
   near-invisible text. Wrap every screen in `Surface`.
2. `GridCells.Fixed(n)` divides the **available** width, so `Fixed(3)` gave 640px cells on the
   1200x1920 tablet and one cover filled the screen. Use `GridCells.Adaptive(minSize)`.

**Why:** a Compose test measures whatever width it is told and reads the semantics tree, so neither
pixel bug is reachable from the JVM. This is the concrete case for [[chronicle-device-check-catches-wiring]].

**How to apply:** on any Compose screen, wrap in `Surface` + `ChronicleTheme`, prefer `Adaptive`
grids, and verify on the device in **both orientations** — cu-141/142/19 were all landscape-only.
Screenshot traps: wait for `dumpsys gfxinfo` to report frames rendered (a pre-first-frame capture is
a blank window that looks like a broken screen), and confirm a suspicious capture by sampling pixels
— a 15 KB PNG of a 1920x1200 screen is itself the tell. Related: [[chronicle-tablet-session]].

Version pins that are **not** arbitrary: Compose BOM held at 2026.06.x and `lifecycle` at 2.10.0,
both because newer releases demand compileSdk 37 (project is on 36) and AGP 9.1. Raise only with
compileSdk.
