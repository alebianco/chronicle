---
name: chronicle-verify-resolved-version
description: A dependency bump can compile green while resolving to the old version — check app:dependencies, and beware that a shell cd does not persist between Bash calls
metadata:
  type: project
---

Bumping a version in `libs.versions.toml` and getting a green compile proves **nothing** in
Chronicle: the app compiles identically against old and new for most AndroidX bumps. Always confirm
with `./gradlew -q app:dependencies --configuration debugRuntimeClasspath | grep -oE "<group>:<artifact>:[0-9.]+$"`
— the trailing `$` matters, since the tree also lists every *requested* version with `->` arrows.

This bit during cu-162: the bump appeared to work while the resolved version stayed 1.5.4, because
**the edit had landed in the main checkout rather than the task worktree**. A shell `cd` does not
persist between Bash tool calls, and a tool result can reset the working directory mid-sequence.
Either use absolute paths or re-`cd` in the same command as the work.

Two related checks worth doing on any dependency bump here:
- **minCompileSdk** from the AAR's `META-INF/com/android/build/gradle/aar-metadata.properties` —
  OkHttp 5.5.0 needs compileSdk 37 while the project is on 36, so cu-66 pinned 5.4.0.
- Whether the new version changed what else resolves (`activity`, `lifecycle` often move with
  `fragment`).

Related: [[chronicle-profile-before-optimising]], [[chronicle-device-check-catches-wiring]].
