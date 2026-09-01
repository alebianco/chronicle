package io.github.mattpvaughn.chronicle.features.currentlyplaying

import android.content.Context
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateUtils
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
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.databinding.FragmentCurrentlyPlayingBinding
import io.github.mattpvaughn.chronicle.features.bookdetails.ChapterListAdapter
import io.github.mattpvaughn.chronicle.features.bookdetails.TrackClickListener
import io.github.mattpvaughn.chronicle.features.player.SleepTimer
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
import io.github.mattpvaughn.chronicle.util.observeEvent
import io.github.mattpvaughn.chronicle.views.ModalBottomSheetSpeedChooser
import io.github.mattpvaughn.chronicle.views.bindImageRounded
import io.github.mattpvaughn.chronicle.views.setBottomChooserState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import timber.log.Timber
import javax.inject.Inject

/** Responsible for playback controls and displaying the currently playing media */
@ExperimentalCoroutinesApi
class CurrentlyPlayingFragment : Fragment() {
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

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    // Activity and context are non-null on view creation. This informs lint about that
    val binding = FragmentCurrentlyPlayingBinding.inflate(inflater, container, false)

    viewModel.showUserMessage.observeEvent(viewLifecycleOwner) { message ->
      Toast.makeText(context, message, LENGTH_SHORT).show()
    }

    // Was 29 binding expressions in fragment_currently_playing.xml.
    binding.skipToPrevious.setOnClickListener { viewModel.skipToPrevious() }
    binding.rewindButton.setOnClickListener { viewModel.skipBackwards() }
    binding.detailsPausePlay.setOnClickListener { viewModel.play() }
    binding.skipForwardButton.setOnClickListener { viewModel.skipForwards() }
    binding.skipToNext.setOnClickListener { viewModel.skipToNext() }
    binding.changeSpeedButton.setOnClickListener { viewModel.showPlaybackSpeedChooser() }
    binding.sleepTimerButton.setOnClickListener { viewModel.showSleepTimerOptions() }

