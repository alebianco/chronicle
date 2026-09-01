package io.github.mattpvaughn.chronicle.features.player

/**
 * Whether `playBook` may retry after finding no local tracks (cu-97).
 *
 * Extracted from `AudiobookMediaSessionCallback` for the reason `OutgoingBookFlush` was: the class
 * takes sixteen collaborators including an `ExoPlayer` and a `MediaSessionCompat`, while this
 * decision needs only how many times the book has already been tried.
 *
 * The path it guards: `playBook` finds no tracks in the local DB, delegates to
 * `handlePlayBookWithNoTracks`, which fetches from the network and — when the fetch reports `isOk`
 * — calls `playBook` again. Nothing bounded that. A fetch that *succeeds* while yielding no tracks
 * therefore recursed forever, issuing one network request per pass: a book deleted server-side
 * between sync and play, a library permission change, or a metadata-only album all produce exactly
 * that shape.
 *
 * The project already holds this line elsewhere — `PlexTokenAuthenticator` retries **once** and its
 * tests mostly assert that it gives up, because looping would hammer plex.tv. This is the same rule
 * on the playback path.
 *
 * One attempt is the right budget rather than two or three: the fetch either populates the tracks
 * or the book genuinely has none, and a second identical request cannot change that answer.
 */
const val MAX_TRACK_FETCH_ATTEMPTS = 1

/**
 * @param attemptsAlreadyMade how many times a track fetch has been tried for this book in this
 *   play request. Zero on the first entry into `playBook`.
 */
fun mayFetchTracksAgain(attemptsAlreadyMade: Int): Boolean = attemptsAlreadyMade < MAX_TRACK_FETCH_ATTEMPTS
