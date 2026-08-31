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

## Example: Adding a "Favorites" Feature

Let's walk through adding a feature to favorite/unfavorite audiobooks.

### Step 1: Plan

**Requirements**:
- Users can mark books as favorites
- Favorites are saved persistently
- Show favorites section on home screen
- Show favorite indicator on book cards

### Step 2: Data Layer

#### Update Data Model

**File**: `data/model/Audiobook.kt`

```kotlin
@Entity
data class Audiobook(
    // ... existing fields ...
    val favorited: Boolean = false,  // Add this field
    // ... rest of fields ...
)
```

#### Update Database

**File**: `data/local/BookDatabase.kt`

Add a database migration:

```kotlin
val BOOK_MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Audiobook ADD COLUMN favorited INTEGER NOT NULL DEFAULT 0")
    }
}

// Update database version and add migration
@Database(entities = [Audiobook::class], version = 9, exportSchema = false)
abstract class BookDatabase : RoomDatabase() {
    abstract val bookDao: BookDao
}

// In getBookDatabase function, add the migration:
.addMigrations(
    // ... existing migrations ...
    BOOK_MIGRATION_8_9
)
```

#### Update DAO

**File**: `data/local/BookDatabase.kt` (BookDao interface)

```kotlin
@Dao
interface BookDao {
    // ... existing methods ...
    
    @Query("UPDATE Audiobook SET favorited = :favorited WHERE id = :bookId")
    suspend fun updateFavorited(bookId: Int, favorited: Boolean)
    
    @Query("SELECT * FROM Audiobook WHERE favorited = 1 ORDER BY titleSort")
    fun getFavoritedBooks(): LiveData<List<Audiobook>>
}
```

#### Update Repository

**File**: `data/local/BookRepository.kt`

```kotlin
class BookRepository @Inject constructor(
    // ... existing dependencies ...
) : IBookRepository {
    
    // Add interface method
    override suspend fun setFavorited(bookId: Int, favorited: Boolean) {
        withContext(Dispatchers.IO) {
            bookDao.updateFavorited(bookId, favorited)
            // Optionally sync to Plex server
            try {
                plexMediaService.updateMetadata(bookId, favorited)
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync favorite state")
            }
        }
    }
    
    override fun getFavoritedBooks(): LiveData<List<Audiobook>> {
        return bookDao.getFavoritedBooks()
    }
}

// Update interface
interface IBookRepository {
    // ... existing methods ...
    suspend fun setFavorited(bookId: Int, favorited: Boolean)
    fun getFavoritedBooks(): LiveData<List<Audiobook>>
}
```

### Step 3: ViewModel Layer

#### Update HomeViewModel

**File**: `features/home/HomeViewModel.kt`

```kotlin
class HomeViewModel(
    // ... existing dependencies ...
) : ViewModel() {
    
    // Add favorites list
    val favoritedBooks = bookRepository.getFavoritedBooks()
    
    // ... rest of existing code ...
}
```

#### Update AudiobookDetailsViewModel

**File**: `features/bookdetails/AudiobookDetailsViewModel.kt`

```kotlin
class AudiobookDetailsViewModel(
    // ... existing dependencies ...
) : ViewModel() {
    
    fun toggleFavorite() {
        viewModelScope.launch {
            val currentBook = audiobook.value ?: return@launch
            val newState = !currentBook.favorited
            
            try {
                bookRepository.setFavorited(currentBook.id, newState)
                _messageForUser.value = Event(
                    if (newState) "Added to favorites" else "Removed from favorites"
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to toggle favorite")
                _messageForUser.value = Event("Failed to update favorite")
            }
        }
    }
}
```

### Step 4: View Layer

#### Update Home Screen Layout

**File**: `res/layout/fragment_home.xml`

Add a favorites section:

```xml
<!-- Add after recently listened section -->
<TextView
    android:id="@+id/favorites_header"
    android:text="@string/favorites"
    android:visibility="@{viewModel.favoritedBooks.size() > 0 ? View.VISIBLE : View.GONE}"
    ... />

<androidx.recyclerview.widget.RecyclerView
    android:id="@+id/favorites_recyclerview"
    android:visibility="@{viewModel.favoritedBooks.size() > 0 ? View.VISIBLE : View.GONE}"
    app:layoutManager="androidx.recyclerview.widget.LinearLayoutManager"
    app:items="@{viewModel.favoritedBooks}"
    ... />
```

#### Update HomeFragment

**File**: `features/home/HomeFragment.kt`

```kotlin
class HomeFragment : Fragment() {
    
    override fun onCreateView(...): View? {
        val binding = FragmentHomeBinding.inflate(inflater, container, false)
        
        // ... existing setup ...
        
        // Add favorites RecyclerView
        binding.favoritesRecyclerview.adapter = makeAudiobookAdapter()
        binding.favoritesRecyclerview.itemAnimator?.changeDuration = 0
        
        return binding.root
    }
}
```

#### Add Favorite Button to Book Details

**File**: `res/layout/fragment_audiobook_details.xml`

```xml
<ImageButton
    android:id="@+id/favorite_button"
    android:src="@{viewModel.audiobook.favorited ? @drawable/ic_favorite_filled : @drawable/ic_favorite_outline}"
    android:onClick="@{() -> viewModel.toggleFavorite()}"
    android:contentDescription="@string/toggle_favorite"
    ... />
```

#### Add Favorite Indicator to Book Cards

**File**: `res/layout/audiobook_list_item.xml`

