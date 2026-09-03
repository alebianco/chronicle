package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLE_COVER_GRID
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLE_DETAILS_LIST
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLE_TEXT_LIST
import timber.log.Timber

/**
 * How a stored library view style maps onto layout decisions, in one place (cu-133).
 *
 * This mapping was written **seven times** across `LibraryFragment`, `CollectionsFragment`,
 * `CollectionDetailsFragment`, `AudiobookAdapter` and `CollectionsAdapter`, and every copy ended in
 * `else -> throw IllegalStateException("Unknown view style")`. Two of them were property
 * initializers.
 *
 * That made an out-of-range preference value a **crash on every launch**: the value is persisted,
 * so the crash recurs before the settings screen that could undo it is reachable. Settings import
 * now refuses such values, which is the real fix; this is defence in depth for what is already on
 * disk, and it was found by seeding `"x"` into preferences on a device — the unit tests all passed
 * while the app died on the fragment copy I had not spotted.
 *
 * Falling back is right for a **cosmetic** preference: the user sees the default layout and a log
 * line, rather than an app that cannot open. It would be the wrong call for anything affecting
 * listening position, where silently guessing loses data.
 */
internal fun viewStyleIsGrid(style: String): Boolean =
  when (style) {
    VIEW_STYLE_COVER_GRID -> true
    VIEW_STYLE_DETAILS_LIST, VIEW_STYLE_TEXT_LIST -> false
    else -> {
      Timber.w("Unknown view style '$style'; showing the cover grid")
      true
    }
  }

/**
 * The adapters' internal view-type constants, which must stay distinct per adapter.
 *
 * Kept as an enum rather than the adapters' loose `Int`s so the mapping cannot silently disagree
 * with [viewStyleIsGrid] about which styles exist.
 */
internal enum class ViewStyleKind {
  CoverGrid,
  TextOnly,
  Details,
  ;

  companion object {
    /** [style]'s kind, falling back to [CoverGrid] rather than throwing. See [viewStyleIsGrid]. */
    fun of(style: String): ViewStyleKind =
      when (style) {
        VIEW_STYLE_COVER_GRID -> CoverGrid
        VIEW_STYLE_TEXT_LIST -> TextOnly
        VIEW_STYLE_DETAILS_LIST -> Details
        else -> {
          Timber.w("Unknown view style '$style'; showing the cover grid")
          CoverGrid
        }
      }
  }
}
