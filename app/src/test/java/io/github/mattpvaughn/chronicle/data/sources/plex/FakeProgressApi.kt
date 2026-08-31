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
    failWith?.let { throw it }
  }

  override suspend fun markWatched(key: String) {
    watchedCalls++
    watchedKeys += key
    failWatchedWith?.let { throw it }
  }
}
