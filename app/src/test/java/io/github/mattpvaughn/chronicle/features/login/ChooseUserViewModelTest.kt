package io.github.mattpvaughn.chronicle.features.login

import io.github.mattpvaughn.chronicle.data.model.LoadingStatus
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexLoginService
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexUser
import io.github.mattpvaughn.chronicle.testing.responseException
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * First tests for [ChooseUserViewModel], which sat at **0% instruction coverage** — 476 missed
 * instructions on the screen that authenticates a Plex home user.
 *
 * Worth covering ahead of the other zero-coverage ViewModels because the decisions here are about
 * **credentials**: whether a PIN is demanded, what happens to a rejected one, and whether a
 * response with no auth token is allowed to count as a successful login.
 *
 * Only three dependencies, all interfaces, all constructor-injected — the class was always
 * testable. `Dispatchers.Main` was the sole blocker, which [MainDispatcherRule] pays for once.
 */
class ChooseUserViewModelTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val withPassword =
    PlexUser(uuid = "uuid-locked", title = "Alex", hasPassword = true)
  private val withoutPassword =
    PlexUser(uuid = "uuid-open", title = "Sam", hasPassword = false)

  private fun authed(token: String? = "token-abc") = PlexUser(uuid = "uuid-open", title = "Sam", hasPassword = false, authToken = token)

  private fun httpException(code: Int) = responseException(code)

  private val loginRepo = mockk<IPlexLoginRepo>(relaxed = true)

  private fun viewModel(service: PlexLoginService) =
    ChooseUserViewModel(
      plexLoginService = service,
      plexLoginRepo = loginRepo,
      // A real handler, not a mock: CoroutineExceptionHandler carries a `key` that the
      // coroutine machinery reads during context folding, and a mock returns null for it.
      exceptionHandler = CoroutineExceptionHandler { _, _ -> },
    )

  /**
   * `hasPassword` is the whole gate. A user carrying one must reach the PIN screen and **no
   * request may be sent** — sending one with a null PIN would burn an attempt against Plex and
   * return a 403 the user never caused.
   */
  @Test
  fun `picking a user with a password shows the pin screen and sends nothing`() =
    runTest {
      val service = mockk<PlexLoginService>(relaxed = true)
      val vm = viewModel(service)

      vm.pickUser(withPassword)
      advanceUntilIdle()

      assertTrue(vm.showPin.value)
      assertEquals(withPassword, vm.user.value)
      coVerify(exactly = 0) { service.pickUser(any(), any()) }
    }

  @Test
  fun `picking a user without a password signs straight in`() =
    runTest {
      val service =
        mockk<PlexLoginService> {
          coEvery { pickUser("uuid-open", null) } returns authed()
        }
      val vm = viewModel(service)

      vm.pickUser(withoutPassword)
      advanceUntilIdle()

      assertFalse("a user with no password must never see the pin screen", vm.showPin.value)
      coVerify { loginRepo.chooseUser(authed()) }
    }

  /**
   * The token guard, which is the security-relevant one.
   *
   * Plex can answer 200 with a user object carrying no `authToken`. Treating that as success
   * would advance the login state with nothing to authenticate later requests with — the empty
   * token then wins the precedence chain in `PlaybackSession.authToken` and every media request
   * goes out unauthenticated (the same defect as the empty-token auth bug, in a different place).
   */
  @Test
  fun `a response with no auth token is not a successful login`() =
    runTest {
      val service =
        mockk<PlexLoginService> {
          coEvery { pickUser(any(), any()) } returns authed(token = null)
        }
      val vm = viewModel(service)

      vm.pickUser(withoutPassword)
      advanceUntilIdle()

      coVerify(exactly = 0) { loginRepo.chooseUser(any()) }
      assertEquals(LoadingStatus.ERROR, vm.pinLoadingStatus.value)
    }

  @Test
  fun `an empty auth token is refused as firmly as a null one`() =
    runTest {
      val service =
        mockk<PlexLoginService> {
          coEvery { pickUser(any(), any()) } returns authed(token = "")
        }
      val vm = viewModel(service)

      vm.pickUser(withoutPassword)
      advanceUntilIdle()

      coVerify(exactly = 0) { loginRepo.chooseUser(any()) }
      assertEquals(LoadingStatus.ERROR, vm.pinLoadingStatus.value)
    }

  /**
   * 403 is the *wrong PIN* case and gets its own message; every other code shares a generic one.
   * Pinned because the two are a single `when` and collapsing them would tell a user with a
   * server-side failure that their PIN was wrong.
   */
  @Test
  fun `a rejected pin reports itself as incorrect rather than as a server error`() =
    runTest {
      val service =
        mockk<PlexLoginService> {
          coEvery { pickUser(any(), any()) } throws httpException(403)
        }
      val vm = viewModel(service)

      vm.pickUser(withoutPassword)
      advanceUntilIdle()

      val message = vm.userMessage.value?.peekContent().orEmpty()
      assertTrue("expected an incorrect-pin message, got: $message", message.contains("Incorrect"))
      assertEquals(LoadingStatus.ERROR, vm.pinLoadingStatus.value)
    }

  @Test
  fun `a server error is not reported as an incorrect pin`() =
    runTest {
      val service =
        mockk<PlexLoginService> {
          coEvery { pickUser(any(), any()) } throws httpException(500)
        }
      val vm = viewModel(service)

      vm.pickUser(withoutPassword)
      advanceUntilIdle()

      val message = vm.userMessage.value?.peekContent().orEmpty()
      assertFalse("a 500 must not blame the user's pin: $message", message.contains("Incorrect"))
      assertTrue(message.contains("500"))
    }

  @Test
  fun `a pin shorter than four digits is reported before submission`() =
    runTest {
      val vm = viewModel(mockk(relaxed = true))

      vm.setPinData("12")
      assertEquals("Too short", vm.pinErrorMessage.value)

      vm.setPinData("1234")
      assertEquals("", vm.pinErrorMessage.value)
    }

  @Test
  fun `dismissing the pin screen hides it`() =
    runTest {
      val vm = viewModel(mockk(relaxed = true))
      vm.pickUser(withPassword)
      advanceUntilIdle()
      assertTrue(vm.showPin.value)

      vm.hidePinScreen()

      assertFalse(vm.showPin.value)
    }

  /** `submitPin` reads the uuid off the *selected* user, so with none picked it must no-op. */
  @Test
  fun `submitting a pin with no user selected sends nothing`() =
    runTest {
      val service = mockk<PlexLoginService>(relaxed = true)
      val vm = viewModel(service)

      vm.submitPin()
      advanceUntilIdle()

      coVerify(exactly = 0) { service.pickUser(any(), any()) }
    }
}
