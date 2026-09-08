package io.github.mattpvaughn.chronicle.features.currentlyplaying

import android.content.Context
import androidx.work.WorkManager
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.IBookmarkRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.features.player.SleepTimerBus
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.testing.testSettingsDataStore
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.testExceptionHandler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The player's **jump icons** and **bookmark actions** — two surfaces of
 * [CurrentlyPlayingViewModel] that the existing suite does not reach.
 *
 * Both are small, and both have a branch that only shows up in an unusual state: the jump icons
 * fall back when the stored interval is not one of the six offered, and `addBookmark` refuses when
 * no book is playing. A bookmark is the one piece of state **no server holds a copy of**,
 * so an action that silently does nothing loses something unrecoverable.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerControlsTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val book =
    Audiobook(id = "1001", source = TEST_SOURCE, title = "Mistborn")

  private val bookFlow = MutableStateFlow(book)
  private val positionFlow = MutableStateFlow(BookOffset(12_345L))

  private val bookmarkRepository = mockk<IBookmarkRepository>(relaxed = true)
  private val prefsRepo = mockk<PrefsRepo>(relaxed = true)

  private val currentlyPlaying =
    mockk<CurrentlyPlaying>(relaxed = true) {
      every { book } returns bookFlow
      every { bookPosition } returns positionFlow
    }

  private fun viewModel() =
    CurrentlyPlayingViewModel(
      bookRepository = mockk<IBookRepository>(relaxed = true),
      trackRepository = mockk<ITrackRepository>(relaxed = true),
      sleepTimerBus = SleepTimerBus(),
      mediaServiceConnection = mockk<MediaServiceConnection>(relaxed = true),
      prefsRepo = prefsRepo,
      plexConfig = mockk<PlexConfig>(relaxed = true),
      currentlyPlaying = currentlyPlaying,
      workManager = mockk<WorkManager>(relaxed = true),
      bookmarkRepository = bookmarkRepository,
      settings = testSettingsDataStore(),
      exceptionHandler = testExceptionHandler(),
      appContext = mockk<Context>(relaxed = true),
    )

  // ---- jump icons ----

  @Test
  fun `each offered forward interval has its own icon`() {
    val expected =
      mapOf(
        10L to R.drawable.ic_forward_10_white,
        15L to R.drawable.ic_forward_15_white,
        20L to R.drawable.ic_forward_20_white,
        30L to R.drawable.ic_forward_30_white,
        60L to R.drawable.ic_forward_60_white,
        90L to R.drawable.ic_forward_90_white,
      )

    expected.forEach { (seconds, icon) ->
      every { prefsRepo.jumpForwardSeconds } returns seconds
      assertEquals("forward $seconds s", icon, viewModel().makeJumpForwardsIcon())
    }
  }

  @Test
  fun `each offered backward interval has its own icon`() {
    val expected =
      mapOf(
        10L to R.drawable.ic_replay_10_white,
        15L to R.drawable.ic_replay_15_white,
        20L to R.drawable.ic_replay_20_white,
        30L to R.drawable.ic_replay_30_white,
        60L to R.drawable.ic_replay_60_white,
        90L to R.drawable.ic_replay_90_white,
      )

    expected.forEach { (seconds, icon) ->
      every { prefsRepo.jumpBackwardSeconds } returns seconds
      assertEquals("backward $seconds s", icon, viewModel().makeJumpBackwardsIcon())
    }
  }

  /**
   * The fallbacks are **asymmetric on purpose** — forward lands on 30s, backward on 10s — which
   * matches the defaults and the usual listening gesture: skip a chunk ahead, nudge a little back.
   * Pinned because a value outside the six is reachable through settings import (import validates
   * keys, and these are longs written straight through), and because the asymmetry looks like a
   * typo to anyone tidying the two `when`s into one.
   */
  @Test
  fun `an unrecognised interval falls back per direction`() {
    every { prefsRepo.jumpForwardSeconds } returns 45L
    every { prefsRepo.jumpBackwardSeconds } returns 45L

    val vm = viewModel()

    assertEquals(R.drawable.ic_forward_30_white, vm.makeJumpForwardsIcon())
    assertEquals(R.drawable.ic_replay_10_white, vm.makeJumpBackwardsIcon())
  }

  // ---- bookmarks ----

  @Test
  fun `adding a bookmark stores it at the current book position`() =
    runTest {
      val stored = Bookmark(id = "b1", bookId = "1001", position = BookOffset(12_345L))
      coEvery { bookmarkRepository.add("1001", BookOffset(12_345L), any(), any()) } returns stored

      val vm = viewModel()
      vm.addBookmark()
      advanceUntilIdle()

      coVerify { bookmarkRepository.add("1001", BookOffset(12_345L), any(), any()) }
      assertEquals(stored, vm.bookmarkAdded.value?.peekContent())
    }

  /**
   * With nothing playing there is no book to attach the note to, so the repository must not be
   * called at all — an empty `bookId` would store an orphan row that no screen ever lists.
   */
  @Test
  fun `adding a bookmark with no book playing stores nothing`() =
    runTest {
      bookFlow.value = EMPTY_AUDIOBOOK

      val vm = viewModel()
      vm.addBookmark()
      advanceUntilIdle()

      coVerify(exactly = 0) { bookmarkRepository.add(any(), any(), any(), any()) }
      assertNull(vm.bookmarkAdded.value)
    }

  /**
   * The user pressed a button; a failure they cannot see is worse than the failure itself, because
   * they will believe the position is saved. Bookmarks have no server copy to recover from.
   */
  @Test
  fun `a failed bookmark tells the user rather than failing silently`() =
    runTest {
      coEvery { bookmarkRepository.add(any(), any(), any(), any()) } throws
        IllegalStateException("db closed")

      val vm = viewModel()
      vm.addBookmark()
      advanceUntilIdle()

      assertNull("no bookmark event on failure", vm.bookmarkAdded.value)
      assertNotNull("the failure must surface", vm.showUserMessage.value)
    }

  @Test
  fun `deleting a bookmark delegates to the repository`() =
    runTest {
      val vm = viewModel()

      vm.deleteBookmark("b1")
      advanceUntilIdle()

      coVerify { bookmarkRepository.delete("b1") }
    }

  @Test
  fun `saving a note delegates to the repository`() =
    runTest {
      val vm = viewModel()

      vm.setBookmarkNote("b1", "the twist")
      advanceUntilIdle()

      coVerify { bookmarkRepository.updateNote("b1", "the twist") }
    }
}
