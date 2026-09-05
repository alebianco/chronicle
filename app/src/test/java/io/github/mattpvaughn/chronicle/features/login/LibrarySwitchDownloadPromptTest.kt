package io.github.mattpvaughn.chronicle.features.login

import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.FormattableString
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Who gets asked about their downloads when a library changes, and who does not.
 *
 * Settings prompts before switching; the login picker did not, so the previous library's downloads
 * were reclaimed silently at some later launch by `CachedFileManager`'s orphan pass — a
 * multi-gigabyte deletion nobody was warned about (cu-130).
 *
 * The gate is cu-126's `replacedDifferentLibrary`, deliberately reused rather than joined by a
 * second signal. It is already false for both cases that must stay silent: a first-ever choice, and
 * a failed re-authentication where the library never changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySwitchDownloadPromptTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private fun library(id: String) = PlexLibrary(name = "Audiobooks", type = MediaType.ARTIST, id = id)

  private fun viewModel(
    replacedDifferentLibrary: Boolean,
    hasDownloads: Boolean,
    cachedFileManager: ICachedFileManager =
      mockk(relaxed = true) {
        coEvery { hasUserCachedTracks() } returns hasDownloads
      },
  ): ChooseLibraryViewModel {
    val loginRepo =
      mockk<IPlexLoginRepo>(relaxed = true) {
        every { chooseLibrary(any()) } returns replacedDifferentLibrary
      }
    return ChooseLibraryViewModel(
      plexMediaService = mockk(relaxed = true),
      // A relaxed PlexConfig hands `isConnected` back as Nothing, and the `init` block collects it.
      plexConfig =
        mockk(relaxed = true) {
          every { isConnected } returns MutableStateFlow(false)
          every { connectionState } returns MutableStateFlow(PlexConfig.ConnectionState.NOT_CONNECTED)
        },
      plexPrefsRepo = mockk(relaxed = true),
      plexLoginRepo = loginRepo,
      bookRepository = mockk(relaxed = true),
      trackRepository = mockk(relaxed = true),
      collectionsRepository = mockk(relaxed = true),
      cachedFileManager = cachedFileManager,
    )
  }

  @Test
  fun `switching to a different library with downloads asks about them`() =
    runTest {
      val vm = viewModel(replacedDifferentLibrary = true, hasDownloads = true)

      vm.chooseLibrary(library("22"))
      advanceUntilIdle()

      assertTrue("a genuine switch with downloads must ask", vm.bottomChooserState.value.shouldShow)
    }

  /**
   * The failed-re-auth case the owner singled out: the user did not choose to switch anything, so
   * asking about deleting their downloads would be alarming and wrong. `chooseLibrary` reports no
   * change because the library id is unchanged.
   */
  @Test
  fun `re-authenticating into the same library never asks`() =
    runTest {
      val cachedFileManager =
        mockk<ICachedFileManager>(relaxed = true) {
          coEvery { hasUserCachedTracks() } returns true
        }
      val vm = viewModel(replacedDifferentLibrary = false, hasDownloads = true, cachedFileManager)

      vm.chooseLibrary(library("14"))
      advanceUntilIdle()

      assertFalse(vm.bottomChooserState.value.shouldShow)
      // And it must not even ask the question of the file manager.
      coVerify(exactly = 0) { cachedFileManager.hasUserCachedTracks() }
    }

  @Test
  fun `a first-ever choice never asks, because no download can exist yet`() =
    runTest {
      val vm = viewModel(replacedDifferentLibrary = false, hasDownloads = false)

      vm.chooseLibrary(library("14"))
      advanceUntilIdle()

      assertFalse(vm.bottomChooserState.value.shouldShow)
    }

  @Test
  fun `a switch with nothing downloaded does not ask a pointless question`() =
    runTest {
      val vm = viewModel(replacedDifferentLibrary = true, hasDownloads = false)

      vm.chooseLibrary(library("22"))
      advanceUntilIdle()

      assertFalse(vm.bottomChooserState.value.shouldShow)
    }

  @Test
  fun `choosing to keep downloads leaves the files alone`() =
    runTest {
      val cachedFileManager =
        mockk<ICachedFileManager>(relaxed = true) {
          coEvery { hasUserCachedTracks() } returns true
        }
      val vm = viewModel(replacedDifferentLibrary = true, hasDownloads = true, cachedFileManager)
      vm.chooseLibrary(library("22"))
      advanceUntilIdle()

      vm.bottomChooserState.value.listener.onItemClicked(FormattableString.yes)
      advanceUntilIdle()

      coVerify(exactly = 0) { cachedFileManager.uncacheAllInLibrary() }
      assertFalse("answering must dismiss the sheet", vm.bottomChooserState.value.shouldShow)
    }

  @Test
  fun `choosing not to keep them deletes them`() =
    runTest {
      val cachedFileManager =
        mockk<ICachedFileManager>(relaxed = true) {
          coEvery { hasUserCachedTracks() } returns true
        }
      val vm = viewModel(replacedDifferentLibrary = true, hasDownloads = true, cachedFileManager)
      vm.chooseLibrary(library("22"))
      advanceUntilIdle()

      vm.bottomChooserState.value.listener.onItemClicked(FormattableString.no)
      advanceUntilIdle()

      coVerify(exactly = 1) { cachedFileManager.uncacheAllInLibrary() }
    }
}
