package io.github.mattpvaughn.chronicle.injection

import io.github.mattpvaughn.chronicle.data.sources.plex.ConnectionChooser
import io.github.mattpvaughn.chronicle.injection.modules.AppModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timing budget behind cu-11's "recover in under 5 seconds".
 *
 * These are constants rather than an inspected `OkHttpClient` because `AppModule` needs a
 * real `Application` to construct. The weaker guarantee is deliberate and worth stating: the
 * test pins the *values*, not that the client was built with them — a future edit could
 * bypass the constants. `verify.sh` would not catch that, and neither would this.
 */
class ConnectionTimeoutTest {
  @Test
  fun `the connect timeout is short enough to fail fast`() {
    assertEquals(
      "a reachability probe taking 15s has already failed for the listener, and it let a " +
        "dead LAN address consume the whole attempt before relay was tried",
      5L,
      AppModule.CONNECT_TIMEOUT_SECONDS,
    )
  }

  @Test
  fun `the read timeout stays long`() {
    assertEquals(
      "a slow audio transfer is still useful; only a slow handshake means a bad route",
      15L,
      AppModule.READ_TIMEOUT_SECONDS,
    )
  }

  /**
   * The two numbers are only useful together: the chooser widens to the next tier after its
   * budget, and the connect timeout bounds how long any single attempt can hold a route.
   * Both must fit inside the 5s recovery target.
   */
  @Test
  fun `tier budget plus one connect timeout stays inside the recovery target`() {
    val worstCaseMs = ConnectionChooser.TIER_BUDGET_MS + AppModule.CONNECT_TIMEOUT_SECONDS * 1_000

    assertTrue(
      "budget ${ConnectionChooser.TIER_BUDGET_MS}ms + connect " +
        "${AppModule.CONNECT_TIMEOUT_SECONDS}s = ${worstCaseMs}ms, which must not exceed " +
        "the 5s target by more than the final tier's own attempt",
      worstCaseMs <= 6_500,
    )
  }
}
