package io.github.mattpvaughn.chronicle.features.home

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.testExceptionHandler
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * `setSearchActive` publishes its flag **synchronously** (cu-52).
 *
 * `postValue` is asynchronous and *coalescing* in production: two calls in the same main-loop pass
 * collapse into one delivery, and a read taken before that pass sees the previous value.
 * `setSearchActive` does two things — sets the flag and tells the `SearchController` — so with
 * `postValue` the two could disagree about whether search is open. That is the shape of all three
 * device races cu-73 found, one of which was fixed by exactly this substitution.
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
 * fails here. The race itself is a device-level fact (cu-73), evidenced there, not here.
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
          every { isConnected } returns MutableLiveData(true)
        },
      bookRepository =
        mockk<IBookRepository>(relaxed = true) {
          every { getRecentlyListened() } returns MutableLiveData(emptyList())
          every { getRecentlyAdded() } returns MutableLiveData(emptyList())
          every { getCachedAudiobooks() } returns MutableLiveData(emptyList())
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
