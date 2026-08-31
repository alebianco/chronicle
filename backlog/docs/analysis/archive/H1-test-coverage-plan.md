# Task H1: Inadequate Test Coverage Resolution Plan

> **Archived 2026-08-31.** [[cu-44]] is Done. Its own reasoning against DAO tests on in-memory
> Room was later overtaken by [[cu-49]]'s `RoomSchemaTest`; see cu-44's Implementation Notes for
> the correction.

**Task ID**: H1  
**Priority**: 🟠 High (Quality Assurance)  
**Created**: 2025-11-28  
**Status**: Planning - Awaiting Approval

---

## Problem Statement

The Chronicle Android app has **critically low test coverage** that creates high regression risk and makes safe refactoring nearly impossible.

**Current State**:
- Unit tests: Only 2 test files (`AudiobookDetailsViewModelTest.kt`, `TrackListStateManagerTest.kt`)
- Instrumented tests: ~5 test files
- **Line 137 in build.gradle.kts disables all DebugAndroidTest tasks!**
- No repository tests
- No DAO tests
- Most ViewModels untested
- No coverage reporting

**Impact**:
- High risk of introducing regressions
- Difficult to refactor safely
- No confidence in changes
- CI/CD less effective
- Technical debt compounds

---

## Solution Strategy

**Phased Approach** - Build coverage gradually over 4+ weeks:

### Week 1: Infrastructure (30% target)
### Week 2: ViewModel Tests (45% target)
### Week 3: Repository Tests (60% target)
### Week 4+: Integration Tests & Ongoing (70%+ target)

---

## Implementation Plan

### Phase 1: Infrastructure Setup (Week 1, 20 hours)
**Risk**: Low

#### Tasks:
- [ ] 1.1. Investigate why Android tests are disabled (build.gradle.kts:137)
- [ ] 1.2. Add JaCoCo for coverage reporting
- [ ] 1.3. Create test utilities and base classes
- [ ] 1.4. Set up CI test execution (see H4)
- [ ] 1.5. Create testing documentation
- [ ] 1.6. Configure test dependencies

**Implementation**:

```kotlin
// testShared/TestBase.kt
package io.github.mattpvaughn.chronicle.testShared

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule

@ExperimentalCoroutinesApi
abstract class ViewModelTestBase {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    
    protected val testDispatcher = StandardTestDispatcher()
    
    @Before
    fun setupBase() {
        Dispatchers.setMain(testDispatcher)
    }
    
    @After
    fun tearDownBase() {
        Dispatchers.resetMain()
    }
}
```

```kotlin
// testShared/TestData.kt
package io.github.mattpvaughn.chronicle.testShared

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack

object TestData {
    val sampleAudiobook = Audiobook(
        id = 1,
        title = "Test Audiobook",
        author = "Test Author",
        isCached = false
    )
    
    val sampleTrack = MediaItemTrack(
        id = 1,
        title = "Track 1",
        parentKey = 1
    )
    
    fun createAudiobook(id: Int = 1, title: String = "Test Book") = Audiobook(
        id = id,
        title = title,
        author = "Test Author"
    )
}
```

```kotlin
// testShared/LiveDataTestUtil.kt
package io.github.mattpvaughn.chronicle.testShared

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

fun <T> LiveData<T>.getOrAwaitValue(
    time: Long = 2,
    timeUnit: TimeUnit = TimeUnit.SECONDS,
    afterObserve: () -> Unit = {}
): T {
    var data: T? = null
    val latch = CountDownLatch(1)
    val observer = object : Observer<T> {
        override fun onChanged(value: T) {
            data = value
            latch.countDown()
            this@getOrAwaitValue.removeObserver(this)
        }
    }
    this.observeForever(observer)
    
    try {
        afterObserve.invoke()
        
        if (!latch.await(time, timeUnit)) {
            throw TimeoutException("LiveData value was never set.")
        }
    } finally {
        this.removeObserver(observer)
    }
    
    @Suppress("UNCHECKED_CAST")
    return data as T
}
```

**JaCoCo Configuration** (build.gradle.kts):

```kotlin
plugins {
    // ...existing plugins...
    id("jacoco")
}

jacoco {
    toolVersion = "0.8.10"
}

tasks.withType<Test> {
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(files("build/intermediates/javac/debug/classes"))
    executionData.setFrom(files("build/jacoco/testDebugUnitTest.exec"))
    
    // Exclude generated code
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "**/R.class",
                    "**/R\$*.class",
                    "**/BuildConfig.*",
                    "**/Manifest*.*",
                    "**/*_Factory.*",
                    "**/*_MembersInjector.*",
                    "**/Dagger*Component*.*",
                    "**/*Module_*Factory.*",
                    "**/*Binding*.*",
                    "**/databinding/*",
                    "**/android/databinding/*"
                )
            }
        })
    )
}
```

