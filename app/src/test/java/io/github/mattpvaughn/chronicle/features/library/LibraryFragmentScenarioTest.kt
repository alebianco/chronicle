package io.github.mattpvaughn.chronicle.features.library

import android.content.Context
import android.content.SharedPreferences
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.Lifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
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
 * The library screen under `FragmentScenario` — the largest of the eight at 1,259 instructions.
 *
 * Follows the recipe established by `CollectionsFragmentScenarioTest` (proof of concept) and
 * `HomeFragmentScenarioTest` (generalisation): real factory over fakes, mocked `ActivityComponent`
 * whose `inject` populates the `lateinit`s, `launchFragmentInContainer` with the app theme.
 *
 * `SharedPreferences` is a hand-written fake rather than a mock, for the reason recorded in
 * CLAUDE.md: `preferenceFlow` registers a listener and emits on callback, so a relaxed mock emits
 * nothing, the `combine` behind `books` never fires, and the screen renders against a flow that
 * produced nothing — green, and proving nothing.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryFragmentScenarioTest {
  private val booksFlow = MutableStateFlow<List<Audiobook>>(emptyList())
  private val scheduler = TestCoroutineScheduler()

  private class FakePrefs : SharedPreferences {
    private val booleans = mutableMapOf<String, Boolean>()
    private val strings = mutableMapOf<String, String>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getBoolean(
      key: String,
      defValue: Boolean,
    ) = booleans[key] ?: defValue

    override fun getString(
      key: String,
      defValue: String?,
    ) = strings[key] ?: defValue

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
      listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
      listeners -= listener
    }

    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()

    override fun getStringSet(
      key: String,
      defValues: MutableSet<String>?,
    ): MutableSet<String>? = defValues

    override fun getInt(
      key: String,
      defValue: Int,
    ) = defValue

    override fun getLong(
      key: String,
      defValue: Long,
    ) = defValue

    override fun getFloat(
      key: String,
      defValue: Float,
    ) = defValue

    override fun contains(key: String) = booleans.containsKey(key) || strings.containsKey(key)

    override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
  }

  private fun realFactory(context: Context): LibraryViewModel.Factory {
    val bookRepository =
      mockk<IBookRepository>(relaxed = true) { every { getAllBooks() } returns booksFlow }
    val syncRepository =
      mockk<LibrarySyncRepository>(relaxed = true) {
        every { isRefreshing } returns MutableStateFlow(false)
        every { errorMessage } returns MutableStateFlow(null)
      }
    return LibraryViewModel.Factory(
      bookRepository = bookRepository,
      trackRepository = mockk<ITrackRepository>(relaxed = true),
      prefsRepo =
        mockk<PrefsRepo>(relaxed = true) {
          every { libraryBookViewStyle } returns "COVER_GRID"
        },
      cachedFileManager = mockk<ICachedFileManager>(relaxed = true),
      librarySyncRepository = syncRepository,
      sharedPreferences = FakePrefs(),
      exceptionHandler = CoroutineExceptionHandler { _, _ -> },
      appContext = context,
      dispatchers = TestDispatcherProvider(scheduler),
    )
  }

  @Before
  fun installGraph() {
    val fragmentSlot = slot<LibraryFragment>()
    testActivityComponent =
      mockk<ActivityComponent>(relaxed = true) {
        every { inject(capture(fragmentSlot)) } answers {
          fragmentSlot.captured.apply {
            viewModelFactory = realFactory(requireContext())
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

  @Test
  fun `the library reaches a resumed state in a generic host`() {
    launchFragmentInContainer<LibraryFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull("the view must be created", it.view) }
    }
  }

  /** The first-run state: no books synced yet. */
  @Test
  fun `the library renders when empty`() {
    booksFlow.value = emptyList()

    launchFragmentInContainer<LibraryFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull(it.view) }
    }
  }

  @Test
  fun `the library renders a populated shelf`() {
    booksFlow.value =
      (1..12).map {
        Audiobook(id = "$it", source = TEST_SOURCE, title = "Book $it", titleSort = "Book $it")
      }

    launchFragmentInContainer<LibraryFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull(it.view) }
    }
  }

  @Test
  fun `the library survives a recreation`() {
    launchFragmentInContainer<LibraryFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.recreate()
      scenario.onFragment { assertNotNull(it.view) }
    }
  }
}
