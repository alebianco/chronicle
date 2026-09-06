package io.github.mattpvaughn.chronicle.views

import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle

/**
 * Gives a Fragment's own [Toolbar] its menu, without routing through the Activity (cu-180).
 *
 * Every screen here used to do this instead:
 *
 * ```kotlin
 * (activity as AppCompatActivity).setSupportActionBar(binding.toolbar)
 * requireActivity().addMenuProvider(provider, viewLifecycleOwner, RESUMED)
 * ```
 *
 * The cast is the problem. `setSupportActionBar` is AppCompat's own API, so it names a concrete
 * host type — and a Fragment that names its host **cannot be hosted by anything else**, including
 * `FragmentScenario`'s `EmptyFragmentActivity`. That is what kept 9,000 instructions of Fragment
 * code (21% of everything uncovered) unreachable on the JVM, after cu-178 had already removed the
 * *other* host cast for dependency injection.
 *
 * It also turned out to be doing very little. Measured before this change:
 *
 * - `MainActivity` has **no toolbar of its own** and no `supportActionBar` reference anywhere.
 * - **No fragment reads the action bar back** — nothing used what `setSupportActionBar` returns.
 *
 * So its only job was handing the fragment's toolbar menu to the Activity's `MenuHost`. A
 * `Toolbar` owns a menu natively, so this does the same work with no Activity involved.
 *
 * ## Why the lifecycle still matters
 *
 * The provider is bound to `viewLifecycleOwner` at [Lifecycle.State.RESUMED] — the same pair every
 * call site already passed, now applied here so it cannot be forgotten. That is **not incidental**: cu-102 documents a crash where menu
 * observers fire at STARTED while the menu is only populated at RESUMED, so `findItem` returned
 * null and `.setIcon` killed the process on every unlock. Binding at RESUMED keeps the timing the
 * observers were written against.
 */
fun Fragment.setToolbarMenu(
  toolbar: Toolbar,
  provider: MenuProvider,
) {
  toolbar.addMenuProvider(provider, viewLifecycleOwner, Lifecycle.State.RESUMED)
}
