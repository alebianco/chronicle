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

enum class PreferenceType { TITLE, CLICKABLE, BOOLEAN, INTEGER, FLOAT }

val prefIntMap =
  mapOf(
    PreferenceType.TITLE to 1,
    PreferenceType.CLICKABLE to 2,
    PreferenceType.BOOLEAN to 3,
    PreferenceType.INTEGER to 4,
    PreferenceType.FLOAT to 5,
  )
