package io.github.mattpvaughn.chronicle.features.home

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.testExceptionHandler
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * `setSearchActive` publishes its flag **synchronously**.
 *
 * `postValue` is asynchronous and *coalescing* in production: two calls in the same main-loop pass
 * collapse into one delivery, and a read taken before that pass sees the previous value.
 * `setSearchActive` does two things — sets the flag and tells the `SearchController` — so with
 * `postValue` the two could disagree about whether search is open. That is the shape of all three
 * device races a live profiling pass found, one of which was fixed by exactly this substitution.
 *
 * **These assertions cannot fail against `postValue`, and that is worth knowing rather than
 * hiding.** `InstantTaskExecutorRule` replaces the `ArchTaskExecutor` with one that runs everything
 * on the calling thread, which makes `postValue` synchronous *in tests* — verified directly with a
 * throwaway probe (`postValue(42)` then `assertEquals(42, value)` passes under the rule). So no JVM
 * unit test in this repo can distinguish the two calls, and a test claiming to prove the fix would
 * be proving the rule instead.
 *
 * What these do pin is the **contract** — that reading the flag straight after setting it returns
 * what was set — so a future change that reintroduces a deferred publish *and* removes the rule
 * fails here. The race itself is a device-level fact, evidenced there, not here, and
 * `PostValueUsageTest` is what actually keeps `postValue` out of the tree.
 */
class HomeSearchToggleTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private fun viewModel() =
    HomeViewModel(
      plexConfig =
        mockk<PlexConfig>(relaxed = true) {
          every { isConnected } returns MutableStateFlow(true)
        },
      bookRepository =
        mockk<IBookRepository>(relaxed = true) {
          every { getRecentlyListened() } returns MutableStateFlow(emptyList())
          every { getRecentlyAdded() } returns MutableStateFlow(emptyList())
          every { getCachedAudiobooks() } returns MutableStateFlow(emptyList())
        },
      librarySyncRepository = mockk<LibrarySyncRepository>(relaxed = true),
      prefsRepo =
        mockk<PrefsRepo>(relaxed = true) {
          every { offlineMode } returns false
        },
      mediaServiceConnection = mockk<MediaServiceConnection>(relaxed = true),
      exceptionHandler = testExceptionHandler(),
    )

  @Test
  fun `the search flag is readable immediately after being set`() {
    val vm = viewModel()

    vm.setSearchActive(true)

    assertEquals(true, vm.isSearchActive.value)
  }

  @Test
  fun `toggling search off is readable immediately`() {
    val vm = viewModel()
    vm.setSearchActive(true)

    vm.setSearchActive(false)

    assertEquals(false, vm.isSearchActive.value)
  }

  /**
   * Two toggles in one pass both land. With `postValue` the intermediate state is dropped, which is
   * harmless here only because the final value happens to match — the failure it stands in for is
   * the `SearchController` seeing a different number of transitions than the flag records.
   */
  @Test
  fun `rapid toggles leave the flag agreeing with the last call`() {
    val vm = viewModel()

    vm.setSearchActive(true)
    vm.setSearchActive(false)
    vm.setSearchActive(true)

    assertEquals(true, vm.isSearchActive.value)
  }
}
