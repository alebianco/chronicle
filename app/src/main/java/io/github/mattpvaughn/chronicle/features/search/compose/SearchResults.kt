package io.github.mattpvaughn.chronicle.features.search.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.SearchField
import io.github.mattpvaughn.chronicle.features.search.SearchOverlayState
import io.github.mattpvaughn.chronicle.features.search.SearchRow

/**
 * Grouped search results (cu-25, migrated in cu-202).
 *
 * Replaces `GroupedSearchAdapter` — two view types, a `DiffUtil` and a `setServerConnected` setter
 * — which was instantiated separately by **three** screens (library, home, collections). The
 * flattening stays in `SearchRow.toRows()`, where it was already testable without inflating
 * anything; this only draws it.
 */
@Composable
fun SearchOverlay(
  state: SearchOverlayState,
  serverConnected: Boolean,
  coverUrl: (String) -> String,
  onBookClick: (Audiobook) -> Unit,
  modifier: Modifier = Modifier,
) {
  when (state) {
    // Nothing drawn at all, so the screen behind shows through — the View version achieved this
    // with `isVisible = false` on an overlay that stayed in the hierarchy.
    SearchOverlayState.Hidden -> Unit

    // Open but nothing typed: the overlay covers the screen behind so the user is not reading
    // stale content, and no claim is made about results.
    SearchOverlayState.AwaitingQuery ->
      Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
      ) {}

    SearchOverlayState.NoResults ->
      Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
      ) {
        // `no_books_found` is what the View version used here. It is a slightly odd fit for a
        // *search* — "No books found" rather than "nothing matched" — but the wording is a
        // product choice, so the migration keeps the existing string rather than inventing one.
        Text(
          text = stringResource(R.string.no_books_found),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(dimensionResource(R.dimen.screen_horizontal_padding)),
        )
      }

    is SearchOverlayState.Results ->
      SearchResults(
        rows = state.rows,
        serverConnected = serverConnected,
        coverUrl = coverUrl,
        onBookClick = onBookClick,
        modifier = modifier,
      )
  }
}

@Composable
fun SearchResults(
  rows: List<SearchRow>,
  serverConnected: Boolean,
  coverUrl: (String) -> String,
  onBookClick: (Audiobook) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Opaque on purpose: this list is drawn *over* the screen behind it, and the View version got
  // that from `android:background` plus `elevation` on the RecyclerView.
  Surface(
    modifier = modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
      rows.forEach { row ->
        when (row) {
          is SearchRow.Header ->
            item(key = "header-${row.field}") { GroupHeader(row) }

          is SearchRow.Book ->
            // Identity is the book *within its group*: the same book can legitimately appear
            // under two headings, and keying on the id alone would collapse them into one row —
            // which for a `LazyColumn` is a duplicate-key crash rather than a silent merge.
            item(key = "book-${row.field}-${row.book.id}") {
              BookRow(
                row = row,
                serverConnected = serverConnected,
                coverUrl = coverUrl,
                onClick = { onBookClick(row.book) },
              )
            }
        }
      }
    }
  }
}

@Composable
private fun GroupHeader(header: SearchRow.Header) {
  Text(
    text = stringResource(header.field.labelRes()),
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier =
      Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(
          horizontal = dimensionResource(R.dimen.screen_horizontal_padding),
          vertical = 8.dp,
        ),
  )
}

@Composable
private fun BookRow(
  row: SearchRow.Book,
  serverConnected: Boolean,
  coverUrl: (String) -> String,
  onClick: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(
          horizontal = dimensionResource(R.dimen.screen_horizontal_padding),
          vertical = 8.dp,
        ),
  ) {
    // Cover art is only fetched when the server can answer; offline this would be a request per
    // row that can only fail.
    AsyncImage(
      model = if (serverConnected) coverUrl(row.book.thumb) else null,
      contentDescription = row.book.title,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(56.dp),
    )
    Column(modifier = Modifier.padding(start = 12.dp)) {
      Text(
        text = row.book.title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = row.subtitle(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * What to show beneath the title.
 *
 * Under a narrator or series heading the author line would repeat what the heading already says,
 * so the *matched* value goes there instead — a book listed under "Narrators" showing only its
 * title gives no way to tell which of several narrators matched (cu-25).
 */
@Composable
private fun SearchRow.Book.subtitle(): String =
  when (field) {
    SearchField.Narrator -> stringResource(R.string.search_matched_narrator, matchedValue)
    SearchField.Series -> stringResource(R.string.search_matched_series, matchedValue)
    SearchField.Title, SearchField.Author -> book.author
  }

internal fun SearchField.labelRes(): Int =
  when (this) {
    SearchField.Title -> R.string.search_group_title
    SearchField.Author -> R.string.search_group_author
    SearchField.Narrator -> R.string.search_group_narrator
    SearchField.Series -> R.string.search_group_series
  }
