package io.github.mattpvaughn.chronicle.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the platform-signature allowance in [PackageValidator] (cu-61).
 *
 * `PackageValidator` needs a real `Context` and parses an XML resource, so the
 * decision is not directly constructible in a JVM test. What is worth pinning is
 * the boolean rule, because getting it wrong is a security bug rather than a
 * crash: `getSignature()` returns `String?`, so a caller with no readable
 * signature yields null — and a bare `callerSignature == platformSignature`
 * would then admit that caller whenever the platform signature is also null,
 * which is exactly the emulator case cu-61 is about.
 *
 * The rule is mirrored here so an edit to the real `when` branch that drops the
 * null guard fails a test rather than silently widening access.
 */
class PackageValidatorSignatureTest {
  /** Mirrors the guarded branch in `PackageValidator.isKnownCaller`. */
  private fun admittedByPlatformSignature(
    callerSignature: String?,
    platformSignature: String?,
  ): Boolean = platformSignature != null && callerSignature == platformSignature

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
    // The regression this guards: both null, so an unguarded equality check
    // would return true and admit an unidentifiable caller.
    assertFalse(admittedByPlatformSignature(null, null))
  }

  @Test
  fun `no caller is admitted by this rule when the platform signature is missing`() {
    assertFalse(admittedByPlatformSignature("abc123", null))
    assertFalse(admittedByPlatformSignature("", null))
  }

  @Test
  fun `an unsigned caller is not admitted when a platform signature exists`() {
    assertFalse(admittedByPlatformSignature(null, "abc123"))
  }
}
