package io.github.mattpvaughn.chronicle.features.bookdetails

import android.content.res.Resources.NotFoundException
import android.graphics.PorterDuff
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.data.model.Chapter
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
      // `toString(16)`, not OkHttp's `internal.toHexString`, which this used to import — reaching
      // into another library's internal package for a hex conversion broke on the OkHttp 5
      // upgrade and had no reason to exist (cu-66).
      Timber.e("Could not bind tint with res: 0x${colorRes.toString(16)}")
    }
  }
}
