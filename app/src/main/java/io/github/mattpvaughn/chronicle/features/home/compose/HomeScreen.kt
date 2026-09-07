package io.github.mattpvaughn.chronicle.features.home.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.ViewStyleKind
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.features.library.compose.BookCard

/**
 * Everything the home screen renders, as one value.
 *
 * The Fragment read four flows by `.value` inside one `refreshShelves()` and made **eight**
 * independent `isVisible` decisions from them. Grouped into a sealed [HomeContent] so "no emission
 * yet" is its own branch rather than three empty lists — with three shelves, a
 * `Loaded(empty, empty, empty)` seed would render "no books found" on every cold start, which is
 * the flash.
 */
data class HomeUiState(
  val content: HomeContent = HomeContent.Loading,
  val isRefreshing: Boolean = false,
  val serverConnected: Boolean = true,
)

sealed interface HomeContent {
  data object Loading : HomeContent

  data object Empty : HomeContent

  data object OfflineEmpty : HomeContent

  data class Loaded(
    val downloaded: List<Audiobook>,
    val recentlyListened: List<Audiobook>,
    val recentlyAdded: List<Audiobook>,
  ) : HomeContent
}

/**
 * The home screen.
 *
 * Each shelf takes its own `List<Audiobook>` rather than the whole state, so a progress tick that
 * changes one book in "recently listened" does not recompose the other two shelves — the Compose
 * equivalent of the `distinctUntilChangedBy { it.booksKey() }`, which stays in the ViewModel.
 */
@Composable
fun HomeScreen(
  state: HomeUiState,
  coverUrl: (String) -> String,
  onBookClick: (Audiobook) -> Unit,
  onResumeClick: (Audiobook) -> Unit,
  onDisableOfflineMode: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    when (val content = state.content) {
      HomeContent.Loading -> Unit
      HomeContent.Empty -> CenteredMessage(stringResource(R.string.no_books_found))
      HomeContent.OfflineEmpty ->
        CenteredMessage(
          text = stringResource(R.string.no_downloaded_books_found),
          action = stringResource(R.string.disable_offline_mode) to onDisableOfflineMode,
        )
      is HomeContent.Loaded ->
        LazyColumn(Modifier.fillMaxSize()) {
          shelf(
            titleRes = R.string.available_offline,
            books = content.downloaded,
            state = state,
            coverUrl = coverUrl,
            onClick = onBookClick,
          )
          // Continue Listening resumes on tap rather than opening details — the whole point,
          // and the only shelf with distinct behaviour.
          shelf(
            titleRes = R.string.recently_listened,
            books = content.recentlyListened,
            state = state,
            coverUrl = coverUrl,
            onClick = onResumeClick,
          )
          shelf(
            titleRes = R.string.recently_added,
            books = content.recentlyAdded,
            state = state,
            coverUrl = coverUrl,
            onClick = onBookClick,
          )
        }
    }
  }
}

/** A titled shelf, or nothing at all when it has no books. */
private fun androidx.compose.foundation.lazy.LazyListScope.shelf(
  @androidx.annotation.StringRes titleRes: Int,
  books: List<Audiobook>,
  state: HomeUiState,
  coverUrl: (String) -> String,
  onClick: (Audiobook) -> Unit,
) {
  if (books.isEmpty()) return
  item(key = "title-$titleRes") {
    Text(
      text = stringResource(titleRes).uppercase(),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
  }
  item(key = "shelf-$titleRes") {
    LazyRow(
      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(books, key = { it.id }) { book ->
        BookCard(
          book = book,
          style = ViewStyleKind.CoverGrid,
          serverConnected = state.serverConnected,
          coverUrl = coverUrl,
          onClick = { onClick(book) },
          modifier = Modifier.width(128.dp),
        )
      }
    }
  }
}

@Composable
private fun CenteredMessage(
  text: String,
  action: Pair<String, () -> Unit>? = null,
) {
  Column(
    Modifier.fillMaxSize().padding(24.dp).semantics { contentDescription = text },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (action != null) {
      Button(onClick = action.second, modifier = Modifier.padding(top = 16.dp)) {
        Text(action.first)
      }
    }
  }
}
