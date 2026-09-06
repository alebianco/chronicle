package io.github.mattpvaughn.chronicle.features.library

import android.os.Bundle
import android.view.*
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.widget.SearchView
import androidx.compose.runtime.getValue
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dagger.hilt.android.AndroidEntryPoint
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.databinding.FragmentLibraryBinding
import io.github.mattpvaughn.chronicle.features.library.compose.LibraryScreen
import io.github.mattpvaughn.chronicle.features.search.compose.SearchOverlay
import io.github.mattpvaughn.chronicle.features.search.searchOverlayState
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import io.github.mattpvaughn.chronicle.views.checkRadioButtonWithTag
import io.github.mattpvaughn.chronicle.views.compose.BottomChooser
import io.github.mattpvaughn.chronicle.views.setToolbarMenu
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class LibraryFragment : Fragment() {
  companion object {
    fun newInstance() = LibraryFragment()
  }

  private val viewModel: LibraryViewModel by viewModels()

  @Inject
  lateinit var prefsRepo: PrefsRepo

  @Inject
  lateinit var navigator: Navigator

  @Inject
  lateinit var plexConfig: PlexConfig

  /**
   * The grouped search results (cu-25).
   *
   * Created per view rather than held across one, because it is handed to the RecyclerView in
   * [onCreateView] and must not outlive it.
   */

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View? {
    Timber.i("Lib frag view create")
    val binding = FragmentLibraryBinding.inflate(inflater, container, false)
    // Search is Compose now (cu-202), shared with home and collections through `SearchOverlay`.
    // The three-source `refreshSearchStates()` becomes `searchOverlayState`, which the other two
    // screens call as well — they had already drifted on whether an empty query says "no results".
    binding.searchCompose.setContent {
      val rows by viewModel.searchRows.collectAsStateWithLifecycle()
      val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
      val isQueryEmpty by viewModel.isQueryEmpty.collectAsStateWithLifecycle()
      val isConnected by plexConfig.isConnected.collectAsStateWithLifecycle()

      val chooser by viewModel.bottomChooserState.collectAsStateWithLifecycle()

      ChronicleTheme {
        SearchOverlay(
          state = searchOverlayState(isSearchActive, isQueryEmpty, rows),
          serverConnected = isConnected,
          coverUrl = plexConfig::toServerString,
          onBookClick = ::openAudiobookDetails,
        )

        // The chooser is `BottomChooser` now (cu-203), hosted here because this composition is
        // always present — the grid's is not, on an empty library.
        BottomChooser(chooser)
      }
    }

    binding.doneFiltering.setOnClickListener { viewModel.setFilterMenuVisible(false) }
    binding.sortByContainer.setOnClickListener { viewModel.toggleSortDirection() }
    viewLifecycleOwner.collectWhileStarted(viewModel.isSortDescending) { descending ->
      binding.sortByContainer.contentDescription =
        getString(
          if (descending == true) {
            R.string.toggle_library_sort_ascending
          } else {
            R.string.toggle_library_sort_descending
          },
        )
    }

    // The grid is `LibraryScreen` now (cu-201). This replaces an `AudiobookAdapter`, the
    // hand-rolled `isDifferentListById` diff with its `submitList(null) { submitList(real) }`
    // scroll-to-top dance, a layout-manager swap, and the two cached locals whose seeds once
    // rendered "No books found" over a full library — the bug `CollectorCachesItsValueTest` was
    // written for.
    binding.libraryCompose.setContent {
      val state by viewModel.uiState.collectAsStateWithLifecycle()
      val isConnected by plexConfig.isConnected.collectAsStateWithLifecycle()

      ChronicleTheme {
        LibraryScreen(
          state = state.copy(serverConnected = isConnected),
          coverUrl = plexConfig::toServerString,
          onBookClick = ::openAudiobookDetails,
          onDisableOfflineMode = viewModel::disableOfflineMode,
        )
      }
    }

    binding.swipeToRefresh.setOnRefreshListener {
      viewModel.refreshData()
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.isRefreshing) {
      binding.swipeToRefresh.isRefreshing = it
    }

    binding.sortByOptions.checkRadioButtonWithTag(prefsRepo.bookSortKey)
    binding.sortByOptions.setOnCheckedStateChangeListener { group: ChipGroup, checkedIds ->
      val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
      val key = group.findViewById<Chip>(checkedId).tag as String
      prefsRepo.bookSortKey = key
    }

    binding.viewStyles.checkRadioButtonWithTag(prefsRepo.libraryBookViewStyle)
    binding.viewStyles.setOnCheckedStateChangeListener { group: ChipGroup, checkedIds ->
      val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
      val key = group.findViewById<Chip>(checkedId).tag as String
      prefsRepo.libraryBookViewStyle = key
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.messageForUser) {
      if (it != null && !it.hasBeenHandled) {
        Toast.makeText(context, it.getContentIfNotHandled(), LENGTH_SHORT).show()
      }
    }

    // A refresh failure. It arrives as a string resource because it is raised on an IO
    // dispatcher, where `Toast.show()` throws; the toast belongs here, on the main thread.
    viewLifecycleOwner.collectWhileStarted(viewModel.syncError) {
      it?.getContentIfNotHandled()?.let { messageRes ->
        Toast.makeText(context, getString(messageRes), LENGTH_SHORT).show()
      }
    }

    val behavior = (binding.filterView.layoutParams) as CoordinatorLayout.LayoutParams
    (behavior.behavior as BottomSheetBehavior).addBottomSheetCallback(
      object :
        BottomSheetBehavior.BottomSheetCallback() {
        override fun onSlide(
          bottomSheet: View,
          slideOffset: Float,
        ) {}

        override fun onStateChanged(
          bottomSheet: View,
          newState: Int,
        ) {
          // ignore in-between states
          if (newState == STATE_EXPANDED || newState == STATE_HIDDEN) {
            viewModel.setFilterMenuVisible(newState == STATE_EXPANDED)
          }
        }
      },
    )

    // Was `android:checked="@{viewModel.arePlayedAudiobooksHidden}"` plus
    // `android:onClick="@{() -> viewModel.toggleHidePlayedAudiobooks()}"`. Both were dropped in the
    // cu-58 conversion, leaving the "hide played" switch inert — it moved when tapped and changed
    // nothing, and never reflected the stored preference. The filtering behind it always worked
    // (cu-73).
    viewLifecycleOwner.collectWhileStarted(viewModel.arePlayedAudiobooksHidden) { hidden ->
      if (binding.hidePlayed.isChecked != hidden) {
        binding.hidePlayed.isChecked = hidden
      }
    }
    binding.hidePlayed.setOnClickListener { viewModel.toggleHidePlayedAudiobooks() }

    viewLifecycleOwner.collectWhileStarted(viewModel.isFilterShown) { isFilterShown ->
      Timber.i("Showing filter view: $isFilterShown")
      val filterBottomSheetState =
        if (isFilterShown) {
          STATE_EXPANDED
        } else {
          STATE_HIDDEN
        }

      val params = binding.filterView.layoutParams as CoordinatorLayout.LayoutParams
      val bottomSheetBehavior = params.behavior as BottomSheetBehavior
      bottomSheetBehavior.state = filterBottomSheetState
    }

    // The toolbar owns its own menu (cu-180). This used to be
    // `(activity as AppCompatActivity).setSupportActionBar(...)` plus a provider on the
    // *activity's* MenuHost — a host-type cast that made this fragment unhostable by anything but
    // MainActivity, and a registration with no lifecycle that outlived the view.
    setToolbarMenu(
      binding.toolbar,
      object : MenuProvider {
        override fun onCreateMenu(
          menu: Menu,
          menuInflater: MenuInflater,
        ) {
          // The toolbar inflates `R.menu.library_menu` itself via `app:menu` in the layout (cu-180), so
          // inflating again here would double every item — which it did, visibly, as two
          // search icons. This provider only wires the items up.
          val searchView = menu.findItem(R.id.search).actionView as SearchView
          val searchItem = menu.findItem(R.id.search)
          val filterItem = menu.findItem(R.id.menu_filter)
          val cacheItem = menu.findItem(R.id.download_all)

          searchItem.setOnActionExpandListener(
            object : MenuItem.OnActionExpandListener {
              override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                filterItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                cacheItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                viewModel.setSearchActive(true)
                return true
              }

              override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                filterItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                cacheItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
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
          return when (menuItem.itemId) {
            R.id.menu_browse -> {
              navigator.showBrowse()
              true
            }
            R.id.menu_filter -> {
              viewModel.setFilterMenuVisible(!viewModel.isFilterShown.value)
              true
            }
            R.id.download_all -> {
              viewModel.promptDownloadAll()
              true
            }
            R.id.search -> true
            else -> false
          }
        }
      },
    )

    // targetSdk 36 is edge-to-edge; the toolbar must inset itself (cu-63).

    binding.toolbarLayout.applyTopSystemBarInset()

    return binding.root
  }

  private fun openAudiobookDetails(audiobook: Audiobook) {
    navigator.showDetails(audiobook.id, audiobook.title, audiobook.isCached)
  }
}
