package io.github.mattpvaughn.chronicle.testing

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario

/**
 * A `FragmentScenario` equivalent that hosts the Fragment in an `@AndroidEntryPoint` Activity.
 *
 * **Hilt makes this necessary** (cu-185): a `@AndroidEntryPoint` Fragment refuses to attach to a
 * host that is not one, and `launchFragmentInContainer`'s stock `EmptyFragmentActivity` is not, so
 * every scenario suite failed with *"Hilt Fragments must be attached to an @AndroidEntryPoint
 * Activity"*.
 *
 * It keeps the surface the four suites already use — [moveToState], [onFragment], [recreate] and
 * `use {}` — so the assertions cu-178 wrote are unchanged. And it keeps what those assertions were
 * *for*: [HiltFragmentHostActivity] is a bare `AppCompatActivity`, so a Fragment that only works
 * inside `MainActivity` still fails here.
 */
class HiltFragmentScenario<F : Fragment>
  @PublishedApi
  internal constructor(
    private val scenario: ActivityScenario<HiltFragmentHostActivity>,
    private val fragmentClass: Class<F>,
  ) : AutoCloseable {
    fun moveToState(state: Lifecycle.State) = apply { scenario.moveToState(state) }

    /** Re-creates the host, which is what a rotation does. */
    fun recreate() = apply { scenario.recreate() }

    @Suppress("UNCHECKED_CAST")
    fun onFragment(block: (F) -> Unit) =
      apply {
        scenario.onActivity { activity ->
          val fragment =
            activity.supportFragmentManager.findFragmentByTag(TAG)
              ?: error("the fragment was not attached to the host")
          block(fragmentClass.cast(fragment) as F)
        }
      }

    override fun close() = scenario.close()

    private companion object {
      const val TAG = "hilt_scenario_fragment"
    }

    @PublishedApi
    internal object Launcher {
      const val TAG = "hilt_scenario_fragment"
    }
  }

/** Launches [F] into [HiltFragmentHostActivity]. See [HiltFragmentScenario]. */
inline fun <reified F : Fragment> launchFragmentInHiltContainer(
  @Suppress("UNUSED_PARAMETER") themeResId: Int = 0,
): HiltFragmentScenario<F> {
  // `ActivityScenario.launch(Class)` resolves through Robolectric's own registry rather than the
  // merged manifest, so the host needs no manifest entry — which matters because it lives in the
  // test source set and lint rejects such a class in the debug manifest (cu-185).
  val scenario = ActivityScenario.launch(HiltFragmentHostActivity::class.java)
  scenario.onActivity { activity ->
    val fragment =
      activity.supportFragmentManager.fragmentFactory.instantiate(
        checkNotNull(F::class.java.classLoader),
        F::class.java.name,
      )
    activity.supportFragmentManager
      .beginTransaction()
      .add(android.R.id.content, fragment, HiltFragmentScenario.Launcher.TAG)
      .commitNow()
  }
  return HiltFragmentScenario(scenario, F::class.java)
}
