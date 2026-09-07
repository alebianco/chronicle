package io.github.mattpvaughn.chronicle.features.login

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.model.OAuthResponse
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.recordingExceptionHandler
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * `LoginViewModel`, which had no test at all before this.
 *
 * Not because it was hard to test but because it was *impossible*: every one of its coroutine
 * launches read `Injector.get().unhandledExceptionHandler()`, and `ChronicleApplication.get()` is
 * `INSTANCE!!` — so a plain JVM test died on NPE the moment it called anything. The whole
 * `features/login` package sat at 0% for that reason. Constructing it with its dependencies is
 * what makes these assertions possible.
 */
class LoginViewModelTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val pin = OAuthResponse(id = 42L, clientIdentifier = "client-1", code = "abcd")

  private fun repo(): IPlexLoginRepo =
    mockk<IPlexLoginRepo>(relaxed = true) {
      every { loginEvent } returns MutableStateFlow(Event(IPlexLoginRepo.LoginState.NOT_LOGGED_IN))
    }

  private fun viewModel(
    loginRepo: IPlexLoginRepo = repo(),
    handler: kotlinx.coroutines.CoroutineExceptionHandler = testExceptionHandler(),
  ) = LoginViewModel(loginRepo, handler)

  @Test
  fun `requesting an oauth pin publishes it`() =
    runTest {
      val loginRepo = repo()
      coEvery { loginRepo.postOAuthPin() } returns pin

      val vm = viewModel(loginRepo)
      vm.loginWithOAuth()
      advanceUntilIdle()

      assertEquals(pin, vm.authEvent.value?.peekContent())
    }

  /**
   * The failure path matters more than the happy one here: a login that silently does nothing
   * leaves the user staring at a spinner with no way to tell whether to wait or retry.
   */
  @Test
  fun `a failed pin request tells the user instead of failing silently`() =
    runTest {
      val loginRepo = repo()
      coEvery { loginRepo.postOAuthPin() } throws IOException("no network")

      val vm = viewModel(loginRepo)
      vm.loginWithOAuth()
      advanceUntilIdle()

      val message = vm.errorEvent.value?.peekContent()
      assertNotNull("expected an error event for the user", message)
      assertTrue("expected the cause in the message, got: $message", message!!.contains("no network"))
      assertNull("a failed login must not publish a pin", vm.authEvent.value)
    }

  /**
   * `checkForAccess` is gated on the custom tab having been launched — polling before the user has
   * had a chance to approve anything would ask plex.tv about a pin nobody has seen.
   */
  @Test
  fun `access is not polled before the login tab has been launched`() =
    runTest {
      val loginRepo = repo()

      viewModel(loginRepo).checkForAccess()
      advanceUntilIdle()

      coVerify(exactly = 0) { loginRepo.checkForOAuthAccessToken() }
    }

  @Test
  fun `access is polled once the login tab has been launched`() =
    runTest {
      val loginRepo = repo()

      val vm = viewModel(loginRepo)
      vm.setLaunched(true)
      vm.checkForAccess()
      advanceUntilIdle()

      coVerify(exactly = 1) { loginRepo.checkForOAuthAccessToken() }
    }

  /**
   * The injected handler is the one actually installed on the scope.
   *
   * `checkForOAuthAccessToken` has no try/catch of its own, so a throw there reaches the
   * `CoroutineExceptionHandler` — which is the whole reason the handler is a constructor parameter
   * rather than something fetched from the service locator.
   */
  @Test
  fun `an unhandled failure reaches the injected handler`() =
    runTest {
      val (handler, caught) = recordingExceptionHandler()
      val loginRepo = repo()
      coEvery { loginRepo.checkForOAuthAccessToken() } throws IOException("server gone")

      val vm = viewModel(loginRepo, handler)
      vm.setLaunched(true)
      vm.checkForAccess()
      advanceUntilIdle()

      assertEquals(1, caught.size)
      assertEquals("server gone", caught.single().message)
    }
}
