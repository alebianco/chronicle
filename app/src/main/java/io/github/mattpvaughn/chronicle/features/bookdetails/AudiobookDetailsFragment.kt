package io.github.mattpvaughn.chronicle.features.bookdetails

import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.view.*
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.widget.Toolbar
import androidx.compose.runtime.getValue
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import io.github.mattpvaughn.chronicle.data.sources.MediaSource
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig.ConnectionState
import io.github.mattpvaughn.chronicle.databinding.FragmentAudiobookDetailsBinding
import io.github.mattpvaughn.chronicle.features.bookdetails.compose.DetailsActions
import io.github.mattpvaughn.chronicle.features.bookdetails.compose.DetailsScreen
import io.github.mattpvaughn.chronicle.features.player.CastMenu
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.features.player.PlayServicesCastAvailability
import io.github.mattpvaughn.chronicle.injection.components.injectFromHost
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInsetAsPinnedBar
import io.github.mattpvaughn.chronicle.util.collectEventsWhileStarted
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import io.github.mattpvaughn.chronicle.views.setBottomChooserState
import io.github.mattpvaughn.chronicle.views.setToolbarMenu
import kotlinx.coroutines.ExperimentalCoroutinesApi
import timber.log.Timber
import javax.inject.Inject

@ExperimentalCoroutinesApi
class AudiobookDetailsFragment : Fragment() {
  companion object {
    fun newInstance() = AudiobookDetailsFragment()

    const val TAG = "details tag"
    const val ARG_AUDIOBOOK_ID = "audiobook_id"
    const val ARG_AUDIOBOOK_TITLE = "ARG_AUDIOBOOK_TITLE"
    const val ARG_IS_AUDIOBOOK_CACHED = "is_audiobook_cached"
  }

  @Inject
  lateinit var prefsRepo: PrefsRepo

  @Inject
  lateinit var navigator: Navigator

  @Inject
  lateinit var trackRepository: ITrackRepository

  @Inject
  lateinit var bookRepository: IBookRepository

  @Inject
  lateinit var plexConfig: PlexConfig

  @Inject
  lateinit var mediaServiceConnection: MediaServiceConnection

  @Inject
  lateinit var viewModelFactory: AudiobookDetailsViewModel.Factory

  lateinit var viewModel: AudiobookDetailsViewModel

  override fun onAttach(context: Context) {
    check(injectFromHost { it.inject(this) }) { "${javaClass.simpleName} needs an ActivityComponentHost" }
    Timber.i("AudiobookDetailsFragment onAttach()")
    super.onAttach(context)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View? {
    Timber.i("AudiobookDetailsFragment onCreateView()")

    val binding = FragmentAudiobookDetailsBinding.inflate(inflater, container, false)

    val inputId = requireArguments().getString(ARG_AUDIOBOOK_ID) ?: NO_AUDIOBOOK_FOUND_ID
    val bookTitle = requireArguments().getString(ARG_AUDIOBOOK_TITLE) ?: ""
    val inputCached = requireArguments().getBoolean(ARG_IS_AUDIOBOOK_CACHED)

    viewModelFactory.inputAudiobook =
      Audiobook(
        id = inputId,
        title = bookTitle,
        source = MediaSource.NO_SOURCE_FOUND,
        isCached = inputCached,
      )
    viewModel =
      ViewModelProvider(this, viewModelFactory)[AudiobookDetailsViewModel::class.java]

    // The header is `DetailsScreen` now (cu-200). This replaces ~24 imperative writes and twelve
    // independent `isVisible` decisions — artwork, title, author, narrator/series, the progress
    // line, the four-flow download control, play/pause and the collapsible summary.
    binding.detailsCompose.setContent {
      val state by viewModel.uiState.collectAsStateWithLifecycle()

      ChronicleTheme {
        DetailsScreen(
          state = state,
          actions =
            DetailsActions(
              onPlayPause = viewModel::pausePlayButtonClicked,
              onDownload = viewModel::onCacheButtonClick,
              onToggleSummary = viewModel::onToggleSummaryView,
              // The series line navigates into the browse facet cu-24 built. It was wired inside
              // `bindMetadataLines`, which is gone — losing it would be a silent feature loss of
              // the kind cu-198 shipped and had to recover.
              onSeriesClick = {
                viewModel.audiobook.value?.let { navigator.showFacetBooks(FacetKind.Series, it.series) }
              },
            ),
          coverUrl = plexConfig::toServerString,
        )
      }
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.serverConnection) { state ->
      binding.connectingToServerIndicator.isVisible = state == ConnectionState.CONNECTING
      binding.connectionFailedMessage.isVisible = state == ConnectionState.CONNECTION_FAILED
    }
    binding.connectionFailedMessage.setOnClickListener { viewModel.connectToServer() }

    // Must run after the tracks adapter is assigned: setChapterList casts
    // recyclerView.adapter, and observe() delivers an already-set value at once.
    viewLifecycleOwner.collectWhileStarted(viewModel.chapters) {
      bindChapterList(binding.tracks, it)
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.bottomChooserState) {
      setBottomChooserState(binding.bottomSheetChooser, it)
    }

    val adapter =
      ChapterListAdapter(
        object : TrackClickListener {
          override fun onClick(chapter: Chapter) {
            Timber.i("Starting chapter with name: ${chapter.title}")
            viewModel.jumpToChapter(
              bookStartTimeOffset = chapter.bookStartTimeOffset,
              trackId = chapter.trackId,
            )
          }
        },
      )
    binding.tracks.adapter = adapter

    // Was `chapterList="@{viewModel.chapters}"` on the list, dropped when cu-58 converted this
    // screen off DataBinding, so the chapter list rendered empty (cu-73). `submitChapters`, not
    // `submitList`: the adapter inserts section headers, and `submitList` is overridden to route
    // through it.
    viewLifecycleOwner.collectWhileStarted(viewModel.chapters) { chapters ->
      adapter.submitChapters(chapters)
    }

    detailsToolbar = binding.detailsToolbar
    binding.detailsToolbar.title = null

    binding.detailsToolbar.setNavigationOnClickListener {
      requireActivity().onBackPressed()
    }

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.messageForUser) { message ->
      Toast.makeText(context, message.format(resources), LENGTH_SHORT).show()
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.activeChapter) { chapter ->
      Timber.i(
        "Updating current chapter: (${chapter.trackId}, ${chapter.discNumber}, ${chapter.index})",
      )
      adapter.updateCurrentChapter(
        trackId = chapter.trackId,
        discNumber = chapter.discNumber,
        chapterIndex = chapter.index,
      )
    }

    // Both observers below reach into the toolbar's menu, which is **not always populated when they
    // fire** (cu-102). `setSupportActionBar` hands the toolbar's menu to the activity's MenuHost,
    // and the provider registered in `onViewCreated` repopulates it only at RESUMED. A LiveData
    // observer, by contrast, becomes active at STARTED and immediately replays its cached value —
    // so on every unlock `findItem` returned null and `.setIcon` threw, killing the process and
    // with it playback. `menuItemOrNull` is the guard; the observers also re-apply in
    // `onPrepareMenu`, which is what makes the state correct rather than merely non-fatal.
    viewLifecycleOwner.collectWhileStarted(viewModel.forceSyncInProgress) { isSyncing ->
      applySyncIconState(isSyncing)
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.isWatchedIcon) { icon ->
      applyWatchedIcon(icon)
    }

    // targetSdk 36 is edge-to-edge; the toolbar must inset itself (cu-63).

    // The *pinned* bar takes the inset, so it paints the status-bar strip itself. Padding the
    // collapsing container instead leaves that strip to the scrolling artwork, which then shows
    // above the toolbar (cu-105).
    binding.pinnedBar.applyTopSystemBarInsetAsPinnedBar()

    return binding.root
  }

