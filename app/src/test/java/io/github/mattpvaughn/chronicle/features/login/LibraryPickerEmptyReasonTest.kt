package io.github.mattpvaughn.chronicle.features.login

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.github.mattpvaughn.chronicle.data.model.LoadingStatus
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainer
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainerWrapper
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Why the library picker is empty.
 *
 * All three causes used to render the layout's static "No libraries found" — a claim about the
 * *server's contents*, and wrong in two of them. The owner hit the worst case during the cu-73
 * live pass: a TLS hostname mismatch after a certificate rotation, reported as though the server
 * had no audiobook libraries, with a retry button that could only fail again (cu-125).
 *
 * It reads as plausible rather than broken because account and server selection succeed first —
 * those are answered by plex.tv, while libraries come from the server itself. So nothing hints at
 * a connection problem, and the remedy (restart Plex Media Server) is unguessable.
 */
class LibraryPickerEmptyReasonTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val connectionState = MutableLiveData(PlexConfig.ConnectionState.CONNECTING)
  private val isConnected = MutableLiveData(false)

  private val plexConfig =
    mockk<PlexConfig>(relaxed = true) {
      every { this@mockk.connectionState } returns this@LibraryPickerEmptyReasonTest.connectionState
      every { this@mockk.isConnected } returns this@LibraryPickerEmptyReasonTest.isConnected
      every { url } returns "https://example.plex.direct:32400"
    }

  private fun viewModel(mediaService: PlexMediaService) =
    ChooseLibraryViewModel(
      mediaService,
      plexConfig,
      mockk(relaxed = true),
      mockk(relaxed = true),
      mockk(relaxed = true),
      mockk(relaxed = true),
      mockk(relaxed = true),
    )

  private fun serviceReturning(directories: List<Any>) =
    mockk<PlexMediaService>(relaxed = true) {
      coEvery { retrieveLibraries() } returns
        PlexMediaContainerWrapper(PlexMediaContainer(size = directories.size.toLong()))
    }

  @Test
  fun `a connection failure is reported as cannot connect, not as an empty library`() =
    runTest {
      val vm = viewModel(serviceReturning(emptyList()))

      connectionState.value = PlexConfig.ConnectionState.CONNECTION_FAILED

      assertEquals(
        "a TLS or network failure must not read as 'this server has no libraries'",
        ChooseLibraryViewModel.EmptyReason.CANNOT_CONNECT,
        vm.emptyReason.value,
      )
    }

  @Test
  fun `a failed library request is distinguished from a failed connection`() =
    runTest {
      val service =
        mockk<PlexMediaService>(relaxed = true) {
          coEvery { retrieveLibraries() } throws IOException("server hung up")
        }
      val vm = viewModel(service)

      connectionState.value = PlexConfig.ConnectionState.CONNECTED
      isConnected.value = true
      // loadLibraries launches into viewModelScope; let it run before asserting.
      advanceUntilIdle()

      assertEquals(
        ChooseLibraryViewModel.EmptyReason.REQUEST_FAILED,
        vm.emptyReason.value,
      )
    }

  @Test
  fun `a server that answers with no audiobook libraries says exactly that`() =
    runTest {
      // The one case where the original message was true.
      val vm = viewModel(serviceReturning(emptyList()))
      // DoubleLiveData only computes while observed; without this `loadingStatus` stays null.
      vm.loadingStatus.observeForever {}

      connectionState.value = PlexConfig.ConnectionState.CONNECTED
      isConnected.value = true
      advanceUntilIdle()

      assertEquals(
        ChooseLibraryViewModel.EmptyReason.NO_LIBRARIES,
        vm.emptyReason.value,
      )
      assertEquals(
        "an empty-but-successful answer is still an error state for the picker",
        LoadingStatus.ERROR,
        vm.loadingStatus.value,
      )
    }
}
