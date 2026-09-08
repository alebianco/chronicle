package io.github.mattpvaughn.chronicle.features.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.testing.testSettingsDataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The record that keeps the partial-prune rule safe after Fetch2's durable queue went away.
 *
 * `partialsSafeToPrune` keeps a partial when the download engine still has a record of it, because
 * a `PAUSED` or `FAILED` download is a resume candidate and deleting its bytes turns a cheap
 * `Range` request into a full re-download. Fetch2 answered that from its own SQLite queue;
 * [KtorDownloader] holds jobs in memory only, so without this store a restart would make every
 * resumable partial look abandoned — the app deleting the user's audio.
 *
 * Robolectric for a real `SharedPreferences`, deliberately: the trap this class documents is a
 * property of the framework implementation, and a fake map-backed `SharedPreferences` would not
 * exhibit it.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadIntentStoreTest {
  private lateinit var store: DownloadIntentStore

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    store = DownloadIntentStore(testSettingsDataStore("download-intent"))
  }

  @Test
  fun `nothing is pending to begin with`() {
    assertTrue(store.pending().isEmpty())
  }

  @Test
  fun `added tracks are pending`() {
    store.add(listOf("2001", "2002"))

    assertEquals(setOf("2001", "2002"), store.pending())
  }

  @Test
  fun `adding is cumulative, not a replacement`() {
    // Enqueueing a second book must not forget the first one's tracks — otherwise the prune would
    // treat the earlier book's partials as abandoned.
    store.add(listOf("2001"))
    store.add(listOf("2002"))

    assertEquals(setOf("2001", "2002"), store.pending())
  }

  @Test
  fun `removing takes only what is named`() {
    store.add(listOf("2001", "2002", "2003"))

    store.remove(listOf("2002"))

    assertEquals(setOf("2001", "2003"), store.pending())
  }

  @Test
  fun `a second reader sees what the first wrote`() {
    // What actually matters: a second reader over the same prefs file sees both writes, which is
    // the closest a unit test gets to a process restart — and surviving a restart is this class's
    // entire reason to exist.
    //
    // Note this does *not* pin the `HashSet` copy in `write`. Removing that copy fails nothing,
    // because `pending() + trackIds` already builds a new set; verified by sabotage rather than
    // assumed. The aliasing hazard is real in general, but it is the callers' set arithmetic that
    // rules it out here, not the copy.
    // One store, two readers: the point is that the *second add* reached storage, so both
    // instances must be looking at the same one. Two `testSettingsDataStore()` calls would create
    // separate temp files and the assertion would pass or fail for the wrong reason.
    val shared = testSettingsDataStore("aliasing-check")
    val first = DownloadIntentStore(shared)

    first.add(listOf("2001"))
    first.add(listOf("2002"))

    val second = DownloadIntentStore(shared)
    assertEquals(
      "the second add must have persisted; if this fails, getStringSet was mutated in place",
      setOf("2001", "2002"),
      second.pending(),
    )
  }

  @Test
  fun `clearing empties the queue`() {
    store.add(listOf("2001", "2002"))

    store.clear()

    assertTrue(store.pending().isEmpty())
  }

  @Test
  fun `adding nothing is a no-op rather than a write`() {
    store.add(listOf("2001"))

    store.add(emptyList())

    assertEquals(setOf("2001"), store.pending())
  }

  @Test
  fun `removing a track that was never added changes nothing`() {
    store.add(listOf("2001"))

    store.remove(listOf("9999"))

    assertEquals(setOf("2001"), store.pending())
  }
}
