package io.github.mattpvaughn.chronicle.views

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.databinding.ModalBottomSheetBookmarkNoteBinding
import io.github.mattpvaughn.chronicle.util.formatPrecisePosition

/**
 * Writes or edits a bookmark's note (cu-22).
 *
 * A bottom sheet, because that is what this app uses for every other transient choice — there is
 * no `AlertDialog` anywhere in the codebase, and adding one here would make the note the odd
 * surface out.
 *
 * State comes in through arguments and results go out through [Listener], so the sheet survives a
 * rotation without holding a reference to a Fragment that may be gone.
 */
class ModalBottomSheetBookmarkNote : BottomSheetDialogFragment() {
  /** What the host does with the result. */
  interface Listener {
    fun onNoteSaved(
      bookmarkId: String,
      note: String,
    )

    fun onBookmarkDeleted(bookmarkId: String)
  }

  /**
   * Resolved from the parent fragment each time rather than stored.
   *
   * A stored callback is the classic bottom-sheet leak: the sheet outlives a configuration change
   * and the captured Fragment does not.
   */
  private val listener: Listener?
    get() = parentFragment as? Listener ?: activity as? Listener

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    val binding = ModalBottomSheetBookmarkNoteBinding.inflate(inflater, container, false)
    val bookmarkId = requireArguments().getString(ARG_BOOKMARK_ID).orEmpty()
    val positionMillis = requireArguments().getLong(ARG_POSITION_MILLIS)
    val existingNote = requireArguments().getString(ARG_NOTE).orEmpty()

    binding.bookmarkNoteTitle.text =
      getString(R.string.bookmark_added, formatPrecisePosition(positionMillis))
    binding.bookmarkNoteEdittext.setText(existingNote)
    // Caret at the end, so editing an existing note does not require repositioning it.
    binding.bookmarkNoteEdittext.setSelection(existingNote.length)

    binding.bookmarkNoteSave.setOnClickListener {
      listener?.onNoteSaved(bookmarkId, binding.bookmarkNoteEdittext.text?.toString().orEmpty())
      dismiss()
    }
    binding.bookmarkNoteDelete.setOnClickListener {
      listener?.onBookmarkDeleted(bookmarkId)
      dismiss()
    }

    return binding.root
  }

  companion object {
    const val TAG = "ModalBottomSheetBookmarkNote"

    private const val ARG_BOOKMARK_ID = "bookmark_id"
    private const val ARG_POSITION_MILLIS = "position_millis"
    private const val ARG_NOTE = "note"

    fun forBookmark(
      bookmarkId: String,
      positionMillis: Long,
      note: String,
    ) = ModalBottomSheetBookmarkNote().apply {
      arguments =
        bundleOf(
          ARG_BOOKMARK_ID to bookmarkId,
          ARG_POSITION_MILLIS to positionMillis,
          ARG_NOTE to note,
        )
    }
  }
}
