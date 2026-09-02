package io.github.mattpvaughn.chronicle.data.sources.plex

import com.tonyodev.fetch2core.Logger
import timber.log.Timber

/**
 * Fetch2's [Logger], with any Plex token stripped before the line reaches logcat.
 *
 * Fetch2 logs whole `DownloadInfo` objects via their `toString()`, and that includes the
 * **headers map** — so a download enqueued with `X-Plex-Token` writes a working credential to
 * logcat on every state transition. Measured before this class existed: three copies of the
 * account's server token before a single byte transferred, and `enableLogging(true)` was
 * unconditional, so it happened in **release** builds too.
 *
 * logcat persists across the session and is routinely pasted wholesale into bug reports, which is
 * the same reasoning behind `TokenLoggingTest`. That guard cannot see this: it scans our own
 * `Timber` calls, not a third-party library's internal logging, so the rule was enforced only
 * where we happen to be the caller.
 *
 * Redacting rather than silencing is deliberate. The download items still open on cu-73 (a
 * `FAILED` download retried on next launch, a token rotated mid-download) are diagnosed from
 * exactly these lines, and cu-109 — an OOM inside Fetch2's own thread — was found by reading
 * them. Turning logging off would trade one problem for a blinder one.
 */
class RedactingFetchLogger(
  private val tag: String = "Fetch2",
  @Volatile private var enabledFlag: Boolean = true,
) : Logger {
  override var enabled: Boolean
    get() = enabledFlag
    set(value) {
      enabledFlag = value
    }

  override fun d(message: String) {
    if (enabled) Timber.tag(tag).d(redact(message))
  }

  override fun d(
    message: String,
    throwable: Throwable,
  ) {
    if (enabled) Timber.tag(tag).d(throwable, redact(message))
  }

  override fun e(message: String) {
    if (enabled) Timber.tag(tag).e(redact(message))
  }

  override fun e(
    message: String,
    throwable: Throwable,
  ) {
    if (enabled) Timber.tag(tag).e(throwable, redact(message))
  }

  companion object {
    /** What a redacted token is replaced with. Presence stays visible; the value does not. */
    const val REDACTED = "<redacted>"

    /**
     * Matches the token in both shapes Fetch2 emits it in:
     * - the headers map, rendered as `X-Plex-Token=abc123` (also tolerating `: ` as a separator)
     * - the request URL, as `?…&X-Plex-Token=abc123`
     *
     * Case-insensitive because the header's case is not ours to guarantee — it is whatever the
     * interceptor set, and a future rename to `x-plex-token` must not silently open the leak.
     */
    private val TOKEN = Regex("""(X-Plex-Token)\s*[=:]\s*([^\s,&)}\]]+)""", RegexOption.IGNORE_CASE)

    /**
     * Replaces every token value in [message], keeping the key so a reader can still see that a
     * token was attached.
     */
    fun redact(message: String): String = TOKEN.replace(message) { "${it.groupValues[1]}=$REDACTED" }
  }
}
