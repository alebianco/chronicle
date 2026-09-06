package io.github.mattpvaughn.chronicle.features.collections

import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.CollectionsRepository
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.testing.launchFragmentInHiltContainer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `FragmentScenario` under Robolectric (cu-178, moved to Hilt in cu-185).
 *
 * Fragments were **9,000 missed instructions — 21% of everything uncovered**, the single largest
 * body in the app, and none of it was reachable on the JVM. `launchFragmentInContainer` drives the
 * real lifecycle without a device, so it counts toward the coverage ratchet in a way an
 * instrumented test does not.
 *
 * ## What cu-185 changed here
 *
 * cu-178's blocker was the app's own DI pattern: every Fragment injected itself with
 * `(activity as MainActivity).activityComponent!!`, naming a concrete Activity and failing with
 * `ClassCastException` inside a generic host. The fix then was to invert the dependency behind an
 * `ActivityComponentHost` capability and mock the component in each suite.
 *
 * Hilt removes the problem rather than working around it: `@AndroidEntryPoint` gets its graph from
 * the *application*, not from the host's type, so `ActivityComponentHost`, `injectFromHost` and
 * the mocked `ActivityComponent` are all gone. `@BindValue` replaces exactly what a scenario needs
 * — the two collaborators this screen reads — and the ViewModel builds itself from the real test
 * graph instead of a hand-assembled factory.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class CollectionsFragmentScenarioTest {
  private val collectionsFlow = MutableStateFlow<List<Collection>>(emptyList())

  @get:Rule
  val hiltRule = HiltAndroidRule(this)

  /**
   * The two collaborators the Fragment itself injects.
   *
   * `isConnected` must be stubbed, not relaxed. A relaxed `StateFlow<Boolean>` hands back a
   * `StateFlow<Object>`, and `collectAsStateWithLifecycle` then throws `ClassCastException` the
   * moment Compose reads it — the cu-187 form of the rule that a relaxed mock is wrong for
   * anything flow-shaped.
   */
  @BindValue
  @JvmField
  val plexConfig: PlexConfig =
    mockk(relaxed = true) {
      every { isConnected } returns MutableStateFlow(true)
      every { toServerString(any()) } returns "http://localhost/cover.jpg"
    }

  @BindValue
  @JvmField
  val navigator: Navigator = mockk(relaxed = true)

  /** The collections the screen reads, bound into the graph the ViewModel builds from. */
  @BindValue
  @JvmField
  val collectionsRepository: CollectionsRepository =
    mockk { every { getAllCollections() } returns collectionsFlow }

  @Before
  fun setUp() {
    hiltRule.inject()
  }

  /**
   * The question this proof of concept existed to answer, now answerable: a Fragment of this app
   * launches into a **generic host** and reaches `RESUMED`.
   *
   * Two host casts had to go first. cu-178 inverted the DI one
   * (`(activity as MainActivity).activityComponent`) behind `ActivityComponentHost`; cu-180
   * removed `(activity as AppCompatActivity).setSupportActionBar`, which was routing the
   * fragment's own toolbar menu through the Activity and pinning the screen to an AppCompat host
   * for no other reason.
   */
  @Test
  fun `the fragment reaches a resumed state in a generic host`() {
    launchFragmentInHiltContainer<CollectionsFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull("the view must be created", it.view) }
    }
  }

  /**
   * The lifecycle is really driven: the `ComposeView` is inflated and hosted once the view is up.
   *
   * This replaces an assertion on `adapter`, which cu-187 deleted along with `CollectionsAdapter`.
   * What is worth pinning is not that a particular field exists but that the **Compose host is
   * reachable from a generic Activity** — a `ComposeView` needs a `ViewTreeLifecycleOwner` and a
   * `SavedStateRegistryOwner`, and a Fragment that failed to provide them would render nothing
   * while every `createComposeRule` test in `CollectionsScreenTest` still passed.
   */
  @Test
  fun `the fragment hosts its compose view`() {
    launchFragmentInHiltContainer<CollectionsFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment {
        assertNotNull(
          "onCreateView must have inflated the ComposeView",
          it.view?.findViewById<ComposeView>(R.id.collections_compose),
        )
      }
    }
  }

  /** A rotation is a destroy/recreate, which is where most Fragment bugs here have come from. */
  @Test
  fun `the fragment survives a recreation`() {
    launchFragmentInHiltContainer<CollectionsFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.recreate()
      scenario.onFragment { assertNotNull(it.view) }
    }
  }
}
