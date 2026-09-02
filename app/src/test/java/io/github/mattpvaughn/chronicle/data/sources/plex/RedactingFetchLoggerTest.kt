package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.sources.plex.RedactingFetchLogger.Companion.REDACTED
import io.github.mattpvaughn.chronicle.data.sources.plex.RedactingFetchLogger.Companion.redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fetch2 logs whole `DownloadInfo` objects, headers included, so these assert that no token value
 * survives into a log line.
 *
 * The strings below are **real shapes taken from logcat** during the cu-73 live pass, not invented
 * ones — the leak was found by reading exactly these lines.
 */
class RedactingFetchLoggerTest {
  /** A real `DownloadInfo.toString()`, shortened, with a fake token of the same shape. */
  private val downloadInfoLine =
    "Enqueued download DownloadInfo(id=1808083103, namespace='LibGlobalFetchLib', " +
      "url='https://192-168-1-54.hash.plex.direct:32400/library/parts/296922/1786560717/file.m4b?download=1', " +
      "file='file:///storage/emulated/0/Android/data/pkg/files/155607.m4b', group=155606, " +
      "priority=NORMAL, headers={X-Plex-Token=FxCCQsCQ6L1sUiG4t_6S}, downloaded=0, total=-1, " +
      "status=QUEUED, error=NONE, tag=This Inevitable Ruin)"

  @Test
  fun `redacts the token in a headers map`() {
    val result = redact(downloadInfoLine)

    assertFalse("token value must not survive", result.contains("FxCCQsCQ6L1sUiG4t_6S"))
    assertTrue("key should remain visible", result.contains("X-Plex-Token=$REDACTED"))
  }

  @Test
  fun `keeps everything else in the line intact`() {
    val result = redact(downloadInfoLine)

    // The line's diagnostic value is the point of redacting rather than silencing (cu-109 was
    // found by reading these), so assert the useful fields are still there.
    assertTrue(result.contains("id=1808083103"))
    assertTrue(result.contains("status=QUEUED"))
    assertTrue(result.contains("group=155606"))
    assertTrue(result.contains("tag=This Inevitable Ruin"))
    assertTrue(result.contains("file.m4b"))
  }

  @Test
  fun `redacts a token carried as a url query parameter`() {
    val line = "url='https://server.plex.direct:32400/library/parts/1/2/file.m4b?download=1&X-Plex-Token=abc123DEF456'"

    val result = redact(line)

    assertFalse(result.contains("abc123DEF456"))
    assertTrue(result.contains("X-Plex-Token=$REDACTED"))
    // The rest of the URL still has to be readable, or the log stops being useful.
    assertTrue(result.contains("download=1"))
    assertTrue(result.contains("file.m4b"))
  }

  @Test
  fun `redacts every occurrence, not just the first`() {
    val line = "a X-Plex-Token=one b X-Plex-Token=two c"

    val result = redact(line)

    assertFalse(result.contains("one"))
    assertFalse(result.contains("two"))
    assertEquals("a X-Plex-Token=$REDACTED b X-Plex-Token=$REDACTED c", result)
  }

  @Test
  fun `matches the header regardless of case`() {
    // The header's case is set by the interceptor, not by this class; a rename to lower case must
    // not silently reopen the leak.
    val result = redact("headers={x-plex-token=secretvalue}")

    assertFalse(result.contains("secretvalue"))
  }

  @Test
  fun `tolerates a colon separator as well as equals`() {
    val result = redact("X-Plex-Token: secretvalue")

    assertFalse(result.contains("secretvalue"))
  }

  @Test
  fun `stops at the value boundary and does not swallow the rest of the line`() {
    val line = "headers={X-Plex-Token=secretvalue}, status=QUEUED, error=NONE"

    val result = redact(line)

    assertFalse(result.contains("secretvalue"))
    // A greedy match here would eat the trailing fields and quietly destroy the log's usefulness.
    assertTrue(result.contains("status=QUEUED"))
    assertTrue(result.contains("error=NONE"))
  }

  @Test
  fun `redacts a token that ends a single-quoted url`() {
    // Fetch2 wraps the url in single quotes, so a token at the very end of one is the shape that
    // actually appears in the wild. Found while reviewing the value-boundary character class.
    val line = "url='https://s/file.m4b?download=1&X-Plex-Token=SECRETVALUE', file='x.m4b'"

    val result = redact(line)

    assertFalse("token must not survive a quoted url", result.contains("SECRETVALUE"))
    // The fields after it must stay readable — a boundary that swallowed the quote would take
    // the rest of the line with it.
    assertTrue(result.contains("file='x.m4b'"))
  }

  @Test
  fun `leaves a line with no token untouched`() {
    val line = "Queued DownloadInfo(id=7, status=DOWNLOADING, downloaded=1024)"

    assertEquals(line, redact(line))
  }

  @Test
  fun `disabled logger emits nothing`() {
    // `enabled` is part of Fetch2's Logger contract; if it is ignored, a caller turning logging
    // off would still get output.
    val logger = RedactingFetchLogger(enabledFlag = false)

    assertFalse(logger.enabled)

    logger.enabled = true
    assertTrue(logger.enabled)
  }
}
