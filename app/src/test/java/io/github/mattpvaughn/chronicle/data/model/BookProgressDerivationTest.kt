package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Book progress is derived from the tracks, never merged as independent state (decision-16).
 *
 * `Audiobook.merge` carefully preserved `local.progress` in both branches, with a comment
 * explaining why — and then `syncAudiobook` discarded it:
 *
 * ```kotlin
 * Audiobook.merge(network, local, forceNetwork).copy(progress = tracks.getProgress(), …)
 * ```
 *
 * So two code paths claimed ownership of the same value and whichever ran last won. Book-level and
 * track-level progress could therefore disagree, and which one the user saw depended on fetch
 * ordering — the owner's *"reloading a book info sometimes makes the current position change
 * unpredictably"*.
 *
 * The resolution is not to make `merge` win; it is to remove the disagreement. Position lives on the
 * tracks, so `Audiobook.progress` is a **cache of that derivation**, written only where the tracks
 * are available (`syncAudiobook`). `merge` must therefore carry the local value through and never
 * take one from the network — `network.progress` carries no information, since Plex has no
 * album-level position.
 *
 * An earlier attempt here zeroed progress in `merge` to make it "have no opinion". That was wrong,
 * and the audit that caught it is the reason this test exists in this shape: `refreshData` merges
 * every book **without loading tracks** and writes the result with `bookDao.insertAll`, so zeroing
 * would have blanked every book's progress in the library list on each refresh — a worse bug than
 * the one being fixed.
 */
class BookProgressDerivationTest {
  private fun book(
    progress: Long,
    lastViewedAt: Long,
  ) = Audiobook(
    id = "1001",
    source = 1L,
    title = "Dune",
    progress = progress,
    lastViewedAt = lastViewedAt,
    duration = 6_000L,
  )

  /**
   * The local value survives, because it is the last derivation from the tracks and a library
   * refresh merges without them.
   */
  @Test
  fun `merge keeps the local progress when the network copy is newer`() {
    val local = book(progress = 4_000L, lastViewedAt = 500L)
    val network = book(progress = 1_000L, lastViewedAt = 900L)

    assertEquals(
      "a refresh must not blank the position the user can see",
      4_000L,
      Audiobook.merge(network = network, local = local).progress,
    )
  }

  @Test
  fun `merge keeps the local progress when the local copy is newer`() {
    val local = book(progress = 4_000L, lastViewedAt = 900L)
    val network = book(progress = 1_000L, lastViewedAt = 500L)

    assertEquals(4_000L, Audiobook.merge(network = network, local = local).progress)
  }

  /**
   * The property that actually matters: the network's album-level progress is never adopted. Plex
   * has no such field, so any value there is a default, and taking it would overwrite a real
   * position with a meaningless one.
   */
  @Test
  fun `merge never adopts progress from the network`() {
    val local = book(progress = 0L, lastViewedAt = 500L)
    val network = book(progress = 9_999L, lastViewedAt = 900L)

    assertEquals(
      "network.progress carries no information; adopting it invents a position",
      0L,
      Audiobook.merge(network = network, local = local).progress,
    )
  }

  @Test
  fun `forcing the network still does not adopt network progress`() {
    val local = book(progress = 4_000L, lastViewedAt = 500L)
    val network = book(progress = 9_999L, lastViewedAt = 100L)

    assertEquals(
      4_000L,
      Audiobook.merge(network = network, local = local, forceNetwork = true).progress,
    )
  }

  /** The fields merge legitimately owns must keep working. */
  @Test
  fun `merge still preserves genuinely local fields`() {
    val local =
      book(progress = 4_000L, lastViewedAt = 500L)
        .copy(isCached = true, favorited = true, duration = 6_000L)
    val network = book(progress = 0L, lastViewedAt = 900L).copy(isCached = false, favorited = false)

    val merged = Audiobook.merge(network = network, local = local)

    assertEquals("isCached is local-only; the server has no such concept", true, merged.isCached)
    assertEquals(true, merged.favorited)
    assertEquals(6_000L, merged.duration)
  }

  /**
   * The derivation itself, end to end: a book's progress equals what its tracks say, and updating
   * the tracks changes it without any merge being involved.
   */
  @Test
  fun `book progress equals the derivation from its tracks`() {
    val tracks =
      listOf(
        MediaItemTrack(id = "1", parentKey = "1001", index = 1, duration = 1_000L, progress = 1_000L, lastViewedAt = 100L),
        MediaItemTrack(id = "2", parentKey = "1001", index = 2, duration = 2_000L, progress = 750L, lastViewedAt = 200L),
        MediaItemTrack(id = "3", parentKey = "1001", index = 3, duration = 3_000L),
      )

    assertEquals(1_000L + 750L, tracks.getProgress())
  }

  /**
   * The branch selector itself, which no other test here observes.
   *
   * Every other assertion in this class reads `.progress`, and `merge` deliberately carries the
   * local progress through in *both* branches (decision-16) — so the freshness comparison could be
   * inverted, or removed entirely, without failing any of them. Two mutants survived on that line
   * for exactly this reason.
   *
   * `lastViewedAt` is the one field the branches treat differently: the stale branch pins it to the
   * local value, the fresh branch lets the network's through. It drives "recently played" ordering,
   * so picking the wrong branch silently reorders the library.
   */
  @Test
  fun `a newer network timestamp is adopted`() {
    val merged =
      Audiobook.merge(
        network = book(progress = 0L, lastViewedAt = 900L),
        local = book(progress = 4_000L, lastViewedAt = 500L),
      )

    assertEquals("the network is newer, so its timestamp wins", 900L, merged.lastViewedAt)
  }

  @Test
  fun `an older network timestamp is not adopted`() {
    val merged =
      Audiobook.merge(
        network = book(progress = 0L, lastViewedAt = 500L),
        local = book(progress = 4_000L, lastViewedAt = 900L),
      )

    assertEquals("the local copy is newer and must keep its timestamp", 900L, merged.lastViewedAt)
  }

  /**
   * The boundary: equal timestamps are not "newer", so the local copy stands.
   *
   * Note this case cannot distinguish `>` from `>=`. Both branches return `network.copy(...)` and
   * the only field the stale branch overrides is `lastViewedAt` — so when the two timestamps are
   * equal the branches produce an identical book, and a boundary mutant here is *equivalent*.
   * `a newer network timestamp is adopted` is what actually pins the comparison.
   */
  @Test
  fun `an equal timestamp keeps the local copy`() {
    val merged =
      Audiobook.merge(
        network = book(progress = 0L, lastViewedAt = 700L),
        local = book(progress = 4_000L, lastViewedAt = 700L),
      )

    assertEquals(700L, merged.lastViewedAt)
  }

  /** Forcing overrides the comparison, including when the network is older. */
  @Test
  fun `forcing the network adopts its timestamp even when older`() {
    val merged =
      Audiobook.merge(
        network = book(progress = 0L, lastViewedAt = 100L),
        local = book(progress = 4_000L, lastViewedAt = 900L),
        forceNetwork = true,
      )

    assertEquals("forceNetwork takes the network branch regardless", 100L, merged.lastViewedAt)
  }
}
