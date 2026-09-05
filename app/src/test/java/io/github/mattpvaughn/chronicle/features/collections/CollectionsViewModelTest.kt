package io.github.mattpvaughn.chronicle.features.collections

import android.content.SharedPreferences
import io.github.mattpvaughn.chronicle.data.local.BookRepository
import io.github.mattpvaughn.chronicle.data.local.CollectionsRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * First tests for [CollectionsViewModel], which sat at **0% instruction coverage** — 438 missed
 * instructions on the screen that lists a library's Plex collections.
 *
 * Nothing about the class prevented testing: every dependency arrives through the constructor and
 * the two that matter are interfaces or open classes MockK can stand in for. The blocker was the
 * same one cu-15 identified for the other ViewModels — `Dispatchers.Main`, which
 * [MainDispatcherRule] pays for once.
 *
 * Scoped to the decisions this class actually owns: the sort direction, the empty-library short
 * circuit, and the search delegation. The `SharedPreferences`-backed flows are driven through a
 * fake rather than a mock, because `preferenceFlow` registers a real listener and
 * `awaitClose` unregisters it — a `relaxed` mock would silently emit nothing and every assertion
 * here would pass vacuously against an empty flow.
 */
class CollectionsViewModelTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val collectionsFlow = MutableStateFlow<List<Collection>>(emptyList())

  /**
   * A real listener registry, not a mock.
   *
   * [io.github.mattpvaughn.chronicle.util.preferenceFlow] emits the current value on collection
   * and then on every listener callback. A `mockk(relaxed = true)` returns `false`/`null` for the
   * getters and drops the registration, so `combine` would never receive a value from these
   * sources and `collections` would never emit at all — the assertions below would then pass
   * against a flow that produced nothing.
   */
  private class FakePrefs(
    private val booleans: MutableMap<String, Boolean> = mutableMapOf(),
    private val strings: MutableMap<String, String> = mutableMapOf(),
  ) : SharedPreferences {
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    fun put(
      key: String,
      value: Boolean,
    ) {
      booleans[key] = value
      listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) }
    }

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

  private val prefs = FakePrefs()

  private fun collection(
    id: String,
    title: String,
  ) = Collection(id = id, source = TEST_SOURCE, title = title)

  private fun viewModel(
    bookRepository: BookRepository = mockk(relaxed = true),
    prefsRepo: PrefsRepo = mockk(relaxed = true),
  ): CollectionsViewModel {
    val collectionsRepository =
      mockk<CollectionsRepository> {
        every { getAllCollections() } returns collectionsFlow
      }
    val syncRepository =
      mockk<LibrarySyncRepository>(relaxed = true) {
        every { isRefreshing } returns MutableStateFlow(false)
        every { errorMessage } returns MutableStateFlow(null)
      }
    return CollectionsViewModel(
      prefsRepo = prefsRepo,
      librarySyncRepository = syncRepository,
      collectionsRepository = collectionsRepository,
      sharedPreferences = prefs,
      bookRepository = bookRepository,
      exceptionHandler = CoroutineExceptionHandler { _, _ -> },
      dispatchers = TestDispatcherProvider(mainDispatcherRule.testDispatcher.scheduler),
    )
  }

  @Test
  fun `sorts collections by title descending by default`() =
    runTest {
      collectionsFlow.value =
        listOf(
          collection("2", "Mistborn"),
          collection("1", "Dune"),
          collection("3", "Neuromancer"),
        )

      val sorted = viewModel().collections.first()

      assertEquals(listOf("Dune", "Mistborn", "Neuromancer"), sorted.map { it.title })
    }

  @Test
  fun `reverses the order when the sort direction is flipped`() =
    runTest {
      prefs.put(PrefsRepo.KEY_IS_LIBRARY_SORT_DESCENDING, false)
      collectionsFlow.value =
        listOf(
          collection("1", "Dune"),
          collection("2", "Mistborn"),
        )

      val sorted = viewModel().collections.first()

      assertEquals(listOf("Mistborn", "Dune"), sorted.map { it.title })
    }

  /**
   * The empty case is a deliberate short circuit in the combiner, not a consequence of sorting an
   * empty list — so it is pinned separately. A comparator that threw on an empty input would
   * otherwise only surface on a library with no collections, which is the first thing a new user
   * sees.
   */
  @Test
  fun `an empty collection list stays empty`() =
    runTest {
      collectionsFlow.value = emptyList()

      assertTrue(viewModel().collections.first().isEmpty())
    }

  @Test
  fun `search activation is mirrored on the view model and cleared on dismissal`() =
    runTest {
      val vm = viewModel()
      assertFalse(vm.isSearchActive.value)

      vm.setSearchActive(true)
      assertTrue(vm.isSearchActive.value)

      vm.setSearchActive(false)
      assertFalse(vm.isSearchActive.value)
      advanceUntilIdle()
      assertTrue("dismissing search must clear the rows", vm.searchRows.value.isEmpty())
    }

  /**
   * A blank query must not reach the repository.
   *
   * `SearchController` debounces and treats an empty query as a clear, so this asserts the
   * *observable* consequence — `isQueryEmpty` stays true and no rows appear — rather than
   * verifying a call count, which would pin the debounce implementation rather than the behaviour.
   */
  @Test
  fun `a blank query leaves the results empty`() =
    runTest {
      val vm = viewModel()
      vm.setSearchActive(true)

      vm.search("   ")
      advanceUntilIdle()

      assertTrue(vm.isQueryEmpty.value)
      assertTrue(vm.searchRows.value.isEmpty())
    }
}
