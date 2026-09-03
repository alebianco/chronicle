package io.github.mattpvaughn.chronicle.features.search

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.SearchField
import io.github.mattpvaughn.chronicle.data.model.groupedSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The grouped search rows render (cu-25).
 *
 * Robolectric so the binding runs for real. The rows are bound through the ViewHolder directly
 * rather than via `submitList`, which is asynchronous — two cu-22 tests asserted against the list
 * before the diff had landed and passed for the wrong reason.
 */
@RunWith(RobolectricTestRunner::class)
class GroupedSearchAdapterTest {
  private lateinit var context: Context
  private lateinit var parent: FrameLayout
  private val clicked = mutableListOf<Audiobook>()

  @Before
  fun setUp() {
    // The row layout uses ?attr/selectableItemBackground, so it needs a context carrying the app
    // theme — a bare application context cannot resolve a theme attribute and inflation throws.
    context =
      ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.AppTheme)
    parent = FrameLayout(context)
    clicked.clear()
  }

  private fun adapter() = GroupedSearchAdapter(onBookClick = { clicked += it })

  private fun book(
    id: String,
    title: String,
    author: String = "",
    narrator: String = "",
    series: String = "",
  ) = Audiobook(
    id = id,
    source = 1L,
    title = title,
    author = author,
    narrator = narrator,
    series = series,
  )

  /**
   * Binds a header row through a real ViewHolder.
   *
   * The view type is passed explicitly rather than asked of the adapter: `getItemViewType` reads
   * the current list, and an adapter that has not been submitted to has no item 0.
   */
  private fun headerView(row: SearchRow.Header): View {
    val holder =
      adapter().onCreateViewHolder(parent, VIEW_TYPE_HEADER)
        as GroupedSearchAdapter.HeaderViewHolder
    holder.bind(row)
    return holder.itemView
  }

  // ---- flattening ----

  @Test
  fun `each group becomes a header followed by its books`() {
    val books =
      listOf(
        book("1", title = "Mistborn Book 10"),
        book("2", title = "Elantris", series = "Mistborn"),
      )

    val rows = books.groupedSearch("Mistborn").toRows()

    assertEquals(
      listOf(
        SearchField.Title to 1,
        SearchField.Series to 1,
      ),
      rows.filterIsInstance<SearchRow.Header>().map { it.field to it.bookCount },
    )
    assertEquals(4, rows.size)
  }

  @Test
  fun `a header precedes the books it counts`() {
    val rows = listOf(book("1", title = "Dune")).groupedSearch("Dune").toRows()

    assertTrue(rows.first() is SearchRow.Header)
    assertTrue(rows[1] is SearchRow.Book)
  }

  @Test
  fun `an empty result produces no rows`() {
    assertTrue(listOf(book("1", title = "Dune")).groupedSearch("zzzq").toRows().isEmpty())
  }

  // ---- header rendering ----

  @Test
  fun `a header shows its field name and book count`() {
    val view = headerView(SearchRow.Header(field = SearchField.Narrator, bookCount = 3))

    val label = view.findViewById<android.widget.TextView>(R.id.search_header_label)
    val count = view.findViewById<android.widget.TextView>(R.id.search_header_count)
    assertEquals(context.getString(R.string.search_group_narrator), label.text.toString())
    assertEquals("3 books", count.text.toString())
  }

  @Test
  fun `a header counting one book says book, not books`() {
    val view = headerView(SearchRow.Header(field = SearchField.Title, bookCount = 1))

    val count = view.findViewById<android.widget.TextView>(R.id.search_header_count)
    assertEquals("1 book", count.text.toString())
  }

  // ---- book rendering ----

  private fun bindBook(row: SearchRow.Book): View {
    val holder =
      adapter().onCreateViewHolder(parent, VIEW_TYPE_BOOK)
        as GroupedSearchAdapter.BookViewHolder
    holder.bind(row, onBookClick = { clicked += it }, isConnected = true)
    return holder.itemView
  }

  private fun subtitleOf(row: SearchRow.Book): String = bindBook(row).findViewById<android.widget.TextView>(R.id.author).text.toString()

  @Test
  fun `a book matched by title shows its author`() {
    val row =
      SearchRow.Book(
        book = book("1", title = "Dune", author = "Frank Herbert"),
        field = SearchField.Title,
        matchedValue = "Dune",
      )

    assertEquals("Frank Herbert", subtitleOf(row))
  }

  /**
   * A narrator hit must say the narrator, not the author.
   *
   * Under a "Narrators" heading, showing the author leaves the user unable to tell which narrator
   * matched — the reason `matchedValue` travels with the row at all.
   */
  @Test
  fun `a book matched by narrator says who narrated it`() {
    val row =
      SearchRow.Book(
        book = book("1", title = "Mistborn", author = "Brandon Sanderson", narrator = "Michael Kramer"),
        field = SearchField.Narrator,
        matchedValue = "Michael Kramer",
      )

    assertEquals("Narrated by Michael Kramer", subtitleOf(row))
  }

  @Test
  fun `a book matched by series names the series`() {
    val row =
      SearchRow.Book(
        book = book("1", title = "The Final Empire", series = "Mistborn"),
        field = SearchField.Series,
        matchedValue = "Mistborn",
      )

    assertEquals("Mistborn series", subtitleOf(row))
  }

  @Test
  fun `tapping a book row reports the book`() {
    val target = book("1", title = "Dune", author = "Frank Herbert")
    val view =
      bindBook(
        SearchRow.Book(book = target, field = SearchField.Title, matchedValue = "Dune"),
      )

    view.findViewById<View>(R.id.search_result_root).performClick()

    assertEquals(listOf(target), clicked)
  }

  /**
   * The view types this test hardcodes really are the ones the adapter reports.
   *
   * The constants below duplicate private ones, so without this the header and book tests would
   * quietly start inflating the wrong layout if the adapter renumbered them.
   */
  @Test
  fun `the adapter reports the view types this test assumes`() {
    val adapter = adapter()
    adapter.submitList(
      listOf(
        SearchRow.Header(field = SearchField.Title, bookCount = 1),
        SearchRow.Book(
          book = book("1", title = "Dune"),
          field = SearchField.Title,
          matchedValue = "Dune",
        ),
      ),
    )
    // submitList diffs against an empty list synchronously on the first submission.
    assertEquals(VIEW_TYPE_HEADER, adapter.getItemViewType(0))
    assertEquals(VIEW_TYPE_BOOK, adapter.getItemViewType(1))
  }

  private companion object {
    /** Mirrors [GroupedSearchAdapter]'s private view types; pinned by the test above. */
    const val VIEW_TYPE_HEADER = 0
    const val VIEW_TYPE_BOOK = 1
  }
}
