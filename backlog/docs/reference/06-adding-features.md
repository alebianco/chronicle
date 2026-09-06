---
id: 06-adding-features
title: Adding New Features
type: reference
created_date: '2026-09-01'
---

# Adding New Features

This guide walks you through implementing new features in Chronicle following the established patterns.

## General Process

1. **Plan**: Understand what you're building
2. **Design**: Decide which components need changes
3. **Data Layer**: Add/modify data models, repository methods
4. **ViewModel**: Add business logic and UI state
5. **View**: Create or modify UI components
6. **Wire Up**: Connect everything with dependency injection
7. **Test**: Verify functionality
8. **Polish**: Handle edge cases, errors, loading states

## Worked example: a per-book "favorite" flag

`Audiobook.favorited` already exists, so this is a *shape* to copy, not work to redo. Every snippet
below compiles against the current codebase.

### 1. Data layer

**Entity** (`data/model/Audiobook.kt`). A flag the server knows nothing about is a **local-only
column**, and that has a specific hazard: a library refresh merges a network copy without loading
tracks, and the field is always the default on that copy. So it must be named in **both** arms of
`Audiobook.merge` or every refresh wipes it (cu-20). `progress` and `playbackSpeed` document the
same rule.

```kotlin
@Entity
data class Audiobook(
    @PrimaryKey val id: String,   // String, not Int (cu-71)
    // ...
    val favorited: Boolean = false,
)
```

**Database.** Bump the version and write the migration in the same change. `exportSchema` stays
**`true`** — the exported JSON is the authority a later migration's column list is written from,
and `RoomSchemaTest` checks each file's name against the version inside it.

```kotlin
@Database(entities = [Audiobook::class], version = 15, exportSchema = true)
abstract class BookDatabase : RoomDatabase() { /* ... */ }

val BOOK_MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Audiobook ADD COLUMN favorited INTEGER NOT NULL DEFAULT 0")
    }
}
```

Then add a case to `RoomMigrationTest`. **A migration is only tested if a *file* is opened through
Room** — an in-memory database is created fresh at the current version and never migrated, so
`RoomSchemaTest` is the load-bearing check. Verify it by deliberate sabotage; a check that cannot
fail proves nothing.

**DAO.** Bind the id as `String`.

```kotlin
@Query("UPDATE Audiobook SET favorited = :favorited WHERE id = :bookId")
suspend fun updateFavorited(bookId: String, favorited: Boolean)
```

A read returning *rows* must also filter by `source`, or `ScopedQueryTest` fails the build
(cu-127). A query keyed on the primary key, as above, is exempt.

**Repository.** Take the dispatcher as an injected `DispatcherProvider` — never `Dispatchers.IO`
directly, which `RepositoryDispatcherTest` fails the build on.

```kotlin
override suspend fun setFavorited(bookId: String, favorited: Boolean) =
    withContext(dispatchers.io) { bookDao.updateFavorited(bookId, favorited) }
```

### 2. ViewModel

Private `MutableStateFlow`, public immutable `StateFlow`. Never `LiveData`, never `postValue`
(banned by `PostValueUsageTest` — it defers to the next main-loop pass, so a read-after-write sees
a stale value).

```kotlin
private val _favorites = MutableStateFlow<List<Audiobook>>(emptyList())
val favorites: StateFlow<List<Audiobook>> = _favorites.asStateFlow()

fun toggleFavorite(book: Audiobook) = viewModelScope.launch {
    bookRepository.setFavorited(book.id, !book.favorited)
}
```

Take every dependency as a **constructor parameter**. Never call `Injector.get()` — a class that
fetches its own dependencies cannot be constructed in a unit test at all, and
`ServiceLocatorUsageTest` fails the build on a new call.

When combining flows use `combineDistinct` (`util/FlowCombinators.kt`), not a bare `combine`. If a
click handler reads `.value` without collecting, the flow needs `stateIn(..., Eagerly)` — under
`WhileSubscribed` it reads the seed.

### 3. UI

**New UI is written in Compose** ([[decision-22]]). Wrap the screen in `ChronicleTheme` *and* a
`Surface` — `MaterialTheme` defines `colorScheme.background` but paints nothing, so a bare `Box`
renders near-invisible text while every unit test passes.

```kotlin
@Composable
fun FavoritesScreen(viewModel: HomeViewModel) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    ChronicleTheme {
        Surface {
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 160.dp)) {
                items(favorites, key = { it.id }) { book -> BookCard(book) }
            }
        }
    }
}
```

