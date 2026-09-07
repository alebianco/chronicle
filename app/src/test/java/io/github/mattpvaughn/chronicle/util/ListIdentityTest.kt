package io.github.mattpvaughn.chronicle.util

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test

/**
 * The list comparison the library and collections screens share.
 *
 * It decides whether to force a scroll-to-top, so a false "different" is visible as the list
 * jumping under the reader's finger — and comparing by **id** rather than by equality is what
 * prevents that, since the playing book's `progress` changes once a second.
 */
class ListIdentityTest {
  private data class Row(val id: String, val progress: Long = 0L)

  private fun different(
    incoming: List<Row>,
    current: List<Row>?,
  ) = isDifferentListById(incoming, current?.map { it.id }) { it.id }

  @Test
  fun `the same ids in the same order are not a new list`() {
    val current = listOf(Row("1"), Row("2"), Row("3"))

    assertThat(different(listOf(Row("1"), Row("2"), Row("3")), current), equalTo(false))
  }

  /** The progress-churn case: a field changed every second, but the list is the same list. */
  @Test
  fun `a changed field on the same ids is not a new list`() {
    val current = listOf(Row("1", progress = 0L), Row("2"))

    assertThat(different(listOf(Row("1", progress = 5_000L), Row("2")), current), equalTo(false))
  }

  @Test
  fun `a different id is a new list`() {
    assertThat(different(listOf(Row("1"), Row("9")), listOf(Row("1"), Row("2"))), equalTo(true))
  }

  @Test
  fun `a different size is a new list`() {
    assertThat(different(listOf(Row("1")), listOf(Row("1"), Row("2"))), equalTo(true))
  }

  @Test
  fun `the same ids reordered are a new list`() {
    // Order is what the adapter renders, so a re-sort must redraw.
    assertThat(different(listOf(Row("2"), Row("1")), listOf(Row("1"), Row("2"))), equalTo(true))
  }

  @Test
  fun `no adapter yet counts as different, because nothing has been submitted`() {
    assertThat(different(listOf(Row("1")), null), equalTo(true))
  }

  @Test
  fun `two empty lists are not a new list`() {
    assertThat(different(emptyList(), emptyList()), equalTo(false))
  }

  @Test
  fun `an emptied list is a new list`() {
    assertThat(different(emptyList(), listOf(Row("1"))), equalTo(true))
  }
}
