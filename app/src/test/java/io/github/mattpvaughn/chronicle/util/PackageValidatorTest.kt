package io.github.mattpvaughn.chronicle.util

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [PackageValidator.isKnownCaller] — the real decision, not a copy of it (cu-100).
 *
 * This replaces `PackageValidatorSignatureTest`, which asserted against private helpers
 * *re-implemented inside the test file*. Those 8 tests passed while `PackageValidator` sat at 0%
 * coverage, because production code was never executed: a check that cannot fail proves nothing,
 * and mirrors drift silently. The blocker cited there — needing a `Context`, a `PackageManager` and
 * a parsed XML resource — is answered by Robolectric, already used in nine other test classes.
 *
 * Both rules covered here are security-relevant in the **fail-open** direction, and each
 * historically admitted or crashed on a caller it should not have (cu-61):
 *
 * - the platform-signature branch admitted an *unsigned* caller whenever the platform signature was
 *   also null, which is the case on an emulator image with no platform signature;
 * - the allowlist branch used `first {}`, which **throws** when nothing matches, so an allowlisted
 *   app whose signature was not among the pinned keys took down an exported service instead of
 *   simply being refused.
 *
 * `PackageValidator` caches by package name, so every test uses a distinct package: sharing one
 * would let an earlier verdict answer a later question.
 */
@RunWith(RobolectricTestRunner::class)
class PackageValidatorTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun validator() = PackageValidator(context, R.xml.auto_allowed_callers)

  /**
   * Installs [packageName] with [signatureBytes] as its signing certificate, and returns the uid
   * Robolectric assigned it.
   *
   * A null [signatureBytes] installs a package with **no** signatures, which is what produces the
   * `callerSignature == null` case the first fail-open bug depended on.
   */
  private fun installPackage(
    packageName: String,
    signatureBytes: ByteArray?,
    uid: Int = 12345,
    permissions: Array<String> = emptyArray(),
  ): Int {
    val packageInfo =
      PackageInfo().apply {
        this.packageName = packageName
        applicationInfo =
          android.content.pm.ApplicationInfo().apply {
            this.packageName = packageName
            this.uid = uid
          }
        if (signatureBytes != null) {
          // At SDK 28+ `getSignature` reads `signingInfo`, NOT the deprecated `signatures` array,
          // and robolectric.properties pins SDK 34. Setting only `signatures` left the caller's
          // signature null, which short-circuits `callerSignature != null &&` — so an earlier
          // version of this fixture never reached the allowlist branch it claimed to cover, and a
          // deliberate sabotage of that branch passed. Populate the path the code actually reads.
          signingInfo =
            SigningInfo().also { info ->
              shadowOf(info).setSignatures(arrayOf(Signature(signatureBytes)))
            }
          @Suppress("DEPRECATION")
          signatures = arrayOf(Signature(signatureBytes))
        }
        requestedPermissions = permissions
        // Only *granted* permissions are honoured, so the parallel flags array must say so —
        // requesting `MEDIA_CONTENT_CONTROL` without holding it must not admit a caller.
        requestedPermissionsFlags =
          IntArray(permissions.size) { PackageInfo.REQUESTED_PERMISSION_GRANTED }
      }
    shadowOf(context.packageManager).installPackage(packageInfo)
    return uid
  }

  /**
   * A caller with no signing certificate must be refused.
   *
   * This is the first fail-open bug. The branch was `callerSignature == platformSignature`; under
   * Robolectric there is no platform signature, so `platformSignature` is null — and an unsigned
   * caller's signature is null too. Without the `platformSignature != null` guard the two compare
   * equal and the caller is admitted.
   */
  @Test
  fun `an unsigned caller is refused when there is no platform signature`() {
    val uid = installPackage("com.example.unsigned", signatureBytes = null, uid = 20001)

    assertFalse(
      "null == null must not admit a caller; that is the emulator fail-open case",
      validator().isKnownCaller("com.example.unsigned", uid),
    )
  }

  /**
   * The second fail-open bug, and the more damaging one: this used to **throw**
   * `NoSuchElementException` out of an exported `MediaBrowserService`, taking playback down rather
   * than refusing the caller.
   *
   * `com.google.android.projection.gearhead` is allowlisted in `auto_allowed_callers.xml`, but with
   * a signature that is not one of its three pinned keys — the shape a Google signing-key rotation
   * would produce.
   */
  @Test
  fun `an allowlisted package with an unpinned signature is refused, not fatal`() {
    val uid =
      installPackage(
        "com.google.android.projection.gearhead",
        signatureBytes = byteArrayOf(9, 9, 9, 9),
        uid = 20002,
      )

    assertFalse(
      "an unpinned signature must be refused; it used to throw out of an exported service",
      validator().isKnownCaller("com.google.android.projection.gearhead", uid),
    )
  }

  /** An ordinary third-party app, neither allowlisted nor privileged, is refused. */
  @Test
  fun `an unknown caller is refused`() {
    val uid =
      installPackage("com.example.random", signatureBytes = byteArrayOf(1, 2, 3), uid = 20003)

    assertFalse(validator().isKnownCaller("com.example.random", uid))
  }

  /**
   * `MEDIA_CONTENT_CONTROL` is system-only and is one of the deliberate allowances — Android TV and
   * the Assistant rely on it. Pinned so a refactor of the `when` cannot silently drop it.
   */
  @Test
  fun `a caller holding MEDIA_CONTENT_CONTROL is admitted`() {
    val uid =
      installPackage(
        "com.example.tv",
        signatureBytes = byteArrayOf(4, 5, 6),
        uid = 20004,
        permissions = arrayOf(android.Manifest.permission.MEDIA_CONTENT_CONTROL),
      )

    assertTrue(validator().isKnownCaller("com.example.tv", uid))
  }

  /** The Wear OS allowance, via `BIND_NOTIFICATION_LISTENER_SERVICE`. */
  @Test
  fun `a caller holding BIND_NOTIFICATION_LISTENER_SERVICE is admitted`() {
    val uid =
      installPackage(
        "com.example.wear",
        signatureBytes = byteArrayOf(7, 8, 9),
        uid = 20005,
        permissions = arrayOf(android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE),
      )

    assertTrue(validator().isKnownCaller("com.example.wear", uid))
  }

  /**
   * The app itself must always be admitted — this is what lets Chronicle's own service bind.
   * `Process.myUid()` is the test process under Robolectric.
   */
  @Test
  fun `the app itself is admitted`() {
    val uid =
      installPackage(
        "io.github.mattpvaughn.chronicle.self",
        signatureBytes = byteArrayOf(1),
        uid = android.os.Process.myUid(),
      )

    assertTrue(validator().isKnownCaller("io.github.mattpvaughn.chronicle.self", uid))
  }

  /**
   * The cache must key on the uid as well as the package name. A package name can be reused across
   * a reinstall with a new uid, and answering from a stale verdict would admit or refuse the wrong
   * app.
   */
  @Test
  fun `a repeated query for the same caller gives the same answer`() {
    val uid =
      installPackage("com.example.repeat", signatureBytes = byteArrayOf(3, 3), uid = 20006)
    val validator = validator()

    assertFalse(validator.isKnownCaller("com.example.repeat", uid))
    assertFalse(
      "the cached verdict must match the computed one",
      validator.isKnownCaller("com.example.repeat", uid),
    )
  }
}
