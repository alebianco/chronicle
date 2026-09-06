package io.github.mattpvaughn.chronicle.features.collections

import android.content.SharedPreferences
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.Lifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.BookRepository
import io.github.mattpvaughn.chronicle.data.local.CollectionsRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.injection.components.ActivityComponent
import io.github.mattpvaughn.chronicle.injection.components.testActivityComponent
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proof of concept for `FragmentScenario` under Robolectric (cu-178).
 *
 * Fragments are **9,000 missed instructions — 21% of everything uncovered**, the single largest
 * body in the app, and none of it was reachable on the JVM. `launchFragmentInContainer` drives the
 * real lifecycle without a device, so it counts toward the coverage ratchet in a way an
 * instrumented test does not.
 *
 * `CollectionsFragment` was chosen as the smallest of the eight (806 instructions) whose ViewModel
 * is already tested — so a failure here is unambiguously the new plumbing rather than the screen.
 *
 * ## What this proved, and what it cost
 *
 * The library works out of the box. **The blocker was the app's own DI pattern**: every Fragment
 * injected itself with `(activity as MainActivity).activityComponent!!`, naming a concrete Activity
 * and so failing with `ClassCastException` inside `EmptyFragmentActivity` before a line of the
 * screen ran. That is a dependency-inversion problem, not an Android one — the Fragment depended on
 * its host's *type* when it needed a capability.
 *
 * `ActivityComponentHost` is that capability, `MainActivity` implements it (so production is
 * unchanged), and [FragmentHostActivity] implements it for tests. The component is mocked because a
 * scenario needs exactly one of its two dozen members: the `inject` overload for this Fragment.
 *
 * The pattern for the next seven screens: implement the host, stub `inject` to populate the
 * Fragment's `lateinit` fields, launch.
 */
@RunWith(RobolectricTestRunner::class)
class CollectionsFragmentScenarioTest {
  private val collectionsFlow = MutableStateFlow<List<Collection>>(emptyList())
  private val scheduler = TestCoroutineScheduler()

  private fun collection(
    id: String,
    title: String,
  ) = Collection(id = id, source = TEST_SOURCE, title = title)

  /**
   * A **real** [CollectionsViewModel.Factory] over fakes.
   *
   * Not a mock: `ViewModelProvider` picks among several `create` overloads (Class,
   * Class+CreationExtras, KClass+CreationExtras) and stubbing the wrong one fails at run time with
   * "no answer found". The production factory is a plain class taking the same dependencies as the
   * ViewModel, so constructing it is both simpler and closer to what ships.
   */
  private fun realFactory(): CollectionsViewModel.Factory {
    val collectionsRepository =
      mockk<CollectionsRepository> { every { getAllCollections() } returns collectionsFlow }
    val syncRepository =
      mockk<LibrarySyncRepository>(relaxed = true) {
        every { isRefreshing } returns MutableStateFlow(false)
        every { errorMessage } returns MutableStateFlow(null)
      }
    val prefs =
      mockk<SharedPreferences>(relaxed = true) {
        every { getString(any(), any()) } returns "COVER_GRID"
        every { getBoolean(any(), any()) } answers { secondArg() }
      }
    return CollectionsViewModel.Factory(
      prefsRepo = mockk<PrefsRepo>(relaxed = true) { every { libraryBookViewStyle } returns "COVER_GRID" },
      collectionsRepository = collectionsRepository,
      librarySyncRepository = syncRepository,
      sharedPreferences = prefs,
      bookRepository = mockk<BookRepository>(relaxed = true),
      exceptionHandler = CoroutineExceptionHandler { _, _ -> },
      dispatchers = TestDispatcherProvider(scheduler),
    )
  }

  @Before
  fun installGraph() {
    val factory = realFactory()
    val fragmentSlot = slot<CollectionsFragment>()
    testActivityComponent =
      mockk<ActivityComponent>(relaxed = true) {
        // Stands in for Dagger's field injection: populate exactly the `lateinit`s this screen
        // reads before `onCreateView`.
        every { inject(capture(fragmentSlot)) } answers {
          fragmentSlot.captured.apply {
            viewModelFactory = factory
            prefsRepo = mockk(relaxed = true)
            navigator = mockk(relaxed = true)
            plexConfig = mockk(relaxed = true)
          }
          Unit
        }
      }
  }

  @After
  fun clearGraph() {
    testActivityComponent = null
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
    launchFragmentInContainer<CollectionsFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull("the view must be created", it.view) }
    }
  }

  /** The lifecycle is really driven: the adapter exists once the view is up. */
  @Test
  fun `the fragment builds its adapter`() {
    launchFragmentInContainer<CollectionsFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull("onCreateView must have run", it.adapter) }
    }
  }

  /** A rotation is a destroy/recreate, which is where most Fragment bugs here have come from. */
  @Test
  fun `the fragment survives a recreation`() {
    launchFragmentInContainer<CollectionsFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.recreate()
      scenario.onFragment { assertNotNull(it.view) }
    }
  }
}
