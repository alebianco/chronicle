package io.github.mattpvaughn.chronicle.data.sources.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Auth tokens must never reach logcat.
 *
 * logcat persists across the session, is readable by any app holding `READ_LOGS` on older
 * devices, and is routinely pasted wholesale into bug reports — so a logged token is a
 * working credential handed to whoever reads it.
 *
 * The scan matches a whole `Timber.x(...)` call rather than a single line, because the
 * leak this was written for spanned five lines inside a `trimMargin` block: a per-line
 * regex passed while the credential was still being logged.
 */
class TokenLoggingTest {
  @Test
  fun `no production source logs an auth token`() {
    val offenders =
      File(MAIN_SOURCE_ROOT)
        .walkTopDown()
        .filter { it.extension == "kt" }
        .filter { file -> TIMBER_CALL.findAll(file.readText()).any(::leaksToken) }
        .map { it.name }
        .sorted()
        .toList()

    assertEquals("auth tokens must not be logged", emptyList<String>(), offenders)
  }

  /** Guards the guard: a wrong path would scan nothing and pass. */
  @Test
  fun `the scan reaches a plausible number of files`() {
    val scanned = File(MAIN_SOURCE_ROOT).walkTopDown().count { it.extension == "kt" }

    assertTrue("expected the main source root to resolve, found $scanned files", scanned > 100)
  }

  /** And that the matcher itself can actually fire. */
  @Test
  fun `the matcher detects a known-bad log statement`() {
    val bad = """Timber.i(""${'"'}token = ${'$'}{user?.authToken}""${'"'})"""

    assertTrue(
      "if this fails the regex is broken and the sweep above proves nothing",
      TIMBER_CALL.findAll(bad).any(::leaksToken),
    )
  }

  private fun leaksToken(match: MatchResult): Boolean = TOKEN_INTERPOLATION.containsMatchIn(match.value)

  private companion object {
    /** Relative to the `app` module dir, the unit tests' working directory. */
    const val MAIN_SOURCE_ROOT = "src/main/java"

    /** A `Timber.x( ... )` call including its arguments, across newlines. */
    val TIMBER_CALL =
      Regex("""Timber\.[a-z]\((?:[^()]|\([^()]*\))*\)""", RegexOption.DOT_MATCHES_ALL)

    /**
     * A token *value* being interpolated: `$token`, `${user?.authToken}`,
     * `${server?.accessToken}`.
     *
     * Deliberately excludes a token reference that is immediately reduced to a boolean —
     * `${token.isNotEmpty()}`, `${user?.authToken.isNullOrEmpty()}` — since logging
     * whether a credential exists leaks nothing. Without that carve-out this test flags
     * the very fix it is meant to accept.
     */
    val TOKEN_INTERPOLATION =
      Regex("""\$\{?[A-Za-z0-9_.?]*[Tt]oken(?!\s*[.?]*\s*(?:isNotEmpty|isEmpty|isNullOrEmpty|isNullOrBlank))""")
  }
}