    viewModel.hasFailedProgressSync.observe(viewLifecycleOwner) { failed ->
      binding.syncFailedBadge.isVisible = failed == true
    }
    viewModel.jumpBackwardsIcon.observe(viewLifecycleOwner) {
      binding.rewindButton.setImageResource(it)
    }
    viewModel.jumpForwardsIcon.observe(viewLifecycleOwner) {
      binding.skipForwardButton.setImageResource(it)
    }
    viewModel.isPlaying.observe(viewLifecycleOwner) { playing ->
      binding.detailsPausePlay.setImageResource(
        if (playing == true) {
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
    viewModel.isAudioLoading.observe(viewLifecycleOwner) { loading ->
      binding.audioLoadingSpinner.isVisible = loading == true
      binding.detailsPausePlay.visibility =
        if (loading == true) View.INVISIBLE else View.VISIBLE
    }
    viewModel.playbackSpeedString.observe(viewLifecycleOwner) {
      binding.changeSpeedButton.text = it
    }
    viewModel.isSleepTimerActive.observe(viewLifecycleOwner) { active ->
      binding.sleepTimerButton.imageTintList =
        ColorStateList.valueOf(
          ContextCompat.getColor(
            requireContext(),
            if (active == true) R.color.iconActive else R.color.icon,
          ),
        )
      binding.sleepTimerCountdown.isVisible = active == true
    }
    viewModel.sleepTimerTimeRemainingString.observe(viewLifecycleOwner) {
      binding.sleepTimerCountdown.text = it
    }

    // The slider falls back to track values when there is no chapter. valueTo
    // must be set before value: Material's Slider throws if value falls outside
    // the current range, which DataBinding handled internally.
    fun refreshSlider() {
      // The guard belongs *here*, at the write, not on the sources. Four observers call this and
      // only two of them carried the `isSliding` filter — `currentTrack` and `chapterDuration` are
      // unfiltered and fire on every playback tick, so the stale position reached the thumb anyway.
      // Filtering the flows was not enough; this is the single line that moves the slider (cu-93).
      if (viewModel.isSliding) {
        return
      }
      val chapterDuration = viewModel.chapterDuration.value ?: 0L
      val trackDuration = viewModel.currentTrack.value?.duration ?: 0L
      val max = (if (chapterDuration == 0L) trackDuration else chapterDuration).toFloat()
      val chapterProgress = viewModel.chapterProgressForSlider.value ?: -1L
      val trackProgress = viewModel.trackProgressForSlider.value ?: 0L
      val current = if (chapterProgress == -1L) trackProgress else chapterProgress

      binding.chapterProgressSeekbar.valueTo = if (max > 0f) max else 1f
      binding.chapterProgressSeekbar.value =
        current.toFloat().coerceIn(0f, binding.chapterProgressSeekbar.valueTo)
    }
    viewModel.chapterDuration.observe(viewLifecycleOwner) { refreshSlider() }
    viewModel.currentTrack.observe(viewLifecycleOwner) { refreshSlider() }
    viewModel.chapterProgressForSlider.observe(viewLifecycleOwner) { refreshSlider() }
    viewModel.trackProgressForSlider.observe(viewLifecycleOwner) { refreshSlider() }

    viewModel.progressString.observe(viewLifecycleOwner) { binding.progress.text = it }
    viewModel.progressPercentageString.observe(viewLifecycleOwner) {
      binding.progressPercentage.text = it
    }
    viewModel.chapterProgressString.observe(viewLifecycleOwner) { chapter ->
      binding.chapterProgress.text =
        if (chapter.isNullOrEmpty()) viewModel.trackProgress.value.orEmpty() else chapter
    }
    viewModel.chapterDurationString.observe(viewLifecycleOwner) { durationString ->
      binding.chapterDuration.text =
        if ((viewModel.chapterDuration.value ?: 0L) == 0L) {
          viewModel.trackDuration.value.orEmpty()
        } else {
          durationString
        }
    }
    viewModel.currentChapter.observe(viewLifecycleOwner) { chapter ->
      binding.chapterTitle.text =
        if (chapter?.title.isNullOrEmpty()) {
          viewModel.currentTrack.value?.title.orEmpty()
        } else {
          chapter?.title.orEmpty()
        }
    }
    viewModel.audiobook.observe(viewLifecycleOwner) { book ->
      binding.bookTitle.text = book?.title.orEmpty()
      binding.detailsArtwork.contentDescription = book?.title.orEmpty()
      bindImageRounded(binding.detailsArtwork, book?.thumb, plexConfig.isConnected.value == true)
    }
    plexConfig.isConnected.observe(viewLifecycleOwner) { connected ->
      bindImageRounded(
        binding.detailsArtwork,
        viewModel.audiobook.value?.thumb,
        connected == true,
      )
    }

    viewModel.isLoadingTracks.observe(viewLifecycleOwner) {
      binding.loadingTracksSpinner.isVisible = it == true
    }
    viewModel.bottomChooserState.observe(viewLifecycleOwner) {
      setBottomChooserState(binding.bottomSheetChooser, it)
    }
    viewModel.sleepTimerChooserState.observe(viewLifecycleOwner) {
      setBottomChooserState(binding.sleepTimerChooser, it)
    }

    val adapter =
      ChapterListAdapter(
        object : TrackClickListener {
          override fun onClick(chapter: Chapter) {
            viewModel.jumpToChapter(chapter.startTimeOffset, chapter.trackId)
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

    binding.chapterProgressSeekbar.setLabelFormatter { value: Float ->
      DateUtils.formatElapsedTime(
        StringBuilder(),
        value.toLong() / 1000,
      )
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

    binding.tracks.adapter = adapter

    // Same omission as the details screen: the `chapterList` binding was dropped in the cu-58
    // conversion and nothing fed this adapter, so the chapter list was empty while playing (cu-73).
    viewModel.chapters.observe(viewLifecycleOwner) { chapters ->
      adapter.submitChapters(chapters)
    }

    // Keeps the highlighted row in step with playback; the adapter diffs on the active flag.
    viewModel.currentChapter.observe(viewLifecycleOwner) { chapter ->
      adapter.updateCurrentChapter(chapter.trackId, chapter.discNumber, chapter.index)
    }

    binding.detailsToolbar.setNavigationOnClickListener {
      currentlyPlayingInterface.setBottomSheetState(COLLAPSED)
    }

    viewModel.showModalBottomSheetSpeedChooser.observe(viewLifecycleOwner) { eventShowChooser ->
      if (!eventShowChooser.hasBeenHandled) {
        ModalBottomSheetSpeedChooser().show(
          childFragmentManager,
          ModalBottomSheetSpeedChooser.TAG,
        )
        eventShowChooser.getContentIfNotHandled()
      }
    }

    // targetSdk 36 is edge-to-edge; the toolbar must inset itself (cu-63).

    binding.appBarLayout.applyTopSystemBarInset()

    return binding.root
  }
}
