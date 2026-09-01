package io.github.mattpvaughn.chronicle.features.player

/**
 * Turns a playback failure into something a log dump or a toast can actually act on (cu-103).
 *
 * `ExoPlaybackException.message` is the type name — for a streamed book it is almost always the
 * bare string `"Source error"`, which says only "something upstream failed". The fact worth having
 * (an HTTP 401, a socket timeout, a DNS failure, a 416 on a range request) sits one or more levels
 * down the `cause` chain.
 *
 * This cost a listening session to learn. A book stopped every 10-15 minutes and the only trace was
 * `Exoplayer playback error: ... Source error`, with no cause, because the log line interpolated
 * the throwable instead of passing it. Walking the chain here means the answer is in the first line
 * rather than buried in a stack trace that may be truncated by the time anyone reads it.
 *
 * Deliberately **not** localized: this goes to Timber and to the media session's error slot, both
 * diagnostic surfaces. `MainActivity` maps well-known statuses onto real strings for the user.
 */
fun describePlaybackError(error: Throwable): String {
  val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_DEPTH)

  return chain
    .map { link ->
      val type = link::class.java.simpleName
      val message = link.message?.takeIf { it.isNotBlank() }
      if (message == null) type else "$type: $message"
    }
    .distinct()
    .joinToString(" <- ")
}

/**
 * A cause chain is normally two or three deep; the cap is there because a malformed chain can be
 * self-referential in ways the `!==` guard above does not catch, and an unbounded walk inside an
 * error handler would turn a recoverable stall into a hang.
 */
private const val MAX_DEPTH = 8
