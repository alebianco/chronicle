package io.github.mattpvaughn.chronicle.features.browse

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Facet
import io.github.mattpvaughn.chronicle.databinding.ListItemFacetBinding

/** A list of facet values with their book counts (cu-24). */
class FacetListAdapter(private val onClick: (Facet) -> Unit) :
  ListAdapter<Facet, FacetListAdapter.ViewHolder>(DiffCallback) {
  override fun onCreateViewHolder(
    parent: ViewGroup,
    viewType: Int,
  ): ViewHolder = ViewHolder(ListItemFacetBinding.inflate(LayoutInflater.from(parent.context), parent, false))

  override fun onBindViewHolder(
    holder: ViewHolder,
    position: Int,
  ) {
    holder.bind(getItem(position), onClick)
  }

  class ViewHolder(private val binding: ListItemFacetBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(
      facet: Facet,
      onClick: (Facet) -> Unit,
    ) {
      binding.facetValue.text = facet.value
      // A plural, via `strings.xml` — "1 books" is the kind of detail that makes a screen feel
      // unfinished, and Android has quantity strings precisely for it (convention 5).
      binding.facetCount.text =
        binding.root.resources.getQuantityString(
          R.plurals.facet_book_count,
          facet.bookCount,
          facet.bookCount,
        )
      binding.facetRow.setOnClickListener { onClick(facet) }
    }
  }

  private object DiffCallback : DiffUtil.ItemCallback<Facet>() {
    override fun areItemsTheSame(
      oldItem: Facet,
      newItem: Facet,
    ): Boolean = oldItem.value == newItem.value

    override fun areContentsTheSame(
      oldItem: Facet,
      newItem: Facet,
    ): Boolean = oldItem == newItem
  }
}
