package io.github.mattpvaughn.chronicle.data.sources.plex

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Choosing a library must say whether it *replaced a different one*.
 *
 * Switching libraries invalidates every cached book and track. Settings already handled that — it
 * clears the databases and asks about downloads — but the login picker did not, so choosing a
 * different library there left Room holding the previous library's catalogue and the app showed a
 * **union of two libraries** until the next refresh pruned it (cu-126).
 *
 * The distinction that matters is *changed*, not *chosen*: a first-ever selection has nothing to
 * invalidate, and treating it as a change would clear a database that is already empty and, in the
 * Settings flow, prompt about downloads that cannot exist yet.
 */
class LibrarySwitchClearsCacheTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  private fun library(id: String) = PlexLibrary(name = "Audiobooks", type = MediaType.ARTIST, id = id)

  private fun repo(existing: PlexLibrary?): IPlexLoginRepo {
    val prefs =
      mockk<PlexPrefsRepo>(relaxed = true) {
        every { library } returns existing
      }
    return PlexLoginRepo(
      plexPrefsRepo = prefs,
      plexLoginService = mockk(relaxed = true),
      plexConfig = mockk(relaxed = true),
      accountAuthState = AccountAuthState(),
    )
  }

  @Test
  fun `choosing a different library reports a change`() {
    val changed = repo(existing = library("14")).chooseLibrary(library("22"))

    assertTrue("switching libraries must be reported, so the stale catalogue is dropped", changed)
  }

  @Test
  fun `re-choosing the same library reports no change`() {
    // Re-picking the current library is a no-op; clearing here would throw away a good cache and,
    // in the Settings flow, prompt about downloads for no reason.
    val changed = repo(existing = library("14")).chooseLibrary(library("14"))

    assertFalse(changed)
  }

  @Test
  fun `a first-ever choice is not a change`() {
    // Onboarding: there is nothing cached to invalidate.
    val changed = repo(existing = null).chooseLibrary(library("14"))

    assertFalse("a first selection must not be treated as a switch", changed)
  }
}
