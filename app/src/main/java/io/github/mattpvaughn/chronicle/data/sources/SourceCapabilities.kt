package io.github.mattpvaughn.chronicle.data.sources

/**
 * What a [MediaSource] can actually answer (D11).
 *
 * The three flags already existed on [MediaSource] itself, but nothing read them: every path
 * assumed Plex's answers to all three. Carrying them as a value lets the ingestion path be given a
 * source's capabilities without being given the source, which is what keeps
 * `IBookRepository.ingest` free of any backend type.
 *
 * The defaults are **false**, not true. A source that forgets to declare a capability gets the
 * conservative behaviour — no tag seeding, no server progress adopted — which degrades to "less
 * metadata" rather than to "wrong metadata" or, worse, to overwritten listening position.
 */
data class SourceCapabilities(
  /** Whether the source can supply a narrator (Plex: `Style` tags, the Audnexus convention). */
  val hasNarrator: Boolean = false,
  /** Whether the source can supply a series (Plex: `Mood` tags). */
  val hasSeries: Boolean = false,
  /**
   * Whether the source stores listening position server-side.
   *
   * `false` does **not** mean progress is lost — it means the local value is the only one, and a
   * network copy must never overwrite it. That is already `Audiobook.merge`'s unconditional rule
   * (decision-16): position is owned by the tracks and `merge` never adopts
   * `network.progress`. This flag makes that a property of the source rather than a constant, so a
   * backend that genuinely has server-side progress can opt in later without the rule being
   * rewritten from memory.
   */
  val hasServerProgress: Boolean = false,
) {
  companion object {
    /** Plex answers all three (narrator and series are detail-only, but they exist). */
    val PLEX = SourceCapabilities(hasNarrator = true, hasSeries = true, hasServerProgress = true)

    /** A source that supplies only what is in the file — the local/WebDAV shape. */
    val FILES_ONLY = SourceCapabilities()
  }
}

/** The capabilities this source declares, as a value. */
fun MediaSource.capabilities(): SourceCapabilities =
  SourceCapabilities(
    hasNarrator = hasNarrator,
    hasSeries = hasSeries,
    hasServerProgress = hasServerProgress,
  )
