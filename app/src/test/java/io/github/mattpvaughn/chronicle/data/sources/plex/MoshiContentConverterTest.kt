package io.github.mattpvaughn.chronicle.data.sources.plex

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The JSON boundary that keeps the Ktor migration from also being a serializer migration.
 *
 * Ktor ships converters for kotlinx-serialization, Gson and Jackson — not Moshi — and this
 * project's models are Moshi's, each with a KSP-generated adapter. Writing this converter meant
 * the HTTP layer could change without every Plex model changing at the same time, so a parsing
 * regression and a transport regression would stay distinguishable.
 *
 * The cases below are the ones that actually bit, or would have:
 *
 * - **Generic types.** `TypeInfo` carries a `KClass` plus an optional `KType`, and only the `KType`
 *   preserves type arguments. Falling back to the raw class erases `List<Thing>` to `List`, and
 *   Plex responses are full of generics.
 * - **An empty body with a 200.** A scrobble answers that way. Moshi throws on empty input, and a
 *   request that *succeeded* must not surface as a parse failure.
 * - **A non-JSON content type carrying JSON.** Retrofit's converter ignored content type entirely;
 *   Ktor's `ContentNegotiation` dispatches on it, so a Plex endpoint replying `text/html` with a
 *   JSON body would go unparsed unless that type is registered.
 */
class MoshiContentConverterTest {
  @JsonClass(generateAdapter = false)
  data class Thing(val name: String, val size: Int)

  private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

  private fun client(
    body: String,
    contentType: ContentType = ContentType.Application.Json,
  ) = HttpClient(
    MockEngine {
      respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf("Content-Type", contentType.toString()),
      )
    },
  ) {
    install(ContentNegotiation) {
      val converter = MoshiContentConverter(moshi)
      register(ContentType.Application.Json, converter)
      register(ContentType.Text.Html, converter)
      register(ContentType.Text.Plain, converter)
    }
  }

  @Test
  fun `a simple object round-trips`() =
    runTest {
      val thing: Thing = client("""{"name":"Mistborn","size":3}""").get("http://localhost/x").body()

      assertEquals(Thing("Mistborn", 3), thing)
    }

  @Test
  fun `a generic list keeps its element type`() =
    runTest {
      // The `KType` path. With only the raw `KClass`, Moshi would be asked for an adapter for a
      // bare `List` and could not construct the elements.
      val things: List<Thing> =
        client("""[{"name":"A","size":1},{"name":"B","size":2}]""").get("http://localhost/x").body()

      assertEquals(listOf(Thing("A", 1), Thing("B", 2)), things)
    }

  @Test
  fun `an empty body deserializes to null rather than throwing`() =
    runTest {
      // Plex answers a scrobble with 200 and no body. Moshi throws on empty input, and a
      // successful request must not look like a parse failure.
      val thing: Thing? = client("").get("http://localhost/x").body()

      assertNull(thing)
    }

  @Test
  fun `a whitespace-only body is also treated as empty`() =
    runTest {
      val thing: Thing? = client("   \n  ").get("http://localhost/x").body()

      assertNull(thing)
    }

  @Test
  fun `JSON served as text html is still parsed`() =
    runTest {
      // Registered explicitly because `ContentNegotiation` dispatches on content type, unlike
      // Retrofit's converter which ignored it. An unregistered type is a silent non-parse.
      val thing: Thing =
        client("""{"name":"Elantris","size":1}""", ContentType.Text.Html)
          .get("http://localhost/x")
          .body()

      assertEquals(Thing("Elantris", 1), thing)
    }

  @Test
  fun `JSON served as text plain is still parsed`() =
    runTest {
      val thing: Thing =
        client("""{"name":"Warbreaker","size":1}""", ContentType.Text.Plain)
          .get("http://localhost/x")
          .body()

      assertEquals(Thing("Warbreaker", 1), thing)
    }
}
