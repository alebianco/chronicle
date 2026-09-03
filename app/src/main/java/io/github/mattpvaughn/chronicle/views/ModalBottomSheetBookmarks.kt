package io.github.mattpvaughn.chronicle.views

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.databinding.ModalBottomSheetBookmarksBinding

/**
 * The bookmarks of the book being played (cu-22).
 *
 * The list lives here rather than on the book-details screen so bookmarks have **one** home,
 * reachable from where they are made. Details already has a single RecyclerView for chapters, and
 * adding a second list there would mean restructuring that layout for a list most books will not
 * have.
 *
 * Data comes from the host through [setBookmarks] rather than from a repository, so this sheet has
 * no Dagger dependency and its host keeps ownership of the LiveData subscription.
 */
class ModalBottomSheetBookmarks : BottomSheetDialogFragment() {
  interface Listener {
    fun onBookmarkJump(bookmark: Bookmark)

    fun onBookmarkEdit(bookmark: Bookmark)
  }

  /** Resolved each time rather than stored: a captured Fragment outlives its own destruction. */
  private val listener: Listener?
    get() = parentFragment as? Listener ?: activity as? Listener

  private var binding: ModalBottomSheetBookmarksBinding? = null
  private var adapter: BookmarkListAdapter? = null

  /** The most recent list, held so a submit that arrives before the view does is not lost. */
  private var pending: List<Bookmark> = emptyList()

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    val binding = ModalBottomSheetBookmarksBinding.inflate(inflater, container, false)
    this.binding = binding
    val adapter =
      BookmarkListAdapter(
        onJump = { bookmark ->
          listener?.onBookmarkJump(bookmark)
          dismiss()
        },
        onEdit = { bookmark ->
          listener?.onBookmarkEdit(bookmark)
          dismiss()
        },
      )
    this.adapter = adapter
    binding.bookmarksList.adapter = adapter
    render(pending)
    return binding.root
  }

  /** Updates the list. Safe before the view exists — the value is held and applied on create. */
  fun setBookmarks(bookmarks: List<Bookmark>) {
    pending = bookmarks
    render(bookmarks)
  }

  private fun render(bookmarks: List<Bookmark>) {
    val binding = binding ?: return
    adapter?.submitList(bookmarks)
    // The empty message and the list are mutually exclusive: showing both reads as a bug, and
    // showing neither leaves a blank sheet with no explanation.
    binding.bookmarksEmpty.isVisible = bookmarks.isEmpty()
    binding.bookmarksList.isVisible = bookmarks.isNotEmpty()
  }

  override fun onDestroyView() {
    super.onDestroyView()
    binding?.bookmarksList?.adapter = null
    binding = null
    adapter = null
  }

  companion object {
    const val TAG = "ModalBottomSheetBookmarks"
  }
}
