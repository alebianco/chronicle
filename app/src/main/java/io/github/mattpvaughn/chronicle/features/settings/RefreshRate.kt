package io.github.mattpvaughn.chronicle.features.settings

import androidx.annotation.StringRes
import io.github.mattpvaughn.chronicle.R

/**
 * How often the library re-syncs, as the settings screen presents it.
 *
 * Extracted from `SettingsViewModel.makePreferences()` (cu-101), where the same fact lived twice and
 * neither copy was reachable by a test:
 *
 * - a `when` chain turned a stored minute count into a label, reaching through
 *   `Injector.get().applicationContext().resources` for every string;
 * - a second `when` turned a chosen option's *string resource id* back into a minute count.
 *
 * Two mappings of one fact, written 80 lines apart, with nothing tying them together — the shape
 * that produced the chapter and progress bugs this project keeps re-finding. A value offered by the
 * chooser but missing from the formatter renders as the wrong unit, and the formatter's original
 * `when` had no `else`, so an unmatched value threw.
 *
 * Modelling the options as one list makes the round trip total by construction: [forMinutes] and
 * [ofChoice] read the same entries.
 *
 * The label is deliberately **not** resolved here. Resolving it needs a `Context`, which is what
 * pushed the original code into the service locator; returning [Label] lets the caller format it
 * and keeps this file testable on plain JVM.
 */
enum class RefreshRate(
  val minutes: Long,
  /** The option as offered in the chooser. */
  @StringRes val choiceRes: Int,
) {
  Always(minutes = 0L, choiceRes = R.string.settings_refresh_rate_always),
  FifteenMinutes(minutes = 15L, choiceRes = R.string.settings_refresh_rate_15_minutes),
  OneHour(minutes = 60L, choiceRes = R.string.settings_refresh_rate_1_hour),
  ThreeHours(minutes = 180L, choiceRes = R.string.settings_refresh_rate_3_hours),
  SixHours(minutes = 360L, choiceRes = R.string.settings_refresh_rate_6_hours),
  OneDay(minutes = 60L * 24, choiceRes = R.string.settings_refresh_rate_1_day),
  ThreeDays(minutes = 60L * 24 * 3, choiceRes = R.string.settings_refresh_rate_3_days),
  OneWeek(minutes = 60L * 24 * 7, choiceRes = R.string.settings_refresh_rate_1_week),
  Manual(minutes = Long.MAX_VALUE, choiceRes = R.string.settings_refresh_rate_manual),
  ;

  companion object {
    /** The chooser's options, in the order they are offered. */
    val choices: List<RefreshRate> = entries.toList()

    /** The rate [choiceRes] identifies, or null if it names no option. */
    fun ofChoice(
      @StringRes choiceRes: Int,
    ): RefreshRate? = entries.firstOrNull { it.choiceRes == choiceRes }
  }
}

/**
 * How a stored minute count should be written out.
 *
 * A [Quantity] carries the number and the unit separately so the caller supplies the localized unit
 * — "3" plus *hours*, rather than a string assembled here against a `Context` this class must not
 * hold.
 */
sealed interface RefreshRateLabel {
  /** A named rate with no number: *Always* or *Manual*. */
  data class Named(
    @StringRes val stringRes: Int,
  ) : RefreshRateLabel

  /** A count and the unit it is counted in, e.g. 3 + [R.string.hours]. */
  data class Quantity(
    val count: Long,
    @StringRes val unitRes: Int,
  ) : RefreshRateLabel
}

/**
 * Describes [minutes] for display.
 *
 * The boundaries are the original's and are load-bearing: 60 reads as "1 hours" rather than "60
 * minutes", and exactly a week is still a day count, while anything beyond it is manual. They are
 * pinned by test because the two `when` chains this replaces disagreed about the top end — the
 * formatter treated `> 1 week` as manual while the chooser offered `Long.MAX_VALUE`, so any other
 * large value fell through a chain with **no `else` branch** and threw.
 */
fun refreshRateLabel(minutes: Long): RefreshRateLabel =
  when {
    minutes <= 0L -> RefreshRateLabel.Named(R.string.settings_refresh_rate_always)
    minutes < 60 -> RefreshRateLabel.Quantity(minutes, R.string.minutes)
    minutes < 60 * 24 -> RefreshRateLabel.Quantity(minutes / 60, R.string.hours)
    minutes <= 60 * 24 * 7 -> RefreshRateLabel.Quantity(minutes / (60 * 24), R.string.days)
    else -> RefreshRateLabel.Named(R.string.settings_refresh_rate_manual)
  }
