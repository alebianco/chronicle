package io.github.mattpvaughn.chronicle.views

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import io.github.mattpvaughn.chronicle.application.MainActivity
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import io.github.mattpvaughn.chronicle.databinding.ModalBottomSheetSpeedChooserBinding
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * The consolidated speed/effects popover: speed, a per-book override, and skip-silence
 * (RESEARCH_FINDINGS §3.1 rule 6).
 *
 * Speed has two homes — the global preference and this book's own [Audiobook.playbackSpeed]
 * override (cu-20) — and the switch chooses which one a write lands in. Which one that is, and
 * what the controls should read, is decided by [SpeedChooserState]; this class only moves values
 * between that and the views.
 *
 * Nothing here applies the speed to the player: `MediaPlayerService.invalidatePlaybackParams` is
 * the single writer of `PlaybackParameters` and reacts to both a pref change and a book change.
 */
@ExperimentalCoroutinesApi
class ModalBottomSheetSpeedChooser : BottomSheetDialogFragment() {
  private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

  @Inject
  lateinit var prefs: PrefsRepo

  @Inject
  lateinit var bookRepository: IBookRepository

  @Inject
  lateinit var currentlyPlaying: CurrentlyPlaying

  private var binding: ModalBottomSheetSpeedChooserBinding? = null

  /**
   * The book the popover opened over.
   *
   * Read from [currentlyPlaying] at creation rather than collected: a track change republishes the
   * book, and re-reading it mid-interaction would move the switch under the user's finger. The
   * popover is short-lived, so a snapshot is the honest model.
   */
  private var book: Audiobook = EMPTY_AUDIOBOOK

  /**
   * Guards the listeners while *this* code sets control values.
   *
   * `Slider.value`, `ChipGroup.check` and `SwitchMaterial.isChecked` all fire their listeners on a
   * programmatic write, so rendering state would write it straight back — and via the prefs
   * listener, in a loop.
   */
  private var isRendering = false

  override fun onAttach(context: Context) {
    (requireActivity() as MainActivity).activityComponent!!.inject(this)
    super.onAttach(context)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    val binding = ModalBottomSheetSpeedChooserBinding.inflate(inflater, container, false)
    this.binding = binding
    book = currentlyPlaying.book.value

    // Was `valueFrom`/`valueTo`/`value` binding expressions in the layout. The bounds must be
    // set before the value, or Slider rejects a value outside its (default) range.
    binding.speedSlider.valueFrom = SpeedChooserState.SPEED_MIN
    binding.speedSlider.valueTo = SpeedChooserState.SPEED_MAX
    binding.speedSlider.setLabelFormatter { value: Float -> String.format("%.2f", value) + "x" }

    binding.speedSlider.addOnSliderTouchListener(
      object : Slider.OnSliderTouchListener {
        override fun onStartTrackingTouch(slider: Slider) {}

        override fun onStopTrackingTouch(slider: Slider) {
          if (!isRendering) {
            writeSpeed(slider.value)
          }
        }
      },
    )

    binding.speedPresets.setOnCheckedStateChangeListener { group: ChipGroup, checkedIds ->
      if (isRendering) {
        return@setOnCheckedStateChangeListener
      }
      val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
      // The tag is a plain number, not `@string/playback_speed_1_0x`. It used to be that string
      // resource, so a locale rendering it "1,0x" matched no branch and every preset silently
      // became 1.0x.
      val tag = group.findViewById<Chip>(checkedId)?.tag as? String
      val speed = tag?.toFloatOrNull()
      if (speed == null) {
        Timber.e("Speed preset chip has no numeric tag: $tag")
        return@setOnCheckedStateChangeListener
      }
      writeSpeed(speed)
    }

    binding.perBookSpeedSwitch.setOnCheckedChangeListener { _, isChecked ->
      if (isRendering) {
        return@setOnCheckedChangeListener
      }
      val shown = SpeedChooserState.of(book, prefs.playbackSpeed).speed
      persistBookSpeed(SpeedChooserState.speedForToggle(isChecked, shown))
    }

    binding.skipSilenceSwitch.setOnCheckedChangeListener { _, isChecked ->
      if (!isRendering) {
        prefs.skipSilence = isChecked
      }
    }

    prefsListener =
      SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
          PrefsRepo.KEY_PLAYBACK_SPEED, PrefsRepo.KEY_SKIP_SILENCE -> render()
        }
      }.apply { prefs.registerPrefsListener(this) }

    render()
    return binding.root
  }

  /** Writes a chosen speed to whichever home the override switch selects. */
  private fun writeSpeed(speed: Float) {
    when (SpeedChooserState.destinationFor(book)) {
      SpeedDestination.THIS_BOOK -> persistBookSpeed(speed)
      SpeedDestination.GLOBAL -> prefs.playbackSpeed = speed
    }
  }

  /**
   * Persists [speed] onto the book row and updates the local snapshot.
   *
   * The snapshot is updated here rather than awaited from the DB because [render] runs
   * synchronously after this returns; waiting for the round trip would render the stale value and
   * leave the switch visibly lagging the tap.
   */
  private fun persistBookSpeed(speed: Float) {
    if (!SpeedChooserState.of(book, prefs.playbackSpeed).canOverride) {
      Timber.w("No book playing; cannot store a per-book speed")
      return
    }
    val bookId = book.id
    book = book.copy(playbackSpeed = speed)
    render()
    // Publish before the DB write so the player picks the speed up now. `ProgressUpdater` would
    // re-read the book and republish it eventually, but only while playing — a change made while
    // paused would otherwise not apply until playback resumed.
    currentlyPlaying.updateSpeedOverride(bookId, speed)
    lifecycleScope.launch {
      try {
        bookRepository.updatePlaybackSpeed(bookId, speed)
      } catch (e: Throwable) {
        Timber.e(e, "Failed to store per-book speed for $bookId")
      }
    }
  }

  /** Renders every control from state, with the listeners suppressed. */
  private fun render() {
    val binding = binding ?: return
    val state = SpeedChooserState.of(book, prefs.playbackSpeed)
    isRendering = true
    try {
      binding.speedSlider.value = state.speed
      binding.skipSilenceSwitch.isChecked = prefs.skipSilence
      binding.perBookSpeedSwitch.isEnabled = state.canOverride
      binding.perBookSpeedSwitch.isChecked = state.isOverrideEnabled

      val presetId = presetChipIdFor(binding.speedPresets, state.speed)
      if (presetId == null) {
        binding.speedPresets.clearCheck()
      } else {
        binding.speedPresets.check(presetId)
      }
    } finally {
      isRendering = false
    }
  }

  /** The chip whose tag equals [speed], or null when the speed is not one of the presets. */
  private fun presetChipIdFor(
    group: ChipGroup,
    speed: Float,
  ): Int? {
    for (i in 0 until group.childCount) {
      val chip = group.getChildAt(i) as? Chip ?: continue
      val tag = (chip.tag as? String)?.toFloatOrNull() ?: continue
      if (tag == speed) {
        return chip.id
      }
    }
    return null
  }

  override fun onStart() {
    super.onStart()
    expandBottomSheetOnStart()
  }

  override fun onDestroyView() {
    super.onDestroyView()
    prefsListener?.let { prefs.unregisterPrefsListener(it) }
    prefsListener = null
    binding = null
  }

  companion object {
    const val TAG = "ModalBottomSheetSpeedChooser"
  }
}
