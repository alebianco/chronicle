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
  OkHttp 5.5.0 needed compileSdk 37 when it was pinned to 5.4.0. **compileSdk is 37 now**, so that
  particular pin may be liftable; the method stands, the example is spent. Check the metadata rather
  than trusting a recorded reason — and note the same file also carries a **minimum AGP** version,
  which is what actually holds Compose, lifecycle and navigation-compose here.
- Whether the new version changed what else resolves (`activity`, `lifecycle` often move with
  `fragment`).

Related: [[chronicle-profile-before-optimising]], [[chronicle-device-check-catches-wiring]].
