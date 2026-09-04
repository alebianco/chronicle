package io.github.mattpvaughn.chronicle.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Collects [flow] while [owner] is at least STARTED, cancelling when it is not (cu-52).
 *
 * The `Flow` counterpart of `liveData.observe(viewLifecycleOwner) { … }`, and the reason it is a
 * helper rather than 121 hand-written blocks: the correct form is
 * `lifecycleScope.launch { repeatOnLifecycle(STARTED) { flow.collect { … } } }`, and the plausible
 * wrong forms are subtly broken rather than obviously so.
 *
 * - `lifecycleScope.launchWhenStarted` **suspends** the coroutine while stopped rather than
 *   cancelling it, so an upstream `Flow` keeps producing into a buffer and the screen renders a
 *   burst of stale values on return. It is deprecated for that reason.
 * - A bare `lifecycleScope.launch { flow.collect { … } }` never stops collecting at all, so a
 *   backgrounded screen keeps doing work — the thing cu-110 measured.
 *
 * In a `Fragment`, pass `viewLifecycleOwner`, **never** `this`: a fragment outlives its view across
 * a detach, and a collector bound to the fragment writes into a destroyed binding.
 */
fun <T> LifecycleOwner.collectWhileStarted(
  flow: Flow<T>,
  onEach: (T) -> Unit,
) {
  lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
      flow.collect { onEach(it) }
    }
  }
}

/**
 * Collects an [Event] flow while [owner] is at least STARTED, delivering each payload **once**.
 *
 * The `Flow` counterpart of `observeEvent`. The one-shot guarantee is [Event]'s, not the flow's:
 * `getContentIfNotHandled` returns null for an event already delivered, so a re-collect after a
 * configuration change does not re-show a Toast or re-navigate. That matters more here than it did
 * with `LiveData`, because a `StateFlow` replays its current value to *every* new collector — so
 * without the [Event] wrapper, rotating the screen would replay the last error message.
 *
 * The flow's element is nullable so a state holder with no meaningful "empty" value — an error
 * event, say — can seed itself `null` rather than inventing a blank one to publish.
 */
fun <T> LifecycleOwner.collectEventsWhileStarted(
  flow: Flow<Event<T>?>,
  onEach: (T) -> Unit,
) {
  collectWhileStarted(flow) { event -> event?.getContentIfNotHandled()?.let(onEach) }
}
