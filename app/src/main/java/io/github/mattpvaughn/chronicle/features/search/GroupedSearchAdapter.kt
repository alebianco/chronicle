package io.github.mattpvaughn.chronicle.features.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.SearchField
import io.github.mattpvaughn.chronicle.databinding.ListItemSearchHeaderBinding
import io.github.mattpvaughn.chronicle.databinding.ListItemSearchResultAudiobookBinding
import io.github.mattpvaughn.chronicle.views.bindImageRounded

/**
 * Grouped search results: a heading per matched field, then its books (cu-25).
 *
 * Replaces the flat `AudiobookSearchAdapter` on the search overlays. Two view types rather than a
 * concatenated adapter, because the rows come from one already-ordered list and a header's count
 * has to stay adjacent to the group it counts.
 */
class GroupedSearchAdapter(
  private val onBookClick: (Audiobook) -> Unit,
) : ListAdapter<SearchRow, RecyclerView.ViewHolder>(DiffCallback) {
  private var serverConnected: Boolean = false

  fun setServerConnected(connected: Boolean) {
    if (serverConnected == connected) return
    serverConnected = connected
    notifyDataSetChanged()
  }

  override fun getItemViewType(position: Int): Int =
    when (getItem(position)) {
      is SearchRow.Header -> VIEW_TYPE_HEADER
      is SearchRow.Book -> VIEW_TYPE_BOOK
    }

  override fun onCreateViewHolder(
    parent: ViewGroup,
    viewType: Int,
  ): RecyclerView.ViewHolder {
    val inflater = LayoutInflater.from(parent.context)
    return when (viewType) {
      VIEW_TYPE_HEADER ->
        HeaderViewHolder(ListItemSearchHeaderBinding.inflate(inflater, parent, false))
      VIEW_TYPE_BOOK ->
        BookViewHolder(ListItemSearchResultAudiobookBinding.inflate(inflater, parent, false))
      else -> throw IllegalStateException("Unknown search row view type: $viewType")
    }
  }

  override fun onBindViewHolder(
    holder: RecyclerView.ViewHolder,
    position: Int,
  ) {
    when (val row = getItem(position)) {
      is SearchRow.Header -> (holder as HeaderViewHolder).bind(row)
      is SearchRow.Book -> (holder as BookViewHolder).bind(row, onBookClick, serverConnected)
    }
  }

  class HeaderViewHolder(private val binding: ListItemSearchHeaderBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(row: SearchRow.Header) {
      val context = binding.root.context
      binding.searchHeaderLabel.setText(labelFor(row.field))
      binding.searchHeaderCount.text =
        context.resources.getQuantityString(
          R.plurals.facet_book_count,
          row.bookCount,
          row.bookCount,
        )
    }
  }

  class BookViewHolder(private val binding: ListItemSearchResultAudiobookBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(
      row: SearchRow.Book,
      onBookClick: (Audiobook) -> Unit,
      isConnected: Boolean,
    ) {
      val book = row.book
      binding.searchResultRoot.setOnClickListener { onBookClick(book) }
      binding.title.text = book.title
      // Under a narrator or series heading the author line would repeat what the heading already
      // said while hiding the fact that matched; say what matched instead.
      binding.author.text = subtitleFor(row)
      binding.bookCoverImg.contentDescription = book.title
      bindImageRounded(binding.bookCoverImg, book.thumb, isConnected)
      binding.notPlayedDogEar.isVisible = book.viewCount == 0L && book.progress == 0L
    }

    private fun subtitleFor(row: SearchRow.Book): String {
      val context = binding.root.context
      return when (row.field) {
        SearchField.Narrator ->
          context.getString(R.string.search_matched_narrator, row.matchedValue)
        SearchField.Series ->
          context.getString(R.string.search_matched_series, row.matchedValue)
        SearchField.Title, SearchField.Author -> row.book.author
      }
    }
  }

  private object DiffCallback : DiffUtil.ItemCallback<SearchRow>() {
    override fun areItemsTheSame(
      oldItem: SearchRow,
      newItem: SearchRow,
    ): Boolean =
      when {
        oldItem is SearchRow.Header && newItem is SearchRow.Header ->
          oldItem.field == newItem.field
        // Identity is the book *within its group*: the same book can legitimately appear under two
        // headings in principle, and keying on the id alone would make those one row.
        oldItem is SearchRow.Book && newItem is SearchRow.Book ->
          oldItem.book.id == newItem.book.id && oldItem.field == newItem.field
        else -> false
      }

    override fun areContentsTheSame(
      oldItem: SearchRow,
      newItem: SearchRow,
    ): Boolean = oldItem == newItem
  }

  companion object {
    private const val VIEW_TYPE_HEADER = 0
    private const val VIEW_TYPE_BOOK = 1

    fun labelFor(field: SearchField): Int =
      when (field) {
        SearchField.Title -> R.string.search_group_title
        SearchField.Author -> R.string.search_group_author
        SearchField.Narrator -> R.string.search_group_narrator
        SearchField.Series -> R.string.search_group_series
      }
  }
}
