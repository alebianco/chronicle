package io.github.mattpvaughn.chronicle.navigation.circuit

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * A `ViewModelStoreOwner` that keeps Circuit's per-screen store but borrows the Activity's Hilt
 * factory.
 *
 * ### Why this is needed
 *
 * `NavigableCircuitContent` installs `ViewModelNavStackRecordLocalProvider`, which provides a
 * `LocalViewModelStoreOwner` **scoped to each back-stack record** — that is what gives a Circuit
 * screen the same per-screen ViewModel lifetime a `composable {}` entry had, and what clears a
 * ViewModel when its screen is popped rather than when the Activity finishes. That part is right,
 * and worth keeping.
 *
 * What that owner does *not* implement is [HasDefaultViewModelProviderFactory]. `hiltViewModel()`
 * reads exactly that interface to find the Hilt factory and its `CreationExtras`; without it the
 * call silently falls through to the **default** factory, which tries to construct the ViewModel by
 * reflection and throws `Cannot create an instance of class …ViewModel` at runtime.
 *
 * That failure is invisible to every unit test — presenters take their ViewModel as a parameter, so
 * nothing off-device ever resolves one — and it is not a compile error either. It surfaced on the
 * first launch on a device, which is why rule 5 exists.
 *
 * ### What it does
 *
 * Delegates `viewModelStore` to Circuit's record-scoped owner, so lifetime is unchanged, and takes
 * the factory and creation extras from the Activity, which Hilt has installed on. The result is a
 * Hilt-built ViewModel that lives and dies with its screen.
 */
private class RecordScopedHiltOwner(
  private val recordOwner: ViewModelStoreOwner,
  private val factoryOwner: HasDefaultViewModelProviderFactory,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
  override val viewModelStore: ViewModelStore
    get() = recordOwner.viewModelStore

  override val defaultViewModelProviderFactory: ViewModelProvider.Factory
    get() = factoryOwner.defaultViewModelProviderFactory

  override val defaultViewModelCreationExtras: CreationExtras
    get() = factoryOwner.defaultViewModelCreationExtras
}

/**
 * The owner to hand `hiltViewModel()` inside a Circuit presenter or `Ui`.
 *
 * Falls back to the current `LocalViewModelStoreOwner` unchanged when it already carries a default
 * factory — which is what happens outside a `NavigableCircuitContent`, in a preview or a test.
 */
@Composable
fun rememberRecordScopedViewModelStoreOwner(): ViewModelStoreOwner {
  val recordOwner =
    checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner in this composition" }
  // The Activity itself, which is where Hilt installed the factory. Reached through the context
  // rather than a composition local, because Circuit has already shadowed
  // `LocalViewModelStoreOwner` with its per-record one by the time a presenter runs.
  val activityOwner = LocalContext.current.findComponentActivity()

  return remember(recordOwner, activityOwner) {
    when {
      recordOwner is HasDefaultViewModelProviderFactory -> recordOwner
      activityOwner is HasDefaultViewModelProviderFactory ->
        RecordScopedHiltOwner(recordOwner, activityOwner)
      else -> recordOwner
    }
  }
}

/** A convenience mirroring `hiltViewModel()`, scoped to the Circuit record. */
@Composable
inline fun <reified VM : ViewModel> recordViewModel(): VM =
  androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(rememberRecordScopedViewModelStoreOwner())

/** A convenience mirroring assisted-injection `hiltViewModel()`, scoped to the Circuit record. */
@Composable
inline fun <reified VM : ViewModel, reified VMF> recordViewModel(noinline creationCallback: (VMF) -> VM): VM =
  androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<VM, VMF>(
    viewModelStoreOwner = rememberRecordScopedViewModelStoreOwner(),
    creationCallback = creationCallback,
  )

/** Walks the `ContextWrapper` chain to the hosting activity, as `LocalActivity` does. */
fun Context.findComponentActivity(): ComponentActivity? {
  var context = this
  while (context is ContextWrapper) {
    if (context is ComponentActivity) return context
    context = context.baseContext
  }
  return null
}
