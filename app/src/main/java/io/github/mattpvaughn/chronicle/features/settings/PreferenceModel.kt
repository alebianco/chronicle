package io.github.mattpvaughn.chronicle.features.settings

import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.FormattableString

data class PreferenceModel(
  val type: PreferenceType,
  val title: FormattableString,
  val key: String = "",
  val explanation: FormattableString = FormattableString.EMPTY_STRING,
  val click: PreferenceClick =
    object : PreferenceClick {
      override fun onClick() {
        // Do nothing by default
      }
    },
  /**
   * The switch's current value, for [PreferenceType.SWITCH] rows.
   *
   * Typed `Boolean?` rather than `Any?`: it is only ever a boolean, the sole reader cast it to
   * one, and `DiffUtil.areContentsTheSame` compares it — an `Any?` there compares by identity
   * for any type without `equals()`, so a row would silently stop repainting. Narrowing makes
   * the compiler enforce what lint could only warn about.
   */
  val defaultValue: Boolean? = null,
) {
  fun hasExplanation(): Boolean {
    return explanation != FormattableString.EMPTY_STRING
  }
}

interface PreferenceClick {
  fun onClick()
}

/**
 * The three kinds of settings row (cu-201).
 *
 * `INTEGER` and `FLOAT` are **deleted**, not renamed: no `makePreferences` row ever constructed
 * them, and both mapped to the same ViewHolder as `CLICKABLE` — a distinction the code drew and
 * then ignored. Removing them makes the `when` in `SettingsScreen` exhaustive over states that
 * actually occur.
 *
 * `prefIntMap` went with them. It existed to give `RecyclerView` an integer view type, and
 * `onCreateViewHolder` recovered the enum with an O(n) reverse lookup that threw on a miss. A
 * `LazyColumn` needs neither.
 */
enum class PreferenceType { TITLE, CLICKABLE, BOOLEAN }
