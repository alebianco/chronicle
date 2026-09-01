package io.github.mattpvaughn.chronicle.features.bookdetails

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.view.*
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.app.AppCompatActivity
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
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
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
              offset = chapter.startTimeOffset,
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

    viewModel.forceSyncInProgress.observe(viewLifecycleOwner) { isSyncing ->
      val syncMenuItem = binding.detailsToolbar.menu.findItem(R.id.force_sync)
      val syncIcon = syncMenuItem.icon
      if (syncIcon is AnimatedVectorDrawable) {
        if (isSyncing) syncIcon.start() else syncIcon.stop()
      }
    }

    viewModel.isWatchedIcon.observe(viewLifecycleOwner) { icon ->
      Timber.d("isWatchedIcon.observe called")
      binding.detailsToolbar.menu.findItem(R.id.toggle_watched).setIcon(icon)
    }

    // targetSdk 36 is edge-to-edge; the toolbar must inset itself (cu-63).

    binding.appBarLayout.applyTopSystemBarInset()

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
          menuInflater.inflate(R.menu.audiobook_details_menu, menu)
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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
  }
}
