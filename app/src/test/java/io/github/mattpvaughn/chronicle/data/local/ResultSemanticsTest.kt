package io.github.mattpvaughn.chronicle.data.local

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the kotlin-result semantics the repositories depend on.
 *
 * kotlin-result 2.x turned `Result` into a value class: `Ok`/`Err` are factory
 * functions rather than types, so the old `x is Ok` type check silently stops
 * compiling and had to become `x.isOk` (cu-48). These branches gate whether
 * track loading falls back to cached data, and nothing else covers them — a
 * library upgrade that inverted this would be invisible until playback broke.
 */
class ResultSemanticsTest {
  private fun succeed(): Result<List<String>, Throwable> = Ok(listOf("track-1"))

  private fun fail(cause: Throwable): Result<List<String>, Throwable> = Err(cause)

  @Test
  fun `Ok reports isOk and carries its value`() {
    val result = succeed()

    assertTrue("Ok must satisfy isOk", result.isOk)
    assertFalse("Ok must not satisfy isErr", result.isErr)
    assertEquals(listOf("track-1"), result.get())
  }

  @Test
  fun `Err reports isErr and carries its cause`() {
    val cause = IllegalStateException("no network")
    val result = fail(cause)

    assertTrue("Err must satisfy isErr", result.isErr)
    assertFalse("Err must not satisfy isOk", result.isOk)
    assertEquals(cause, result.getError())
    assertNull("Err has no value", result.get())
  }

  @Test
  fun `an Ok wrapping an empty list is still a success`() {
    // Guards a plausible misreading: "no tracks returned" is not an error, and
    // callers branch on isOk rather than on emptiness.
    val result: Result<List<String>, Throwable> = Ok(emptyList())

    assertTrue(result.isOk)
    assertEquals(emptyList<String>(), result.get())
  }
}
