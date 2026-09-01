package io.github.mattpvaughn.chronicle.features.player

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.mattpvaughn.chronicle.R

/**
 * The top-level categories of the Android Auto browse tree (cu-99).
 *
 * Each carries a **stable [id]** used as the media id and matched in `onLoadChildren`, kept separate
 * from the localized [labelRes] shown to the user.
 *
 * They were one and the same before: the tree was built with `getString(R.string.auto_category_*)`
 * as the media id *and* matched on the same call. Browsing therefore worked only while the device
 * locale matched the locale the tree was built in — after a language change every category resolved
 * to no branch of the `when`, and Auto showed empty lists with no error. A localized string is
 * display text and can change under you; an identifier must not.
 *
 * The ids are wire format: Android Auto may hold one across a process restart, so **treat them as
 * persisted values and do not rename them** to match a refactor.
 */
enum class AutoBrowseCategory(
  val id: String,
  @StringRes val labelRes: Int,
  @DrawableRes val iconRes: Int,
) {
  RecentlyListened(
    id = "chronicle.auto.recently_listened",
    labelRes = R.string.auto_category_recently_listened,
    iconRes = R.drawable.ic_recent,
  ),
  Offline(
    id = "chronicle.auto.offline",
    labelRes = R.string.auto_category_offline,
    iconRes = R.drawable.ic_cloud_download_white,
  ),
  RecentlyAdded(
    id = "chronicle.auto.recently_added",
    labelRes = R.string.auto_category_recently_added,
    iconRes = R.drawable.ic_add,
  ),
  Library(
    id = "chronicle.auto.library",
    labelRes = R.string.auto_category_library,
    iconRes = R.drawable.nav_library,
  ),
  ;

  companion object {
    /** The category [id] identifies, or null when [id] is not a category (a book id, say). */
    fun fromId(id: String): AutoBrowseCategory? = entries.firstOrNull { it.id == id }
  }
}
