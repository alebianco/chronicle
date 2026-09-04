package io.github.mattpvaughn.chronicle.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/*
 * The `Flow` replacements for `DoubleLiveData` and friends (cu-52).
 *
 * Those were hand-rolled `MediatorLiveData` subclasses whose whole job — combine N sources, publish
 * only when the result changed — is what `combine` plus `distinctUntilChanged` does natively. Kept
 * as named helpers rather than inlined at 23 call sites so the **dedup stays mandatory**: that is
 * not an optimisation, it is the cu-110 fix.
 *
 * `Flow`'s `combine` also improves on the originals in one way that matters here: it waits for
 * *every* source to emit before producing anything, where `DoubleLiveData` published immediately
 * with `null`s for the sources that had not arrived. Several `FirstFrameFlashTest` cases exist
 * because of exactly that — a combinator emitting a default-shaped value for one frame.
 */

/**
 * How long a `stateIn` keeps its upstream alive after the last collector goes away.
 *
 * Five seconds is the standard `WhileSubscribed` timeout: long enough to survive a configuration
 * change — a rotation would otherwise re-run every Room query and re-do whatever grouping or
 * sorting the flow does — and short enough that a screen the user actually left stops collecting.
 *
 * Shared so every screen makes the same trade. `Eagerly` is the alternative and is right only for
 * process-lifetime state with no upstream to release (see `PlexConfig.isConnected`).
 */
const val STOP_TIMEOUT_MILLIS = 5_000L

/**
 * Combines two flows, emitting only when the combined result changes.
 *
 * Equality is the result's own `equals`, so a combiner returning a data class or a primitive
 * dedupes correctly. One returning a fresh mutable list every call will not — such a combiner
 * should project to something comparable (see `booksKey`), which is the same caveat the
 * `DoubleLiveData` this replaces carried.
 */
fun <T, K, S> combineDistinct(
  source1: Flow<T>,
  source2: Flow<K>,
  combiner: (T, K) -> S,
): Flow<S> = combine(source1, source2, combiner).distinctUntilChanged()

/** Three-source [combineDistinct]. */
fun <T, K, S, R> combineDistinct(
  source1: Flow<T>,
  source2: Flow<K>,
  source3: Flow<S>,
  combiner: (T, K, S) -> R,
): Flow<R> = combine(source1, source2, source3, combiner).distinctUntilChanged()

/** Four-source [combineDistinct]. */
fun <T, K, S, Q, R> combineDistinct(
  source1: Flow<T>,
  source2: Flow<K>,
  source3: Flow<S>,
  source4: Flow<Q>,
  combiner: (T, K, S, Q) -> R,
): Flow<R> = combine(source1, source2, source3, source4, combiner).distinctUntilChanged()

/**
 * Four-source combine whose combiner is suspending and runs off the main thread.
 *
 * Replaces `QuadLiveDataAsync`, which launched into an injected scope and hopped to
 * `Dispatchers.IO` by hand. `flowOn` does that upstream-only, so the collector still resumes on
 * whichever dispatcher it was collected from — no `postValue` and no scope to inject.
 *
 * The dispatcher is a parameter rather than `Dispatchers.IO` so it obeys convention 4 (inject a
 * `DispatcherProvider`, cu-15) and a test can control it.
 */
fun <T, K, S, Q, R> combineDistinctAsync(
  source1: Flow<T>,
  source2: Flow<K>,
  source3: Flow<S>,
  source4: Flow<Q>,
  dispatcher: kotlin.coroutines.CoroutineContext,
  combiner: suspend (T, K, S, Q) -> R,
): Flow<R> =
  combine(source1, source2, source3, source4, combiner)
    .flowOn(dispatcher)
    .distinctUntilChanged()

/**
 * Five-source [combineDistinctAsync].
 *
 * `combine` has no arity-5 overload with typed parameters, so this goes through the vararg form and
 * casts positionally. Contained here rather than at the call site precisely because that cast is
 * unchecked: one place to get wrong, and the types at the boundary are still checked by the
 * signature.
 */
@Suppress("UNCHECKED_CAST")
fun <T, K, S, Q, P, R> combineDistinctAsync(
  source1: Flow<T>,
  source2: Flow<K>,
  source3: Flow<S>,
  source4: Flow<Q>,
  source5: Flow<P>,
  dispatcher: kotlin.coroutines.CoroutineContext,
  combiner: suspend (T, K, S, Q, P) -> R,
): Flow<R> =
  combine(source1, source2, source3, source4, source5) { values ->
    combiner(values[0] as T, values[1] as K, values[2] as S, values[3] as Q, values[4] as P)
  }.flowOn(dispatcher).distinctUntilChanged()
