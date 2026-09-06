package io.github.mattpvaughn.chronicle.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The XML theme, expressed for Compose (cu-181).
 *
 * The migration is screen-by-screen, so for as long as it lasts a Compose screen sits beside XML
 * ones and **must not look different**. These values are the same literals as
 * `res/values/colors.xml`; they are duplicated rather than read through
 * `colorResource(R.color.colorPrimary)` for one reason: a `@Preview` and a Compose UI test render
 * without an Android theme, and resolving theme attributes there either fails or silently returns
 * a stock Material colour, which is exactly the "looks right in the test, wrong on the device"
 * failure this project keeps hitting.
 *
 * `ChronicleThemeTest` pins each value against the XML resource, so the duplication cannot drift.
 *
 * Dark-only, matching the app: `res/values/styles.xml` declares one theme with no `values-night`
 * variant, so there is no light scheme to express yet.
 */
object ChronicleColors {
  val Primary = Color(0xFF2D3043)
  val PrimaryDark = Color(0xFF191A2A)
  val Accent = Color(0xFF00B8D4)
  val TextPrimary = Color(0xD8FFFFFF)
  val TextSecondary = Color(0x9EE3D5EB)
  val TextError = Color(0xFFFF8A80)
}

private val ChronicleColorScheme =
  darkColorScheme(
    primary = ChronicleColors.Accent,
    onPrimary = Color.White,
    // `background` is the window behind a screen; `surface` is a card or sheet sitting on it.
    // The XML theme uses colorPrimary for both, so a Compose screen inherits the same flatness
    // rather than introducing an elevation contrast no other screen has.
    background = ChronicleColors.Primary,
    onBackground = ChronicleColors.TextPrimary,
    surface = ChronicleColors.Primary,
    onSurface = ChronicleColors.TextPrimary,
    onSurfaceVariant = ChronicleColors.TextSecondary,
    error = ChronicleColors.TextError,
  )

/**
 * Wraps [content] in the app's colours.
 *
 * Every `ComposeView` in the app must set this, and so must every `@Preview` and UI test — an
 * unwrapped composable renders in stock Material purple, which is obvious on a device but easy to
 * miss in a unit test that only asserts text.
 */
@Composable
fun ChronicleTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = ChronicleColorScheme, content = content)
}
