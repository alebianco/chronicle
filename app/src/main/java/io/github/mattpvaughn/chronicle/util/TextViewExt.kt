package io.github.mattpvaughn.chronicle.util

import android.widget.TextView

/**
 * Sets [text] only if it differs from what the view already shows.
 *
 * `TextView.setText` does not compare: it re-lays-out and invalidates even when handed an equal
 * string. That is fine for a one-off, and expensive on a per-second path — the player's position,
 * percentage, chapter title and chapter duration are all rewritten on every `ProgressUpdater`
 * tick, and most ticks change none of them. A second of a 47-hour book leaves the percentage
 * identical; a chapter title is identical for many minutes at a time.
 *
 * Introduced for cu-117, where the measured cost of playback jank was re-rendering rather than
 * computation — the same finding cu-110 recorded one layer up. Two guards apply together: this
 * one, for values that repeat, and an `isShown` check at the call site, for views nobody can see.
 *
 * Compares with `==` on the rendered `CharSequence`, which is a plain `String` comparison for the
 * formatted strings this is used with. It deliberately does not try to be clever about spannables:
 * a styled `CharSequence` that is `equals` to the current text is genuinely a no-op write.
 */
fun TextView.setTextIfChanged(text: CharSequence) {
  if (this.text != text) {
    this.text = text
  }
}
