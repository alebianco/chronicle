package io.github.mattpvaughn.chronicle.injection.components

import androidx.fragment.app.Fragment

/**
 * An Application that can hand a Fragment the singleton graph to inject itself from.
 *
 * The login-flow screens do not use [ActivityComponent] — they run before a library is chosen, so
 * they take their dependencies from [AppComponent] instead, and they reached it by casting twice:
 *
 * ```kotlin
 * ((activity as Activity).application as ChronicleApplication).appComponent.inject(this)
 * ```
 *
 * That names a concrete `Application` *and* a concrete `Activity`, so it fails under
 * `FragmentScenario` exactly as the [ActivityComponentHost] cast did — with the login flow being
 * the part of the app a household sees when something has already gone wrong (cu-178).
 *
 * `ChronicleApplication` implements this, so production behaviour is unchanged.
 */
interface AppComponentHost {
  /** The singleton graph. */
  val appComponentForInjection: AppComponent
}

/**
 * The app graph installed for tests.
 *
 * **This is checked *before* the Application, which is the opposite of [testActivityComponent] and
 * is deliberate.** Robolectric reads the real `AndroidManifest.xml` and instantiates the *real*
 * `ChronicleApplication`, so the host branch always resolves under test and a fallback-shaped
 * override would never be consulted. That is not a hypothetical: written fallback-first, a
 * `ChooseServerFragment` scenario suite passed **with its mock's `inject` doing nothing at all** —
 * it was silently building the real Dagger graph and asserting against it.
 *
 * `FragmentScenario` hosts fragments in `EmptyFragmentActivity`, which is *not* an
 * [ActivityComponentHost] — so for the activity graph the fallback genuinely is the only path and
 * fallback-first is correct there. The Application has no such empty stand-in. The two seams differ
 * because the frameworks differ, and both orderings are verified by sabotage.
 *
 * Null in production, so this read costs one null check and the real Application is used.
 */
@Volatile
var testAppComponent: AppComponent? = null

/**
 * Injects [fragment] from the application's graph.
 *
 * Prefers [testAppComponent] when a test has installed one — see the note there for why that
 * ordering is required rather than merely convenient — then the real Application. Returns false
 * when neither is available; callers treat that as fatal, because a missing graph in production is
 * a wiring bug rather than a state to tolerate.
 */
fun Fragment.injectFromAppGraph(inject: (AppComponent) -> Unit): Boolean {
  val component =
    testAppComponent
      ?: (activity?.application as? AppComponentHost)?.appComponentForInjection
      ?: return false
  inject(component)
  return true
}
