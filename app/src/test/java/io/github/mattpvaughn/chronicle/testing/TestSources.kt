package io.github.mattpvaughn.chronicle.testing

import io.github.mattpvaughn.chronicle.data.model.SourceId

/**
 * Source ids for fixtures (cu-127, decision-21).
 *
 * Two of them, and the second one is the point. A scoping bug shows up only when rows from
 * *different* sources are present at once: a test where every book carries the same id passes
 * whether the scoping works or not.
 *
 * This is the cu-24 fixture trap in a new field — three fixtures carried `source = 1L` where
 * every real row carried `0L`, and nothing noticed until cu-80's source-scoped removal made them
 * fail. A fixture written to match the code proves only that the code matches itself.
 */
val TEST_SOURCE = SourceId.forPlexServer(TEST_SERVER_ID)

/** The `clientIdentifier` [TEST_SOURCE] is derived from, for stubbing `PlexPrefsRepo.server`. */
const val TEST_SERVER_ID = "test-server"

/** A second, unrelated backend installation. Use with [TEST_SOURCE] to prove rows stay apart. */
val OTHER_TEST_SOURCE = SourceId.forPlexServer("other-server")