Use `GridCells.Adaptive`, not `Fixed(n)` — `Fixed(3)` gives 640px cells on the 1200px tablet and
one cover fills the screen.

**On a not-yet-migrated screen** you are in ViewBinding. There is no DataBinding: layouts have no
`<layout>` wrapper and no `@{...}` expressions, and `binding.viewModel` / `binding.lifecycleOwner`
do not exist. Set state from Kotlin and collect with `collectWhileStarted` on
`viewLifecycleOwner`:

```kotlin
val binding = FragmentHomeBinding.inflate(inflater, container, false)
collectWhileStarted(viewModel.favorites) { books ->
    binding.favoritesSection.isVisible = books.isNotEmpty()
}
```

**A view whose visibility is Kotlin-driven needs an XML default**, or it flashes its default for a
frame — and `FirstFrameFlashTest` fails the build without one. A view defaulted to `gone` with no
writer is *permanently* invisible, which is the worse half of that guard.

### 4. Strings and tests

User-facing text goes in `res/values/strings.xml`, always.

Add tests for the repository and the ViewModel — that is the definition of done (D6/D10), not an
extra. Testing a `StateFlow` needs **a subscriber *and* a drained dispatcher**: a
`WhileSubscribed` flow computes only while collected and `MainDispatcherRule` queues rather than
runs, so `.value` read without both is the `stateIn` seed. Use `keepCollected` / `settledValue`
from `util/FlowTestExt.kt`.

Mock a collaborator you only call; **fake** a collaborator that calls you back — a
`relaxed` mock's silence is indistinguishable from correct behaviour for anything callback- or
flow-shaped.

## Common Patterns for Different Features

### Adding a New Screen

New screens are **Compose** ([[decision-22]]). Navigation Compose is the target, so **do not adopt
the Fragment Navigation Component** — it would be migrated twice.

1. **Create Feature Package**: `features/newfeature/`
2. **Create ViewModel**: `NewFeatureViewModel.kt` — constructor injection, no `Injector.get()`
3. **Create the screen**: `features/newfeature/compose/NewFeatureScreen.kt`
4. **Host it**: a Fragment holding a `ComposeView`, while the app still has XML screens
5. **Update `Navigator.kt`**: navigation goes through it, data via Bundles/args
6. **Update Dagger**: inject into `ActivityComponent` if needed

```kotlin
class NewFeatureFragment : Fragment() {
    @Inject lateinit var viewModelFactory: NewFeatureViewModel.Factory
    private lateinit var viewModel: NewFeatureViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        (requireActivity() as MainActivity).activityComponent!!.inject(this)
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this, viewModelFactory)[NewFeatureViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setContent { ChronicleTheme { Surface { NewFeatureScreen(viewModel) } } }
    }
}

class NewFeatureViewModel(
    private val repository: SomeRepository,
) : ViewModel() {

    @Suppress("UNCHECKED_CAST")
    class Factory @Inject constructor(
        private val repository: SomeRepository,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NewFeatureViewModel(repository) as T
    }
}
```

### Adding a Setting

**There is no settings preference XML.** `res/xml/` holds only the Auto, backup and network-security
configs; the settings screen is built in Kotlin from `features/settings/SettingsList.kt`.

1. **Add to `PrefsRepo`** — an *interface* in `data/local/SharedPreferencesPrefsRepo.kt`, with
   `SharedPreferencesPrefsRepo` as the implementation:

```kotlin
interface PrefsRepo {
    var newSetting: Boolean

    companion object {
        const val PREF_NEW_SETTING = "new_setting"
        const val DEFAULT_NEW_SETTING = false
    }
}

// in SharedPreferencesPrefsRepo
override var newSetting: Boolean
    get() = sharedPreferences.getBoolean(PREF_NEW_SETTING, DEFAULT_NEW_SETTING)
    set(value) = sharedPreferences.edit { putBoolean(PREF_NEW_SETTING, value) }
```

2. **Add a row** to `SettingsList.kt` (a `PreferenceModel`), with title and summary as string
   resources.

3. **Decide whether it is backed up.** If the setting should survive a restore, add its key to
   `BACKUP_SETTING_KEYS`. Two rules: never enumerate `sharedPreferences.all` into an export — the
   allowlist is what keeps a credential out — and the allowlist gates **keys, not values**, so a
   setting with a closed set of valid options must be validated on import (cu-77).

4. **Read it** through the injected `prefsRepo`. To react to changes, use `util/PreferenceFlow.kt`
   rather than reading the value once.

