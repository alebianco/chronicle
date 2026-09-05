package io.github.mattpvaughn.chronicle.injection.components

import androidx.fragment.app.Fragment

/**
 * An Activity that can hand a Fragment the graph to inject itself from.
 *
 * Every Fragment used to reach its dependencies by casting:
 *
 * ```kotlin
 * (activity as MainActivity).activityComponent!!.inject(this)
 * ```
 *
 * which names a *concrete* Activity and therefore **cannot be hosted by anything else** — including
 * `FragmentScenario`'s `EmptyFragmentActivity`, which fails with a `ClassCastException` in
 * `onAttach` before a single line of the screen runs. That is what kept 9,000 instructions of
 * Fragment code (21% of everything uncovered) untestable on the JVM, and it is a
 * dependency-inversion problem rather than an Android one: the Fragment declared a dependency on
 * its host's *type* when all it needed was a capability (cu-178).
 *
 * `MainActivity` implements this, so production behaviour is unchanged. A test host implements it
 * too, supplying a component built from fakes.
 */
interface ActivityComponentHost {
  /** The graph for this Activity, or null before `onCreate` has built it. */
  val activityComponent: ActivityComponent?
}

/**
 * The graph installed for tests, when the host cannot supply one.
 *
 * `FragmentScenario` hosts every Fragment in its own `EmptyFragmentActivity` and offers **no
 * overload that accepts a host class** — checked against `fragment-testing` 1.8.9, whose four
 * `launch`/`launchInContainer` signatures take only a fragment class, args, a theme and a factory.
 * So a Fragment under scenario has no way to reach a real graph through its Activity, and the only
 * seams are a debug-manifest host activity or this.
 *
 * Null in production and on any real device: `MainActivity` implements [ActivityComponentHost], so
 * [injectFromHost] never reads this. It exists so a *test* can hand a Fragment its dependencies
 * without the app inventing a second injection path.
 */
@Volatile
var testActivityComponent: ActivityComponent? = null

/**
 * Injects [fragment] from its host's graph.
 *
 * Prefers the host — which is what production always uses — and falls back to
 * [testActivityComponent], which is null unless a test set it. Returns false when neither is
 * available; callers treat that as fatal, because a missing graph in production is a wiring bug
 * rather than a state to tolerate.
 */
fun Fragment.injectFromHost(inject: (ActivityComponent) -> Unit): Boolean {
  val component =
    (activity as? ActivityComponentHost)?.activityComponent
      ?: testActivityComponent
      ?: return false
  inject(component)
  return true
}
