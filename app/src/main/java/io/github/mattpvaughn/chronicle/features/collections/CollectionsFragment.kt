package io.github.mattpvaughn.chronicle.features.collections

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.widget.SearchView
import androidx.compose.runtime.getValue
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.databinding.FragmentCollectionsBinding
import io.github.mattpvaughn.chronicle.features.collections.compose.CollectionsScreen
import io.github.mattpvaughn.chronicle.features.search.GroupedSearchAdapter
import io.github.mattpvaughn.chronicle.injection.components.injectFromHost
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import io.github.mattpvaughn.chronicle.views.setToolbarMenu
import timber.log.Timber
import javax.inject.Inject

/** TODO: refactor search to reuse code from Library + Home fragments */
class CollectionsFragment : Fragment() {
  companion object {
    fun newInstance() = CollectionsFragment()
  }

  @Inject
  lateinit var viewModelFactory: CollectionsViewModel.Factory

  private val viewModel: CollectionsViewModel by lazy {
    ViewModelProvider(this, viewModelFactory).get(CollectionsViewModel::class.java)
  }

  @Inject
  lateinit var navigator: Navigator

  @Inject
  lateinit var plexConfig: PlexConfig

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    val binding = FragmentCollectionsBinding.inflate(inflater, container, false)

    // The grid, the empty state and the offline state are all `CollectionsScreen` now (cu-187).
    // What this replaces is worth naming: a `CollectionsAdapter`, a hand-rolled
    // `isDifferentListById` diff with a `submitList(null) { submitList(real) }` scroll-to-top
    // dance, a layout-manager swap for grid-vs-list, and three `isVisible` assignments that
    // decided between empty, offline and populated — badly, since an empty library showed the
    // offline container *and* the empty message at once.
    binding.collectionsCompose.setContent {
      val state by viewModel.uiState.collectAsStateWithLifecycle()
      val isConnected by plexConfig.isConnected.collectAsStateWithLifecycle()

      // Every composable is wrapped in `ChronicleTheme` — unwrapped it renders in stock Material
      // purple, which is obvious on a device and easy to miss in a test asserting only text.
      ChronicleTheme {
        CollectionsScreen(
          // `serverConnected` comes from `PlexConfig` rather than the ViewModel's state: it only
          // decides whether Coil is handed a URL, and folding it in would need a five-source
          // combinator for one boolean.
          state = state.copy(serverConnected = isConnected),
          coverUrl = plexConfig::toServerString,
          onCollectionClick = ::openCollectionDetails,
          onDisableOfflineMode = viewModel::disableOfflineMode,
        )
      }
    }

    val searchAdapter =
      GroupedSearchAdapter(onBookClick = { openAudiobookDetails(it) }, coverUrl = plexConfig::toServerString)
    binding.searchResultsList.adapter = searchAdapter

    // Search is still Views: `GroupedSearchAdapter` is shared with Library and Home, so it
    // migrates with them (cu-188) rather than being forked here.
    //
    // These must stay below the adapter assignment above, since collection delivers an
    // already-set value synchronously.
    viewLifecycleOwner.collectWhileStarted(viewModel.searchRows) { rows ->
      searchAdapter.submitList(rows)
      updateSearchVisibility(binding)
    }

    viewLifecycleOwner.collectWhileStarted(plexConfig.isConnected) { isConnected ->
      searchAdapter.setServerConnected(isConnected)
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.isSearchActive) { updateSearchVisibility(binding) }
    viewLifecycleOwner.collectWhileStarted(viewModel.isQueryEmpty) { updateSearchVisibility(binding) }

    // `SwipeRefreshLayout` stays as the Compose view's host rather than moving to a Compose
    // pull-refresh: it is a working widget the rest of the app also uses, and swapping it would be
    // an unrelated behaviour change inside a screen migration.
    binding.swipeToRefresh.setOnRefreshListener {
      viewModel.refreshData()
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.isRefreshing) {
      binding.swipeToRefresh.isRefreshing = it
    }

    // A `StateFlow` must hold a value, so a one-shot event starts null and stays null until one
    // fires — unlike `LiveData`, which simply never emitted (cu-52).
    viewLifecycleOwner.collectWhileStarted(viewModel.messageForUser) { event ->
      if (event != null && !event.hasBeenHandled) {
        Toast.makeText(context, event.getContentIfNotHandled(), LENGTH_SHORT).show()
      }
    }

    // A refresh failure. It arrives as a string resource because it is raised on an IO
    // dispatcher, where `Toast.show()` throws; the toast belongs here, on the main thread.
    viewLifecycleOwner.collectWhileStarted(viewModel.syncError) { event ->
      event?.getContentIfNotHandled()?.let { messageRes ->
        Toast.makeText(context, getString(messageRes), LENGTH_SHORT).show()
      }
    }

    // The toolbar owns its menu (cu-180): no host cast, no activity MenuHost.
    setToolbarMenu(
      binding.toolbar,
      object : MenuProvider {
        override fun onCreateMenu(
          menu: Menu,
          menuInflater: MenuInflater,
        ) {
          // The toolbar inflates `R.menu.collections_menu` itself via `app:menu` in the layout
          // (cu-180), so inflating again here would double every item — which it did, visibly, as
          // two search icons. This provider only wires the items up.
          val searchView = menu.findItem(R.id.search).actionView as SearchView
          val searchItem = menu.findItem(R.id.search)

          searchItem.setOnActionExpandListener(
            object : MenuItem.OnActionExpandListener {
              override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                viewModel.setSearchActive(true)
                return true
              }

              override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.setSearchActive(false)
                return true
              }
            },
          )

          searchView.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
              override fun onQueryTextSubmit(query: String?): Boolean {
                return true
              }

              override fun onQueryTextChange(newText: String?): Boolean {
                if (newText != null) {
                  viewModel.search(newText)
                }
                return true
              }
            },
          )
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
          return menuItem.itemId == R.id.search
        }
      },
    )

    // targetSdk 36 is edge-to-edge; the toolbar must inset itself (cu-63).
    binding.toolbarLayout.applyTopSystemBarInset()

    return binding.root
  }

  /**
   * Was the two `android:visibility` binding expressions on the search views in
   * fragment_collections.xml. Both depend on more than one flow, so every source re-evaluates the
   * pair rather than each collector owning one view.
   */
  private fun updateSearchVisibility(binding: FragmentCollectionsBinding) {
    val isSearchActive = viewModel.isSearchActive.value
    val isQueryEmpty = viewModel.isQueryEmpty.value
    val hasNoResults = viewModel.searchRows.value.isEmpty()

    binding.searchResultsList.isVisible = isSearchActive
    binding.noSearchResultsMessage.isVisible = hasNoResults && isSearchActive && !isQueryEmpty
  }

  private fun openCollectionDetails(collection: Collection) {
    navigator.showCollectionDetails(collection.id)
  }

  private fun openAudiobookDetails(audiobook: Audiobook) {
    navigator.showDetails(audiobook.id, audiobook.title, audiobook.isCached)
  }

  override fun onAttach(context: Context) {
    // Asks the host for a graph rather than casting to `MainActivity` (cu-178). The cast named a
    // concrete Activity, so this Fragment could not be hosted by anything else — including
    // `FragmentScenario`'s empty activity, which failed in `onAttach` before a line of the screen
    // ran. `check` rather than a silent skip: in production a missing graph is a wiring bug.
    check(injectFromHost { it.inject(this) }) { "CollectionsFragment needs an ActivityComponentHost" }
    super.onAttach(context)
    Timber.i("Reattached!")
  }
}
