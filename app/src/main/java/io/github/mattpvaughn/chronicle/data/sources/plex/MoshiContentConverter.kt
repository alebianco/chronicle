package io.github.mattpvaughn.chronicle.data.sources.plex

import com.squareup.moshi.Moshi
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.serialization.ContentConverter
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readString
import java.nio.charset.Charset
import kotlin.reflect.javaType

/**
 * Ktor's JSON boundary, backed by **Moshi**.
 *
 * Ktor ships converters for kotlinx-serialization, Gson and Jackson — but not Moshi, and this
 * project's models are Moshi's: each carries `@JsonClass(generateAdapter = true)` and a KSP
 * processor generates a real adapter for it. Deliberately, too — the reflective
 * `KotlinJsonAdapterFactory` is left out so that a model which *loses* its annotation fails rather
 * than being silently handled reflectively.
 *
 * Writing this converter is what keeps the Ktor migration from also being a **serializer**
 * migration. Swapping Moshi for kotlinx-serialization would touch every Plex model and every
 * generated adapter at the same time as the HTTP layer changed, so a parsing regression and a
 * transport regression would be indistinguishable. That swap is a separate question the library
 * review already owns; this is roughly forty lines and defers it cleanly.
 *
 * Note it does keep the models JVM-bound, so the portability gain from this migration is the HTTP
 * layer's, not the models'. That is stated plainly in decision-24 rather than glossed.
 */
class MoshiContentConverter(
  private val moshi: Moshi,
) : ContentConverter {
  override suspend fun serialize(
    contentType: ContentType,
    charset: Charset,
    typeInfo: TypeInfo,
    value: Any?,
  ): OutgoingContent {
    val adapter = moshi.adapter<Any>(typeInfo.moshiType())
    return TextContent(adapter.toJson(value), contentType.withCharset(charset))
  }

  override suspend fun deserialize(
    charset: Charset,
    typeInfo: TypeInfo,
    content: ByteReadChannel,
  ): Any? {
    val text = content.readRemaining().readString()
    // Plex answers some endpoints with an empty body and a 200 — a scrobble, for instance. Moshi
    // would throw on that, and the caller wants null rather than an exception for a request that
    // succeeded.
    if (text.isBlank()) return null
    return moshi.adapter<Any>(typeInfo.moshiType()).fromJson(text)
  }
}

/**
 * The JVM type Moshi should build an adapter for.
 *
 * Ktor's `TypeInfo` carries a `KClass` plus an optional `KType`, and the `KType` is what preserves
 * generic arguments — Plex responses are full of them. Falling back to the raw class would erase
 * `List<Audiobook>` to `List`, so the fallback only applies where there is no `KType` at all.
 */
@OptIn(ExperimentalStdlibApi::class)
private fun TypeInfo.moshiType(): java.lang.reflect.Type = kotlinType?.javaType ?: type.java

private fun ContentType.withCharset(charset: Charset): ContentType =
  if (parameter("charset") != null) this else withParameter("charset", charset.name())
