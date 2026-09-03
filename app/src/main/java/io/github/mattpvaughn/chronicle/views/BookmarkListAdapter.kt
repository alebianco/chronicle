package io.github.mattpvaughn.chronicle.views

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.databinding.ListItemBookmarkBinding
import io.github.mattpvaughn.chronicle.util.formatPrecisePosition

/** The bookmark list (cu-22). Tap a row to jump; tap the pencil to edit or delete. */
class BookmarkListAdapter(
  private val onJump: (Bookmark) -> Unit,
  private val onEdit: (Bookmark) -> Unit,
) : ListAdapter<Bookmark, BookmarkListAdapter.ViewHolder>(DiffCallback) {
  override fun onCreateViewHolder(
    parent: ViewGroup,
    viewType: Int,
  ): ViewHolder =
    ViewHolder(
      ListItemBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

  override fun onBindViewHolder(
    holder: ViewHolder,
    position: Int,
  ) {
    holder.bind(getItem(position), onJump, onEdit)
  }

  class ViewHolder(private val binding: ListItemBookmarkBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(
      bookmark: Bookmark,
      onJump: (Bookmark) -> Unit,
      onEdit: (Bookmark) -> Unit,
    ) {
      // `formatPrecisePosition`, not `DateUtils` — a position inside a book is shown the same way
      // everywhere in this app (cu-19, RESEARCH_FINDINGS §3.1 rule 3).
      binding.bookmarkPosition.text = formatPrecisePosition(bookmark.position.millis)
      binding.bookmarkNote.text = bookmark.note
      binding.bookmarkNote.isVisible = bookmark.hasNote
      binding.bookmarkRow.setOnClickListener { onJump(bookmark) }
      binding.bookmarkEdit.setOnClickListener { onEdit(bookmark) }
    }
  }

  /**
   * Identity is the id, contents are everything shown.
   *
   * `areContentsTheSame` deliberately compares the note and the position rather than the whole
   * record: `createdAt` never changes, so including it costs nothing, but being explicit here is
   * what stops a future field from silently suppressing a rebind.
   */
  private object DiffCallback : DiffUtil.ItemCallback<Bookmark>() {
    override fun areItemsTheSame(
      oldItem: Bookmark,
      newItem: Bookmark,
    ): Boolean = oldItem.id == newItem.id

    override fun areContentsTheSame(
      oldItem: Bookmark,
      newItem: Bookmark,
    ): Boolean = oldItem.position == newItem.position && oldItem.note == newItem.note
  }
}
