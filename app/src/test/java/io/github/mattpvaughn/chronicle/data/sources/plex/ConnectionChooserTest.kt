package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Choosing the best usable route to the server.
 *
 * The code this replaces started every attempt at once and then polled them every 500ms,
 * so it was not tiered at all: sorting a list whose elements all begin simultaneously only
 * changes the order completions are *noticed*, and a relay answering in 80ms beat a LAN
 * address answering in 120ms.
 *
 * `probe` is injected, so these cases never touch Retrofit — the trade-off is that the real
 * `checkServer` wiring is not covered here (see cu-73).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionChooserTest {
  private val lan = Connection("https://lan:32400", local = true)
  private val direct = Connection("https://direct:32400", local = false)
  private val relay = Connection("https://relay:443", local = false, relay = true)

  @Test
  fun `LAN is preferred when every tier works`() =
    runTest {
      val chosen = chooser().choose(listOf(relay, direct, lan)) { true }

      assertEquals("input order must not decide the winner", lan, chosen)
    }

  @Test
  fun `a dead LAN address falls through to direct`() =
    runTest {
      val chosen = chooser().choose(listOf(lan, direct, relay)) { it != lan }

      assertEquals(direct, chosen)
    }

  @Test
  fun `relay is used only when nothing else answers`() =
    runTest {
      val chosen = chooser().choose(listOf(lan, direct, relay)) { it == relay }

      assertEquals(relay, chosen)
    }

  @Test
  fun `null when no connection answers`() =
    runTest {
      assertNull(chooser().choose(listOf(lan, direct, relay)) { false })
    }

  @Test
  fun `null for an empty connection list`() =
    runTest {
      assertNull(chooser().choose(emptyList()) { true })
    }

  @Test
  fun `a probe that throws counts as a failure, not a crash`() =
    runTest {
      val chosen =
        chooser().choose(listOf(lan, direct)) { conn ->
          if (conn == lan) throw IOException("no route to host")
          true
        }

      assertEquals(direct, chosen)
    }

  /**
   * The reason the budget exists: a LAN address that hangs must not hold the whole attempt
   * for the full connect timeout, which is what burned 15 seconds before relay was even
   * tried.
   */
  @Test
  fun `a hanging LAN address does not block the lower tiers`() =
    runTest {
      val chosen =
        chooser().choose(listOf(lan, relay), tierBudgetMs = 100L) { conn ->
          if (conn == lan) delay(60_000)
          true
        }

      assertEquals(relay, chosen)
    }

  /**
   * And the converse, which a plain "try tiers in sequence" design would get wrong: a slow
   * but working LAN address is still worth a short wait over a relay that already answered.
   */
  @Test
  fun `a slow LAN address still beats relay inside the budget`() =
    runTest {
      val chosen =
        chooser().choose(listOf(lan, relay), tierBudgetMs = 1_000L) { conn ->
          if (conn == lan) delay(200)
          true
        }

      assertEquals(lan, chosen)
    }

  @Test
  fun `a single LAN connection is awaited rather than budgeted away`() =
    runTest {
      // A LAN-only server is the common self-hosted case: there is no lower tier to fall
      // back to, so a slow answer must still be waited for.
      val chosen =
        chooser().choose(listOf(lan), tierBudgetMs = 50L) {
          delay(500)
          true
        }

      assertEquals(lan, chosen)
    }

  private fun TestScope.chooser() = ConnectionChooser(TestDispatcherProvider(testScheduler))
}
