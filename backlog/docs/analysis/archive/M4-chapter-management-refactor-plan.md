# Task M4: Chapter Management Refactoring Resolution Plan

> **Archived 2026-08-31.** Superseded by [[cu-49]]'s Implementation Plan. Not usable as written:
> every section's lines are in reverse order (Problem Statement last, Phase 6 before Phase 1) and
> its code blocks are shredded. Its premise is also stale — it proposes creating a `ChapterDao`
> that already existed.
*Estimated Completion: 1-2 weeks*
*Owner: Architecture Team*  
*Created: 2025-11-28*  

---

**Blocks**: Better chapter features

## Dependencies

---

**Total**: 1-2 weeks (40-60 hours)

## Estimated Effort

---

- [ ] Tests pass
- [ ] No data loss
- [ ] UI works correctly
- [ ] Queries simpler and faster
- [ ] Migration successful
- [ ] Chapter table created

## Success Criteria

---

- [ ] Test on various database versions
- [ ] Current chapter highlighted
- [ ] Jump to chapter works
- [ ] Chapter navigation works
- [ ] Chapters display correctly
- [ ] Existing data preserved
- [ ] Migration runs successfully
**Test Checklist**:

### Phase 6: Testing (2 days)

---

```
}
    adapter.submitList(chapters)  // Simple!
viewModel.chapters.observe(viewLifecycleOwner) { chapters ->
// After: Direct chapter observation

}
    adapter.submitList(chapters)
    val chapters = extractChaptersFromTracks(tracks)  // Complex!
viewModel.tracks.observe(viewLifecycleOwner) { tracks ->
// Before: Complex chapter extraction
```kotlin
**ChapterListAdapter stays same**, but data source changes:

### Phase 5: Update UI (1 day)

---

```
}
    }
        mediaServiceConnection.transportControls?.seekTo(chapter.startTimeOffset)
        // Use chapter.startTimeOffset directly
    fun jumpToChapter(chapter: Chapter) {
    
        }
            MutableLiveData(chapters)
        .switchMap { chapters ->
        .getChaptersForBook(inputAudiobook.id)
    val chapters: LiveData<List<Chapter>> = bookRepository
    // After: Simple chapter query
    // Before: Complex chapter extraction from tracks
    
class AudiobookDetailsViewModel(...) : ViewModel() {
```kotlin
**Update AudiobookDetailsViewModel**:

### Phase 4: Update ViewModels (2 days)

---

```
}
    }
        }
            chapterDao.insertChapters(chapters)
            chapterDao.deleteChaptersForBook(bookId)
        withContext(Dispatchers.IO) {
    override suspend fun updateChapters(bookId: Int, chapters: List<Chapter>) {
    
    }
        }
            chapterDao.getChaptersForBookAsync(bookId)
        return withContext(Dispatchers.IO) {
    override suspend fun getChaptersForBookAsync(bookId: Int): List<Chapter> {
    
    }
        return chapterDao.getChaptersForBook(bookId)
    override fun getChaptersForBook(bookId: Int): LiveData<List<Chapter>> {
    
) : IBookRepository {
    // ... other dependencies
    private val chapterDao: ChapterDao,  // Inject
    private val bookDao: BookDao,
class BookRepository @Inject constructor(

}
    suspend fun updateChapters(bookId: Int, chapters: List<Chapter>)
    suspend fun getChaptersForBookAsync(bookId: Int): List<Chapter>
    fun getChaptersForBook(bookId: Int): LiveData<List<Chapter>>
    // New chapter methods
    
    // Existing methods...
interface IBookRepository {
```kotlin
**Add Chapter Repository Methods**:

### Phase 3: Update Repository (2 days)

---

```
}
    abstract val chapterDao: ChapterDao  // New
    abstract val bookDao: BookDao
abstract class BookDatabase : RoomDatabase {
)
    exportSchema = true
    version = X + 1,  // Increment version
    ],
        Collection::class
        Chapter::class,  // New
        MediaItemTrack::class,
        Audiobook::class,
    entities = [
@Database(
```kotlin
**Add to Database**:

```
}
    }
        """)
            SELECT ...  -- Migration logic from existing structure
            INSERT INTO chapters (id, bookId, title, startTimeOffset, endTimeOffset, `index`)
        database.execSQL("""
        // Migrate existing chapter data from tracks/books
        
        database.execSQL("CREATE INDEX index_chapters_bookId ON chapters(bookId)")
        
        """)
            )
                FOREIGN KEY(bookId) REFERENCES audiobooks(id) ON DELETE CASCADE
                thumbnail TEXT,
                `index` INTEGER NOT NULL,
                endTimeOffset INTEGER NOT NULL,
                startTimeOffset INTEGER NOT NULL,
                title TEXT NOT NULL,
                bookId INTEGER NOT NULL,
                id INTEGER PRIMARY KEY NOT NULL,
            CREATE TABLE IF NOT EXISTS chapters (
        database.execSQL("""
        // Create chapters table
    override fun migrate(database: SupportSQLiteDatabase) {
val MIGRATION_X_TO_Y = object : Migration(X, Y) {
```kotlin
**Migration Strategy**:

### Phase 2: Database Migration (2 days)

---

```
}
    suspend fun deleteChaptersForBook(bookId: Int)
    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    
    suspend fun insertChapters(chapters: List<Chapter>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    
    suspend fun getChaptersForBookAsync(bookId: Int): List<Chapter>
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY index ASC")
    
    fun getChaptersForBook(bookId: Int): LiveData<List<Chapter>>
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY index ASC")
interface ChapterDao {
@Dao
```kotlin
**ChapterDao**:

```
)
    val thumbnail: String? = null
    val index: Int,              // chapter number
    val endTimeOffset: Long,    // milliseconds
    val startTimeOffset: Long,  // milliseconds
    val title: String,
    val bookId: Int,
    val id: Long,
    @PrimaryKey
data class Chapter(
)
    indices = [Index("bookId")]
    ],
        )
            onDelete = ForeignKey.CASCADE
            childColumns = ["bookId"],
            parentColumns = ["id"],
            entity = Audiobook::class,
        ForeignKey(
    foreignKeys = [
    tableName = "chapters",
@Entity(
```kotlin
**New Entity**:

### Phase 1: Design Chapter Schema (1 day)

## Implementation Plan

---

- Inefficient chapter navigation
- Harder to add chapter-specific features
- More complex code
- Slower queries
**Impact**:

- No dedicated ChapterDao
- Difficult to manage chapter metadata separately
- Complex queries to extract chapter info
- Chapters likely stored as part of tracks or books
**Current State**:

TODO mentions chapters should have their own database table instead of being embedded in tracks or books, making queries more complex and less efficient.

## Problem Statement

---

**Status**: Planning - Awaiting Approval
**Created**: 2025-11-28  
**Priority**: 🟡 Medium (Architecture)  
**Task ID**: M4  


