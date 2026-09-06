package io.github.mattpvaughn.chronicle.features.currentlyplaying

import android.content.Context
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.slider.Slider
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.MainActivity
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState.COLLAPSED
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.databinding.FragmentCurrentlyPlayingBinding
import io.github.mattpvaughn.chronicle.features.bookdetails.ChapterListAdapter
import io.github.mattpvaughn.chronicle.features.bookdetails.TrackClickListener
import io.github.mattpvaughn.chronicle.features.player.SleepTimer
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInsetAsPinnedBar
import io.github.mattpvaughn.chronicle.util.collectEventsWhileStarted
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import io.github.mattpvaughn.chronicle.util.formatPrecisePosition
import io.github.mattpvaughn.chronicle.util.setImageResourceIfChanged
import io.github.mattpvaughn.chronicle.util.setTextIfChanged
import io.github.mattpvaughn.chronicle.views.ModalBottomSheetBookmarkNote
import io.github.mattpvaughn.chronicle.views.ModalBottomSheetBookmarks
import io.github.mattpvaughn.chronicle.views.ModalBottomSheetSpeedChooser
import io.github.mattpvaughn.chronicle.views.bindImageRounded
import io.github.mattpvaughn.chronicle.views.setBottomChooserState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import timber.log.Timber
import javax.inject.Inject

