package io.github.mattpvaughn.chronicle.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A view whose visibility is driven from Kotlin declares a default in XML (cu-68, cu-58).
 *
 * DataBinding evaluated every expression once at initial bind; ViewBinding does not. So a view that
 * used to be `android:visibility="@{...}"` now holds its **XML default** until the first LiveData
 * emission — and several of those sources are cold (`DoubleLiveData`, `QuadLiveDataAsync` computing
 * on `Dispatchers.IO`, an unseeded `MutableLiveData`), so "default" can last well past a frame.
 * The visible result was "No libraries found" painted over onboarding, and the bottom nav and mini
 * player flashing over the login screen.
 *
 * A source guard rather than a rendering test, because the defect is *structural*: the view is
 * correct in every state the app reaches, and wrong only in the instant before the first state
 * arrives. Nothing that inspects a laid-out view after its observer has run can see it.
 *
 * The check is deliberately narrow — a layout's *own* fragment or activity, matched by name — so it
 * flags what it can attribute rather than every id that happens to collide across screens.
 */
class FirstFrameFlashTest {
  private val layoutDir = File("src/main/res/layout")
  private val sourceDir = File("src/main/java/io/github/mattpvaughn/chronicle")

  /** `bottomNav` -> `bottom_nav`, the ViewBinding property name to its XML id. */
  private fun String.toSnakeCase(): String = replace(Regex("(?<!^)(?=[A-Z])"), "_").lowercase()

  /** `fragment_audiobook_details` -> `AudiobookDetails`, to find the class that owns a layout. */
  private fun layoutOwnerHint(layoutName: String): String =
    layoutName
      .removePrefix("fragment_")
      .removePrefix("activity_")
      .split('_')
      .joinToString("") { it.replaceFirstChar(Char::uppercase) }

  private data class Driven(
    val viewId: String,
    val sourceFile: String,
  )

  /** Every `binding.<view>.isVisible =` / `.visibility =` in the app's Kotlin. */
  private fun drivenViews(): List<Driven> =
    sourceDir.walkTopDown()
      .filter { it.extension == "kt" }
      .flatMap { file ->
        // `\w*[Bb]inding` rather than `binding`: ChooseUserFragment holds a local `tempBinding`,
        // and a scan that missed it defaulted two of its views to `gone` while reporting them as
        // having no writer — the orphan check below caught exactly that.
        Regex("""\w*[Bb]inding\.(\w+)\.(?:isVisible|visibility)\s*=""")
          .findAll(file.readText())
          .map { Driven(it.groupValues[1].toSnakeCase(), file.name) }
      }
      .toList()

  /** The element declaring [viewId] in [layout], or null. */
  private fun elementFor(
    layout: File,
    viewId: String,
  ): String? {
    val text = layout.readText()
    val idMatch = Regex("""android:id="@\+id/${Regex.escape(viewId)}"""").find(text) ?: return null
    val start = text.lastIndexOf('<', idMatch.range.first)
    val end = text.indexOf('>', idMatch.range.first)
    if (start < 0 || end < 0) return null
    return text.substring(start, end)
  }

  @Test
  fun `every Kotlin-driven view in its own layout declares an XML visibility`() {
    val driven = drivenViews()
    val offenders = mutableListOf<String>()

    for (layout in layoutDir.listFiles().orEmpty().filter { it.extension == "xml" }) {
      val hint = layoutOwnerHint(layout.nameWithoutExtension)
      for (view in driven) {
        // Only judge a view against the layout its own screen owns: the same id (`no_books_message`)
        // legitimately appears in several layouts driven by different classes, and attributing one
        // screen's Kotlin to another's XML would flag views that are perfectly correct.
        if (!view.sourceFile.startsWith(hint)) continue
        val element = elementFor(layout, view.viewId) ?: continue
        if (!element.contains("android:visibility")) {
          offenders += "${layout.name}: ${view.viewId} (driven from ${view.sourceFile})"
        }
      }
    }

    assertEquals(
      "these views hold their XML default until their first LiveData emission, which for a cold " +
        "source is long enough to see — give each an android:visibility, or android:visibility=" +
        "\"visible\" if it genuinely is the first state:\n" + offenders.joinToString("\n"),
      emptyList<String>(),
      offenders,
    )
  }

  /**
   * The specific views cu-68 fixed stay fixed.
   *
   * Named individually because these are the ones that were *seen* to flash — the general rule
   * above could be satisfied by a future edit that sets them `visible`, which for an error message
   * would reintroduce exactly the bug.
   */
  @Test
  fun `the views that were seen to flash default to gone`() {
    val mustBeGone =
      mapOf(
        "activity_main.xml" to listOf("bottom_nav", "currently_playing_container"),
        "onboarding_plex_choose_library.xml" to listOf("no_libraries_found", "library_list"),
        "onboarding_plex_choose_server.xml" to listOf("no_servers_found", "server_list"),
        "fragment_collections.xml" to listOf("offline_mode_container", "no_books_message"),
      )

    mustBeGone.forEach { (layoutName, ids) ->
      val layout = File(layoutDir, layoutName)
      assertTrue("$layoutName is missing", layout.exists())
      ids.forEach { id ->
        val element = elementFor(layout, id)
        assertTrue("$layoutName has no $id", element != null)
        assertTrue(
          "$layoutName:$id must default to gone — it is an error, empty or logged-in state, " +
            "none of which is true before the first emission",
          element!!.contains("""android:visibility="gone""""),
        )
      }
    }
  }

  /**
   * A `gone` default must not outlive the first emission.
   *
   * The risk this change introduces is the mirror of the one it fixes: a view defaulted to `gone`
   * that nothing ever un-hides is *permanently* invisible, which is worse than a one-frame flash.
   * Every id defaulted here is therefore checked to have a Kotlin writer — the same scan that
   * chose it, run in reverse.
   */
  @Test
  fun `every view defaulted to gone has something that can show it`() {
    val writers = drivenViews().map { it.viewId }.toSet()
    val orphans = mutableListOf<String>()

    for (layout in layoutDir.listFiles().orEmpty().filter { it.extension == "xml" }) {
      val text = layout.readText()
      for (match in Regex("""android:id="@\+id/(\w+)"[^>]*?android:visibility="gone"""", RegexOption.DOT_MATCHES_ALL)
        .findAll(text)) {
        val id = match.groupValues[1]
        // Only ids this sweep touched: an id that was already `gone` before cu-68 may legitimately
        // be shown by something this scan cannot see (an adapter, a binding helper).
        if (id in TOUCHED_BY_CU_68 && id !in writers) {
          orphans += "${layout.name}: $id"
        }
      }
    }

    assertEquals(
      "these default to gone with no Kotlin writer, so they would never appear:\n" +
        orphans.joinToString("\n"),
      emptyList<String>(),
      orphans,
    )
  }

  private companion object {
    /** The ids cu-68 defaulted to `gone`, so the orphan check judges only its own work. */
    val TOUCHED_BY_CU_68 =
      setOf(
        "bottom_nav", "currently_playing_container", "pause_play_button",
        "no_libraries_found", "library_list", "no_servers_found", "server_list",
        "user_list", "no_users_found", "offline_mode_container", "no_books_message",
        "connection_failed_message", "caching_tracks_spinner", "audio_loading_spinner",
        "bookmarks_list", "facet_list", "details_pause_play", "sleep_timer_countdown",
        "loading_tracks_spinner", "swipe_to_refresh", "downloaded_title",
        "downloaded_recyclerview", "recently_listened_title", "on_deck_recyclerview",
        "recently_added_title", "recently_added_recyclerview", "download",
        "info_summary", "info_expand_summary", "connecting_to_server_indicator",
      )
  }
}
