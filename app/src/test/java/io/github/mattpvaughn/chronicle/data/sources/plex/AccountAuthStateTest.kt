package io.github.mattpvaughn.chronicle.data.sources.plex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signed-out signal itself.
 *
 * Small, but it is the single piece of state the whole cu-84 fix hangs on: `PlexTokenAuthenticator`
 * writes it, `PlexLoginRepo.determineLoginState` reads it, and the settings "Sign in again" entry
 * clears it. Its transitions are worth pinning independently of the two collaborators, so a failure
 * says *which* part broke.
 */
class AccountAuthStateTest {
  @Test
  fun `a fresh state is not signed out`() {
    assertFalse(
      "the default must be optimistic, or a first launch reports a problem it has not seen",
      AccountAuthState().isSignedOut.value,
    )
  }

  @Test
  fun `a rejection sets the signed-out state`() {
    val state = AccountAuthState()

    state.onAccountRejected()

    assertTrue(state.isSignedOut.value)
  }

  @Test
  fun `authenticating clears the signed-out state`() {
    val state = AccountAuthState()
    state.onAccountRejected()

    state.onAuthenticated()

    assertFalse(state.isSignedOut.value)
  }

  /** A repeated rejection must not toggle it back off. */
  @Test
  fun `repeated rejections stay signed out`() {
    val state = AccountAuthState()

    state.onAccountRejected()
    state.onAccountRejected()

    assertTrue(state.isSignedOut.value)
  }

  @Test
  fun `authenticating from a clean state is a no-op`() {
    val state = AccountAuthState()

    state.onAuthenticated()

    assertFalse(state.isSignedOut.value)
  }

  /**
   * Recovery must be repeatable: a token can be invalidated, restored, and invalidated again
   * (a second password change), and each transition has to land.
   */
  @Test
  fun `the state survives a full rejection recovery rejection cycle`() {
    val state = AccountAuthState()

    state.onAccountRejected()
    assertTrue(state.isSignedOut.value)
    state.onAuthenticated()
    assertFalse(state.isSignedOut.value)
    state.onAccountRejected()
    assertTrue("a second invalidation must be noticed too", state.isSignedOut.value)
  }
}
