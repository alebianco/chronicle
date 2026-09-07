package io.github.mattpvaughn.chronicle.features.collections.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.views.compose.CoverImage

/**
 * Everything the collections screen renders, as one value.
 *
 * The Fragment reads six separate flows and pushes each into views independently, so three
 * `isVisible` assignments decide between "empty", "offline and empty" and "populated" — and nothing
 * stops two of them being true at once. That is the failure class, and `FirstFrameFlashTest`
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
  /**
   * Nothing has been read yet — the `stateIn` seed.
   *
   * A distinct state rather than `Loaded(emptyList())`: that seed renders an empty grid
   * before the first Room emission, and worse, it is indistinguishable from a genuinely empty
   * library, so a test asserting "empty" passes against a flow that has produced nothing. That is
   * the vacuous-pass shape CLAUDE.md warns about for `WhileSubscribed` flows.
   */
  data object Loading : CollectionsContent

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
  // `Surface`, not a bare `Box`: `MaterialTheme` *defines* `colorScheme.background` but nothing
  // paints it -- that is `Surface`'s (or `Scaffold`'s) job. Without this the window's own theme
  // colour shows through, and on this app's dark window that renders as #121212 with the
  // onBackground text nearly invisible on it. Caught on a device; every unit test still passed,
  // because the semantics tree is correct and only the *pixels* were wrong.
  Surface(
    modifier = modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    when (val content = state.content) {
      // Deliberately blank: the screen is behind a `SwipeRefreshLayout` that shows its own
      // spinner, and a second one here would double up on every refresh.
      CollectionsContent.Loading -> Unit

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
    // `Adaptive`, not `Fixed(3)`: a fixed count divides the *available* width, so on this 1920px
    // tablet each cell was 640px wide and one square cover filled the screen -- a bug only a
    // device shows, since a Compose test measures whatever width it is told to. Adaptive asks for
    // a minimum cell size and picks the count itself, which is also what makes the same screen
    // right on a phone, a tablet and in both orientations (Android's guidance for adaptive
    // layouts). The list style stays a single column.
    columns = if (isGrid) GridCells.Adaptive(minSize = 180.dp) else GridCells.Fixed(1),
    contentPadding = PaddingValues(8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    // `key` is the identity the diff runs on. The Fragment reaches the same conclusion by hand —
    // `isDifferentListById`, then `submitList(null) { submitList(real) }` to force scroll-to-top —
    // because a full equals() comparison re-scrolls once a second while a book is playing.
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
    CoverImage(
      thumb = collection.thumb,
      serverConnected = serverConnected,
      coverUrl = coverUrl,
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
