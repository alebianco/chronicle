# Task C3: Deprecated Fresco API Migration Plan

> **Archived.** Its task [[cu-43]] is Done; kept as historical context, not part of the
> active reference set.

**Task ID**: C3  
**Priority**: 🔴 Critical  
**Created**: 2025-11-28  
**Status**: Planning - Awaiting Approval

---

## Problem Statement

The project uses deprecated Fresco `DraweeView` APIs that generate build warnings and may be removed in future versions:

```
w: 'class DraweeView<DH : DraweeHierarchy!> : ImageView' is deprecated. Deprecated in Java.
w: 'fun setImageURI(p0: Uri?): Unit' is deprecated. Deprecated in Java.
```

**Impact**:
- Build warnings clutter
- Future Fresco versions may remove these APIs
- Maintenance burden (Fresco is complex and less actively maintained)
- Potential runtime crashes in future

---

## Current State Analysis

### What I Found:

#### 1. **Fresco Usage** (15 occurrences across 7 files):

**Custom View**:
- `views/ChronicleDraweeView.kt` - Custom replacement for deprecated `GenericDraweeView`
- Extends deprecated `DraweeView<GenericDraweeHierarchy>`

**Binding Adapters**:
- `views/BindingAdapters.kt` - Data binding for image loading
- Uses `DraweeView<GenericDraweeHierarchy>`
- Uses `Fresco.newDraweeControllerBuilder()`

**Direct Usage**:
- `features/login/UserListAdapter.kt` - User avatars
- Uses deprecated `setImageURI()`

**Cache Management**:
- `application/ChronicleApplication.kt` - Initialize Fresco, clear caches
- `data/sources/plex/PlexConfig.kt` - Clear image pipeline caches
- `features/settings/SettingsViewModel.kt` - Clear cache setting

**Layout Files** (9 layouts using ChronicleDraweeView):
- `activity_main.xml` - Main screen artwork
- `fragment_audiobook_details.xml` - Book cover
- `fragment_currently_playing.xml` - Now playing artwork
- `grid_item_audiobook.xml` - Grid view covers
- `list_item_audiobook_with_details.xml` - List view covers
- `list_item_user.xml` - User avatars
- And 3 more...

#### 2. **Glide Also Present**:
- Glide is already a dependency (`libs.glide`)
- Used minimally (cache clearing)
- Not configured for use with Plex authentication

#### 3. **Image Loading Requirements**:
- **Authentication**: Custom headers (Plex tokens) via interceptors
- **Caching**: Disk and memory cache with custom cache keys
- **Transformations**: Rounded corners, aspect ratio
- **Placeholders**: Error states, loading states
- **OkHttp Integration**: Share network stack with Retrofit
- **Data Binding**: XML attribute binding support

---

## Migration Options Analysis

### Option A: Migrate to Coil 🥇 **RECOMMENDED**

**Pros**:
- ✅ Modern, Kotlin-first library
- ✅ Coroutines-native (fits project architecture)
- ✅ Excellent OkHttp integration
- ✅ Simple API, less boilerplate
- ✅ Active development (Square/Block)
- ✅ Great performance (on par with Fresco)
- ✅ Compose-ready (future-proof)
- ✅ Excellent documentation
- ✅ Small APK impact (~500KB)

**Cons**:
- ⚠️ Need to learn new API (but simpler than Fresco)
- ⚠️ Migration effort (moderate)

**Best For**: Modern Android apps, Kotlin projects

---

### Option B: Migrate to Glide

**Pros**:
- ✅ Already in dependencies
- ✅ Mature, widely used
- ✅ Good performance
- ✅ Extensive features

**Cons**:
- ⚠️ Java-first (less idiomatic Kotlin)
- ⚠️ More boilerplate than Coil
- ⚠️ Annotation processing complexity
- ⚠️ Larger API surface

**Best For**: Teams already familiar with Glide

---

### Option C: Update Fresco to Non-Deprecated APIs

**Pros**:
- ✅ Minimal code changes
- ✅ Keep existing patterns

**Cons**:
- ❌ Fresco is complex and less maintained
- ❌ Deprecated APIs may still be removed
- ❌ Doesn't solve long-term maintainability
- ❌ Large library size
- ❌ Not recommended by community

