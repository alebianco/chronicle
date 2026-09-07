package io.github.mattpvaughn.chronicle.features.settings.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.features.settings.licenses.LicenseCatalog
import io.github.mattpvaughn.chronicle.features.settings.licenses.LicenseSummary
import io.github.mattpvaughn.chronicle.features.settings.licenses.LicensedLibrary
import io.github.mattpvaughn.chronicle.features.settings.licenses.LicensesUiState
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme

/**
 * The third-party licences list.
 *
 * A pure function of [LicensesUiState] — the catalogue behind it is generated from the resolved
 * dependency graph at build time, so nothing here decides *what* is listed, only how it reads.
 *
 * ### Why the count is rendered
 *
 * The number is on the screen rather than implied by the list's length, because it is the one
 * assertion a reader can check and a test can pin. `LicenseCatalogCountTest` reconciles it against
 * the generated `aboutlibraries.json`, so a dependency lost between the graph and this list fails
 * the build instead of quietly shortening the page.
 *
 * ### Why the licence is a link, not embedded text
 *
 * Embedding full licence text would mean fetching it from the GitHub API at build time, which is
 * rate-limited without a token and would make the build depend on the network. Each entry carries
 * its licence name and its canonical SPDX URL instead. An entry whose metadata has no URL renders
 * as plain text — a link that goes nowhere is worse than an honest absence.
 *
 * Rendered as our own Compose rather than with `aboutlibraries-compose-m3`: that module depends on
 * Compose Multiplatform 1.12.0 and material3 1.9.0, a second Compose stack beside the BOM this
 * project pins deliberately.
 */
@Composable
fun LicensesScreen(
  state: LicensesUiState,
  onLicenseClick: (LicenseSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  // `MaterialTheme` defines `colorScheme.background` but paints nothing — that is `Surface`'s job.
  Surface(modifier = modifier.fillMaxSize()) {
    val padding = dimensionResource(R.dimen.screen_horizontal_padding)

    when (state) {
      LicensesUiState.Loading ->
        Column(
          modifier = Modifier.fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Spacer(Modifier.height(48.dp))
          CircularProgressIndicator()
        }

      LicensesUiState.Failed ->
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = padding)) {
          Spacer(Modifier.height(24.dp))
          Text(
            text = stringResource(R.string.licenses_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }

      is LicensesUiState.Loaded ->
        LicenseList(
          catalog = state.catalog,
          onLicenseClick = onLicenseClick,
          horizontalPadding = padding,
        )
    }
  }
}

@Composable
private fun LicenseList(
  catalog: LicenseCatalog,
  onLicenseClick: (LicenseSummary) -> Unit,
  horizontalPadding: androidx.compose.ui.unit.Dp,
) {
  LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding)) {
    item(key = "intro") {
      Column {
        Spacer(Modifier.height(16.dp))
        Text(
          text = stringResource(R.string.licenses_intro),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          text = stringResource(R.string.licenses_own_license_note),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
          text = pluralStringResource(R.plurals.licenses_count, catalog.total, catalog.total),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
      }
    }

    // Keyed on the coordinate, never the display name: several groups publish a library called
    // "Core", and a duplicate key throws in a LazyColumn.
    items(catalog.libraries, key = { it.uniqueId }) { library ->
      LicenseRow(library = library, onLicenseClick = onLicenseClick)
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }

    item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
  }
}

@Composable
private fun LicenseRow(
  library: LicensedLibrary,
  onLicenseClick: (LicenseSummary) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
    Text(
      text = library.name,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      // The coordinate, so a reader can tell two libraries with the same display name apart, and
      // the version, because a licence can differ between versions of the same artifact.
      text = if (library.version.isBlank()) library.uniqueId else "${library.uniqueId}:${library.version}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))

    if (library.licenses.isEmpty()) {
      // Kept and marked rather than dropped: a missing licence is a finding, and hiding the row
      // would make the page shorter and less true at the same time.
      Text(
        text = stringResource(R.string.licenses_no_license),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
      )
    } else {
      library.licenses.forEach { license ->
        LicenseLabel(libraryName = library.name, license = license, onClick = onLicenseClick)
      }
    }
  }
}

@Composable
private fun LicenseLabel(
  libraryName: String,
  license: LicenseSummary,
  onClick: (LicenseSummary) -> Unit,
) {
  val hasLink = license.url != null
  val description = stringResource(R.string.licenses_open_link, libraryName)

  Text(
    text = license.name,
    style = MaterialTheme.typography.bodyMedium,
    // Underlined only when there is somewhere to go. Styling every licence as a link and having
    // half of them do nothing on tap is the dead-link problem in a different costume.
    textDecoration = if (hasLink) TextDecoration.Underline else null,
    color = if (hasLink) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    modifier =
      Modifier
        .then(
          if (hasLink) {
            Modifier
              .clickable { onClick(license) }
              .semantics { contentDescription = description }
          } else {
            Modifier
          },
        ).padding(vertical = 2.dp),
  )
}

@Preview
@Composable
private fun LicensesScreenPreview() {
  ChronicleTheme {
    LicensesScreen(
      state =
        LicensesUiState.Loaded(
          LicenseCatalog.from(
            listOf(
              LicensedLibrary(
                uniqueId = "androidx.compose.ui:ui",
                name = "Compose UI",
                version = "1.9.4",
                licenses = listOf(LicenseSummary("Apache License 2.0", "https://spdx.org/licenses/Apache-2.0.html")),
              ),
              LicensedLibrary(
                uniqueId = "io.ktor:ktor-client-core",
                name = "Ktor client core",
                version = "3.2.1",
                licenses = listOf(LicenseSummary("Apache License 2.0", "https://spdx.org/licenses/Apache-2.0.html")),
              ),
              LicensedLibrary(
                uniqueId = "com.example:unlicensed",
                name = "An artifact with no licence in its POM",
                version = "1.0.0",
                licenses = emptyList(),
              ),
            ),
          ),
        ),
      onLicenseClick = {},
    )
  }
}
