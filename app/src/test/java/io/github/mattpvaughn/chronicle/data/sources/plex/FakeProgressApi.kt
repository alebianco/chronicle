package io.github.mattpvaughn.chronicle.data.sources.plex

/**
 * A [ProgressApi] that records calls and can be told to fail.
 *
 * Deliberately hand-written rather than mocked: the assertions here are about *which*
 * exception maps to which retry decision, and a stub that throws a real
 * `IOException`/`HttpException` exercises the same `catch` clauses production does.
 */
class FakeProgressApi(
  private val failWith: Throwable? = null,
  private val failWatchedWith: Throwable? = null,
) : ProgressApi {
  var progressCalls = 0
    private set

  var watchedCalls = 0
    private set

  /** Keys passed to [markWatched], in call order. */
  val watchedKeys = mutableListOf<String>()

  /**
   * The arguments of the last [reportProgress] call.
   *
   * Recorded because counting calls cannot see *what* was sent: the duration Plex is told is
   * deliberately doubled, and a mutant turning that into a division survived every call-counting
   * assertion here.
   */
  var lastReportedDuration: Long? = null
    private set

  var lastReportedOffset: String? = null
    private set

  var lastReportedKey: String? = null
    private set

  override suspend fun reportProgress(
    ratingKey: String,
    offset: String,
    key: String,
    duration: Long,
    playState: String,
    playbackTime: Long,
    playQueueItemId: Long,
  ) {
    progressCalls++
    lastReportedDuration = duration
    lastReportedOffset = offset
    lastReportedKey = key
    failWith?.let { throw it }
  }

  override suspend fun markWatched(key: String) {
    watchedCalls++
    watchedKeys += key
    failWatchedWith?.let { throw it }
  }
}
