package io.github.mattpvaughn.chronicle.features.login

import io.github.mattpvaughn.chronicle.data.model.LoadingStatus
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainer
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainerWrapper
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
 * *server's contents*, and wrong in two of them. The owner hit the worst case during a live pass:
 * a TLS hostname mismatch after a certificate rotation, reported as though the server
 * had no audiobook libraries, with a retry button that could only fail again.
 *
 * It reads as plausible rather than broken because account and server selection succeed first —
 * those are answered by plex.tv, while libraries come from the server itself. So nothing hints at
 * a connection problem, and the remedy (restart Plex Media Server) is unguessable.
 */
class LibraryPickerEmptyReasonTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val connectionState = MutableStateFlow(PlexConfig.ConnectionState.CONNECTING)
  private val isConnected = MutableStateFlow(false)

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
      // The ViewModel's init collector is *scheduled* on the test dispatcher, not run, so the
      // state change below needs draining before its effect can be read.
      advanceUntilIdle()

      connectionState.value = PlexConfig.ConnectionState.CONNECTION_FAILED
      advanceUntilIdle()

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
      // `loadingStatus` is `stateIn(WhileSubscribed)`, so it computes only while collected —
      // without a collector it would sit on its seed. Same reason the LiveData version needed an
      // `observeForever`; `backgroundScope` unsubscribes when the test ends.
      backgroundScope.launch { vm.loadingStatus.collect {} }

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
