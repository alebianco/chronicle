package io.github.mattpvaughn.chronicle.application

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import io.github.mattpvaughn.chronicle.debug.DebugHooks

/**
 * The instrumentation runner, named in `defaultConfig.testInstrumentationRunner`.
 *
 * It no longer swaps in a `TestChronicleApplication`. That subclass existed to install a parallel
 * Dagger graph (`UITestAppComponent` / `UITestAppModule`) duplicating every binding in `AppModule`
 * — the same drift hazard as the debug/release `DebugHooks` twins, and it had already rotted along
 * with the tests it served (cu-54). The suite runs against the **real** graph instead.
 *
 * What it does do is enable mock-Plex mode *before the application starts*. `ChronicleApplication`
 * reads the flag in `onCreate`, and it must be set before `setupNetwork()` or the app refreshes
 * connections against the real plex.tv and clears the seeded server — so the flag cannot be set
 * from a `@Before`.
 *
 * [newApplication] is the hook, not `onCreate`: `InstrumentationRegistry` is not populated until
 * after `super.onCreate()` returns, and reading it there crashes the process with "No
 * instrumentation registered". `newApplication` runs later than that but still before
 * `Application.onCreate`, which is exactly the window required.
 *
 * The upshot: the suite needs no credentials and no live server — every request is answered by the
 * cu-16 fixture pack over a local `MockWebServer`.
 */
class ChronicleTestRunner : AndroidJUnitRunner() {
  override fun newApplication(
    cl: ClassLoader?,
    className: String?,
    context: Context?,
  ): Application {
    context?.let { DebugHooks.setMockPlexEnabled(it, enabled = true) }
    return super.newApplication(cl, className, context)
  }
}
