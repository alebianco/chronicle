---
name: chronicle-device-check-catches-wiring
description: A green Chronicle unit suite says nothing about fragment wiring; the library screen showed "No books found" over a full library with all 1301 tests passing
metadata:
  type: project
---

During the cu-52 StateFlow migration, `LibraryFragment` collected a flow and **discarded the
emission** — `collectWhileStarted(viewModel.books) { refreshEmptyStates() }`, where the lambda
should have assigned `latestBooks = it` first. The screen rendered "No books found" over a 196-book
library. It compiled, all 1301 unit tests passed, and Home was unaffected because it reads `.value`
instead of caching into a local.

**Why:** nothing in the JVM suite constructs a Fragment, so the whole ViewModel↔view wiring layer is
untested by construction. The ViewModel was correct; only the wiring was wrong.

**How to apply:** after any change that rewires fragments, install on the tablet and open **every
tab**, not just the one that was worked on — and background/restore the app, since that is where
`repeatOnLifecycle` either works or leaves a stale screen. When a wiring defect is found, add a
*source* guard (the `FirstFrameFlashTest` pattern), because no test that inspects a laid-out view
can tell "the flow has not emitted" from "the emission was thrown away".
`CollectorCachesItsValueTest` is the one written for this.

Related: [[chronicle-tablet-session]], [[chronicle-stateflow-testing]].
