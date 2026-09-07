package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.ChronicleJson
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json

/**
 * Registers the JSON converter for what Plex actually sends.
 *
 * Plex answers `Accept: application/json` with `application/json`, but some endpoints reply
 * `text/html` or no content type at all while still returning JSON. Retrofit's converter did not
 * care about content type; Ktor's `ContentNegotiation` does, so the types are named explicitly
 * rather than discovered by a 200 that fails to parse.
 *
 * This is **Ktor's own kotlinx converter** — the hand-written `MoshiContentConverter` it replaces
 * existed only because Ktor ships no Moshi converter, and it is deleted along with Moshi.
 * Sharing one function between the DI graph and the tests is the point: three test files stood up
 * their own converter registration, and a client configured differently from production is a test
 * that proves the wrong thing.
 */
fun HttpClientConfig<*>.installPlexJson() {
  install(ContentNegotiation) {
    json(ChronicleJson, ContentType.Application.Json)
    json(ChronicleJson, ContentType.Text.Html)
    json(ChronicleJson, ContentType.Text.Plain)
  }
}
