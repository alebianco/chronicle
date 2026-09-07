package io.github.mattpvaughn.chronicle.features.settings.licenses

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Reads the dependency catalogue the AboutLibraries Gradle plugin generates.
 *
 * ### The content is generated, never written here
 *
 * The plugin resolves the variant's **runtime classpath** at build time and writes
 * `res/raw/aboutlibraries.json` into the variant's generated resources. That is the point of the
 * arrangement: a hand-maintained licences page drifts silently the moment a dependency is added,
 * and a page that quietly misses a dependency is worse than no page, because it looks like
 * diligence. Nothing in this file knows the name of a single library.
 *
 * `LicenseCatalogCountTest` reconciles what the screen renders against that generated file, so an
 * entry lost between the graph and the screen fails the build.
 *
 * ### This class is only the two Android-shaped lines
 *
 * Opening a raw resource needs a `Context`; everything after that is [LicenseCatalogParser], which
 * is framework-free and carries the fallbacks worth testing. Keeping the split means the part that
 * can be *wrong* has a plain JVM test, and this half has nothing left to get wrong but the id.
 *
 * `R.raw.aboutlibraries` is referenced by generated id rather than looked up by name with
 * `getIdentifier`, which is what the library's own loader does: a name lookup returns 0 for a
 * missing resource and turns a build misconfiguration into a silent empty screen, whereas a missing
 * generated id does not compile.
 */
class GeneratedLicenseCatalogSource
  @Inject
  constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
  ) : LicenseCatalogSource {
    override suspend fun load(): LicenseCatalog? =
      withContext(dispatchers.io) {
        // Broad on purpose, and logged rather than swallowed. The failure here is a missing or
        // unreadable resource — `Resources.NotFoundException`, or an IO failure on the stream — and
        // returning null renders an honest error instead of an empty list that looks like a
        // complete one.
        val json =
          runCatching {
            context.resources.openRawResource(R.raw.aboutlibraries).use { it.readBytes().decodeToString() }
          }.onFailure { error ->
            Timber.e(error, "Could not open the generated third-party licence catalogue")
          }.getOrNull()

        if (json == null) {
          null
        } else {
          // A second, distinct failure: the file was there and did not parse, which means the
          // plugin's schema moved. Logged separately so the two are told apart in a bug report.
          LicenseCatalogParser.parse(json)
            .also { if (it == null) Timber.e("Generated licence catalogue is present but unparseable") }
        }
      }
  }