  /**
   * The details toolbar while the view exists, cleared in [onDestroyView].
   *
   * Held because the menu observers outlive the local `binding` in [onCreateView] and must not
   * capture a destroyed view.
   */
  private var detailsToolbar: Toolbar? = null

  /** The menu item, or null when the menu has not been populated yet. See cu-102. */
  private fun menuItemOrNull(itemId: Int): MenuItem? = detailsToolbar?.menu?.findItem(itemId)

  private fun applyWatchedIcon(icon: Int?) {
    if (icon == null) return
    menuItemOrNull(R.id.toggle_watched)?.setIcon(icon)
  }

  private fun applySyncIconState(isSyncing: Boolean?) {
    val syncIcon = menuItemOrNull(R.id.force_sync)?.icon
    if (syncIcon is AnimatedVectorDrawable) {
      if (isSyncing == true) syncIcon.start() else syncIcon.stop()
    }
  }

  override fun onViewCreated(
    view: View,
    savedInstanceState: Bundle?,
  ) {
    super.onViewCreated(view, savedInstanceState)

    // The toolbar owns its menu (cu-180): no host cast, no activity MenuHost.
    setToolbarMenu(
      view.findViewById(R.id.details_toolbar),
      object : MenuProvider {
        // @UnstableApi for the Cast route button below; scoped to this callback rather than the
        // Fragment so the opt-in does not silently cover unrelated screen code.
        @UnstableApi
        override fun onCreateMenu(
          menu: Menu,
          menuInflater: MenuInflater,
        ) {
          // The toolbar inflates `R.menu.audiobook_details_menu` itself via `app:menu` in the layout (cu-180), so
          // inflating again here would double every item — which it did, visibly, as two
          // search icons. This provider only wires the items up.
          // Reveals the route button only where Cast can actually work; a no-op otherwise, which
          // is why the menu item ships hidden (cu-168).
          CastMenu.setUp(requireContext(), menu, PlayServicesCastAvailability(requireContext()))
        }

        /**
         * Re-applies the icon state once the menu exists.
         *
         * Without this the guard alone would leave the icons stale: the observers fire while the
         * menu is empty, so their values are dropped and nothing re-delivers them.
         */
        override fun onPrepareMenu(menu: Menu) {
          applyWatchedIcon(viewModel.isWatchedIcon.value)
          applySyncIconState(viewModel.forceSyncInProgress.value)
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
          return when (menuItem.itemId) {
            R.id.toggle_watched -> {
              viewModel.toggleWatched()
              true
            }

            R.id.force_sync -> {
              viewModel.forceSyncBook(hasUserConfirmation = false)
              true
            }

            else -> false
          }
        }
      },
    )
  }

  override fun onDestroyView() {
    // The field outlives the view otherwise, which is a leak of the whole view hierarchy.
    detailsToolbar = null
    super.onDestroyView()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
  }
}