```xml
<ImageView
    android:id="@+id/favorite_indicator"
    android:src="@drawable/ic_favorite_small"
    android:visibility="@{audiobook.favorited ? View.VISIBLE : View.GONE}"
    ... />
```

### Step 5: Resources

#### Add Strings

**File**: `res/values/strings.xml`

```xml
<string name="favorites">Favorites</string>
<string name="toggle_favorite">Toggle favorite</string>
<string name="added_to_favorites">Added to favorites</string>
<string name="removed_from_favorites">Removed from favorites</string>
```

#### Add Icons

Add favorite icons to `res/drawable/`:
- `ic_favorite_filled.xml` (filled heart)
- `ic_favorite_outline.xml` (outline heart)
- `ic_favorite_small.xml` (small indicator)

### Step 6: Testing

1. **Manual Testing**:
   - Favorite a book from details screen
   - Check it appears in favorites section on home
   - Unfavorite and verify it disappears
   - Restart app and verify favorites persist
   - Check offline mode

2. **Unit Tests** (if applicable):

**File**: `test/.../BookRepositoryTest.kt`

```kotlin
@Test
fun `setFavorited updates database`() = runTest {
    // Given
    val bookId = 123
    
    // When
    repository.setFavorited(bookId, true)
    
    // Then
    verify(bookDao).updateFavorited(bookId, true)
}
```

## Common Patterns for Different Features

### Adding a New Screen

1. **Create Feature Package**: `features/newfeature/`
2. **Create Fragment**: `NewFeatureFragment.kt`
3. **Create ViewModel**: `NewFeatureViewModel.kt`
4. **Create Layout**: `res/layout/fragment_new_feature.xml`
5. **Update Navigator**: Add navigation method
6. **Update Dagger**: Inject into ActivityComponent if needed
7. **Add to Navigation**: Update bottom nav or add menu item

Example structure:
```kotlin
// NewFeatureFragment.kt
class NewFeatureFragment : Fragment() {
    @Inject lateinit var viewModelFactory: NewFeatureViewModel.Factory
    private lateinit var viewModel: NewFeatureViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        (requireActivity() as MainActivity).activityComponent!!.inject(this)
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this, viewModelFactory)
            .get(NewFeatureViewModel::class.java)
    }
    
    override fun onCreateView(...): View {
        val binding = FragmentNewFeatureBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel
        return binding.root
    }
}

// NewFeatureViewModel.kt
class NewFeatureViewModel(
    private val repository: SomeRepository
) : ViewModel() {
    
    @Suppress("UNCHECKED_CAST")
    class Factory @Inject constructor(
        private val repository: SomeRepository
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NewFeatureViewModel(repository) as T
        }
    }
    
    // ViewModel logic here
}
```

### Adding a Setting

1. **Add to PrefsRepo**:

```kotlin
// PrefsRepo.kt
class PrefsRepo {
    companion object {
        const val PREF_NEW_SETTING = "new_setting"
        const val DEFAULT_NEW_SETTING = false
    }
    
    var newSetting: Boolean
        get() = sharedPrefs.getBoolean(PREF_NEW_SETTING, DEFAULT_NEW_SETTING)
        set(value) = sharedPrefs.edit().putBoolean(PREF_NEW_SETTING, value).apply()
}
```

2. **Add to Settings UI**:

```xml
<!-- settings.xml -->
<SwitchPreferenceCompat
    app:key="new_setting"
    app:title="@string/new_setting_title"
    app:summary="@string/new_setting_summary"
    app:defaultValue="false" />
```

3. **Use in Code**:

```kotlin
if (prefsRepo.newSetting) {
    // Do something
}
```

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
    fun getAll(): LiveData<List<NewEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NewEntity)
}
```

3. **Update Database**:

```kotlin
@Database(
    entities = [Audiobook::class, NewEntity::class],  // Add new entity
    version = 10,  // Increment version
    exportSchema = false
)
abstract class BookDatabase : RoomDatabase() {
    abstract val bookDao: BookDao
    abstract val newEntityDao: NewEntityDao  // Add DAO
}

// Add migration
val BOOK_MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE NewEntity (" +
            "id INTEGER PRIMARY KEY NOT NULL, " +
            "name TEXT NOT NULL, " +
            "value INTEGER NOT NULL)"
        )
    }
}
```

## Best Practices

1. **Follow Existing Patterns**: Look at similar features for guidance
2. **Keep ViewModels Thin**: Heavy logic goes in Repositories
3. **Use LiveData for UI**: Always expose LiveData to Views
4. **Handle Errors**: Try-catch in Repositories, show messages in ViewModels
5. **Test Incrementally**: Test each layer as you build it
6. **Use Data Binding**: Bind data directly in XML when possible
7. **Keep UI Thread Free**: All heavy work in background threads
8. **Log Important Events**: Use Timber for debugging
9. **Handle Loading States**: Show progress indicators during async operations
10. **Consider Offline Mode**: How does your feature work offline?

## Code Review Checklist

Before submitting:
- [ ] Code follows ktlint style (`./gradlew ktlintFormat`)
- [ ] No direct database access from ViewModels
- [ ] All async operations use coroutines properly
- [ ] Error handling implemented
- [ ] Loading states handled
- [ ] Offline mode considered
- [ ] Resources in strings.xml (no hardcoded strings)
- [ ] Dependency injection used correctly
- [ ] Database migrations added if needed
- [ ] LiveData used for UI updates
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

