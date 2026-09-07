package io.github.mattpvaughn.chronicle.testing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking

/**
 * A real Ktor [ResponseException] for a given status code.
 *
 * Six test files used to build `HttpException(Response.error(code, ...))`, which was a one-liner
 * because Retrofit's `Response.error` is a public factory. Ktor has no equivalent: a
 * [ResponseException] wraps an `HttpResponse`, and an `HttpResponse` cannot be constructed outside
 * a client call — it holds a live call context. So the honest way to get one is to *make* a call
 * that fails, against an engine that answers with the status wanted.
 *
 * That is heavier than a factory, and it is also more faithful: the exception these tests hand to
 * production code is now exactly the shape a real failure produces, including Ktor's own split
 * between [ClientRequestException] for 4xx and [ServerResponseException] for 5xx — a distinction
 * Retrofit did not make and which production code branches on.
 *
 * `runBlocking` is fine here: the mock engine answers without touching the network, and these are
 * fixtures rather than the thing under test.
 */
fun responseException(code: Int): ResponseException {
  // `expectSuccess = true` explicitly. Measured rather than assumed: in Ktor 3 the default is
  // **false**, so a client left at the default returns the error response instead of throwing and
  // this helper produced nothing — which is what the first version of it got wrong.
  val client =
    HttpClient(MockEngine { respondError(HttpStatusCode.fromValue(code)) }) {
      expectSuccess = true
    }
  return runBlocking {
    runCatching { client.get("http://localhost/fixture") }
      .exceptionOrNull() as? ResponseException
      ?: error(
        "expected a ResponseException for HTTP $code, which requires `expectSuccess = true` on " +
          "the fixture client — Ktor 3 defaults it to false and returns the response instead.",
      )
  }
}
