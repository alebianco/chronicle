package io.github.mattpvaughn.chronicle.features.bookdetails

import android.content.res.Resources.NotFoundException
import android.graphics.PorterDuff
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.data.model.Chapter
import okhttp3.internal.toHexString
import timber.log.Timber

fun bindChapterList(
  recyclerView: RecyclerView,
  chapters: List<Chapter>?,
) {
  val adapter = recyclerView.adapter as ChapterListAdapter
  adapter.submitChapters(chapters ?: emptyList())
}

fun bindImageDrawableSource(
  imageView: ImageView,
  @DrawableRes drawableRes: Int,
) {
  imageView.setImageResource(drawableRes)
}

fun bindTintResource(
  imageView: ImageView,
  @ColorRes colorRes: Int,
) {
  if (colorRes != 0) {
    try {
      imageView.setColorFilter(
        ContextCompat.getColor(imageView.context, colorRes),
        PorterDuff.Mode.SRC_IN,
      )
    } catch (rnf: NotFoundException) {
      Timber.e("Could not bind tint with res: 0x${colorRes.toHexString()}")
    }
  }
}
