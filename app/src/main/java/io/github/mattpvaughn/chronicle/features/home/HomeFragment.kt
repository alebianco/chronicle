package io.github.mattpvaughn.chronicle.features.home

import android.os.Bundle
import android.view.*
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.MainActivity
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.BOOK_COVER_STYLE_SQUARE
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLE_COVER_GRID
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.databinding.FragmentHomeBinding
import io.github.mattpvaughn.chronicle.features.library.AudiobookAdapter
import io.github.mattpvaughn.chronicle.features.library.AudiobookSearchAdapter
import io.github.mattpvaughn.chronicle.features.library.LibraryFragment.AudiobookClick
import io.github.mattpvaughn.chronicle.features.library.bindRecyclerView
import io.github.mattpvaughn.chronicle.navigation.Navigator
import javax.inject.Inject

class HomeFragment : Fragment() {
  @Inject
  lateinit var viewModelFactory: HomeViewModel.Factory

  private lateinit var viewModel: HomeViewModel

  @Inject
  lateinit var prefsRepo: PrefsRepo

  @Inject
  lateinit var navigator: Navigator

  @Inject
  lateinit var plexConfig: PlexConfig

  override fun onCreate(savedInstanceState: Bundle?) {
    (requireActivity() as MainActivity).activityComponent!!.inject(this)
    super.onCreate(savedInstanceState)
    viewModel = ViewModelProvider(this, viewModelFactory).get(HomeViewModel::class.java)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View? {
    val binding = FragmentHomeBinding.inflate(inflater, container, false)

    // Was compound visibility expressions across three shelves in fragment_home.xml.
    // XML re-ran the whole condition when any source changed; in Kotlin every
    // contributing source has to drive the shared refresh explicitly.
    fun refreshShelves() {
      val added = viewModel.recentlyAdded.value.orEmpty()
      val listened = viewModel.recentlyListened.value.orEmpty()
      val downloaded = viewModel.downloaded.value.orEmpty()
      val offline = viewModel.offlineMode.value == true
      val allEmpty = added.isEmpty() && listened.isEmpty() && downloaded.isEmpty()

      binding.noBooksMessage.isVisible = allEmpty && !offline
      binding.offlineEmptyMessage.isVisible = allEmpty && offline

      binding.downloadedTitle.isVisible = downloaded.isNotEmpty()
      binding.downloadedRecyclerview.isVisible = downloaded.isNotEmpty()
      binding.recentlyListenedTitle.isVisible = listened.isNotEmpty()
      binding.onDeckRecyclerview.isVisible = listened.isNotEmpty()
      binding.recentlyAddedTitle.isVisible = added.isNotEmpty()
      binding.recentlyAddedRecyclerview.isVisible = added.isNotEmpty()

      bindRecyclerView(binding.downloadedRecyclerview, downloaded)
      bindRecyclerView(binding.onDeckRecyclerview, listened)
      bindRecyclerView(binding.recentlyAddedRecyclerview, added)
    }
    viewModel.recentlyAdded.observe(viewLifecycleOwner) { refreshShelves() }
    viewModel.recentlyListened.observe(viewLifecycleOwner) { refreshShelves() }
    viewModel.downloaded.observe(viewLifecycleOwner) { refreshShelves() }
    viewModel.offlineMode.observe(viewLifecycleOwner) { refreshShelves() }

    plexConfig.isConnected.observe(viewLifecycleOwner) { connected ->
      bindRecyclerView(binding.downloadedRecyclerview, connected == true)
      bindRecyclerView(binding.onDeckRecyclerview, connected == true)
      bindRecyclerView(binding.recentlyAddedRecyclerview, connected == true)
    }

    binding.disableOfflineMode.setOnClickListener { viewModel.disableOfflineMode() }

    binding.recentlyAddedRecyclerview.adapter = makeAudiobookAdapter()
    binding.recentlyAddedRecyclerview.itemAnimator?.changeDuration = 0
    binding.onDeckRecyclerview.adapter = makeAudiobookAdapter()
    binding.onDeckRecyclerview.itemAnimator?.changeDuration = 0
    binding.downloadedRecyclerview.adapter = makeAudiobookAdapter()
    binding.downloadedRecyclerview.itemAnimator?.changeDuration = 0
    binding.searchResultsList.adapter =
      AudiobookSearchAdapter(
        object : AudiobookClick {
          override fun onClick(audiobook: Audiobook) {
            openAudiobookDetails(audiobook)
          }
        },
      )

    binding.swipeToRefresh.setOnRefreshListener {
      viewModel.refreshData()
    }

    viewModel.isRefreshing.observe(
      viewLifecycleOwner,
      Observer {
        binding.swipeToRefresh.isRefreshing = it
      },
    )

    viewModel.messageForUser.observe(
      viewLifecycleOwner,
      Observer {
        if (!it.hasBeenHandled) {
          Toast.makeText(context, it.getContentIfNotHandled(), LENGTH_SHORT).show()
        }
      },
    )

    (activity as MainActivity).setSupportActionBar(binding.toolbar)

    return binding.root
  }

  override fun onViewCreated(
    view: View,
    savedInstanceState: Bundle?,
  ) {
    super.onViewCreated(view, savedInstanceState)

    val menuHost: MenuHost = requireActivity()
    menuHost.addMenuProvider(
      object : MenuProvider {
        override fun onCreateMenu(
          menu: Menu,
          menuInflater: MenuInflater,
        ) {
          menuInflater.inflate(R.menu.home_menu, menu)
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
      viewLifecycleOwner,
      Lifecycle.State.RESUMED,
    )
  }

  private fun makeAudiobookAdapter(): AudiobookAdapter {
    return AudiobookAdapter(
      initialViewStyle = VIEW_STYLE_COVER_GRID,
      isVertical = false,
      isSquare = prefsRepo.bookCoverStyle == BOOK_COVER_STYLE_SQUARE,
      audiobookClick =
        object : AudiobookClick {
          override fun onClick(audiobook: Audiobook) {
            openAudiobookDetails(audiobook)
          }
        },
    )
  }

  fun openAudiobookDetails(audiobook: Audiobook) {
    navigator.showDetails(audiobook.id, audiobook.title, audiobook.isCached)
  }

  companion object {
    const val TAG: String = "home tag"

    @JvmStatic
    fun newInstance() = HomeFragment()
  }
}
