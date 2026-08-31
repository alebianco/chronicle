package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.github.mattpvaughn.chronicle.data.sources.plex.model.ConnectionTier
import io.github.mattpvaughn.chronicle.data.sources.plex.model.tier
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Picks the best usable route to the Plex server: LAN, then direct WAN, then relay.
 *
 * Replaces a loop that launched every attempt simultaneously and then polled them every
 * 500ms. That was not tiered despite appearing to be — sorting a list whose elements all
 * start at once only changes the order in which completions are *noticed*, so a relay
 * answering in 80ms beat a LAN address answering in 120ms (cu-11, #103/#98).
 *
 * Tiers are tried best-first with a **budget** rather than strictly in sequence. Waiting out
 * a full LAN timeout before trying anything else would just relocate the old stall: instead
 * each tier's attempts start in parallel, and if none has answered within [TIER_BUDGET_MS]
 * the next tier starts too *while the earlier attempts keep running*. So a dead LAN address
 * costs a budget rather than a connect timeout, and a slow-but-working one can still win.
 */
class ConnectionChooser
  @Inject
  constructor(private val dispatchers: DispatcherProvider) {
    /**
     * @param probe returns true when [Connection] is reachable. Injected so this is
     *   testable without Retrofit; production passes a `checkServer` call.
     * @return the winning connection, or null when nothing answered.
     */
    suspend fun choose(
      connections: List<Connection>,
      tierBudgetMs: Long = TIER_BUDGET_MS,
      probe: suspend (Connection) -> Boolean,
    ): Connection? =
      coroutineScope {
        val byTier = connections.groupBy { it.tier }
        val tiersPresent = ConnectionTier.entries.filter { byTier.containsKey(it) }
        val running = mutableListOf<Deferred<Connection?>>()

        tiersPresent.forEachIndexed { index, tier ->
          val inTier = byTier.getValue(tier)
          Timber.i("Trying ${inTier.size} $tier connection(s)")

          inTier.forEach { connection ->
            running +=
              async(dispatchers.io) {
                runCatching { if (probe(connection)) connection else null }
                  .onFailure {
                    Timber.i("Connection failed: ${connection.uri} (${it.message})")
                  }
                  .getOrNull()
              }
          }

          // On the last tier there is nothing left to widen to, so wait for a real answer
          // instead of expiring a budget — a LAN-only server must not be given up on.
          val isLastTier = index == tiersPresent.lastIndex
          val winner =
            if (isLastTier) {
              awaitFirstSuccess(running)
            } else {
              withTimeoutOrNull(tierBudgetMs) { awaitFirstSuccess(running) }
            }

          if (winner != null) {
            Timber.i("Chose ${winner.tier} connection: ${winner.uri}")
            return@coroutineScope winner
          }
        }

        Timber.w("No connection answered out of ${connections.size}")
        null
      }

    /**
     * The first attempt to report success, ignoring failures.
     *
     * Rebuilds its pending list from [attempts] on every call, because the caller invokes it
     * again after a budget expires with more attempts added — reusing a partially drained
     * list would silently ignore the tier that was just started.
     *
     * Returns null once every attempt has settled without a success.
     */
    private suspend fun awaitFirstSuccess(attempts: List<Deferred<Connection?>>): Connection? {
      val pending = attempts.toMutableList()
      while (pending.isNotEmpty()) {
        val (settled, result) =
          select<Pair<Deferred<Connection?>, Connection?>> {
            pending.forEach { attempt -> attempt.onAwait { attempt to it } }
          }
        pending.remove(settled)
        if (result != null) return result
      }
      return null
    }

    companion object {
      /**
       * How long to give the tiers started so far before also starting the next one down.
       *
       * Deliberately short: the point is to stop a dead LAN address consuming the whole
       * connect timeout before relay is even attempted. A working route on a home network
       * answers well inside this.
       */
      const val TIER_BUDGET_MS = 1_500L
    }
  }
