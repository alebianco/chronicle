package io.github.mattpvaughn.chronicle.views

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.databinding.ModalBottomSheetBookmarksBinding
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.views.compose.BookmarkList

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

  /**
   * The most recent list, held so a submit that arrives before the view does is not lost.
   *
   * **Not directly tested, deliberately** (cu-203). Asserting it needs the *rendered* list, and
   * the body is Compose: walking the View hierarchy for text finds only the sheet's title, and a
   * Compose semantics rule needs an activity in the manifest, which no test activity provides.
   * Two attempts are recorded in cu-203's notes — the first passed with `render(pending)` deleted,
   * which is worse than no test. `BookmarkListTest` covers what the list renders; this field's
   * behaviour is a one-line hand-off that `onCreateView` performs unconditionally.
   */
  private var pending: List<Bookmark> = emptyList()

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    val binding = ModalBottomSheetBookmarksBinding.inflate(inflater, container, false)
    this.binding = binding
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
    // The list and its empty message are one composable now (cu-203), so the two `isVisible`
    // writes that had to stay mutually exclusive are a single branch.
    binding.bookmarksCompose.setContent {
      ChronicleTheme {
        BookmarkList(
          bookmarks = bookmarks,
          onJump = { bookmark ->
            listener?.onBookmarkJump(bookmark)
            dismiss()
          },
          onEdit = { bookmark ->
            listener?.onBookmarkEdit(bookmark)
            dismiss()
          },
        )
      }
    }
  }

  override fun onStart() {
    super.onStart()
    expandBottomSheetOnStart()
  }

  override fun onDestroyView() {
    super.onDestroyView()
    binding = null
  }

  companion object {
    const val TAG = "ModalBottomSheetBookmarks"
  }
}