### Adding Network API Call

1. **Add to PlexService**:

```kotlin
// PlexService.kt
interface PlexService {
    @GET("/library/sections/{libraryId}/newEndpoint")
    suspend fun getNewData(
        @Path("libraryId") libraryId: String
    ): Response<NewDataResponse>
}
```

2. **Add Response Model**:

```kotlin
// In data/sources/plex/model/
data class NewDataResponse(
    val data: List<NewItem>
)
```

3. **Use in Repository**:

```kotlin
suspend fun fetchNewData(): List<NewItem> = withContext(Dispatchers.IO) {
    val response = plexService.getNewData(libraryId)
    if (response.isSuccessful) {
        response.body()?.data ?: emptyList()
    } else {
        throw IOException("Failed to fetch: ${response.code()}")
    }
}
```

### Adding Database Table

1. **Create Entity**:

```kotlin
@Entity
data class NewEntity(
    // String, not Int: ids are backend-neutral since cu-71 so a non-numeric backend
    // (Audiobookshelf UUIDs, local file paths) can be represented. Any DAO parameter
    // bound against this column must also be String — a numeric bind silently matches
    // no row, because SQLite compares across storage classes without erroring.
    @PrimaryKey val id: String,
    val name: String,
    val value: Int
)
```

2. **Create DAO**:

```kotlin
@Dao
interface NewEntityDao {
    @Query("SELECT * FROM NewEntity")
    fun getAll(): Flow<List<NewEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NewEntity)
}
```

3. **Update Database**:

```kotlin
@Database(
    entities = [Audiobook::class, NewEntity::class],  // Add new entity
    version = 15,          // Increment version
    exportSchema = true,   // always — RoomSchemaTest reads the exported JSON
)
abstract class BookDatabase : RoomDatabase() {
    abstract val bookDao: BookDao
    abstract val newEntityDao: NewEntityDao  // Add DAO
}

// Add migration
val BOOK_MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE NewEntity (" +
            "id TEXT PRIMARY KEY NOT NULL, " +
            "name TEXT NOT NULL, " +
            "value INTEGER NOT NULL)"
        )
    }
}
```

## Best Practices

1. **Follow Existing Patterns**: Look at similar features for guidance
2. **Keep ViewModels Thin**: Heavy logic goes in Repositories
3. **Use StateFlow for UI**: expose `StateFlow` from ViewModels and collect it with
   `collectWhileStarted`. There is no `LiveData` in this codebase (cu-52), and `postValue` is
   banned by `PostValueUsageTest`
4. **Handle Errors**: Try-catch in Repositories, show messages in ViewModels
5. **Test Incrementally**: Test each layer as you build it
6. **Write new UI in Compose** ([[decision-22]]); there is no DataBinding (cu-58)
7. **Keep UI Thread Free**: All heavy work in background threads
8. **Log Important Events**: Use Timber for debugging
9. **Handle Loading States**: Show progress indicators during async operations
10. **Consider Offline Mode**: How does your feature work offline?

## Code Review Checklist

Before submitting:
- [ ] `./verify.sh` green — that is the definition of "the build is fine", not CI
- [ ] No direct database access from ViewModels
- [ ] All async operations use coroutines properly
- [ ] Error handling implemented
- [ ] Loading states handled
- [ ] Offline mode considered
- [ ] Resources in strings.xml (no hardcoded strings)
- [ ] Dependency injection used correctly
- [ ] Database migrations added if needed
- [ ] `StateFlow` used for UI state, collected via `collectWhileStarted`
- [ ] Navigation follows existing patterns
- [ ] Tested on physical device
- [ ] No memory leaks (check lifecycle awareness)

## Getting Help

- **Check similar features**: Look at existing code for patterns
- **Read documentation**: Review these docs
- **Debug tools**: Use Android Studio's debugger and layout inspector
- **Timber logs**: Add logging to trace execution
- **Ask questions**: Comment on issues or discussions

## Common Pitfalls to Avoid

1. **Don't access database from ViewModels** - Always use Repository
2. **Don't block the UI thread** - Use coroutines for heavy work
3. **Don't forget lifecycle** - Use viewLifecycleOwner for Fragment observers
4. **Don't ignore errors** - Handle exceptions gracefully
5. **Don't hardcode strings** - Use strings.xml
6. **Don't forget offline mode** - Consider cached data
7. **Don't skip migrations** - Database changes need migrations
8. **Don't forget Dagger** - New dependencies need to be provided

