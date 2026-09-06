---
id: M7-large-library-performance-plan
title: "Task M7: Large Library Performance Resolution Plan"
type: analysis
created_date: '2026-09-01'
---

# Task M7: Large Library Performance Resolution Plan

**Task ID**: M7  
**Priority**: 🟡 Medium (Performance)  
**Created**: 2025-11-28  
**Status**: Planning - Awaiting Approval

---

## Problem Statement

TODO mentions issues loading huge libraries. Timeout was increased from 15s to 30s as a stopgap, but real fix needs incremental loading and query optimization. Repository gets "don't scale (sub-n²)".

**Current State**:
- Large libraries (1000+ books) load slowly
- Timeout doubled to 30s (band-aid fix)
- Repository queries inefficient
- Mentioned as "sub-n²" complexity
- UI may freeze during load
- No pagination

**Impact**:
- Poor UX for users with large libraries
- Timeouts on very large libraries
- App may appear frozen
- Higher battery drain
- Memory pressure

---

## Solution Strategy

Implement incremental loading, optimize queries, add database indexes, and use pagination to handle libraries of any size.

---

## Implementation Plan

### Phase 1: Profile Current Performance (1 day)

**Setup Profiling**:
```kotlin
// Add timing logs
val startTime = System.currentTimeMillis()
val books = bookRepository.getAllBooks()
Timber.i("Loaded ${books.size} books in ${System.currentTimeMillis() - startTime}ms")
```

**Create Test Data**:
```bash
# Generate large test library
# Insert 1000, 5000, 10000 books into test DB
```

**Measure**:
- Time to load 100 books
- Time to load 1000 books
- Time to load 5000 books
- Time to load 10000 books
- Memory usage at each level
- UI responsiveness

**Identify Bottlenecks**:
- Database query time
- Network fetch time (if remote)
- UI rendering time
- Object allocation

---

### Phase 2: Add Database Indexes (4 hours)

**Analyze Missing Indexes**:
```kotlin
// Check current indexes
@Entity(
    tableName = "audiobooks",
    indices = [
        Index("id"),  // Primary key (auto-indexed)
        Index("title"),  // Add if sorting by title
        Index("author"),  // Add if sorting by author
        Index("dateAdded"),  // Add if sorting by date
        Index("isCached"),  // Add if filtering cached
        Index("lastViewedAt")  // Add for recent sort
    ]
)
data class Audiobook(...)
```

**Add Migration**:
```kotlin
val MIGRATION_X_TO_Y = object : Migration(X, Y) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE INDEX index_audiobooks_title ON audiobooks(title)")
        database.execSQL("CREATE INDEX index_audiobooks_author ON audiobooks(author)")
        database.execSQL("CREATE INDEX index_audiobooks_dateAdded ON audiobooks(dateAdded)")
    }
}
```

**Expected Improvement**: 2-5x faster queries

---

### Phase 3: Implement Pagination (1 week)

**Use Paging 3 Library**:

```kotlin
// Add dependency
implementation("androidx.paging:paging-runtime-ktx:3.2.1")
```

**Update DAO**:
```kotlin
@Dao
interface BookDao {
    @Query("SELECT * FROM audiobooks WHERE offlineMode = 0 OR isCached = 1 ORDER BY title ASC")
    fun getAllBooksPaged(): PagingSource<Int, Audiobook>
    
    // Keep non-paged version for specific needs
    @Query("SELECT * FROM audiobooks WHERE offlineMode = 0 OR isCached = 1 LIMIT :limit")
    fun getAllBooksLimited(limit: Int): LiveData<List<Audiobook>>
}
```

**Update Repository**:
```kotlin
class BookRepository @Inject constructor(
    private val bookDao: BookDao
) {
    fun getAllBooksPaged(): Flow<PagingData<Audiobook>> {
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                prefetchDistance = 10,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { bookDao.getAllBooksPaged() }
        ).flow
    }
}
```

**Update ViewModel**:
```kotlin
class LibraryViewModel @Inject constructor(
    private val bookRepository: IBookRepository
) : ViewModel() {
    
    val books: Flow<PagingData<Audiobook>> = bookRepository
        .getAllBooksPaged()
        .cachedIn(viewModelScope)
}
```

**Update Adapter**:
```kotlin
class BookListAdapter : PagingDataAdapter<Audiobook, BookViewHolder>(
    BookDiffCallback()
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        // ...
    }
    
    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        val book = getItem(position)
        if (book != null) {
            holder.bind(book)
        }
    }
}

class BookDiffCallback : DiffUtil.ItemCallback<Audiobook>() {
    override fun areItemsTheSame(oldItem: Audiobook, newItem: Audiobook): Boolean {
        return oldItem.id == newItem.id
    }
    
    override fun areContentsTheSame(oldItem: Audiobook, newItem: Audiobook): Boolean {
        return oldItem == newItem
    }
}
```

**Update Fragment**:
```kotlin
class LibraryFragment : Fragment() {
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        adapter = BookListAdapter()
        binding.recyclerView.adapter = adapter
        
        // Collect paging data
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.books.collectLatest { pagingData ->
                adapter.submitData(pagingData)
            }
        }
        
        // Optional: Load state handling
        adapter.addLoadStateListener { loadState ->
            when (loadState.refresh) {
                is LoadState.Loading -> showLoading()
                is LoadState.NotLoading -> hideLoading()
                is LoadState.Error -> showError()
            }
        }
    }
}
```

---

### Phase 4: Optimize Network Fetching (1 week)

**Current Issue** (from TODO):
```kotlin
// Current: Fetches ALL books at once
val response = plexMediaService.retrieveAllAlbums(libraryId)
// This times out for large libraries
```