---

### Phase 2: ViewModel Tests (Week 2, 20 hours)
**Risk**: Low-Medium

**Goal**: Cover critical ViewModels (30% coverage target)

**Priority ViewModels** (in order):
1. `LibraryViewModel` - Core feature, most used
2. `AudiobookDetailsViewModel` - Expand existing tests
3. `CurrentlyPlayingViewModel` - Playback critical
4. `SettingsViewModel` - Many settings to test
5. `MainActivityViewModel` - App entry point

**Test Template**:

```kotlin
@ExperimentalCoroutinesApi
class LibraryViewModelTest : ViewModelTestBase() {
    
    private lateinit var viewModel: LibraryViewModel
    private val mockBookRepository: IBookRepository = mockk()
    private val mockCachedFileManager: ICachedFileManager = mockk()
    private val mockPrefsRepo: PrefsRepo = mockk()
    
    @Before
    fun setup() {
        // Setup mocks
        every { mockBookRepository.getAllBooks() } returns MutableLiveData(emptyList())
        every { mockPrefsRepo.offlineMode } returns false
        
        viewModel = LibraryViewModel(
            mockBookRepository,
            mockCachedFileManager,
            mockPrefsRepo
        )
    }
    
    @Test
    fun `when books loaded then books displayed`() = runTest {
        // Given
        val books = listOf(TestData.sampleAudiobook)
        every { mockBookRepository.getAllBooks() } returns MutableLiveData(books)
        
        // When
        val result = viewModel.books.getOrAwaitValue()
        
        // Then
        assertThat(result).isEqualTo(books)
    }
    
    @Test
    fun `when sort changed then books re-sorted`() = runTest {
        // Given
        val books = listOf(
            TestData.createAudiobook(1, "B Book"),
            TestData.createAudiobook(2, "A Book")
        )
        every { mockBookRepository.getAllBooks() } returns MutableLiveData(books)
        
        // When
        viewModel.setSortOrder(SortOrder.TITLE_ASC)
        val result = viewModel.sortedBooks.getOrAwaitValue()
        
        // Then
        assertThat(result[0].title).isEqualTo("A Book")
        assertThat(result[1].title).isEqualTo("B Book")
    }
    
    @Test
    fun `when download clicked then download starts`() = runTest {
        // Given
        val book = TestData.sampleAudiobook
        
        // When
        viewModel.downloadBook(book)
        
        // Then
        verify { mockCachedFileManager.downloadTracks(book.id, book.title) }
    }
    
    @Test
    fun `when search query entered then books filtered`() = runTest {
        // Test search functionality
    }
}
```

---

### Phase 3: Repository Tests (Week 3, 20 hours)
**Risk**: Medium

**Goal**: Test data layer (45% coverage target)

**Priority Repositories**:
1. `BookRepository` - Core data management
2. `TrackRepository` - Track handling
3. `PlexLoginRepo` - Authentication critical

**Test Pattern**:

```kotlin
@ExperimentalCoroutinesApi
class BookRepositoryTest {
    
    private lateinit var repository: BookRepository
    private lateinit var bookDao: BookDao
    private lateinit var database: BookDatabase
    private val mockPlexMediaService: PlexMediaService = mockk()
    private val mockPrefsRepo: PrefsRepo = mockk()
    private val mockPlexPrefsRepo: PlexPrefsRepo = mockk()
    
    @Before
    fun setup() {
        // In-memory database for testing
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        bookDao = database.bookDao
        
        every { mockPrefsRepo.offlineMode } returns false
        
        repository = BookRepository(
            bookDao,
            mockPrefsRepo,
            mockPlexPrefsRepo,
            mockPlexMediaService
        )
    }
    
    @After
    fun tearDown() {
        database.close()
    }
    
    @Test
    fun `when books inserted then retrieved from database`() = runTest {
        // Given
        val books = listOf(TestData.sampleAudiobook)
        
        // When
        bookDao.upsert(books)
        val result = bookDao.getAllRows(false).getOrAwaitValue()
        
        // Then
        assertThat(result).containsExactlyElementsIn(books)
    }
    
    @Test
    fun `when book updated then changes persisted`() = runTest {
        // Test update functionality
    }
    
    @Test
    fun `when offline mode enabled then only cached books returned`() = runTest {
        // Test offline filtering
    }
}
```

