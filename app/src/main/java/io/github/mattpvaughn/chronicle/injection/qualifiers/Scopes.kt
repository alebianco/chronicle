package io.github.mattpvaughn.chronicle.injection.qualifiers

import javax.inject.Qualifier

/**
 * The app-wide [kotlinx.coroutines.CoroutineScope], living as long as the process.
 *
 * Qualified since cu-185. The hand-written graph had **separate components**, so an unqualified
 * `CoroutineScope` was unambiguous — the service component saw the service's, everything else saw
 * the application's. Hilt puts them in one hierarchy, where the same type bound twice is an error
 * rather than a silent choice. Naming which scope is wanted is the point: before, the answer
 * depended on which component happened to resolve the injection.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * The media service's [kotlinx.coroutines.CoroutineScope], cancelled when the service dies.
 *
 * Work started here must not outlive playback — see `MediaPlayerService.serviceJob`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlayerServiceScope
