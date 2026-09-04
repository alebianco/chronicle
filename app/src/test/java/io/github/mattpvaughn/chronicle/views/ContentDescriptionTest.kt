package io.github.mattpvaughn.chronicle.views

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every image is either labelled for a screen reader or explicitly marked decorative (cu-47).
 *
 * TalkBack announces an unlabelled `ImageView` as "unlabelled image" — or, for a control, says
 * nothing useful at all — so a blind listener cannot tell the skip button from the bookmark one.
 *
 * **A label may be set in XML or from Kotlin**, and both are accepted, because the eight cover-art
 * views legitimately take the *book's title* as their description and only know it at bind time
 * (`binding.bookCoverImg.contentDescription = audiobook.title`). Requiring an XML string there
 * would force a worse label.
 *
 * The audit that produced this found the labelling already in good shape — 27 XML descriptions,
 * 8 set from Kotlin, none genuinely missing — so this guard exists to *keep* it that way rather
 * than to drive a cleanup. It is the cheap half of the accessibility work; the parts needing a
 * human ear are called out in the task.
 */
class ContentDescriptionTest {
  private val layoutDir = File("src/main/res/layout")
  private val sourceDir = File("src/main/java/io/github/mattpvaughn/chronicle")

  /** `book_cover_img` -> `bookCoverImg`, the ViewBinding property a Kotlin label would use. */
  private fun String.toCamelCase(): String =
    split('_').mapIndexed { i, part ->
      if (i == 0) part else part.replaceFirstChar(Char::uppercase)
    }.joinToString("")

  private fun kotlinSources(): String = sourceDir.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }

  @Test
  fun `every image is labelled or explicitly decorative`() {
    val sources = kotlinSources()
    val offenders = mutableListOf<String>()

    layoutDir.walkTopDown().filter { it.extension == "xml" }.forEach { file ->
      val text = file.readText()
      Regex(
        """<(ImageView|ImageButton|com\.google\.android\.material\.imageview\.ShapeableImageView)\b([^>]*?)/>""",
        RegexOption.DOT_MATCHES_ALL,
      ).findAll(text).forEach { match ->
        val tag = match.groupValues[2]
        if ("android:contentDescription" in tag) return@forEach
        // The explicit opt-out for genuinely decorative imagery.
        if ("""android:importantForAccessibility="no"""" in tag) return@forEach

        val id = Regex("""android:id="@\+id/([^"]+)"""").find(tag)?.groupValues?.get(1)
        if (id == null) {
          // No id means nothing can label it later, so it must be decorative or described in XML.
          offenders += "${file.name}: an unnamed image has no contentDescription and is not marked decorative"
          return@forEach
        }
        // Accept a description assigned from Kotlin through ViewBinding.
        if ("${id.toCamelCase()}.contentDescription" in sources) return@forEach

        offenders += "${file.name}: $id has no contentDescription in XML or Kotlin"
      }
    }

    assertTrue(
      "images with no screen-reader label:\n" + offenders.joinToString("\n"),
      offenders.isEmpty(),
    )
  }
}
