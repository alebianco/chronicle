package io.github.mattpvaughn.chronicle.features.settings

import androidx.annotation.StringRes
import io.github.mattpvaughn.chronicle.R

/**
 * How far the player's skip controls jump, as the settings screen offers it.
 *
 * Extracted from `SettingsViewModel.makePreferences()` (cu-101). The jump-forward and jump-backward
 * choosers each listed the same six options and each carried its own resource→seconds `when`,
 * 70 lines apart — two copies of one fact, neither reachable by a test.
 *
 * No live bug here: both copies agreed. It is the same defect class as [BookCoverStyle] because
 * nothing *made* them agree. Adding a seventh option means editing two option lists and two `when`
 * chains, and missing one fails silently — the shape that produced the cover-style mismatch.
 *
 * Unlike the other mappings, seconds are stored as a plain `Long`, so there is no persisted-label
 * problem: [seconds] is both the stored value and the number shown.
 */
enum class JumpInterval(
  val seconds: Long,
  /** The option as offered in the chooser. */
  @StringRes val choiceRes: Int,
) {
  Ten(seconds = 10L, choiceRes = R.string.settings_jump_10_seconds),
  Fifteen(seconds = 15L, choiceRes = R.string.settings_jump_15_seconds),
  Twenty(seconds = 20L, choiceRes = R.string.settings_jump_20_seconds),
  Thirty(seconds = 30L, choiceRes = R.string.settings_jump_30_seconds),
  Sixty(seconds = 60L, choiceRes = R.string.settings_jump_60_seconds),
  Ninety(seconds = 90L, choiceRes = R.string.settings_jump_90_seconds),
  ;

  companion object {
    /**
     * The fallbacks the original `else` branches used, one per direction.
     *
     * They differ, and that is deliberate: each mirrors that preference's own default in
     * `SharedPreferencesPrefsRepo`. Named here so the asymmetry is a stated fact rather than two
     * magic numbers in unrelated `when` chains.
     */
    const val DEFAULT_FORWARD_SECONDS = 30L
    const val DEFAULT_BACKWARD_SECONDS = 10L

    /** The chooser's options, in the order they are offered. */
    val choices: List<JumpInterval> = entries.toList()

    /** The interval [choiceRes] identifies, or null if it names no option. */
    fun ofChoice(
      @StringRes choiceRes: Int,
    ): JumpInterval? = entries.firstOrNull { it.choiceRes == choiceRes }

    /**
     * The seconds [choiceRes] selects, or [orElse] if it names no option.
     *
     * The fallback is the caller's to supply, because forward and backward disagree about it —
     * passing it in keeps that visible at the call site rather than behind a `forward` flag.
     */
    fun secondsOfChoice(
      @StringRes choiceRes: Int,
      orElse: Long,
    ): Long = ofChoice(choiceRes)?.seconds ?: orElse
  }
}