/** Responsible for playback controls and displaying the currently playing media */
@ExperimentalCoroutinesApi
class CurrentlyPlayingFragment :
  Fragment(),
  ModalBottomSheetBookmarkNote.Listener,
  ModalBottomSheetBookmarks.Listener {
  private lateinit var currentlyPlayingInterface: MainActivity.CurrentlyPlayingInterface

  @Inject
  lateinit var plexConfig: PlexConfig

  @Inject
  lateinit var viewModelFactory: CurrentlyPlayingViewModel.Factory

  @Inject
  lateinit var localBroadcastManager: LocalBroadcastManager

  private val viewModel: CurrentlyPlayingViewModel by lazy {
    ViewModelProvider(this, viewModelFactory).get(CurrentlyPlayingViewModel::class.java)
  }

  /**
   * What [renderPlayerArtwork] last bound, so an unchanged book skips the rebind (cu-117).
   *
   * Fields on the fragment rather than locals in `onCreateView`, because they must persist across
   * emissions. Reset with the view, since a recreated view has nothing bound.
   */
  private var boundTitle: String? = null
  private var boundThumb: String? = null

  /**
   * Opens the bookmark list, keeping it fed while it is shown.
   *
   * The sheet holds no repository: this observes and pushes, so the subscription belongs to the
   * Fragment's lifecycle and dies with it.
   */
  private fun showBookmarkList() {
    val sheet = ModalBottomSheetBookmarks()
    sheet.show(childFragmentManager, ModalBottomSheetBookmarks.TAG)
    viewLifecycleOwner.collectWhileStarted(viewModel.bookmarks) { bookmarks ->
      sheet.setBookmarks(bookmarks)
    }
  }

  override fun onBookmarkJump(bookmark: Bookmark) {
    viewModel.jumpToBookmark(bookmark)
  }

  override fun onBookmarkEdit(bookmark: Bookmark) {
    ModalBottomSheetBookmarkNote.forBookmark(
      bookmarkId = bookmark.id,
      positionMillis = bookmark.position.millis,
      note = bookmark.note,
    ).show(childFragmentManager, ModalBottomSheetBookmarkNote.TAG)
  }

  override fun onNoteSaved(
    bookmarkId: String,
    note: String,
  ) {
    viewModel.setBookmarkNote(bookmarkId, note)
  }

  override fun onBookmarkDeleted(bookmarkId: String) {
    viewModel.deleteBookmark(bookmarkId)
  }

  companion object {
    fun newInstance() = CurrentlyPlayingFragment()
  }

  override fun onAttach(context: Context) {
    currentlyPlayingInterface = (context as MainActivity).getCurrentlyPlayingInterface()
    context.activityComponent!!.inject(this)
    super.onAttach(context as Context)
  }

  override fun onStart() {
    super.onStart()
    localBroadcastManager.registerReceiver(
      viewModel.onUpdateSleepTimer,
      IntentFilter(SleepTimer.ACTION_SLEEP_TIMER_CHANGE),
    )
  }

  override fun onStop() {
    localBroadcastManager.unregisterReceiver(viewModel.onUpdateSleepTimer)
    super.onStop()
  }

  /**
   * Writes the expanded player's text, but only while it is on screen.
   *
   * Same reasoning as [refreshSlider]'s `isShown` guard (cu-110), applied to the five text
   * observers that were left unguarded (cu-117). Each `TextView.text` write invalidates and
   * re-measures; five of them on every 1 Hz tick, for a sheet the user cannot see, is the
   * measured cause of the remaining playback jank.
   *
   * Uses the seekbar as the probe rather than each view in turn: they live in the same sheet, so
   * one ancestor chain decides all of them. It must be a view that is present in **both**
   * orientations — probing `binding.progress` blanked the whole block in landscape, where that
   * view used to be GONE (cu-19). cu-141 has since stopped `progress` keying its visibility off
   * the artwork, but the seekbar remains the right probe: it is the one view here that no
   * orientation hides.
   */
  private fun renderPlayerText(binding: FragmentCurrentlyPlayingBinding) {
    val strings: StringResolver = { resId, args -> getString(resId, *args) }
    // Two things must hold before writing text, and `isShown` alone establishes neither.
    //
    // It reports only the visibility *flags* up the ancestor chain. The player lives in a
    // bottom sheet whose container collapses to **zero height** rather than going GONE, and
    // every child keeps `VISIBLE` with real bounds inside it — measured while collapsed:
    // `seekShown=true seekW=1824` with the fragment root at `height=0`. So the guard passed,
    // text was written into a hierarchy with no room, and `wrap_content` views below the
    // collapsed region measured to zero width. That is why the book-progress line was blank
    // in landscape *intermittently*: whether it recovered depended on which tick happened to
    // land after an expand, not on any constraint (cu-141).
    //
    // Hence the height check. `binding.root.height` is the sheet's own resolved height, so it
    // is zero exactly while collapsed and non-zero once expanded — in both orientations, and
    // without naming any view that one of them hides.
    if (!binding.chapterProgressSeekbar.isShown || binding.root.height == 0) {
      return
    }

    // `setText` with an equal CharSequence still invalidates, so compare first — the strings
    // genuinely repeat, since a second of a 47-hour book leaves the readout unchanged.
    //
    // Two-level, human-formatted progress, never raw h:mm:ss/h:mm:ss (§3.1 rule 3, cu-19):
    // "Ch 3 of 6" · "2:30 left in chapter" on one line, "6h 12m left in book" on the other.
    val progress = viewModel.playerProgress.value
    binding.progress.setTextIfChanged(PlayerText.bookProgress(progress, strings))
    binding.progressPercentage.setTextIfChanged(
      viewModel.progressPercentageString.value,
    )
    binding.chapterProgress.setTextIfChanged(PlayerText.chapterPosition(progress, strings))
    binding.chapterDuration.setTextIfChanged(PlayerText.chapterRemaining(progress, strings))

    val currentChapter = viewModel.currentChapter.value
    binding.chapterTitle.setTextIfChanged(
      if (currentChapter?.title.isNullOrEmpty()) {
        viewModel.currentTrack.value.title
      } else {
        currentChapter?.title.orEmpty()
      },
    )
  }

  /**
   * Binds the expanded player's cover art, only when the displayed book actually changed.
   *
   * `audiobook` is Room-backed and `ProgressUpdater` rewrites `Audiobook.progress` every second,
   * so this emitted at tick rate with identical title and artwork while `bindImageRounded` did a
   * Dagger lookup, a `Uri` parse and a Coil load each time. `MainActivity` already guards its
   * mini-player copy this way (cu-117); this is the expanded sheet's.
   *
   * Not gated on `isShown`: the artwork must be bound before the sheet is expanded, or it opens
   * blank. The change check is what makes it cheap.
   */
  private fun renderPlayerArtwork(binding: FragmentCurrentlyPlayingBinding) {
    val book = viewModel.audiobook.value
    val title = book?.title.orEmpty()
    val thumb = book?.thumb
    if (title == boundTitle && thumb == boundThumb) {
      return
    }
    boundTitle = title
    boundThumb = thumb

    binding.bookTitle.setTextIfChanged(title)
    binding.detailsArtwork.contentDescription = title
    bindImageRounded(binding.detailsArtwork, thumb, plexConfig.isConnected.value, plexConfig::toServerString)
  }

  // The slider falls back to track values when there is no chapter. valueTo
  // must be set before value: Material's Slider throws if value falls outside
  // the current range, which DataBinding handled internally.
  private fun refreshSlider(binding: FragmentCurrentlyPlayingBinding) {
    // The guard belongs *here*, at the write, not on the sources. Four observers call this and
    // only two of them carried the `isSliding` filter — `currentTrack` and `chapterDuration` are
    // unfiltered and fire on every playback tick, so the stale position reached the thumb anyway.
    // Filtering the flows was not enough; this is the single line that moves the slider (cu-93).
    if (viewModel.isSliding.value) {
      return
    }

    // Nothing to refresh while the sheet is not on screen (DRAFT-117). All four observers fire
    // on every 1 Hz progress tick whether or not the player is visible, and each write to
    // `valueTo`/`value` invalidates the Slider — so a *collapsed* sheet was driving four full
    // measure/layout passes a second over the whole activity. Measured: 89 observer firings and
    // 33 refreshes in 18 s, against 1312 `View.measure` calls, at 87% janky frames.
    //
    // `isShown` accounts for every ancestor's visibility *flags*, so a backgrounded fragment
    // and a hidden container read false — but a **collapsed bottom sheet does not**. It
    // collapses to zero height with every child still `VISIBLE`, so `isShown` stays true and
    // this guard passed while nothing was on screen (an earlier version of this comment
    // claimed otherwise; it was measured wrong — cu-141). The height check is what actually
    // establishes "the sheet is open".
    //
    // The sheet re-reads current values from the ViewModel when it is expanded, so nothing is
    // stale — this only skips work whose result cannot be seen.
    if (!binding.chapterProgressSeekbar.isShown || binding.root.height == 0) {
      return
    }

    val chapterDuration = viewModel.chapterDuration.value
    val trackDuration = viewModel.currentTrack.value.duration
    val max = (if (chapterDuration == 0L) trackDuration else chapterDuration).toFloat()
    // Chapter progress when there is a chapter to be inside, track progress otherwise. As
    // `LiveData` the "no chapter" case was a null coalesced to -1; as a non-null `StateFlow` it
    // is expressed directly, by asking whether the chapter has a duration at all. Coalescing to
    // 0 instead would have made this fall back never — a chapter-less book would read 0 forever.
    val current =
      if (chapterDuration == 0L) {
        viewModel.trackProgressForSlider.value
      } else {
        viewModel.chapterProgressForSlider.value
      }

    val newMax = if (max > 0f) max else 1f
    val newValue = current.toFloat().coerceIn(0f, newMax)

    // Only write when the value actually changed. Material's Slider invalidates on every
    // assignment, even an identical one, and four observers assigning the same number per tick
    // is four redundant invalidations.
    if (binding.chapterProgressSeekbar.valueTo != newMax) {
      binding.chapterProgressSeekbar.valueTo = newMax
    }
    if (binding.chapterProgressSeekbar.value != newValue) {
      binding.chapterProgressSeekbar.value = newValue
    }
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    // Activity and context are non-null on view creation. This informs lint about that
    val binding = FragmentCurrentlyPlayingBinding.inflate(inflater, container, false)

    // A new view has nothing bound, so the artwork guard must not think it already did the work
    // — otherwise the recreated sheet opens with no cover (cu-117).
    boundTitle = null
    boundThumb = null

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.showUserMessage) { message ->
      Toast.makeText(context, message, LENGTH_SHORT).show()
    }

    // A new bookmark opens its note sheet straight away, so writing one is part of the same
    // gesture rather than something to go and find afterwards (cu-22). The note is optional —
    // dismissing the sheet leaves a perfectly good positional bookmark.
    viewLifecycleOwner.collectEventsWhileStarted(viewModel.bookmarkAdded) { bookmark ->
      ModalBottomSheetBookmarkNote.forBookmark(
        bookmarkId = bookmark.id,
        positionMillis = bookmark.position.millis,
        note = bookmark.note,
      ).show(childFragmentManager, ModalBottomSheetBookmarkNote.TAG)
    }

    // Was 29 binding expressions in fragment_currently_playing.xml.
    binding.skipToPrevious.setOnClickListener { viewModel.skipToPrevious() }
    binding.rewindButton.setOnClickListener { viewModel.skipBackwards() }
    binding.detailsPausePlay.setOnClickListener { viewModel.play() }
    binding.skipForwardButton.setOnClickListener { viewModel.skipForwards() }
    binding.skipToNext.setOnClickListener { viewModel.skipToNext() }
    binding.changeSpeedButton.setOnClickListener { viewModel.showPlaybackSpeedChooser() }
    binding.sleepTimerButton.setOnClickListener { viewModel.showSleepTimerOptions() }
    binding.bookmarkButton.setOnClickListener { viewModel.addBookmark() }
    // Long-press lists them. A tap is the frequent action (mark this moment) and gets the plain
    // press; browsing is rarer, so it takes the long one — §3.1 rule 2, one tray icon per job.
    binding.bookmarkButton.setOnLongClickListener {
      showBookmarkList()
      true
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.hasFailedProgressSync) { failed ->
      binding.syncFailedBadge.isVisible = failed
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.jumpBackwardsIcon) {
      binding.rewindButton.setImageResourceIfChanged(it)
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.jumpForwardsIcon) {
      binding.skipForwardButton.setImageResourceIfChanged(it)
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.isPlaying) { playing ->
      binding.detailsPausePlay.setImageResourceIfChanged(
        if (playing) {
          R.drawable.ic_pause_button_large_colored
        } else {
          R.drawable.ic_play_button_large_colored
        },
      )
    }

    // Buffering: show the spinner *instead of* the play/pause icon rather than over it, so the two
    // do not overlap. INVISIBLE, not GONE — the icon keeps its slot so the row does not reflow, the
    // same reasoning as the download spinner on the details screen (cu-95).
    //
    // The control stays clickable while buffering: cancelling a stalled start is exactly when a
    // listener wants to press it.
    viewLifecycleOwner.collectWhileStarted(viewModel.isAudioLoading) { loading ->
      binding.audioLoadingSpinner.isVisible = loading
      binding.detailsPausePlay.visibility = if (loading) View.INVISIBLE else View.VISIBLE
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.playbackSpeedString) {
      binding.changeSpeedButton.setTextIfChanged(it)
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.isSleepTimerActive) { active ->
      binding.sleepTimerButton.imageTintList =
        ColorStateList.valueOf(
          ContextCompat.getColor(
            requireContext(),
            if (active == true) R.color.iconActive else R.color.icon,
          ),
        )
      binding.sleepTimerCountdown.isVisible = active
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.sleepTimerTimeRemainingString) {
      binding.sleepTimerCountdown.setTextIfChanged(it)
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.chapterDuration) { refreshSlider(binding) }
    viewLifecycleOwner.collectWhileStarted(viewModel.currentTrack) { refreshSlider(binding) }
    viewLifecycleOwner.collectWhileStarted(viewModel.chapterProgressForSlider) { refreshSlider(binding) }
    viewLifecycleOwner.collectWhileStarted(viewModel.trackProgressForSlider) { refreshSlider(binding) }

    // Every collector below fires on the 1 Hz tick and writes to a view in the expanded player.
    // While the sheet is collapsed those writes still invalidate views nobody can see and drive
    // measure/layout over the whole activity: measured on a 28-track book, foreground playback drew
    // ~60-75 frames per 20 s at ~30% jank against 4 frames at 0% backgrounded (cu-117).
    //
    // The listener below re-runs [renderPlayerText] and [refreshSlider] when the sheet gains
    // height — necessary because an expand during *paused* playback has no further tick to
    // correct the stale text, where the slider can wait for one.
    //
    // It watches the **root's height**, not `isShown`. The sheet collapses to zero height
    // rather than going GONE, so every child stays `VISIBLE` throughout and an `isShown`
    // transition never fires on expand: `wasShown` went true while still collapsed, and the
    // `shown && !wasShown` edge was therefore missed every time. Anything written before that
    // point measured against a zero-height container (cu-141).
    var wasExpanded = false
    binding.root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
      val expanded = view.height > 0 && view.isShown
      if (expanded && !wasExpanded) {
        renderPlayerText(binding)
        refreshSlider(binding)
      }
      wasExpanded = expanded
    }

    // One source for the progress line now, instead of four strings that each re-rendered the
    // whole block (cu-19). `playerProgress` is already distinctUntilChanged, so this fires only
    // when a displayed number actually moved.
    viewLifecycleOwner.collectWhileStarted(viewModel.playerProgress) { renderPlayerText(binding) }
    viewLifecycleOwner.collectWhileStarted(viewModel.progressPercentageString) {
      renderPlayerText(binding)
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.currentChapter) { renderPlayerText(binding) }
    viewLifecycleOwner.collectWhileStarted(viewModel.audiobook) { renderPlayerArtwork(binding) }
    viewLifecycleOwner.collectWhileStarted(plexConfig.isConnected) { connected ->
      bindImageRounded(
        binding.detailsArtwork,
        viewModel.audiobook.value?.thumb,
        connected,
        plexConfig::toServerString,
      )
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.isLoadingTracks) {
      binding.loadingTracksSpinner.isVisible = it
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.bottomChooserState) {
      setBottomChooserState(binding.bottomSheetChooser, it)
    }
    viewLifecycleOwner.collectWhileStarted(viewModel.sleepTimerChooserState) {
      setBottomChooserState(binding.sleepTimerChooser, it)
    }

    val adapter =
      ChapterListAdapter(
        object : TrackClickListener {
          override fun onClick(chapter: Chapter) {
            viewModel.jumpToChapter(chapter.bookStartTimeOffset, chapter.trackId)
          }
        },
      )

    binding.chapterProgressSeekbar.addOnSliderTouchListener(
      object : Slider.OnSliderTouchListener {
        override fun onStartTrackingTouch(slider: Slider) {
          viewModel.onSlideStart()
        }

        override fun onStopTrackingTouch(slider: Slider) {
          // The guard is *not* cleared here — `seekTo` holds it until playback reports the new
          // position, so the thumb does not snap back to where it was (cu-93).
          viewModel.seekTo(slider.value.toDouble() / slider.valueTo)
        }
      },
    )

    // The scrub tooltip is a position, so it keeps its seconds — but through the same formatter
    // as the rest of the player, not `DateUtils`, which pads to `0:32:10` at the hour (cu-19).
    binding.chapterProgressSeekbar.setLabelFormatter { value: Float ->
      formatPrecisePosition(value.toLong())
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

    binding.tracks.adapter = adapter

    // Same omission as the details screen: the `chapterList` binding was dropped in the cu-58
    // conversion and nothing fed this adapter, so the chapter list was empty while playing (cu-73).
    viewLifecycleOwner.collectWhileStarted(viewModel.chapters) { chapters ->
      adapter.submitChapters(chapters)
    }

    // Keeps the highlighted row in step with playback; the adapter diffs on the active flag.
    viewLifecycleOwner.collectWhileStarted(viewModel.currentChapter) { chapter ->
      adapter.updateCurrentChapter(chapter.trackId, chapter.discNumber, chapter.index)
    }

    binding.detailsToolbar.setNavigationOnClickListener {
      currentlyPlayingInterface.setBottomSheetState(COLLAPSED)
    }

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.showModalBottomSheetSpeedChooser) {
      ModalBottomSheetSpeedChooser().show(
        childFragmentManager,
        ModalBottomSheetSpeedChooser.TAG,
      )
    }

    // targetSdk 36 is edge-to-edge; the toolbar must inset itself (cu-63).

    // The *pinned* bar takes the inset, so it paints the status-bar strip itself. Padding the
    // collapsing container instead leaves that strip to the scrolling artwork, which then shows
    // above the toolbar (cu-105).
    binding.detailsToolbar.applyTopSystemBarInsetAsPinnedBar()

    return binding.root
  }
}