**Best For**: Short-term fix only (NOT RECOMMENDED)

---

## Recommendation: **Migrate to Coil**

Coil is the best choice because:
1. **Kotlin-first** - matches project language
2. **Coroutines** - matches async architecture
3. **Simple** - cleaner code, less configuration
4. **Modern** - actively maintained, future-proof
5. **Performance** - as fast as Fresco with less overhead
6. **OkHttp** - seamless integration with existing network stack

---

## Migration Strategy

### Approach: **Incremental Migration with Parallel Operation**

1. Add Coil alongside Fresco
2. Migrate layouts one feature at a time
3. Test each feature thoroughly
4. Remove Fresco once all migrated
5. Clean up unused code

This minimizes risk and allows partial rollback.

---

## Implementation Plan

### Phase 1: Setup & Configuration (Day 1 - Morning, 2-3 hours)
**Risk**: Low

#### Tasks:
- [ ] 1.1. Add Coil dependencies
- [ ] 1.2. Configure Coil with OkHttp client (Plex auth)
- [ ] 1.3. Create custom ImageLoader with Plex headers
- [ ] 1.4. Test basic image loading
- [ ] 1.5. Create feature branch

**Implementation**:

```kotlin
// gradle/libs.versions.toml
[versions]
coil = "2.5.0"

[libraries]
coil = { group = "io.coil-kt", name = "coil", version.ref = "coil" }
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
```

```kotlin
// app/build.gradle.kts
dependencies {
  implementation(libs.coil)
  // Keep Fresco temporarily for gradual migration
  implementation(libs.fresco)
  implementation(libs.fresco.imagepipeline)
}
```

```kotlin
// injection/modules/AppModule.kt
@Provides
@Singleton
fun coilImageLoader(
  context: Context,
  plexConfig: PlexConfig,
  @Named(OKHTTP_CLIENT_MEDIA) okHttpClient: OkHttpClient
): ImageLoader {
  return ImageLoader.Builder(context)
    .okHttpClient(okHttpClient) // Reuse existing OkHttp with Plex auth
    .crossfade(true)
    .respectCacheHeaders(false) // Use our custom caching
    .memoryCachePolicy(CachePolicy.ENABLED)
    .diskCachePolicy(CachePolicy.ENABLED)
    .build()
}
```

**Validation**:
- [ ] Project syncs successfully
- [ ] Coil loads a test image
- [ ] Plex authentication headers included
- [ ] Caching works

---

### Phase 2: Create Coil Binding Adapters (Day 1 - Afternoon, 2-3 hours)
**Risk**: Low

#### Tasks:
- [ ] 2.1. Create Coil binding adapters matching Fresco functionality
- [ ] 2.2. Add rounded image support
- [ ] 2.3. Add placeholder/error handling
- [ ] 2.4. Add cross-fade animations
- [ ] 2.5. Test binding adapters

**Implementation**:

```kotlin
// views/CoilBindingAdapters.kt
@BindingAdapter(value = ["coilSrc", "serverConnected"], requireAll = true)
fun loadImageWithCoil(
  imageView: ImageView,
  src: String?,
  serverConnected: Boolean
) {
  if (src.isNullOrEmpty() || !serverConnected) {
    imageView.load(R.drawable.placeholder_album_art) {
      crossfade(true)
    }
    return
  }

  val config = Injector.get().plexConfig()
  val imageSize = imageView.resources.getDimension(R.dimen.currently_playing_artwork_max_size).toInt()
  val url = config.toServerString("photo/:/transcode?width=$imageSize&height=$imageSize&url=$src")
  
  imageView.load(url) {
    crossfade(true)
    placeholder(R.drawable.placeholder_album_art)
    error(R.drawable.placeholder_album_art)
    // Add transformations if needed
    transformations(RoundedCornersTransformation(16f))
  }
}

@BindingAdapter("coilRoundedSrc")
fun loadRoundedImageWithCoil(
  imageView: ImageView,
  src: String?
) {
  imageView.load(src) {
    crossfade(true)
    transformations(RoundedCornersTransformation(16f))
    placeholder(R.drawable.placeholder_album_art)
    error(R.drawable.placeholder_album_art)
  }
}
```

