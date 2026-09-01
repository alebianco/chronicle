package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack

/**
 * Whether the outgoing book's position must be written before [incomingBookId] replaces it.
 *
 * Extracted from `AudiobookMediaSessionCallback` so the decision is testable: that class takes
 * sixteen collaborators including an `ExoPlayer` and a `MediaSessionCompat`, while the judgement
 * needs only the outgoing track and the incoming book id.
 *
 * `AudiobookDetailsViewModel.updateProgressIfChangingBook` used to attempt this, and its condition
 * was inverted — it asked whether the playing track belonged to the book *being viewed*, which is
 * true exactly when the user is **not** switching books. It therefore emitted a spurious STOPPED
 * report for the book already playing and stayed silent for the case it existed to serve, so the
 * outgoing position was never sent (cu-91). That is one candidate cause of positions diverging
 * across devices: the device you left a book on never told the server where you stopped.
 *
 * @param outgoingTrack what is playing now, or [MediaItemTrack.EMPTY_TRACK] when nothing is.
 */
fun shouldFlushOutgoingBook(
  outgoingTrack: MediaItemTrack,
  incomingBookId: String,
): Boolean {
  // Nothing was playing, so there is no position to preserve.
  if (outgoingTrack.id == TRACK_NOT_FOUND) {
    return false
  }
  // A track with no parent cannot be attributed to a book; reporting it would be guesswork.
  if (outgoingTrack.parentKey.isEmpty()) {
    return false
  }
  // Same book: resuming, seeking, or restarting. The regular progress updates already own this,
  // and a STOPPED report here is what made an ordinary play/pause emit a stray one.
  return outgoingTrack.parentKey != incomingBookId
}
