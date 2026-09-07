---
name: chronicle-sabotage-rerun-tasks
description: "Sabotage-verifying a test in Chronicle needs --rerun-tasks, and the restore must be a separate tool call"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 3ec1857c-0cea-4d73-888a-864f48761807
  modified: 2026-09-03T20:40:42.433Z
---

When sabotage-verifying a test in Chronicle (the repo rule: "a check that cannot fail proves
nothing"), two things make the sabotage silently appear to pass:

1. **Gradle reports the task up-to-date** and never runs the sabotaged code — `BUILD SUCCESSFUL`
   with *no test count line* is the tell. Use `./gradlew testDebugUnitTest --tests '*X*'
   --rerun-tasks`.
2. **Chaining sabotage → test → restore in one command** can let the restore land before the test
   task actually executes, so the run measures the restored code. Do the sabotage, the test run,
   and the restore as three separate calls, and confirm the sabotage is on disk (`grep`) before
   running.

**Why:** I concluded twice in one session that a debounce test was not load-bearing, when it was —
the second diagnosis wasted a round trip and nearly led me to delete a good test.
**How to apply:** any time the repo's sabotage rule is being followed. See
[[chronicle-branch-base]] for the surrounding workflow.