**Validation**:
- [ ] Images load correctly
- [ ] Rounded corners work
- [ ] Placeholders show
- [ ] Error states work
- [ ] Cross-fade animations smooth

---

### Phase 3: Migrate User Avatars (Day 2 - Morning, 2 hours)
**Risk**: Low (simple migration, single feature)

#### Why First?
- Simplest usage (direct `setImageURI`)
- Small scope (login flow only)
- Good test case for Coil setup

#### Tasks:
- [ ] 3.1. Update `UserListAdapter.kt` to use Coil
- [ ] 3.2. Update `list_item_user.xml` to use ImageView + binding
- [ ] 3.3. Test user selection screen
- [ ] 3.4. Verify circular avatars work

**Implementation**:

```kotlin
// features/login/UserListAdapter.kt
class UserViewHolder private constructor(val binding: ListItemUserBinding) :
  RecyclerView.ViewHolder(binding.root) {
    fun bind(user: PlexUser, clickListener: UserClickListener) {
      binding.user = user
      // OLD: binding.userThumb.setImageURI(user.thumb?.toUri())
      // NEW: Use Coil with binding adapter
      binding.userThumbUrl = user.thumb
      binding.clickListener = clickListener
      binding.executePendingBindings()
    }
}
```

```xml
<!-- list_item_user.xml -->
<!-- OLD: ChronicleDraweeView -->
<!-- NEW: AppCompatImageView with Coil -->
<ImageView
    android:id="@+id/user_thumb"
    android:layout_width="64dp"
    android:layout_height="64dp"
    app:coilRoundedSrc="@{user.thumb}"
    android:contentDescription="@string/user_avatar" />
```

**Validation**:
- [ ] User avatars load
- [ ] Circular shape works
- [ ] Login flow works
- [ ] No crashes

---

### Phase 4: Migrate Now Playing Screen (Day 2 - Afternoon, 3 hours)
**Risk**: Low-Medium (high visibility screen)

#### Tasks:
- [ ] 4.1. Update `fragment_currently_playing.xml`
- [ ] 4.2. Update binding adapters
- [ ] 4.3. Test album artwork loading
- [ ] 4.4. Test aspect ratio preservation
- [ ] 4.5. Test placeholder states

**Implementation**:

```xml
<!-- fragment_currently_playing.xml -->
<ImageView
    android:id="@+id/album_artwork"
    android:layout_width="match_parent"
    android:layout_height="0dp"
    app:coilSrc="@{viewModel.currentTrack.albumArt}"
    app:serverConnected="@{viewModel.serverConnected}"
    android:contentDescription="@string/album_artwork"
    android:scaleType="centerCrop" />
```

**Validation**:
- [ ] Album art loads while playing
- [ ] Artwork updates on track change
- [ ] Placeholder shows when no art
- [ ] Smooth transitions
- [ ] No memory leaks

---

### Phase 5: Migrate Library & Detail Screens (Day 3 - Morning, 3-4 hours)
**Risk**: Medium (many layouts, grid/list views)

#### Tasks:
- [ ] 5.1. Update `grid_item_audiobook.xml`
- [ ] 5.2. Update `list_item_audiobook_with_details.xml`
- [ ] 5.3. Update `fragment_audiobook_details.xml`
- [ ] 5.4. Update `grid_item_collection.xml`
- [ ] 5.5. Update `list_item_collection_with_details.xml`
- [ ] 5.6. Update `list_item_search_result_audiobook.xml`
- [ ] 5.7. Test grid view scrolling performance
- [ ] 5.8. Test list view scrolling performance

**Implementation Pattern**:

```xml
<!-- grid_item_audiobook.xml -->
<ImageView
    android:id="@+id/book_cover"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:coilSrc="@{audiobook.coverUrl}"
    app:serverConnected="@{serverConnected}"
    android:contentDescription="@{@string/book_cover_desc(audiobook.title)}"
    android:scaleType="centerCrop" />
```

