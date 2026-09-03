package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Imported values must be validated, not just their keys (cu-133).
 *
 * The cu-17 allowlist gates **keys**. `SettingType.STRING` then accepted any string, and
 * `applyParsed` wrote it straight to the editor with `putString` — bypassing the typed setters in
 * `SharedPreferencesPrefsRepo`, three of which reject unknown values by throwing.
 *
 * The consequence was not a failed import but a **crash loop**: hand-edit an export to
 * `"key_library_view_style": "x"`, import it, and `AudiobookAdapter` throws
 * `IllegalStateException("Unknown view style")` from a *property initializer* as soon as the
 * library renders. The bad value is in prefs, so it crashes on every launch, and the settings
 * screen needed to undo it may be unreachable.
 *
 * Not a privilege boundary — it needs local file access. But it defeats the file-over-app promise
 * (D13, decision-8) that these exports are hand-editable, which is the point of an open format.
 */
class ImportValueValidationTest {
  @Test
  fun `a valid view style is accepted`() {
    val parsed =
      parseSettingOrNull(PrefsRepo.KEY_LIBRARY_VIEW_STYLE, PrefsRepo.VIEW_STYLE_TEXT_LIST)

    assertEquals(ParsedSetting.StringSetting(PrefsRepo.VIEW_STYLE_TEXT_LIST), parsed)
  }

  /** The exact payload from the finding. */
  @Test
  fun `an unknown view style is refused`() {
    assertNull(parseSettingOrNull(PrefsRepo.KEY_LIBRARY_VIEW_STYLE, "x"))
  }

  @Test
  fun `an unknown sort key is refused`() {
    assertNull(parseSettingOrNull(PrefsRepo.KEY_BOOK_SORT_BY, "not_a_sort_key"))
  }

  @Test
  fun `every declared sort key is accepted`() {
    Audiobook.SORT_KEYS.forEach { key ->
      assertNotNull(
        "$key is offered by the app, so an export containing it must import",
        parseSettingOrNull(PrefsRepo.KEY_BOOK_SORT_BY, key),
      )
    }
  }

  @Test
  fun `an unknown media type is refused`() {
    assertNull(parseSettingOrNull(PrefsRepo.KEY_LIBRARY_MEDIA_TYPE, "sideways"))
  }

  @Test
  fun `every declared media type is accepted`() {
    PrefsRepo.LIBRARY_MEDIA_TYPES.forEach { type ->
      assertNotNull(type, parseSettingOrNull(PrefsRepo.KEY_LIBRARY_MEDIA_TYPE, type))
    }
  }

  @Test
  fun `an unknown book cover style is refused`() {
    assertNull(parseSettingOrNull(PrefsRepo.KEY_BOOK_COVER_STYLE, "Rectangle"))
  }

  /**
   * `"Rectangle"` above is not a hypothetical: it is what the settings screen itself stored before
   * cu-101, so a legacy export can genuinely contain it. Refusing it is right — the value matches
   * neither consumer constant — and the user keeps their current style rather than a broken one.
   */
  @Test
  fun `both real book cover styles are accepted`() {
    assertNotNull(
      parseSettingOrNull(PrefsRepo.KEY_BOOK_COVER_STYLE, PrefsRepo.BOOK_COVER_STYLE_SQUARE),
    )
    assertNotNull(
      parseSettingOrNull(PrefsRepo.KEY_BOOK_COVER_STYLE, PrefsRepo.BOOK_COVER_STYLE_RECT),
    )
  }

  /** An empty string is a value like any other, and it is not in any allowed set. */
  @Test
  fun `an empty string is refused for a constrained key`() {
    assertNull(parseSettingOrNull(PrefsRepo.KEY_LIBRARY_VIEW_STYLE, ""))
  }

  /**
   * Guards the guard: every constrained key must appear in the allowed-values map, or this whole
   * test class passes while validating nothing. Adding a fifth constrained STRING key without a
   * value set must fail here rather than silently reopen the hole.
   */
  @Test
  fun `every constrained string key declares its allowed values`() {
    val constrained =
      listOf(
        PrefsRepo.KEY_LIBRARY_VIEW_STYLE,
        PrefsRepo.KEY_LIBRARY_MEDIA_TYPE,
        PrefsRepo.KEY_BOOK_SORT_BY,
        PrefsRepo.KEY_BOOK_COVER_STYLE,
      )

    constrained.forEach { key ->
      val allowed = BACKUP_SETTING_VALUES[key]
      assertTrue("$key must declare its allowed values", !allowed.isNullOrEmpty())
    }
  }

  /**
   * An unconstrained STRING key, if one is ever added, must still import — the validation is a
   * per-key allowlist, not a blanket refusal of strings.
   */
  @Test
  fun `a string key with no declared values still imports`() {
    val unconstrained =
      BACKUP_SETTING_TYPES.entries
        .filter { it.value == SettingType.STRING }
        .map { it.key }
        .firstOrNull { it !in BACKUP_SETTING_VALUES }

    if (unconstrained != null) {
      assertNotNull(parseSettingOrNull(unconstrained, "anything at all"))
    }
  }
}
