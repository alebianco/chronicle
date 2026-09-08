package io.github.mattpvaughn.chronicle.debug

import java.io.File

/**
 * The external dir matching [target], or null when it is not one of [candidates].
 *
 * Split out as a pure function so the refusal is testable without a device. It matters more than it
 * looks: `cachedMediaDir` accepts any path, so an unmatched one would point downloads at a
 * directory the app cannot write, and the failure would surface much later as downloads silently
 * not working rather than as a bad argument here.
 *
 * An **exact** match, not `startsWith`: a parent of a real external dir is not itself writable by
 * the app, so a prefix match would accept precisely the paths this exists to refuse.
 *
 * Lives in `main/` rather than in the debug `DebugHooks` twin, though only the debug twin calls it.
 * `app/src/test/` is shared by every variant, so a test reaching a debug-only symbol compiles under
 * the debug variant and fails under release. That is not hypothetical: this function was `internal`
 * on the debug `DebugHooks`, its test called it there, and `:app:compileReleaseUnitTestKotlin` had
 * therefore **never compiled** — unnoticed for as long as the hook tests existed, because nothing
 * built that variant. Keeping pure helpers here rather than on either twin is what stops it
 * recurring. See `DebugHooksContract`'s note on the same failure mode in production code.
 */
internal fun resolveSyncTarget(
  target: String,
  candidates: List<File>,
): File? = candidates.firstOrNull { it.absolutePath == target }