---

### Phase 4: Integration Tests (Week 4, 20 hours)
**Risk**: Medium-High

**Goal**: Test critical flows (60% coverage target)

**Critical Flows**:
1. Login → Library → Play
2. Download → Offline Play
3. Progress sync
4. Search → Result → Play

---

### Phase 5: Ongoing (Beyond Week 4)
**Goal**: Maintain and improve coverage (70%+ target)

**Practices**:
- Every new feature requires tests (enforce in PR reviews)
- Bug fixes require regression tests
- Monthly coverage review meetings
- Quarterly coverage improvement sprints

---

## Android Tests Investigation

**Current Issue** (build.gradle.kts:137):
```kotlin
tasks.matching { it.name.contains("DebugAndroidTest") && !it.name.contains("Lint") }.configureEach {
  enabled = false
}
```

**Action Items**:
1. [ ] Check git history for context (`git log --all --grep="DebugAndroidTest"`)
2. [ ] Determine why disabled (failing? slow? flaky?)
3. [ ] Decide: Re-enable, fix issues, or document reason

**Options**:
- A) Re-enable if no blocking issues
- B) Fix underlying issues then re-enable
- C) Keep disabled but document why (with plan to re-enable)

---

## Success Criteria

### Must Have ✅:
1. [ ] JaCoCo configured and generating reports
2. [ ] Android test situation resolved (re-enabled or documented)
3. [ ] 5+ ViewModel test classes added
4. [ ] 3+ Repository test classes added
5. [ ] CI runs tests and reports coverage (H4)
6. [ ] Testing guide documented
7. [ ] 30% coverage by Week 2
8. [ ] 45% coverage by Week 3
9. [ ] 60% coverage by Week 4

### Should Have ✅:
1. [ ] Test utilities create reusable patterns
2. [ ] Coverage badge in README
3. [ ] Test results published to CI artifacts
4. [ ] PR coverage comments working

### Nice to Have 🎯:
1. [ ] Mutation testing configured
2. [ ] Test coverage gates in CI
3. [ ] Test performance monitoring

---

## Risks & Mitigation

| Risk | Likelihood | Impact | Mitigation |
|------|------------|---------|------------|
| Tests take too long to write | High | Medium | Start with happy paths only |
| Flaky tests | Medium | High | Use proper test dispatchers, avoid time dependencies |
| Mocking complexity | Medium | Medium | Create test utilities and helpers |
| CI slowdown | Low | Medium | Parallel test execution, caching |
| Coverage pressure | Medium | Medium | Set realistic incremental goals |

---

## Dependencies

**Depends On**: None - can start immediately

**Blocks**: None

**Benefits**: 
- H4 (CI Test Execution) - coverage reporting
- H5 (Dispatcher Injection) - easier testing

**Blocked By**: None

---

## Estimated Effort Breakdown

| Phase | Task | Time |
|-------|------|------|
| 1 | Infrastructure | 20h (Week 1) |
| 2 | ViewModel Tests | 20h (Week 2) |
| 3 | Repository Tests | 20h (Week 3) |
| 4 | Integration Tests | 20h (Week 4) |
| 5 | Ongoing | 4h/week |
| **Total Initial** | | **80h (4 weeks)** |

---

## Approval Checklist

Before proceeding:

- [ ] **Phased approach approved**: 30%→45%→60%→70% over 4+ weeks
- [ ] **Android test investigation**: Approved to spend time investigating
- [ ] **Coverage targets realistic**: 30/45/60/70% goals acceptable
- [ ] **Resource allocation**: 20h/week for 4 weeks acceptable
- [ ] **Can start parallel**: With other high-priority tasks

---

## Next Steps After Approval

1. ✅ Create feature branch: `feature/H1-test-coverage`
2. ✅ Investigate Android test disablement
3. ✅ Start Phase 1: Infrastructure setup
4. ✅ Weekly progress updates
5. ✅ Coordinate with H4 (CI) and H5 (Dispatchers)

---

**This is an ongoing effort** - The goal is to establish a testing culture, not just hit a number. Focus on testing critical paths first, then expand coverage over time.

---

*Created: 2025-11-28*  
*Owner: Engineering Team (All)*  
*Estimated Completion: Ongoing (initial phase 4 weeks)*  
*Reviewer: Tech Lead / QA Lead*

