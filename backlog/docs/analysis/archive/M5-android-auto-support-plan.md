# Task M5: Android Auto Support Completion Resolution Plan

> **Archived 2026-08-31.** Superseded by [[cu-89]], which records what was actually verified in
> the code (service type, session activation, audio focus, MediaStyle, caller allowlist) and what
> the real symptom is — another app holding the media card while Chronicle plays. This file's
> premise ("unclear what works") is answered there, it contains no findings cu-89 lacks, and its
> code blocks are corrupted (lines out of order).

**Task ID**: M5  
**Priority**: 🟡 Medium (Feature)  
**Created**: 2025-11-28  
**Status**: Planning - Awaiting Approval

---

## Problem Statement

README states Android Auto is "feature flagged, but should be functional" - unclear what works, what doesn't, and whether the feature flag should be removed.

**Current State**:
- Android Auto partially implemented
- Behind feature flag (unclear why)
- Untested or minimally tested
- No documentation of known issues
- Unknown: voice commands, browsing, playback control

**Impact**:
- Users can't use Android Auto (if flagged off)
- Unclear if it actually works
- Support burden (users asking about it)
- Missing feature differentiation

---

## Implementation Plan

### Phase 1: Audit Current State (1 day)

**Find Auto Implementation**:
```bash
grep -r "AUTO" app/src/main --include="*.kt"
grep -r "automotive" app/src/main --include="*.xml"
grep -r "MediaBrowserService" app/src/main --include="*.kt"
```

**Check Feature Flag**:
```bash
grep -r "FEATURE.*AUTO" app/src/main --include="*.kt"
grep -r "BuildConfig.*AUTO" app/src/main --include="*.kt"
```

**Test Checklist**:
```markdown
## Android Auto Testing

### Connection
- [ ] App appears in Android Auto
- [ ] Library browsable
- [ ] Books display correctly

### Playback
- [ ] Play book from Auto
- [ ] Pause/resume works
- [ ] Skip forward/backward works
- [ ] Progress updates
- [ ] Artwork displays

### Navigation
- [ ] Browse by author
- [ ] Browse by recent
- [ ] Search works
- [ ] Navigate back works

### Voice Commands
- [ ] "Play [book name]"
- [ ] "Pause"
- [ ] "Resume"
- [ ] "Skip forward"
- [ ] "Skip backward"

### Edge Cases
- [ ] Connection lost
- [ ] Phone call interruption
- [ ] Switch to another app
- [ ] Return to Chronicle
```

---

### Phase 2: Fix Issues Found (1 week)

**Common Android Auto Issues**:

1. **MediaBrowserService**:
```kotlin
class MediaPlayerService : MediaBrowserService() {
    
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        // Verify client is Android Auto
        return if (isAutoClient(clientPackageName)) {
            BrowserRoot(MEDIA_ROOT_ID, null)
        } else {
            null
        }
    }
    
    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        // Provide browsable content
        result.sendResult(getMediaItems(parentId))
    }
}
```

2. **Media Metadata**:
```kotlin
// Ensure complete metadata for Auto
val metadata = MediaMetadataCompat.Builder()
    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, book.title)
    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, book.author)
    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, book.title)
    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, book.artworkUrl)
    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, book.duration)
    .build()
```

3. **Playback Actions**:
```kotlin
val stateBuilder = PlaybackStateCompat.Builder()
    .setActions(
        PlaybackStateCompat.ACTION_PLAY or
        PlaybackStateCompat.ACTION_PAUSE or
        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
        PlaybackStateCompat.ACTION_SEEK_TO or
        PlaybackStateCompat.ACTION_FAST_FORWARD or
        PlaybackStateCompat.ACTION_REWIND
    )
```

---

### Phase 3: Test Thoroughly (3 days)

**Test Setup**:
1. Android Auto Desktop Head Unit (DHU)
2. Real car with Android Auto
3. Various Android versions

**Test Scenarios**:
- Cold start from Auto
- Warm start (app already running)
- Background/foreground transitions
- Long playback sessions
- Network issues
- Library changes while connected

---

### Phase 4: Documentation (1 day)

**Add to README**:
```markdown
## Android Auto Support

Chronicle fully supports Android Auto:
- Browse your Plex audiobook library
- Control playback
- Voice commands
- Artwork and metadata display

### Setup
1. Connect phone to Android Auto
2. Open Chronicle from Auto home screen
3. Browse and play audiobooks

### Supported Commands
- "Play [book name]"
- "Pause/Resume"
- "Skip forward/backward"

### Known Limitations
- [List any limitations]
```

---

### Phase 5: Remove Feature Flag (1 hour)

**If testing successful**:
```kotlin
// Remove feature flag checks
// Before
if (BuildConfig.FEATURE_AUTO_ENABLED) {
    // Auto functionality
}

// After
// Just enable it
// Auto functionality
```

---

## Success Criteria

- [ ] Android Auto fully tested
- [ ] All features work
- [ ] Voice commands work
- [ ] Documentation complete
- [ ] Feature flag removed (if stable)
- [ ] Known issues documented

---

## Estimated Effort

**Total**: 1-2 weeks

---

## Dependencies

**Requires**: Car with Android Auto or DHU for testing

---

*Created: 2025-11-28*  
*Owner: Features Team*  
*Estimated Completion: 1-2 weeks*

