package io.github.mattpvaughn.chronicle.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two allowlist rules in [PackageValidator] that decide whether an
 * app may browse the media library (cu-61).
 *
 * These mirror the production expressions rather than calling them: the real
 * decision needs a `Context`, a `PackageManager` and a parsed XML resource, and
 * is private. An adversarial review rightly called an earlier version of this
 * file documentation-not-coverage for exactly that reason — so the mirrors are
 * now kept deliberately *minimal and literal*, and each carries the production
 * `file:line` it shadows. If either expression changes, update both together;
 * a divergence is a review failure, not a test failure.
 *
 * Both rules are security-relevant in the fail-open direction, which is why they
 * are worth pinning at all: each historically admitted a caller it should not.
 */
class PackageValidatorSignatureTest {
  /**
   * Mirrors the platform-signature branch (`PackageValidator.isKnownCaller`).
   *
   * `getSignature()` returns `String?`, so an unsigned caller yields null. An
   * unguarded `callerSignature == platformSignature` therefore admitted that
   * caller whenever the platform signature was *also* null — the case on an
   * emulator image with no platform signature.
   */
  private fun admittedByPlatformSignature(
    callerSignature: String?,
    platformSignature: String?,
  ): Boolean = platformSignature != null && callerSignature == platformSignature

  /**
   * Mirrors the whitelist branch (`PackageValidator.isKnownCaller`).
   *
   * This used `first {}`, which **throws** when nothing matches rather than
   * returning null, and is evaluated before the `when` — so a whitelisted app
   * whose signature was not among the pinned keys crashed the exported
   * `MediaBrowserService` instead of simply being refused.
   */
  private fun admittedByWhitelist(
    callerSignature: String?,
    pinnedSignatures: List<String>,
  ): Boolean = callerSignature != null && pinnedSignatures.any { it == callerSignature }

  @Test
  fun `a caller signed with the platform certificate is admitted`() {
    assertTrue(admittedByPlatformSignature("abc123", "abc123"))
  }

  @Test
  fun `a caller with a different signature is not admitted`() {
    assertFalse(admittedByPlatformSignature("deadbeef", "abc123"))
  }

  @Test
  fun `an unsigned caller is not admitted when the platform signature is missing`() {
    assertFalse(admittedByPlatformSignature(null, null))
  }

  @Test
  fun `no caller is admitted by the platform rule when that signature is missing`() {
    assertFalse(admittedByPlatformSignature("abc123", null))
    assertFalse(admittedByPlatformSignature("", null))
  }

  @Test
  fun `an unsigned caller is not admitted when a platform signature exists`() {
    assertFalse(admittedByPlatformSignature(null, "abc123"))
  }

  @Test
  fun `a whitelisted caller with a pinned signature is admitted`() {
    assertTrue(admittedByWhitelist("aa11", listOf("bb22", "aa11")))
  }

  @Test
  fun `a whitelisted package with an unpinned signature is refused, not fatal`() {
    // The regression: `first {}` threw here, taking down the exported
    // MediaBrowserService. Refusal is the correct outcome.
    assertFalse(admittedByWhitelist("rotated-key", listOf("bb22", "aa11")))
  }

  @Test
  fun `an unsigned caller is never admitted by the whitelist`() {
    assertFalse(admittedByWhitelist(null, listOf("bb22")))
    assertFalse(admittedByWhitelist(null, emptyList()))
  }
}
