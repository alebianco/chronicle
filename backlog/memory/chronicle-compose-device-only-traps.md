---
name: chronicle-compose-device-only-traps
description: Two Compose failures that a green unit suite cannot see — painterResource throwing on a shape drawable, and AnimatedVisibility composing hidden content
metadata:
  type: project
---

Both found in cu-206/cu-207 **after** `verify.sh` reported 8 stages green, and both only by
installing the build.

1. **`painterResource` throws for a `<shape>` drawable** — *"Only VectorDrawables and rasterized
   asset types are supported ex. PNG, JPG, WEBP"*. It is a crash, not a fallback, on the first frame
   that renders the affected item. `book_cover_missing_placeholder` is a `<shape>`, so using it as a
   Compose placeholder killed the app on launch while every test passed. Draw a placeholder as a
   `Box` background instead; it cannot fail that way.

2. **`AnimatedVisibility` keeps its content composed while hidden.** Wrapping the expanded player in
   one put every per-second recomposition back behind a *collapsed* sheet — the exact cost cu-110 and
   cu-117 measured and removed. A plain `if (state == EXPANDED)` is a stronger guarantee than any
   guard, because the work is not merely skipped, it is never scheduled.

**Why:** a Compose unit test asserts on the **semantics tree**, which was correct in both cases. The
first is a runtime throw the JVM suite never reaches; the second is a *performance* property no
assertion expresses. Source-scanning guards caught neither on their own — `CoverImageTest` and
`CollapsedSheetGuardTest` pin them now, and the second one caught its bug precisely because it was
**repointed at the new files rather than deleted** when the migration made it fail.

**How to apply:** treat a failing guard during a migration as evidence about the migration, not
noise to clear. And when a build is green, the remaining question is always which classes of bug the
suite structurally cannot see — for UI that is pixels, crashes-on-first-frame, and per-tick cost.
Related: [[chronicle-device-check-catches-wiring]], [[chronicle-compose-adopted]],
[[chronicle-profile-before-optimising]].
