package io.github.mattpvaughn.chronicle.features.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.databinding.ListItemSampleTitleBinding

/**
 * Real titles from the user's library, offered as test subjects (cu-151).
 *
 * These are the books that currently parse to **no position** — the ones a rule would be written to
 * fix. Offering them is the fourth acceptance criterion and what makes the screen useful before any
 * rule exists.
 */
class SampleTitleAdapter(
  private val onChosen: (String) -> Unit,
) : ListAdapter<String, SampleTitleAdapter.ViewHolder>(DIFF) {
  override fun onCreateViewHolder(
    parent: ViewGroup,
    viewType: Int,
  ) = ViewHolder(
    ListItemSampleTitleBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    onChosen,
  )

  override fun onBindViewHolder(
    holder: ViewHolder,
    position: Int,
  ) = holder.bind(getItem(position))

  class ViewHolder(
    private val binding: ListItemSampleTitleBinding,
    private val onChosen: (String) -> Unit,
  ) : RecyclerView.ViewHolder(binding.root) {
    fun bind(titleSort: String) {
      binding.sampleTitle.text = titleSort
      // Says what a tap *does*, not just what the row contains — the row is a control, and
      // "Warhammer 40,000 - Horus Rising" alone does not tell a screen-reader user it is tappable.
      binding.sampleTitle.contentDescription =
        binding.root.context.getString(R.string.series_rules_sample_description, titleSort)
      binding.root.setOnClickListener { onChosen(titleSort) }
    }
  }

  private companion object {
    val DIFF =
      object : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(
          oldItem: String,
          newItem: String,
        ) = oldItem == newItem

        override fun areContentsTheSame(
          oldItem: String,
          newItem: String,
        ) = oldItem == newItem
      }
  }
}