**Implement Pagination**:
```kotlin
suspend fun refreshDataPaginated() {
    withContext(Dispatchers.IO) {
        var offset = 0
        val pageSize = 100
        
        do {
            val response = plexMediaService
                .retrieveAlbumPage(libraryId, offset, pageSize)
                .plexMediaContainer
            
            val books = response.asAudiobooks()
            bookDao.upsert(books)
            
            offset += pageSize
            
            // Emit progress
            _syncProgress.postValue(offset to response.totalSize)
            
        } while (offset < response.totalSize)
    }
}
```

**Show Progress**:
```kotlin
class LibraryViewModel(...) {
    private val _syncProgress = MutableLiveData<Pair<Int, Int>>()
    val syncProgress: LiveData<Pair<Int, Int>> = _syncProgress
    
    val syncProgressText: LiveData<String> = _syncProgress.map { (current, total) ->
        "Loading $current of $total books..."
    }
}
```

---

### Phase 5: UI Optimization (3 days)

**RecyclerView Optimization**:
```kotlin
// Use DiffUtil (already using with Paging)
// Use stable IDs
adapter.setHasStableIds(true)

// Optimize item animation
binding.recyclerView.itemAnimator?.changeDuration = 0

// Use RecyclerView.RecycledViewPool for nested lists
val sharedPool = RecyclerView.RecycledViewPool()
binding.recyclerView.setRecycledViewPool(sharedPool)

// Prefetch
binding.recyclerView.setItemViewCacheSize(20)
```

**Image Loading Optimization**:
```kotlin
// Load smaller thumbnails for list view
Coil.imageLoader(context).enqueue(
    ImageRequest.Builder(context)
        .data(book.thumbnailUrl)
        .size(200, 200)  // Smaller than full size
        .target(imageView)
        .build()
)
```

**Lazy Loading**:
```kotlin
// Don't load everything at once
viewModel.books.observe(viewLifecycleOwner) { books ->
    // Paging handles this automatically
    adapter.submitData(lifecycle, books)
}
```

---

### Phase 6: Memory Optimization (2 days)

**Reduce Object Allocation**:
```kotlin
// Use data class carefully
data class Audiobook(
    val id: Int,
    val title: String,
    // ... only essential fields
)

// Lazy load heavy fields
val detailedDescription: String? by lazy {
    // Load from DB only when needed
}
```

**Bitmap Optimization**:
```kotlin
// Use Coil's memory cache
Coil.imageLoader(context).apply {
    memoryCache {
        maxSizePercent(0.25)  // 25% of app memory
    }
}
```

---

### Phase 7: Testing (3 days)

**Performance Tests**:
```kotlin
@Test
fun testLargeLibraryPerformance() = runTest {
    // Insert 10,000 books
    val books = (1..10000).map { createBook(it) }
    bookDao.upsert(books)
    
    // Measure load time
    val startTime = System.currentTimeMillis()
    val pagedBooks = bookRepository.getAllBooksPaged()
    val duration = System.currentTimeMillis() - startTime
    
    // Should load first page quickly
    assertThat(duration).isLessThan(1000)  // < 1 second
}
```

**Test Scenarios**:
- [ ] Load empty library
- [ ] Load small library (10 books)
- [ ] Load medium library (100 books)
- [ ] Load large library (1,000 books)
- [ ] Load huge library (10,000 books)
- [ ] Scroll performance
- [ ] Search performance
- [ ] Sort performance
- [ ] Filter performance

**Measure Improvements**:
```markdown
## Performance Metrics

### Before
- 100 books: 500ms
- 1,000 books: 15s ❌
- 5,000 books: timeout ❌
- 10,000 books: crash ❌

### After (Target)
- 100 books: 200ms ✅
- 1,000 books: 500ms ✅
- 5,000 books: 1s ✅
- 10,000 books: 2s ✅
```

---

## Success Criteria

### Must Have ✅:
1. [ ] Libraries of 10,000+ books load
2. [ ] No timeouts
3. [ ] Smooth scrolling
4. [ ] Database indexes added
5. [ ] Pagination implemented
6. [ ] Memory usage acceptable

### Should Have ✅:
1. [ ] Load progress indicator
2. [ ] 5x performance improvement
3. [ ] Tests for large libraries
4. [ ] Documentation updated

### Nice to Have 🎯:
1. [ ] Incremental sync in background
2. [ ] Predictive prefetch
3. [ ] Virtual scrolling

---

## Estimated Effort

| Phase | Time |
|-------|------|
| 1. Profiling | 1d |
| 2. Indexes | 0.5d |
| 3. Pagination | 1w |
| 4. Network | 1w |
| 5. UI Optimization | 3d |
| 6. Memory | 2d |
| 7. Testing | 3d |
| **Total** | **3-4 weeks** |

---

## Dependencies

**Depends On**: None

**Blocks**: User satisfaction with large libraries

**Blocked By**: None

---

## Approval Checklist

- [ ] **Timeline OK**: 3-4 weeks acceptable
- [ ] **Paging 3**: Approved to use Paging library
- [ ] **Breaking changes**: UI changes acceptable
- [ ] **Testing**: Can generate large test data

---

## Next Steps After Approval

1. ✅ Create branch: `feature/M7-large-library-performance`
2. ✅ Profile current performance
3. ✅ Add database indexes (quick win)
4. ✅ Implement Paging 3
5. ✅ Optimize network fetching
6. ✅ Test with 10,000+ books

---

**This is important for power users** with large audiobook collections.

---

*Created: 2025-11-28*  
*Owner: Performance Team*  
*Estimated Completion: 3-4 weeks*  
*Difficulty: Hard*

