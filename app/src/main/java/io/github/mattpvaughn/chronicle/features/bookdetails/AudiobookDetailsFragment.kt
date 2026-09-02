package io.github.mattpvaughn.chronicle.features.bookdetails

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.view.*
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.MainActivity
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import io.github.mattpvaughn.chronicle.data.sources.MediaSource
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager.CacheStatus
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig.ConnectionState
import io.github.mattpvaughn.chronicle.databinding.FragmentAudiobookDetailsBinding
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInsetAsPinnedBar
import io.github.mattpvaughn.chronicle.util.observeEvent
import io.github.mattpvaughn.chronicle.views.bindImageRounded
import io.github.mattpvaughn.chronicle.views.setBottomChooserState
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
    (requireActivity() as MainActivity).activityComponent!!.inject(this)
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

    // Was 29 binding expressions in fragment_audiobook_details.xml.
    viewModel.audiobook.observe(viewLifecycleOwner) { book ->
      binding.bookTitle.text = book?.title.orEmpty()
      binding.author.text = book?.author.orEmpty()
      binding.infoSummary.text = book?.summary.orEmpty()
      binding.detailsArtwork.contentDescription = book?.title.orEmpty()
      bindImageRounded(
        binding.detailsArtwork,
        book?.thumb,
        plexConfig.isConnected.value == true,
      )
    }
    plexConfig.isConnected.observe(viewLifecycleOwner) { connected ->
      bindImageRounded(
        binding.detailsArtwork,
        viewModel.audiobook.value?.thumb,
        connected == true,
      )
    }

    viewModel.progressString.observe(viewLifecycleOwner) { binding.progress.text = it }
    viewModel.progressPercentageString.observe(viewLifecycleOwner) {
      binding.progressPercentage.text = it
    }

    viewModel.cacheStatus.observe(viewLifecycleOwner) { status ->
      binding.cachingTracksSpinner.isVisible = status == CacheStatus.CACHING
      // INVISIBLE, not GONE: the icon keeps its slot while the spinner overlays it.
      binding.download.visibility =
        if (status == CacheStatus.CACHING) View.INVISIBLE else View.VISIBLE
      // Disabled until the status is known, so a press cannot be silently swallowed (cu-92).
      // `android:enabled="false"` in the layout is the matching default — without it the button
      // renders enabled for a frame before this first fires.
      val statusKnown = status != null
      binding.download.isEnabled = statusKnown
      binding.cachingTracksSpinner.isEnabled = statusKnown
    }
    viewModel.cacheIconDrawable.observe(viewLifecycleOwner) { binding.download.setImageResource(it) }
    viewModel.cacheIconTint.observe(viewLifecycleOwner) { tint ->
      binding.download.imageTintList = ColorStateList.valueOf(tint)
    }
    binding.download.setOnClickListener { viewModel.onCacheButtonClick() }
    binding.cachingTracksSpinner.setOnClickListener { viewModel.onCacheButtonClick() }

    viewModel.isBookInViewPlaying.observe(viewLifecycleOwner) { playing ->
      binding.detailsPausePlay.setImageResource(
        if (playing == true) {
          R.drawable.ic_pause_button_large_colored
        } else {
          R.drawable.ic_play_button_large_colored
        },
      )
    }
    binding.detailsPausePlay.setOnClickListener { viewModel.pausePlayButtonClicked() }
    viewModel.isAudioLoading.observe(viewLifecycleOwner) { loading ->
      binding.audioLoadingSpinner.isVisible = loading == true
      binding.detailsPausePlay.isVisible = loading != true
    }

    viewModel.summaryLinesShown.observe(viewLifecycleOwner) { binding.infoSummary.maxLines = it }
    viewModel.showSummary.observe(viewLifecycleOwner) { show ->
      binding.infoSummary.isVisible = show == true
      binding.infoExpandSummary.isVisible = show == true
    }
    viewModel.isExpanded.observe(viewLifecycleOwner) { expanded ->
      binding.infoExpandSummary.text =
        getString(if (expanded == true) R.string.less else R.string.more)
    }
    binding.infoExpandSummary.setOnClickListener { viewModel.onToggleSummaryView() }

    viewModel.isLoadingTracks.observe(viewLifecycleOwner) {
      binding.loadingTracksSpinner.isVisible = it == true
    }
    viewModel.serverConnection.observe(viewLifecycleOwner) { state ->
      binding.connectingToServerIndicator.isVisible = state == ConnectionState.CONNECTING
      binding.connectionFailedMessage.isVisible = state == ConnectionState.CONNECTION_FAILED
    }
    binding.connectionFailedMessage.setOnClickListener { viewModel.connectToServer() }

    // Must run after the tracks adapter is assigned: setChapterList casts
    // recyclerView.adapter, and observe() delivers an already-set value at once.
    viewModel.chapters.observe(viewLifecycleOwner) { bindChapterList(binding.tracks, it) }
    viewModel.bottomChooserState.observe(viewLifecycleOwner) {
      setBottomChooserState(binding.bottomSheetChooser, it)
    }

    val adapter =
      ChapterListAdapter(
        object : TrackClickListener {
          override fun onClick(chapter: Chapter) {
            Timber.i("Starting chapter with name: ${chapter.title}")
            viewModel.jumpToChapter(
              offset = chapter.bookStartTimeOffset,
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
    viewModel.chapters.observe(viewLifecycleOwner) { chapters ->
      adapter.submitChapters(chapters)
    }

    // TODO casting
//        val menu = binding.detailsToolbar.menu
//        val mediaRouteButton = menu.findItem(R.id.media_route_menu_item).actionView
//
//        if (castContext.castState != CastState.NO_DEVICES_AVAILABLE) {
//            mediaRouteButton.visibility = View.VISIBLE
//        }
//
//        castContext.addCastStateListener { state ->
//            if (state == CastState.NO_DEVICES_AVAILABLE) {
//                mediaRouteButton.visibility = View.GONE
//            } else {
//                if (mediaRouteButton.visibility == View.GONE) {
//                    mediaRouteButton.visibility = View.VISIBLE
//                }
//            }
//        }

    detailsToolbar = binding.detailsToolbar
    (activity as AppCompatActivity).setSupportActionBar(binding.detailsToolbar)
    binding.detailsToolbar.title = null

    binding.detailsToolbar.setNavigationOnClickListener {
      requireActivity().onBackPressed()
    }

    viewModel.messageForUser.observeEvent(viewLifecycleOwner) { message ->
      Toast.makeText(context, message.format(resources), LENGTH_SHORT).show()
    }

    viewModel.activeChapter.observe(viewLifecycleOwner) { chapter ->
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
    viewModel.forceSyncInProgress.observe(viewLifecycleOwner) { isSyncing ->
      applySyncIconState(isSyncing)
    }

    viewModel.isWatchedIcon.observe(viewLifecycleOwner) { icon ->
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

    val menuHost: MenuHost = requireActivity()
    menuHost.addMenuProvider(
      object : MenuProvider {
        override fun onCreateMenu(
          menu: Menu,
          menuInflater: MenuInflater,
        ) {
          menuInflater.inflate(R.menu.audiobook_details_menu, menu)
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
      viewLifecycleOwner,
      Lifecycle.State.RESUMED,
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
