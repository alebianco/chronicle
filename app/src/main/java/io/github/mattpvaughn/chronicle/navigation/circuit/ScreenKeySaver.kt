package io.github.mattpvaughn.chronicle.navigation.circuit

import com.slack.circuit.runtime.screen.CircuitSaveable
import com.slack.circuit.runtime.screen.CircuitSaver
import io.github.mattpvaughn.chronicle.navigation.ChronicleScreen
import kotlinx.serialization.json.Json

/**
 * Saves and restores the Circuit back stack as JSON.
 *
 * ### Why a custom saver rather than `Parcelable` keys
 *
 * Circuit's default saver hands each screen key to Compose's `SaveableStateRegistry`, which only
 * accepts what can go in a `Bundle`. A key that is neither `Parcelable` nor a primitive throws
 * **on launch** — "The current SaveableStateRegistry cannot save class …ScreenKey" — so this is not
 * optional, and it is not the kind of thing a unit test catches: nothing off-device composes a real
 * back stack.
 *
 * The obvious fix is `@Parcelize` on every key. It is rejected here for two reasons:
 *
 * - It would put `android.os.Parcelable` into `navigation/Screens.kt`, which is on the
 *   **framework-free** list that `FrameworkFreeCoreTest` enforces — the same list `Destination.kt`
 *   was on, and for the same reason: routing is pure string/data work and should stay testable
 *   without an Android runtime.
 * - `kotlin-parcelize` was removed earlier after being measured as applying to nothing, and
 *   re-adding a plugin to satisfy a saver is a heavier answer than the problem needs.
 *
 * `kotlinx.serialization` is already this project's serializer (24 models, replacing Moshi), the
 * keys are ordinary data classes over strings and one enum, and a JSON string is something the
 * registry saves without complaint. So the keys stay pure Kotlin and the back stack still survives
 * process death — which is the behaviour Navigation Compose gave, and losing it would mean the app
 * came back on Home after being evicted in the background.
 */
object ScreenKeySaver : CircuitSaver() {
  /**
   * Lenient about unknown keys on the way back in.
   *
   * A saved back stack outlives an install: the user backgrounds the app, updates it, and returns
   * to a bundle written by the previous version. Refusing to decode a screen that has since been
   * renamed would crash on resume; [restore] returns null instead and Circuit drops that record.
   */
  private val json = Json { ignoreUnknownKeys = true }

  override fun canSave(value: CircuitSaveable): Boolean = value is ChronicleScreen

  override fun save(value: CircuitSaveable): Any? = encode(value)

  override fun canRestore(value: Any): Boolean = value is String

  override fun restore(value: Any): CircuitSaveable? = decode(value)

  /**
   * [save] and [restore] under names a test can reach.
   *
   * `CircuitSaver` declares both as `protected`, so an override is not callable from a test in
   * another package. The round trip is the whole behaviour here and leaving it unasserted would
   * mean discovering a restore bug the way the user would — by coming back to Home after the app
   * was evicted.
   */
  internal fun encode(value: CircuitSaveable): String? {
    val screen = value as? ChronicleScreen ?: return null
    return json.encodeToString(ChronicleScreen.serializer(), screen)
  }

  /** See [encode]. Returns null for anything this saver did not write. */
  internal fun decode(value: Any): ChronicleScreen? {
    val encoded = value as? String ?: return null
    return runCatching { json.decodeFromString(ChronicleScreen.serializer(), encoded) }.getOrNull()
  }
}
