package io.github.mattpvaughn.chronicle.features.collections.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Collection

/**
 * Everything the collections screen renders, as one value (cu-181).
 *
 * The Fragment reads six separate flows and pushes each into views independently, so three
 * `isVisible` assignments decide between "empty", "offline and empty" and "populated" — and nothing
 * stops two of them being true at once. That is cu-68's failure class, and `FirstFrameFlashTest`
 * exists to guard a symptom of it.
 *
 * One state means the screen cannot describe a contradiction: [content] is a sealed type, so
 * exactly one of the three renders and the `when` over it is exhaustive at compile time.
 */
data class CollectionsUiState(
  val content: CollectionsContent,
  val isRefreshing: Boolean = false,
  val serverConnected: Boolean = true,
  val isGrid: Boolean = true,
)

/**
 * What the body shows. Sealed rather than a set of booleans, so adding a state is a compile error
 * at every `when` rather than a screen that silently renders nothing.
 */
sealed interface CollectionsContent {
  /** The library has collections. */
  data class Loaded(val collections: List<Collection>) : CollectionsContent

  /** No collections, and the server is reachable — a genuinely empty library. */
  data object Empty : CollectionsContent

  /**
   * No collections *because* the app is offline. A distinct state, not a flavour of [Empty]:
   * telling a user their library is empty when it is merely unreachable is the shape of trust bug
   * this project treats as R0.
   */
  data object OfflineEmpty : CollectionsContent
}

/**
 * The collections screen.
 *
 * Stateless: it takes a [CollectionsUiState] and emits events. Nothing here reads a repository, a
 * `SharedPreferences` or the Dagger graph, which is what lets the whole screen be tested with
 * `createComposeRule()` and no Robolectric, no `FragmentScenario` and no mocked component.
 */
@Composable
fun CollectionsScreen(
  state: CollectionsUiState,
  coverUrl: (String) -> String,
  onCollectionClick: (Collection) -> Unit,
  onDisableOfflineMode: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier.fillMaxSize()) {
    when (val content = state.content) {
      is CollectionsContent.Loaded ->
        CollectionsGrid(
          collections = content.collections,
          isGrid = state.isGrid,
          serverConnected = state.serverConnected,
          coverUrl = coverUrl,
          onCollectionClick = onCollectionClick,
        )

      CollectionsContent.Empty ->
        CenteredMessage(text = stringResource(R.string.no_books_found))

      CollectionsContent.OfflineEmpty ->
        CenteredMessage(
          text = stringResource(R.string.no_downloaded_books_found),
          action = stringResource(R.string.disable_offline_mode) to onDisableOfflineMode,
        )
    }
  }
}

@Composable
private fun CollectionsGrid(
  collections: List<Collection>,
  isGrid: Boolean,
  serverConnected: Boolean,
  coverUrl: (String) -> String,
  onCollectionClick: (Collection) -> Unit,
) {
  LazyVerticalGrid(
    // The XML screen swaps GridLayoutManager(3) for a LinearLayoutManager; here the column count
    // is just a number, so there is no layout manager to rebuild on a preference change.
    columns = GridCells.Fixed(if (isGrid) 3 else 1),
    contentPadding = PaddingValues(8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    // `key` is the identity the diff runs on. The Fragment reaches the same conclusion by hand —
    // `isDifferentListById`, then `submitList(null) { submitList(real) }` to force scroll-to-top —
    // because a full equals() comparison re-scrolls once a second while a book is playing (cu-110).
    // Here it is one parameter, and stable ids also preserve scroll position for free.
    items(collections, key = { it.id }) { collection ->
      CollectionCard(
        collection = collection,
        serverConnected = serverConnected,
        coverUrl = coverUrl,
        onClick = { onCollectionClick(collection) },
      )
    }
  }
}

@Composable
private fun CollectionCard(
  collection: Collection,
  serverConnected: Boolean,
  coverUrl: (String) -> String,
  onClick: () -> Unit,
) {
  Column(
    Modifier
      .fillMaxWidth()
      .clickable(onClickLabel = collection.title, onClick = onClick)
      .padding(4.dp),
  ) {
    AsyncImage(
      // Cover art is only fetched when the server is reachable — offline, the model is null and
      // Coil renders nothing rather than retrying against an unreachable host.
      model = if (serverConnected) coverUrl(collection.thumb) else null,
      // Null on purpose: the title below is the accessible label, and describing the cover too
      // would make TalkBack read every item twice (cu-47).
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier =
        Modifier
          .fillMaxWidth()
          .aspectRatio(1f)
          .clip(RoundedCornerShape(4.dp)),
    )
    Text(
      text = collection.title,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onBackground,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}

@Composable
private fun CenteredMessage(
  text: String,
  action: Pair<String, () -> Unit>? = null,
) {
  Column(
    Modifier
      .fillMaxSize()
      .padding(24.dp)
      .semantics { contentDescription = text },
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