**Performance Considerations**:
- Use `RecyclerView.RecycledViewPool` for better performance
- Enable Coil's memory cache
- Use appropriate image sizes (don't load huge images)

**Validation**:
- [ ] Library grid loads smoothly
- [ ] List view scrolls without jank
- [ ] Book details screen loads cover
- [ ] Collection covers load
- [ ] Search results show covers
- [ ] No duplicate loads
- [ ] Memory usage acceptable

---

### Phase 6: Migrate Main Activity (Day 3 - Afternoon, 1 hour)
**Risk**: Low

#### Tasks:
- [ ] 6.1. Update `activity_main.xml`
- [ ] 6.2. Test main screen artwork
- [ ] 6.3. Test mini-player artwork

**Implementation**:

```xml
<!-- activity_main.xml -->
<ImageView
    android:id="@+id/mini_player_artwork"
    android:layout_width="48dp"
    android:layout_height="48dp"
    app:coilSrc="@{viewModel.currentArtwork}"
    app:serverConnected="@{viewModel.connected}"
    android:contentDescription="@string/mini_player_art" />
```

**Validation**:
- [ ] Mini-player artwork shows
- [ ] Updates on track change
- [ ] No lag when changing screens

---

### Phase 7: Remove Fresco & Cleanup (Day 4 - Morning, 2-3 hours)
**Risk**: Low (all migrations complete)

#### Tasks:
- [ ] 7.1. Remove Fresco dependencies
- [ ] 7.2. Delete `ChronicleDraweeView.kt`
- [ ] 7.3. Delete `UrlQueryCacheKey.kt` (custom Fresco cache)
- [ ] 7.4. Remove Fresco binding adapters
- [ ] 7.5. Remove Fresco initialization from Application
- [ ] 7.6. Remove Fresco cache clearing from Settings
- [ ] 7.7. Update cache clearing to use Coil
- [ ] 7.8. Clean build and verify

**Implementation**:

```kotlin
// app/build.gradle.kts
dependencies {
  implementation(libs.coil)
  // ❌ Remove these:
  // implementation(libs.fresco)
  // implementation(libs.fresco.imagepipeline)
}
```

```kotlin
// application/ChronicleApplication.kt
override fun onCreate() {
  // ...
  // ❌ Remove: Fresco.initialize(this, frescoConfig)
  
  // ❌ Remove Glide cache clear too (if not needed)
  // Glide.get(context).clearDiskCache()
}
```

```kotlin
// features/settings/SettingsViewModel.kt
private fun clearImageCache() {
  viewModelScope.launch(Dispatchers.IO) {
    // ❌ OLD: Fresco.getImagePipeline().clearCaches()
    // ✅ NEW:
    Injector.get().imageLoader().diskCache?.clear()
    Injector.get().imageLoader().memoryCache?.clear()
  }
}
```

**Files to Delete**:
- `views/ChronicleDraweeView.kt`
- `views/BindingAdapters.kt` (old Fresco version)
- `util/FrescoExt.kt` (if exists)

**Validation**:
- [ ] Project builds without Fresco
- [ ] No Fresco imports remain
- [ ] Cache clearing works with Coil
- [ ] APK size reduced

---

### Phase 8: Testing & Performance (Day 4 - Afternoon, 3-4 hours)
**Risk**: Medium

#### Comprehensive Test Plan:

**Visual Testing**:
- [ ] 8.1. All images load correctly
- [ ] 8.2. Aspect ratios preserved
- [ ] 8.3. Rounded corners applied
- [ ] 8.4. Placeholders show appropriately
- [ ] 8.5. Error states handled

**Performance Testing**:
- [ ] 8.6. Grid scroll smooth (no jank)
- [ ] 8.7. List scroll smooth
- [ ] 8.8. Memory usage acceptable
- [ ] 8.9. No memory leaks (LeakCanary)
- [ ] 8.10. Network requests efficient

**Functional Testing**:
- [ ] 8.11. Login with user avatar
- [ ] 8.12. Browse library (grid/list)
- [ ] 8.13. View book details
- [ ] 8.14. Play audiobook (artwork updates)
- [ ] 8.15. Search books
- [ ] 8.16. Collections view
- [ ] 8.17. Offline mode (cached images)
- [ ] 8.18. Cache clearing works

**Edge Cases**:
- [ ] 8.19. Missing images (404)
- [ ] 8.20. Network timeout
- [ ] 8.21. Server disconnected
- [ ] 8.22. Very large images
- [ ] 8.23. Rapid scrolling
- [ ] 8.24. Low memory device

**Expected Improvements**:
```
APK Size:
- Fresco: ~3.2 MB
- Coil: ~500 KB
- Savings: ~2.7 MB (85% reduction in image loading library size)

Memory:
- Fresco: Complex cache management
- Coil: More efficient memory cache
- Expected: 10-20% memory improvement

Code:
- Before: ~200 lines (custom view + adapters)
- After: ~100 lines (simple binding adapters)
- Reduction: 50% less code
```

---

### Phase 9: Documentation & PR (Day 4 - End, 1-2 hours)
**Risk**: Low

#### Tasks:
- [ ] 9.1. Update architecture documentation
- [ ] 9.2. Update CONTRIBUTING.md
- [ ] 9.3. Document Coil configuration
- [ ] 9.4. Create migration guide for contributors
- [ ] 9.5. Add before/after metrics to PR
- [ ] 9.6. Create comprehensive PR description

**Documentation to Add**:

```markdown
## Image Loading: Fresco → Coil Migration

We've migrated from Fresco to Coil for image loading.

### Why Coil?
- Modern, Kotlin-first library
- Coroutines-native
- Simpler API
- Better performance
- Smaller APK size (~2.7 MB saved)
- Better maintained

### For Contributors

**Loading Images in XML**:
```xml
<ImageView
    app:coilSrc="@{imageUrl}"
    app:serverConnected="@{connected}" />
```

**Loading Images in Code**:
```kotlin
imageView.load(url) {
    crossfade(true)
    placeholder(R.drawable.placeholder)
    error(R.drawable.error)
}
```

**Custom Transformations**:
```kotlin
imageView.load(url) {
    transformations(RoundedCornersTransformation(16f))
}
```

### Cache Management
```kotlin
// Clear image cache
imageLoader.diskCache?.clear()
imageLoader.memoryCache?.clear()
```

---

## Rollback Plan

### Partial Rollback (Phase 2-7):
- Keep both Coil and Fresco temporarily
- Revert specific layouts back to Fresco
- **Impact**: Larger APK, but functional

### Complete Rollback:
1. Revert all layout changes
2. Restore ChronicleDraweeView
3. Restore Fresco binding adapters
4. Remove Coil dependencies
5. **Time**: < 30 minutes (git revert)

---

## Risk Mitigation

| Risk | Likelihood | Impact | Mitigation |
|------|------------|---------|------------|
| Image loading breaks | Low | High | Incremental migration, test each phase |
| Performance regression | Low | Medium | Benchmark before/after |
| Memory leaks | Low | High | Use LeakCanary during testing |
| Cache issues | Medium | Medium | Thorough cache testing |
| Auth header issues | Low | High | Test Plex auth in Phase 1 |
| Layout issues | Medium | Low | Visual comparison testing |

---

## Success Criteria

### Must Have ✅:
1. [ ] All images load correctly
2. [ ] No build warnings
3. [ ] No performance regression
4. [ ] All features work
5. [ ] Fresco completely removed
6. [ ] APK size reduced
7. [ ] Tests pass

### Should Have ✅:
1. [ ] Performance improved
2. [ ] Memory usage reduced
3. [ ] Code simplified
4. [ ] Documentation updated

### Nice to Have 🎯:
1. [ ] Better image quality
2. [ ] Faster load times
3. [ ] Smoother animations

---

## Open Questions & Clarifications Needed

### 🤔 Question 1: Migration Approach
**Q**: Coil vs Glide - which do you prefer?  
**Context**: 
- Coil: Modern, Kotlin-first, coroutines (RECOMMENDED)
- Glide: Already in dependencies, Java-based, mature

**My Strong Recommendation**: Coil - perfect fit for this Kotlin/coroutines project

---

### 🤔 Question 2: Remove Glide Too?
**Q**: Should we also remove Glide dependency?  
**Context**: Glide is barely used (just cache clearing in one place)  
**Options**:
- A) Remove Glide, use only Coil (cleaner)
- B) Keep Glide for now (less change)

**My Recommendation**: Option A - Remove Glide, one image library is enough

---

### 🤔 Question 3: Testing Depth
**Q**: How thorough should visual testing be?  
**Options**:
- A) Automated screenshot tests (thorough but slow to set up)
- B) Manual testing all screens (recommended)
- C) Spot check key screens (risky)

**My Recommendation**: Option B - Manual testing is sufficient

---

### 🤔 Question 4: Phased Rollout
**Q**: Release all at once or phased rollout?  
**Options**:
- A) Single release (faster)
- B) Beta release first (safer)
- C) Gradual rollout via Play Store (safest)

**My Recommendation**: Option B - Beta test with early users first

---

### 🤔 Question 5: Timeline
**Q**: Is 4 days acceptable?  
**Options**:
- A) Fast track 2-3 days (higher risk)
- B) Standard 4 days (recommended)
- C) Thorough 5-6 days (safest)

**My Recommendation**: Option B - 4 days is good balance

---

## Dependencies

**Depends On**: None - independent task

**Blocks**: 
- Cleaner codebase
- Better maintenance

**Blocked By**: None

**Can be done in parallel with**: C1, C2 (different areas)

---

## Estimated Effort Breakdown

| Phase | Task | Optimistic | Realistic | Pessimistic |
|-------|------|-----------|-----------|-------------|
| 1 | Setup | 1.5h | 2.5h | 3h |
| 2 | Binding Adapters | 1.5h | 2.5h | 3h |
| 3 | User Avatars | 1h | 2h | 3h |
| 4 | Now Playing | 2h | 3h | 4h |
| 5 | Library Screens | 2.5h | 3.5h | 5h |
| 6 | Main Activity | 0.5h | 1h | 1.5h |
| 7 | Fresco Removal | 1.5h | 2.5h | 3h |
| 8 | Testing | 2.5h | 3.5h | 5h |
| 9 | Documentation | 1h | 1.5h | 2h |
| **Total** | | **14h (1.75d)** | **22h (2.75d)** | **29.5h (3.7d)** |

**Recommended**: 4 days (32 hours effort) with buffer for thorough testing

---

## Pre-Migration Checklist

Before starting:

- [ ] Backup current app APK
- [ ] Screenshot all screens with images
- [ ] Measure current APK size
- [ ] Measure current memory usage
- [ ] Test device/emulator ready
- [ ] Plex test server accessible
- [ ] Git working directory clean

---

## Approval Checklist

Please confirm:

- [ ] **Library choice**: Coil approved over Glide?
- [ ] **Remove Glide**: Remove unused Glide dependency?
- [ ] **Timeline**: 4 days acceptable?
- [ ] **Testing**: Manual testing sufficient?
- [ ] **Rollout**: Beta release before production?
- [ ] **Risk tolerance**: Medium risk acceptable?
- [ ] **Open questions resolved**: Answers to questions 1-5

---

## Next Steps After Approval

1. ✅ Create feature branch: `feature/C3-migrate-fresco-to-coil`
2. ✅ Take screenshots of all screens (before)
3. ✅ Measure baseline metrics
4. ✅ Start Phase 1: Add Coil
5. ✅ Progress through phases incrementally
6. ✅ Daily progress updates
7. ✅ Beta test before merging
8. ✅ Compare before/after metrics

---

## Additional Notes

### Why This Matters
- **Maintainability**: Simpler, modern code
- **Performance**: Better memory management
- **APK Size**: ~2.7 MB reduction
- **Developer Experience**: Easier to work with
- **Future-Proof**: Active development, Compose-ready

### What Could Go Wrong
- Layout issues (unlikely, testable)
- Performance issues (unlikely, Coil is fast)
- Cache issues (testable)
- Auth header issues (caught in Phase 1)

### Confidence Level
- **Overall**: 90% confident
- **Coil Setup**: 95% confident
- **Migration**: 90% confident
- **Performance**: 95% confident (Coil is well-tested)

---

**Ready to proceed?** Please review and provide:

1. ✅ **Approval** for Coil migration
2. 📝 **Answers** to the 5 open questions
3. 🎯 **Any concerns** or additional requirements

---

*Created: 2025-11-28*  
*Owner: UI/Frontend Team*  
*Estimated Completion: 2025-12-02*  
*Reviewer: Tech Lead / Android Engineer*

