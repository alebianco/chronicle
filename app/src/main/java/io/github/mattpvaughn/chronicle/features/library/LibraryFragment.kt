package io.github.mattpvaughn.chronicle.features.library

import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.widget.SearchView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.BOOK_COVER_STYLE_SQUARE
import io.github.mattpvaughn.chronicle.data.local.viewStyleIsGrid
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.databinding.FragmentLibraryBinding
import io.github.mattpvaughn.chronicle.features.search.GroupedSearchAdapter
import io.github.mattpvaughn.chronicle.injection.components.injectFromHost
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import io.github.mattpvaughn.chronicle.util.isDifferentListById
import io.github.mattpvaughn.chronicle.views.checkRadioButtonWithTag
import io.github.mattpvaughn.chronicle.views.setBottomChooserState
import io.github.mattpvaughn.chronicle.views.setToolbarMenu
import timber.log.Timber
import javax.inject.Inject

class LibraryFragment : Fragment() {
  companion object {
    fun newInstance() = LibraryFragment()
  }

  @Inject
  lateinit var viewModelFactory: LibraryViewModel.Factory

  private val viewModel: LibraryViewModel by lazy {
    ViewModelProvider(this, viewModelFactory).get(LibraryViewModel::class.java)
  }

  @Inject
  lateinit var prefsRepo: PrefsRepo

  @Inject
  lateinit var navigator: Navigator

  @Inject
  lateinit var plexConfig: PlexConfig

  var adapter: AudiobookAdapter? = null

  /**
   * The grouped search results (cu-25).
   *
   * Created per view rather than held across one, because it is handed to the RecyclerView in
   * [onCreateView] and must not outlive it.
   */
  private lateinit var searchAdapter: GroupedSearchAdapter

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View? {
    Timber.i("Lib frag view create")
    val binding = FragmentLibraryBinding.inflate(inflater, container, false)
    searchAdapter = GroupedSearchAdapter(onBookClick = { openAudiobookDetails(it) }, coverUrl = plexConfig::toServerString)

    // Was compound visibility expressions in fragment_library.xml. XML combined
    // several LiveData sources implicitly; in Kotlin each source has to re-run
    // the whole condition, so the shared logic is factored into one function.
    // `books` and `isOffline` are cold `Flow`s, so there is no `.value` to read (cu-52). The two
    // collectors below keep these locals current and call this; a `StateFlow` would work too, but
    // the sort is O(library) and there is no reason to run it while the screen is away.
    var latestBooks: List<Audiobook> = emptyList()
    var latestOffline = false

    fun refreshEmptyStates() {
      val books = latestBooks
      val offline = latestOffline
      binding.offlineEmptyMessage.isVisible = books.isEmpty() && offline
      binding.noBooksMessage.isVisible = books.isEmpty() && !offline
      binding.swipeToRefresh.isVisible = books.isNotEmpty()
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.books) {
      latestBooks = it
      refreshEmptyStates()
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.isOffline) {
      latestOffline = it
      refreshEmptyStates()
    }

    fun refreshSearchStates() {
      val rows = viewModel.searchRows.value
      val active = viewModel.isSearchActive.value
      val queryEmpty = viewModel.isQueryEmpty.value
      binding.searchResultsList.isVisible = active
      binding.noSearchResultsMessage.isVisible = rows.isEmpty() && active && !queryEmpty
      searchAdapter.submitList(rows)
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.searchRows) { refreshSearchStates() }
    viewLifecycleOwner.collectWhileStarted(viewModel.isSearchActive) { refreshSearchStates() }
    viewLifecycleOwner.collectWhileStarted(viewModel.isQueryEmpty) { refreshSearchStates() }

    viewLifecycleOwner.collectWhileStarted(plexConfig.isConnected) { connected ->
      searchAdapter.setServerConnected(connected)
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.bottomChooserState) { state ->
      setBottomChooserState(binding.bottomSheetChooser, state)
    }

    binding.disableOfflineMode.setOnClickListener { viewModel.disableOfflineMode() }
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

    adapter =
      AudiobookAdapter(
        prefsRepo.libraryBookViewStyle,
        true,
        prefsRepo.bookCoverStyle == BOOK_COVER_STYLE_SQUARE,
        object : AudiobookClick {
          override fun onClick(audiobook: Audiobook) {
            openAudiobookDetails(audiobook)
          }
        },
        plexConfig::toServerString,
      ).apply {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
      }

    binding.libraryGrid.adapter = adapter

    viewLifecycleOwner.collectWhileStarted(viewModel.books) { books ->
      // Adapter is always non-null between view creation and view destruction
      checkNotNull(adapter) { "Adapter must not be null while view exists" }

      // If there are no previous books, submit normally
      if (adapter!!.currentList.isEmpty()) {
        Timber.i("Updating book list: no previous books")
        adapter!!.submitList(books)
        return@collectWhileStarted
      }

      // Sometimes [books] will be the same as [adapter.currentList] so don't do any
      // submission/diffing if that's the case
      //
      // A ListAdapter hands back only an immutable copy of its list, so telling "actually new" from
      // "same list, one field changed" means comparing. By **id**, not equals: the playing book's
      // progress changes once a second (cu-110), and a full comparison would scroll to top on every
      // tick. O(n), and synchronous — it used to sit in `withContext(Dispatchers.IO)` here and in
      // its twin, which was neither IO nor safe, since `currentList` is a UI object (cu-169).
      val isNewList = isDifferentListById(books, adapter?.currentList?.map { it.id }) { it.id }
      if (isNewList) {
        // submit an empty list to force a scroll-to-top, then when it is done, submit
        // the real list
        Timber.i("Updating book list: scroll to top")
        adapter!!.submitList(null) { adapter?.submitList(books) }
      }
    }

    viewLifecycleOwner.collectWhileStarted(plexConfig.isConnected) { isConnected ->
      adapter?.setServerConnected(isConnected)
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.viewStyle) { style ->
      Timber.i("View style is: $style")
      val isGrid =
        viewStyleIsGrid(style)
      binding.libraryGrid.layoutManager =
        if (isGrid) {
          GridLayoutManager(requireContext(), 3)
        } else {
          LinearLayoutManager(requireContext())
        }
      adapter!!.viewStyle = style
    }
    binding.searchResultsList.adapter = searchAdapter

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

  override fun onAttach(context: Context) {
    // Host capability, not host type (cu-178) — what makes this screen launchable by
    // `FragmentScenario`.
    check(injectFromHost { it.inject(this) }) { "LibraryFragment needs an ActivityComponentHost" }
    super.onAttach(context)
    Timber.i("Reattached!")
  }

  override fun onDestroyView() {
    adapter = null
    super.onDestroyView()
  }

  interface AudiobookClick {
    fun onClick(audiobook: Audiobook)

    /**
     * A long press on the same cover.
     *
     * Defaulted to "not handled" so every existing shelf keeps its behaviour: only the Continue
     * Listening shelf overrides it, where a tap resumes and the long press is how the details
     * screen stays reachable (cu-18).
     *
     * @return whether the press was consumed, which is what `setOnLongClickListener` wants — a
     *   `false` lets the platform fall through to the click.
     */
    fun onLongClick(audiobook: Audiobook): Boolean = false
  }
}
