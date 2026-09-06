package io.github.mattpvaughn.chronicle.util.compose

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.mattpvaughn.chronicle.util.Event
import kotlinx.coroutines.flow.Flow

/**
 * Runs [onEvent] for each unhandled [Event], while the screen is at least STARTED (cu-206).
 *
 * The Compose equivalent of `collectEventsWhileStarted`, which took a `LifecycleOwner` a
 * composable does not have. The STARTED gate is the same and matters for the same reason: a
 * `Toast` raised while the screen is backgrounded appears over whatever the user is looking at
 * instead, and the event is consumed either way — `getContentIfNotHandled` returns it once — so
 * the message would be lost rather than deferred.
 *
 * The flow is nullable-typed to match `collectEventsWhileStarted`, whose `Flow<Event<T>?>` reflects
 * that several of these are `MutableStateFlow(null)` seeded — the seed is not an event.
 *
 * Keyed on [events] only. Re-running this effect on every recomposition would re-subscribe
 * constantly, and a `LaunchedEffect(Unit)` would keep collecting a flow from a screen that has
 * been popped.
 */
@Composable
fun <T> EventEffect(
  events: Flow<Event<T>?>,
  onEvent: (T) -> Unit,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(events, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      events.collect { event ->
        event?.getContentIfNotHandled()?.let(onEvent)
      }
    }
  }
}

/** Shows each event's text as a short `Toast`. */
@Composable
fun ToastEffect(events: Flow<Event<String>?>) {
  val context = LocalContext.current
  EventEffect(events) { message ->
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
  }
}

/**
 * Shows each event's string **resource** as a short `Toast`.
 *
 * A separate overload because several of these are raised on an IO dispatcher, where
 * `Toast.show()` throws — so the ViewModel publishes a resource id and the resolution happens
 * here, on the main thread. That split is why `syncError` and `resumeError` are `Event<Int>`
 * rather than `Event<String>`.
 */
@Composable
fun ToastResEffect(events: Flow<Event<Int>?>) {
  val context = LocalContext.current
  // `resources.getString`, not `stringResource`: the id is not known at composition time — it
  // arrives with the event — so the composable form cannot be used, and reading it through
  // `context.getString` is what lint's `LocalContextGetResourceValueCall` flags. Going through
  // `resources` is the same lookup without the pattern lint cannot distinguish.
  val resources = context.resources
  EventEffect(events) { messageRes ->
    Toast.makeText(context, resources.getString(messageRes), Toast.LENGTH_SHORT).show()
  }
}

/**
 * Runs [onResume] each time the screen becomes RESUMED (cu-206).
 *
 * The equivalent of a Fragment's `onResume` override. `LoginDestination` is the reason it exists:
 * the user leaves for a browser to approve an OAuth PIN and comes back, and only a resume tells the
 * app to check whether that succeeded. A `LaunchedEffect` would not do — it runs once per
 * composition, not once per return to the foreground.
 */
@Composable
fun LifecycleResumeEffect(onResume: () -> Unit) {
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, onResume) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          onResume()
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
}
