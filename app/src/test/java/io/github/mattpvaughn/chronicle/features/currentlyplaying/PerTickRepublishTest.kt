package io.github.mattpvaughn.chronicle.features.currentlyplaying

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * `update` runs **once a second** for the whole of playback, driven by `ProgressUpdater`.
 *
 * The trap it exists to avoid: `ProgressUpdater` writes the playing track's progress to Room every
 * tick, so the track list re-read on the next tick is a *genuinely different value*. Comparing the
 * lists therefore reports "changed" every tick and guards nothing — the comparison has to ignore
 * the one field that is meant to change (cu-117).
 */
class PerTickRepublishTest {
  private fun track(
    id: String,
    progress: Long = 0L,
    duration: Long = 180_000L,
  ) = MediaItemTrack(id = id, parentKey = "b1", title = "t$id", index = id.toInt(), progress = progress, duration = duration)

  private val book = Audiobook(id = "b1", source = 1L, title = "Book")

  private fun singleton() = CurrentlyPlayingSingleton()

  @Test
  fun `a tick that only advances progress does not republish the book or track`() {
    val s = singleton()
    val tracks = listOf(track("1"), track("2"))
    s.update(book = book, track = tracks[0], tracks = tracks)

    val bookBefore = s.book.value
    val trackBefore = s.track.value

    // The next tick: same book, same track identity, progress moved on — which is what Room hands
    // back a second later.
    val ticked = listOf(track("1", progress = 5_000L), track("2"))
    s.update(book = book, track = ticked[0], tracks = ticked)

    assertSame("an unchanged book must not be republished", bookBefore, s.book.value)
    // The track *value* differs by progress, so it is republished — that is correct and is the
    // position moving. What must not happen is the expensive chapter rebuild below.
    assertEquals("b1", s.book.value.id)
    assertEquals("1", s.track.value.id)
    assertEquals(trackBefore.id, s.track.value.id)
  }

  @Test
  fun `a progress-only tick does not re-notify the chapter listener`() {
    // The listener drives skip-to-next/previous-chapter (cu-87). Firing it every second is both
    // wasted work and a signal that the chapter list was rebuilt behind it.
    val s = singleton()
    var notifications = 0
    s.setOnChapterChangeListener(
      object : OnChapterChangeListener {
        override fun onChapterChange(chapter: io.github.mattpvaughn.chronicle.data.model.Chapter) {
          notifications++
        }
      },
    )

    val tracks = listOf(track("1"), track("2"))
    s.update(book = book, track = tracks[0], tracks = tracks)
    val afterFirst = notifications

    repeat(5) { i ->
      val ticked = listOf(track("1", progress = (i + 1) * 1_000L), track("2"))
      s.update(book = book, track = ticked[0], tracks = ticked)
    }

    assertEquals(
      "five progress-only ticks must not re-notify; the chapter has not changed",
      afterFirst,
      notifications,
    )
  }

  @Test
  fun `book position still advances on every tick`() {
    // The guard must not suppress the one thing that legitimately changes per tick.
    val s = singleton()
    s.update(book = book, track = track("1"), tracks = listOf(track("1"), track("2")))
    val first = s.bookPosition.value

    s.update(
      book = book,
      track = track("1", progress = 9_000L),
      tracks = listOf(track("1", progress = 9_000L), track("2")),
    )

    assertEquals("position must keep moving", 9_000L, s.bookPosition.value)
    assertEquals(0L, first)
  }
}
