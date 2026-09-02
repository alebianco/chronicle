package io.github.mattpvaughn.chronicle.application

import io.github.mattpvaughn.chronicle.application.ChronicleApplication.Companion.isAccountRejection
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Which startup failures mean "the account is dead" and which mean "we are offline".
 *
 * The startup `/api/v2/resources` refresh used to swallow **everything** in one
 * `catch (e: Exception)` logged as "keeping cached server". A password change with "sign out
 * connected devices" produced a real `401 Unauthorized` from plex.tv and the app said nothing —
 * measured on device during the cu-73 live pass (decision-17).
 *
 * The asymmetry matters in both directions, so both are pinned here: missing the 401 leaves the
 * user stranded with silently dead sync, and over-claiming it reintroduces cu-84, where being
 * offline was reported as being signed out.
 */
class AccountRejectionClassificationTest {
  private fun httpException(code: Int) =
    HttpException(
      Response.error<Any>(code, "".toResponseBody("text/plain".toMediaType())),
    )

  @Test
  fun `a 401 is an account rejection`() {
    assertTrue(isAccountRejection(httpException(401)))
  }

  @Test
  fun `being offline is not an account rejection`() {
    // The cu-84 rule: no network says nothing about the credential.
    assertFalse(isAccountRejection(UnknownHostException("plex.tv")))
    assertFalse(isAccountRejection(IOException("network unreachable")))
  }

  @Test
  fun `a timeout is not an account rejection`() {
    assertFalse(isAccountRejection(SocketTimeoutException("timed out")))
  }

  @Test
  fun `a server error is not an account rejection`() {
    // plex.tv having a bad day is not the user being signed out, and retrying later is right.
    assertFalse(isAccountRejection(httpException(500)))
    assertFalse(isAccountRejection(httpException(503)))
  }

  @Test
  fun `other client errors are not account rejections`() {
    // 403 is deliberately excluded: it means "understood, refused", which for plex.tv is about
    // the resource rather than the identity. Claiming a sign-out from it would be a guess.
    assertFalse(isAccountRejection(httpException(403)))
    assertFalse(isAccountRejection(httpException(404)))
    assertFalse(isAccountRejection(httpException(429)))
  }

  @Test
  fun `an unexpected throwable is not an account rejection`() {
    // Anything unrecognised must fall to the safe side.
    assertFalse(isAccountRejection(IllegalStateException("parse failure")))
  }
}
