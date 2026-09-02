package io.github.mattpvaughn.chronicle.features.library

import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Audiobook

fun bindRecyclerView(
  recyclerView: RecyclerView,
  data: List<Audiobook>?,
) {
  val adapter = recyclerView.adapter as AudiobookAdapter
  adapter.submitList(data)
}

fun bindRecyclerView(
  recyclerView: RecyclerView,
  serverConnected: Boolean,
) {
  val adapter = recyclerView.adapter as AudiobookAdapter
  adapter.setServerConnected(serverConnected)
}

fun overrideWidth(
  view: View,
  width: Float,
) {
  view.layoutParams.width = if (width > 0) width.toInt() else MATCH_PARENT
}

fun setSquareAspectRatio(
  constraintLayout: ConstraintLayout,
  isSquare: Boolean,
) {
  val ratio = if (isSquare) "1:1" else "5:8"

  // Skip the work when the ratio is already what we want.
  //
  // `clone()` + `setConstraintSet()` rebuilds the whole constraint graph for the row and forces a
  // re-layout. A list row rebinds whenever DiffUtil sees its contents change, and
  // `AudiobookAdapter.areContentsTheSame` includes `progress` — which moves every second for the
  // book being played, and that book is in the "Recently listened" shelf by definition. So each
  // visible row was rebuilding its constraint graph once a second to set a ratio that had not
  // changed since the row was created. That is the bulk of the `View.measure` storm the cu-110
  // profile showed (1285 calls in 16 s).
  //
  // The tag is per-view, so a recycled holder rebinding to a *different* view style still applies
  // the new ratio.
  val previous = constraintLayout.getTag(R.id.tag_thumb_aspect_ratio) as? String
  if (previous == ratio) {
    return
  }
  constraintLayout.setTag(R.id.tag_thumb_aspect_ratio, ratio)

  GLOBAL_CONSTRAINT.clone(constraintLayout)
  GLOBAL_CONSTRAINT.setDimensionRatio(R.id.thumb_container, ratio)
  constraintLayout.setConstraintSet(GLOBAL_CONSTRAINT)
}

val GLOBAL_CONSTRAINT = ConstraintSet()
