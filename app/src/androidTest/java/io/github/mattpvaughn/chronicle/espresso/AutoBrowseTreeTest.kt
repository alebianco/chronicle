package io.github.mattpvaughn.chronicle.espresso

import android.content.ComponentName
import android.support.v4.media.MediaBrowserCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.github.mattpvaughn.chronicle.debug.MockPlexMode
import io.github.mattpvaughn.chronicle.features.player.AutoBrowseCategory
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Android Auto browse tree, driven through a real [MediaBrowserCompat] (cu-23, cu-99).
 *
 * This is the only way to exercise `onGetRoot`/`onLoadChildren` as Auto actually calls them.
 * A unit test cannot: both are `MediaBrowserServiceCompat` overrides that need a bound service, a
 * real `Result` to detach and send on, and a caller package to validate. cu-99's two bugs — the
 * localized title used as the media id, and a `when` with no fallback branch that hung the request
 * by never calling `sendResult` — were both invisible to everything except a real browse.
 *
 * `ChronicleTestRunner` enables mock-Plex mode before the application starts, so this needs no
 * credentials and no live server. It runs on the Automotive emulator (`chronicle_auto`) as well as
 * on the two managed devices, which is what cu-23's "app reliably appears in Auto" asks about.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AutoBrowseTreeTest {
  private lateinit var browser: MediaBrowserCompat

  /**
   * Built and driven on the **main thread**, awaited on the test thread.
   *
   * `MediaBrowserCompat` constructs a `Handler` in its constructor and posts its callbacks to that
   * looper, so creating it on the instrumentation thread throws *"Can't create handler inside
   * thread ... that has not called Looper.prepare()"* — and `subscribe` would deliver to a looper
   * that never runs. So every browser call is posted to the main thread and the result is awaited
   * through a latch, which is also what stops the await from deadlocking against the callback.
   */
  @Before
  fun connect() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val connected = CountDownLatch(1)
    onMain {
      browser =
        MediaBrowserCompat(
          context,
          ComponentName(context, MediaPlayerService::class.java),
          object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() = connected.countDown()
          },
          null,
        )
      browser.connect()
    }
    assertTrue(
      "the media browser service must accept a connection, or nothing below is being tested",
      connected.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
    )
  }

  @After
  fun disconnect() {
    if (this::browser.isInitialized) {
      onMain { if (browser.isConnected) browser.disconnect() }
    }
  }

  /** Runs [block] on the main looper and waits for it, so browser state is read consistently. */
  private fun <T> onMain(block: () -> T): T {
    var result: T? = null
    var thrown: Throwable? = null
    val done = CountDownLatch(1)
    android.os.Handler(android.os.Looper.getMainLooper()).post {
      try {
        result = block()
      } catch (t: Throwable) {
        thrown = t
      } finally {
        done.countDown()
      }
    }
    assertTrue("main-thread work must complete", done.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  /** The precondition. Without the fixture session the root is the empty one and every case lies. */
  @Test
  fun mockPlexModeIsActive() {
    assertTrue(
      "the fixture server must be running, or this is browsing a logged-out root",
      MockPlexMode.isRunning,
    )
  }

  /**
   * cu-23 criterion 1, the machine-checkable half: the app offers a browse root at all.
   *
   * A logged-out or Auto-disabled install returns `"empty root"`, so asserting the root is *not*
   * that is what distinguishes "Auto works" from "Auto is politely refusing".
   */
  @Test
  fun theBrowseRootIsNotTheEmptyRoot() {
    assertTrue(
      "a seeded session must yield a real browse root, got '${onMain { browser.root }}'",
      onMain { browser.root } != "empty root",
    )
  }

  /** The four categories, by their stable wire ids — not their localized titles (cu-99). */
  @Test
  fun theRootOffersEveryCategoryByItsStableId() {
    val children = loadChildren(onMain { browser.root })

    assertEquals(
      "the root must offer exactly the declared categories, in declaration order",
      AutoBrowseCategory.entries.map { it.id },
      children.map { it.mediaId },
    )
  }

  /** Every category must be browsable, or Auto renders it as an unplayable dead end. */
  @Test
  fun everyCategoryIsBrowsableAndTitled() {
    loadChildren(onMain { browser.root }).forEach { item ->
      assertTrue("${item.mediaId} must be browsable", item.isBrowsable)
      assertTrue(
        "${item.mediaId} must carry a title, or Auto shows a blank row",
        !item.description.title.isNullOrBlank(),
      )
    }
  }

  /**
   * cu-23 criterion 2: a book served to Auto carries the metadata a head unit renders.
   *
   * **Conditional on the library having synced**, and deliberately so. Mock mode seeds the login
   * but does not run a refresh, so on a freshly-provisioned emulator the catalogue is empty — the
   * browse is correct and there is simply nothing to browse. Asserting books exist made this pass
   * on a device that had been used and fail on a clean one, which tests provisioning rather than
   * the browse tree.
   *
   * What is *not* conditional is the shape: if a book is served at all, it must be playable and
   * titled, because a row Auto cannot play or label is the failure this criterion is about.
   * `theLibraryCategoryIsBrowsable` below covers the empty case.
   */
  @Test
  fun anyBookServedToAutoCarriesRenderableMetadata() {
    val books = loadChildren(AutoBrowseCategory.Library.id)

    books.forEach { book ->
      assertTrue("a book must be playable, not browsable", book.isPlayable)
      assertTrue("a book needs a title", !book.description.title.isNullOrBlank())
      assertTrue(
        "a book needs an author line, which is what a head unit shows beneath the title",
        !book.description.subtitle.isNullOrBlank(),
      )
      assertTrue("a book's media id must be its own", !book.mediaId.isNullOrBlank())
    }
  }

  /**
   * The category resolves and completes, whether or not the library has synced.
   *
   * This is the half that always holds: an unsynced library browses to an empty list, and a
   * *broken* one hangs or errors. [loadChildren]'s timeout is what tells those apart.
   */
  @Test
  fun theLibraryCategoryIsBrowsable() {
    loadChildren(AutoBrowseCategory.Library.id)
  }

  /**
   * The fallback branch cu-99 added. An id Auto no longer recognises must return an empty list,
   * **not hang**: before that fix an unmatched `parentId` fell through with no `sendResult` on an
   * already-detached `Result`, so the browse request never completed and Auto showed a spinner
   * forever. The timeout in [loadChildren] is what would catch a regression.
   */
  @Test
  fun anUnknownCategoryReturnsEmptyRatherThanHanging() {
    assertEquals(emptyList<String>(), loadChildren("chronicle.auto.no-such-category").map { it.mediaId })
  }

  private fun loadChildren(parentId: String): List<MediaBrowserCompat.MediaItem> {
    val loaded = CountDownLatch(1)
    var items: List<MediaBrowserCompat.MediaItem> = emptyList()
    onMain {
      browser.subscribe(
        parentId,
        object : MediaBrowserCompat.SubscriptionCallback() {
          override fun onChildrenLoaded(
            parent: String,
            children: MutableList<MediaBrowserCompat.MediaItem>,
          ) {
            items = children.toList()
            loaded.countDown()
          }

          override fun onError(parent: String) = loaded.countDown()
        },
      )
    }
    assertTrue(
      "browsing '$parentId' must complete; a request that never calls sendResult hangs Auto",
      loaded.await(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS),
    )
    onMain { browser.unsubscribe(parentId) }
    return items
  }

  private companion object {
    const val CONNECT_TIMEOUT_SECONDS = 15L

    /** Generous: a category read hits Room and, for the library, the fixture server. */
    const val LOAD_TIMEOUT_SECONDS = 20L
  }
}
