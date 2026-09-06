package io.github.mattpvaughn.chronicle.features.collections

import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.BOOK_COVER_STYLE_SQUARE
import io.github.mattpvaughn.chronicle.data.local.viewStyleIsGrid
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.databinding.FragmentCollectionsBinding
import io.github.mattpvaughn.chronicle.features.search.GroupedSearchAdapter
import io.github.mattpvaughn.chronicle.injection.components.injectFromHost
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import io.github.mattpvaughn.chronicle.util.isDifferentListById
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
  lateinit var prefsRepo: PrefsRepo

  @Inject
  lateinit var navigator: Navigator

  @Inject
  lateinit var plexConfig: PlexConfig

  var adapter: CollectionsAdapter? = null

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    Timber.i("Lib frag view create")
    val binding = FragmentCollectionsBinding.inflate(inflater, container, false)

    adapter =
      CollectionsAdapter(
        prefsRepo.libraryBookViewStyle,
        true,
        prefsRepo.bookCoverStyle == BOOK_COVER_STYLE_SQUARE,
        object : CollectionClick {
          override fun onClick(collection: Collection) {
            openCollectionDetails(collection)
          }
        },
        plexConfig::toServerString,
      ).apply {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
      }

    binding.collectionsGrid.adapter = adapter

    viewLifecycleOwner.collectWhileStarted(viewModel.collections) { collections ->
      // Was three visibility binding expressions in fragment_collections.xml.
      val isEmpty = collections.isEmpty()
      binding.offlineModeContainer.isVisible = isEmpty
      binding.swipeToRefresh.isVisible = !isEmpty
      binding.noBooksMessage.isVisible = isEmpty

      // Adapter is always non-null between view creation and view destruction
      if (adapter == null) {
        return@collectWhileStarted
      }

      // If there are no previous books, submit normally
      if (adapter!!.currentList.isEmpty()) {
        Timber.i("Updating book list: no previous books")
        adapter!!.submitList(collections)
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
      val isNewList = isDifferentListById(collections, adapter?.currentList?.map { it.id }) { it.id }
      if (isNewList) {
        // submit an empty list to force a scroll-to-top, then when it is done, submit
        // the real list
        Timber.i("Updating book list: scroll to top")
        adapter!!.submitList(null) { adapter?.submitList(collections) }
      }
    }

    viewLifecycleOwner.collectWhileStarted(plexConfig.isConnected) { isConnected ->
      adapter?.setServerConnected(isConnected)
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.viewStyle) { style ->
      Timber.i("View style is: $style")
      val isGrid =
        viewStyleIsGrid(style)
      binding.collectionsGrid.layoutManager =
        if (isGrid) {
          GridLayoutManager(requireContext(), 3)
        } else {
          LinearLayoutManager(requireContext())
        }
      adapter!!.viewStyle = style
    }
    val searchAdapter = GroupedSearchAdapter(onBookClick = { openAudiobookDetails(it) }, coverUrl = plexConfig::toServerString)
    binding.searchResultsList.adapter = searchAdapter

    // Was the `searchBookList`/`serverConnectedSearch` binding adapters on search_results_list.
    // These must stay below the adapter assignment above, since observe() delivers an
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

    binding.disableOfflineMode.setOnClickListener { viewModel.disableOfflineMode() }

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
          // The toolbar inflates `R.menu.collections_menu` itself via `app:menu` in the layout (cu-180), so
          // inflating again here would double every item — which it did, visibly, as two
          // search icons. This provider only wires the items up.
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
   * fragment_collections.xml. Both depend on more than one LiveData, so every source
   * re-evaluates the pair rather than each observer owning one view.
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
    // ran. `error` rather than a silent skip: in production a missing graph is a wiring bug.
    check(injectFromHost { it.inject(this) }) { "CollectionsFragment needs an ActivityComponentHost" }
    super.onAttach(context)
    Timber.i("Reattached!")
  }

  override fun onDestroyView() {
    adapter = null
    super.onDestroyView()
  }

  interface CollectionClick {
    fun onClick(collection: Collection)
  }
}
