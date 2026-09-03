package io.github.mattpvaughn.chronicle.views

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.databinding.ListItemBookmarkBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bookmark row, actually bound (cu-22).
 *
 * Robolectric so the binding runs for real: the two things worth checking are that a row without a
 * note does not leave an empty line, and that a tap on the row means *jump* while a tap on the
 * pencil means *edit* — two click targets in one row, which is exactly the arrangement that gets
 * wired to the wrong callback.
 */
@RunWith(RobolectricTestRunner::class)
class BookmarkListAdapterTest {
  private lateinit var parent: FrameLayout
  private val jumped = mutableListOf<Bookmark>()
  private val edited = mutableListOf<Bookmark>()

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    parent = FrameLayout(context)
    jumped.clear()
    edited.clear()
  }

  private fun adapter() = BookmarkListAdapter(onJump = { jumped += it }, onEdit = { edited += it })

  private fun bind(bookmark: Bookmark): ListItemBookmarkBinding {
    val adapter = adapter()
    adapter.submitList(listOf(bookmark))
    val holder = adapter.onCreateViewHolder(parent, 0)
    adapter.bindViewHolder(holder, 0)
    return ListItemBookmarkBinding.bind(holder.itemView)
  }

  private fun bookmark(
    id: String = "bm-1",
    position: Long = 3_753_000L,
    note: String = "",
  ) = Bookmark(id = id, bookId = "1001", position = BookOffset(position), note = note)

  @Test
  fun `a row shows its position, formatted the way the player does`() {
    val binding = bind(bookmark(position = 3_753_000L))

    assertEquals("1:02:33", binding.bookmarkPosition.text.toString())
  }

  @Test
  fun `a row with a note shows it`() {
    val binding = bind(bookmark(note = "the riddle game"))

    assertEquals(View.VISIBLE, binding.bookmarkNote.visibility)
    assertEquals("the riddle game", binding.bookmarkNote.text.toString())
  }

  /**
   * The note line must be **gone**, not merely empty: an empty visible TextView still takes its
   * padding and leaves a gap that reads as a rendering fault.
   */
  @Test
  fun `a row with no note hides the note line entirely`() {
    val binding = bind(bookmark(note = ""))

    assertEquals(View.GONE, binding.bookmarkNote.visibility)
  }

  @Test
  fun `a note of only whitespace also hides the line`() {
    val binding = bind(bookmark(note = "   "))

    assertEquals(View.GONE, binding.bookmarkNote.visibility)
  }

  @Test
  fun `tapping the row jumps`() {
    val target = bookmark()
    val binding = bind(target)

    binding.bookmarkRow.performClick()

    assertSame(target, jumped.single())
    assertEquals("a tap on the row must not open the editor", 0, edited.size)
  }

  @Test
  fun `tapping the pencil edits`() {
    val target = bookmark()
    val binding = bind(target)

    binding.bookmarkEdit.performClick()

    assertSame(target, edited.single())
    assertEquals("a tap on the pencil must not seek", 0, jumped.size)
  }

  /**
   * A recycled row must not keep the previous bookmark's note. This is the classic RecyclerView
   * bug: a view whose visibility is only ever set in the "has a note" branch shows the last one.
   *
   * Bound through the ViewHolder directly rather than by calling `submitList` twice —
   * `ListAdapter` diffs on a background executor, so the second list is not visible to
   * `getItem` synchronously and the test would be asserting against the *first* one. Recycling is
   * about re-binding one holder, which is what this does.
   */
  @Test
  fun `a recycled row does not keep the previous note`() {
    val holder = adapter().onCreateViewHolder(parent, 0)

    holder.bind(bookmark(id = "bm-1", note = "the riddle game"), onJump = {}, onEdit = {})
    holder.bind(bookmark(id = "bm-2", note = ""), onJump = {}, onEdit = {})

    val binding = ListItemBookmarkBinding.bind(holder.itemView)
    assertEquals(View.GONE, binding.bookmarkNote.visibility)
  }

  /** And a recycled row's click goes to the bookmark it is *currently* showing. */
  @Test
  fun `a recycled row jumps to its current bookmark`() {
    val holder = adapter().onCreateViewHolder(parent, 0)

    holder.bind(bookmark(id = "bm-1"), onJump = { jumped += it }, onEdit = {})
    holder.bind(bookmark(id = "bm-2", position = 60_000L), onJump = { jumped += it }, onEdit = {})
    ListItemBookmarkBinding.bind(holder.itemView).bookmarkRow.performClick()

    assertEquals(
      "a stale click listener would seek to the row's previous bookmark",
      "bm-2",
      jumped.single().id,
    )
  }

  @Test
  fun `an empty list binds nothing`() {
    val adapter = adapter()
    adapter.submitList(emptyList())

    assertEquals(0, adapter.itemCount)
    assertNull(jumped.firstOrNull())
  }
}
