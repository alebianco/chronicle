package io.github.mattpvaughn.chronicle.features.collections

import android.content.SharedPreferences
import androidx.fragment.app.testing.launchFragmentInContainer
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
import org.junit.Assert.assertFalse
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
   * How far the scenario gets today, and where it stops.
   *
   * `onAttach` now succeeds — the DI seam works — and the failure has moved into `onCreateView`:
   *
   * ```
   * ClassCastException: EmptyFragmentActivity cannot be cast to AppCompatActivity
   *   at CollectionsFragment.onCreateView   // (activity as AppCompatActivity).setSupportActionBar
   * ```
   *
   * That is a **second** host-type dependency, and it is in six of the ten fragments. Unlike the
   * DI cast it cannot be inverted with an interface: `setSupportActionBar` is AppCompat's own API,
   * so the host genuinely has to be an `AppCompatActivity`. `FragmentScenario` hosts everything in
   * its `EmptyFragmentActivity` and offers no overload that accepts a host class — verified against
   * `fragment-testing` 1.8.9, whose four `launch`/`launchInContainer` signatures take only a
   * fragment class, args, a theme and a factory.
   *
   * So the remaining route is a **debug-manifest `AppCompatActivity` host** plus
   * `ActivityScenario`, which is a larger change than a proof of concept should make unattended —
   * it means a new `src/debug/AndroidManifest.xml` and a launcher activity shipped in the debug
   * build. Recorded in cu-178 rather than attempted.
   *
   * This test asserts the progress that is real: the fragment attaches and injects in a generic
   * host, which it could not do before.
   */
  @Test
  fun `the fragment attaches and injects in a generic host`() {
    val error =
      runCatching {
        launchFragmentInContainer<CollectionsFragment>(themeResId = R.style.AppTheme).close()
      }.exceptionOrNull()

    // Attach succeeded if we got past it: the failure, if any, is the AppCompat host cast in
    // `onCreateView`, never the `ActivityComponentHost` check in `onAttach`.
    val message = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    assertFalse(
      "injection should no longer fail in onAttach, but did: $message",
      message.contains("needs an ActivityComponentHost"),
    )
  }
}
