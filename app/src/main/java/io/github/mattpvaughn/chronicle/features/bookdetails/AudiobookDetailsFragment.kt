package io.github.mattpvaughn.chronicle.features.bookdetails

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.view.*
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
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
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager.CacheStatus
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig.ConnectionState
import io.github.mattpvaughn.chronicle.databinding.FragmentAudiobookDetailsBinding
import io.github.mattpvaughn.chronicle.features.player.CastMenu
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.features.player.PlayServicesCastAvailability
import io.github.mattpvaughn.chronicle.injection.components.injectFromHost
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInsetAsPinnedBar
import io.github.mattpvaughn.chronicle.util.collectEventsWhileStarted
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import io.github.mattpvaughn.chronicle.views.bindImageRounded
import io.github.mattpvaughn.chronicle.views.setBottomChooserState
import io.github.mattpvaughn.chronicle.views.setToolbarMenu
import kotlinx.coroutines.ExperimentalCoroutinesApi
import timber.log.Timber
import javax.inject.Inject

@ExperimentalCoroutinesApi
class AudiobookDetailsFragment : Fragment() {
  /**
   * The narrator and series lines, shown only when there is something to say (cu-145).
   *
   * Each row is `gone` in XML and un-hidden here, rather than shown-and-blanked: cu-24 learns
   * narrator and series only for books the user has opened, and cu-146 leaves the series position
   * unknown wherever the tagging carried no number — so most books are missing one or both today.
   * An empty "Narrated by" line states the book has no narrator, which is a wrong claim rather
   * than a missing one.
   *
   * The series line navigates into the browse facet for that series, which cu-24 already built;
   * the tap target is only attached when there is a series to reach, so a dead row cannot ripple.
   */
  private fun bindMetadataLines(
    binding: FragmentAudiobookDetailsBinding,
    book: Audiobook?,
  ) {
    val narrator = book?.let { BookMetadataLines.narrator(it) }
    binding.narrator.isVisible = narrator != null
    if (narrator != null) {
      binding.narrator.text = getString(R.string.book_narrated_by, narrator)
    }

    val series = book?.let { BookMetadataLines.series(it) }
    binding.series.isVisible = series != null
    if (series != null && book != null) {
      binding.series.text = series
      // Spoken as an action, since it is tappable and the text alone reads as a label (cu-149's
      // lesson: a control's label must say what a tap does).
      binding.series.contentDescription = getString(R.string.book_series_browse, book.series)
      binding.series.setOnClickListener {
        navigator.showFacetBooks(FacetKind.Series, book.series)
      }
    } else {
      // Clear the listener rather than leaving a stale one on a recycled view.
      binding.series.setOnClickListener(null)
      binding.series.isClickable = false
    }
  }

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

    // Was 29 binding expressions in fragment_audiobook_details.xml.
    viewLifecycleOwner.collectWhileStarted(viewModel.audiobook) { book ->
      binding.bookTitle.text = book?.title.orEmpty()
      binding.author.text = book?.author.orEmpty()
      bindMetadataLines(binding, book)
      binding.infoSummary.text = book?.summary.orEmpty()
      binding.detailsArtwork.contentDescription = book?.title.orEmpty()
      bindImageRounded(
        binding.detailsArtwork,
        book?.thumb,
        plexConfig.isConnected.value,
        plexConfig::toServerString,
      )
    }
    viewLifecycleOwner.collectWhileStarted(plexConfig.isConnected) { connected ->
      bindImageRounded(
        binding.detailsArtwork,
        viewModel.audiobook.value?.thumb,
        connected,
        plexConfig::toServerString,
      )
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.progressString) { binding.progress.text = it }
    viewLifecycleOwner.collectWhileStarted(viewModel.progressPercentageString) {
      binding.progressPercentage.text = it
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.cacheStatus) { status ->
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
    viewLifecycleOwner.collectWhileStarted(viewModel.cacheIconDrawable) {
      binding.download.setImageResource(it)
    }
    // Immediately beside the icon it labels, so the two cannot drift apart again (cu-149). The
    // layout's static `android:contentDescription` is gone: it said "Download" for a book that was
    // already downloaded, which is what a screen reader announced.
    viewLifecycleOwner.collectWhileStarted(viewModel.cacheContentDescription) {
      binding.download.contentDescription = getString(it)
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.cacheIconTint) { tint ->
      binding.download.imageTintList = ColorStateList.valueOf(tint)
    }
    binding.download.setOnClickListener { viewModel.onCacheButtonClick() }
    binding.cachingTracksSpinner.setOnClickListener { viewModel.onCacheButtonClick() }

    viewLifecycleOwner.collectWhileStarted(viewModel.isBookInViewPlaying) { playing ->
      binding.detailsPausePlay.setImageResource(
        if (playing) {
          R.drawable.ic_pause_button_large_colored
        } else {
          R.drawable.ic_play_button_large_colored
        },
      )
    }
    binding.detailsPausePlay.setOnClickListener { viewModel.pausePlayButtonClicked() }
    viewLifecycleOwner.collectWhileStarted(viewModel.isAudioLoading) { loading ->
      binding.audioLoadingSpinner.isVisible = loading
      binding.detailsPausePlay.isVisible = !loading
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.summaryLinesShown) {
      binding.infoSummary.maxLines = it
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.showSummary) { show ->
      binding.infoSummary.isVisible = show
      binding.infoExpandSummary.isVisible = show
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.isExpanded) { expanded ->
      binding.infoExpandSummary.text = getString(if (expanded) R.string.less else R.string.more)
    }
    binding.infoExpandSummary.setOnClickListener { viewModel.onToggleSummaryView() }

    viewLifecycleOwner.collectWhileStarted(viewModel.isLoadingTracks) {
      binding.loadingTracksSpinner.isVisible = it
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
