package io.github.mattpvaughn.chronicle.features.home

import android.os.Bundle
import android.view.*
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.widget.SearchView
import androidx.compose.runtime.getValue
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.databinding.FragmentHomeBinding
import io.github.mattpvaughn.chronicle.features.home.compose.HomeScreen
import io.github.mattpvaughn.chronicle.features.library.LibraryFragment.AudiobookClick
import io.github.mattpvaughn.chronicle.features.search.compose.SearchOverlay
import io.github.mattpvaughn.chronicle.features.search.searchOverlayState
import io.github.mattpvaughn.chronicle.injection.components.injectFromHost
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
import io.github.mattpvaughn.chronicle.util.collectEventsWhileStarted
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import io.github.mattpvaughn.chronicle.views.setToolbarMenu
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
    // Asks the host for a graph rather than casting to `MainActivity` (cu-178), which is what
    // lets this screen be launched into a generic host by `FragmentScenario`.
    check(injectFromHost { it.inject(this) }) { "HomeFragment needs an ActivityComponentHost" }
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
    // The three shelves and all three empty states are `HomeScreen` now (cu-201). This replaces
    // `refreshShelves()` — four `.value` reads driving eight independent `isVisible` writes — plus
    // three `AudiobookAdapter` instances and three `itemAnimator.changeDuration = 0` workarounds
    // that existed only because a RecyclerView animates a rebind (cu-110).
    // Search is Compose too now (cu-202) — `GroupedSearchAdapter` was instantiated separately by
    // three screens, and the "is there anything to show" decision was written out in each. It is
    // `searchOverlayState` once, so home can no longer differ from library about what an empty
    // query means. The three dropped XML bindings this used to compensate for (cu-73) are gone
    // with the RecyclerView.
    binding.homeCompose.setContent {
      val state by viewModel.uiState.collectAsStateWithLifecycle()
      val isConnected by plexConfig.isConnected.collectAsStateWithLifecycle()
      val rows by viewModel.searchRows.collectAsStateWithLifecycle()
      val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
      val isQueryEmpty by viewModel.isQueryEmpty.collectAsStateWithLifecycle()

      ChronicleTheme {
        HomeScreen(
          state = state.copy(serverConnected = isConnected),
          coverUrl = plexConfig::toServerString,
          onBookClick = ::openAudiobookDetails,
          // Continue Listening resumes rather than opening details (cu-18).
          onResumeClick = { viewModel.resume(it) },
          onDisableOfflineMode = viewModel::disableOfflineMode,
        )

        // Drawn *over* the shelves, which is what the RecyclerView's `elevation="8dp"` did.
        SearchOverlay(
          state = searchOverlayState(isSearchActive, isQueryEmpty, rows),
          serverConnected = isConnected,
          coverUrl = plexConfig::toServerString,
          onBookClick = ::openAudiobookDetails,
        )
      }
    }

    binding.swipeToRefresh.setOnRefreshListener {
      viewModel.refreshData()
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.isRefreshing) {
      binding.swipeToRefresh.isRefreshing = it
    }

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.messageForUser) { message ->
      Toast.makeText(context, message, LENGTH_SHORT).show()
    }

    // A resume that could not start — offline with an uncached book. A tap that silently does
    // nothing is the worst outcome: the user cannot tell a broken app from an unavailable book.
    viewLifecycleOwner.collectEventsWhileStarted(viewModel.resumeError) { messageRes ->
      Toast.makeText(context, getString(messageRes), LENGTH_SHORT).show()
    }

    // A refresh failure. It arrives as a string resource because it is raised on an IO
    // dispatcher, where `Toast.show()` throws; the toast belongs here, on the main thread.
    viewLifecycleOwner.collectEventsWhileStarted(viewModel.syncError) { messageRes ->
      Toast.makeText(context, getString(messageRes), LENGTH_SHORT).show()
    }

    // targetSdk 36 is edge-to-edge; the toolbar must inset itself (cu-63).

    binding.toolbarLayout.applyTopSystemBarInset()

    return binding.root
  }

  override fun onViewCreated(
    view: View,
    savedInstanceState: Bundle?,
  ) {
    super.onViewCreated(view, savedInstanceState)

    // The toolbar owns its menu (cu-180): no host cast, no activity MenuHost.
    setToolbarMenu(
      view.findViewById(R.id.toolbar),
      object : MenuProvider {
        override fun onCreateMenu(
          menu: Menu,
          menuInflater: MenuInflater,
        ) {
          // The toolbar inflates `R.menu.home_menu` itself via `app:menu` in the layout (cu-180), so
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
  }

  /** Opens the details screen — the right default for a book that has not been started. */
  private val openDetails =
    object : AudiobookClick {
      override fun onClick(audiobook: Audiobook) = openAudiobookDetails(audiobook)
    }

  /**
   * Resumes on tap, with the details screen on a long press.
   *
   * A shelf whose premise is "carry on where you left off" should not need a second screen and a
   * second tap to do it (cu-18).
   */
  private val resumeOnClick =
    object : AudiobookClick {
      // `viewModel` is a lateinit set in onCreate, and these properties initialize during
      // construction — so the read has to stay inside the lambda body, where it happens at click
      // time. Hoisting it to the initializer would throw on the first Home render.
      override fun onClick(audiobook: Audiobook) = viewModel.resume(audiobook)

      override fun onLongClick(audiobook: Audiobook): Boolean {
        openAudiobookDetails(audiobook)
        return true
      }
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
