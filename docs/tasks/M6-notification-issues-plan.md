# Task M6: Notification Issues Resolution Plan
*Difficulty: Medium*
*Estimated Completion: 2-3 days*  
*Owner: Playback Team*  
*Created: 2025-11-28*  

---

**This improves a visible user-facing feature** that users interact with during every playback session.

---

6. ✅ PR with demo video
5. ✅ Optional improvements if time allows
4. ✅ Test on all Android versions
3. ✅ Implement chapter change updates
2. ✅ Audit current implementation
1. ✅ Create branch: `feature/M6-notification-improvements`

## Next Steps After Approval

---

- [ ] **Optional improvements**: Include or skip?
- [ ] **Test devices**: Have Android 8-14 for testing
- [ ] **Timeline OK**: 2-3 days acceptable
- [ ] **Fix approved**: Can spend time on notifications

## Approval Checklist

---

**Blocked By**: None

**Blocks**: Better UX

**Depends On**: None

## Dependencies

---

| **Total** | **16-20h (2-3 days)** |
| 5. Optimization | 2h |
| 4. Improvements | 4h (optional) |
| 3. Test | 4h |
| 2. Implement | 6h |
| 1. Audit | 4h |
|-------|------|
| Phase | Time |

## Estimated Effort

---

- Conversation notifications
- Bubbles support (optional)
**Android 11+ (API 30)**:

- Larger artwork
- Material You themed notifications
**Android 12+ (API 31)**:

- Already handled in manifest, verify at runtime
- Requires POST_NOTIFICATIONS runtime permission
**Android 13+ (API 33)**:

## Known Android Version Differences

---

3. [ ] Rich metadata
2. [ ] Swipe actions (Android 12+)
1. [ ] Chapter navigation buttons
### Nice to Have 🎯:

3. [ ] Lock screen works well
2. [ ] Artwork loads correctly
1. [ ] Progress bar shows
### Should Have ✅:

5. [ ] No excessive updates
4. [ ] No performance issues
3. [ ] Works on all Android versions
2. [ ] Correct chapter title shown
1. [ ] Notification updates on chapter change
### Must Have ✅:

## Success Criteria

---

```
}
    }
        updateNotification()
        delay(500)  // Debounce rapid changes
    chapterUpdateJob = viewModelScope.launch {
    chapterUpdateJob?.cancel()
private fun scheduleNotificationUpdate() {

private var chapterUpdateJob: Job? = null
```kotlin
**Debounce Chapter Changes**:

```
}
    showNotification(getCurrentChapter())
    // Update notification
    
    lastNotificationUpdate = now
    }
        return  // Skip if too frequent
    if (now - lastNotificationUpdate < MIN_UPDATE_INTERVAL) {
    val now = System.currentTimeMillis()
private fun updateNotification() {

private const val MIN_UPDATE_INTERVAL = 1000L  // 1 second
private var lastNotificationUpdate = 0L
```kotlin

**Avoid Excessive Updates**:

### Phase 5: Performance Optimization (2 hours)

---

```
}
    }
        // ... other actions
        )
            pendingIntent
            "Next Chapter",
            R.drawable.ic_skip_next_chapter,
        ACTION_NEXT_CHAPTER -> NotificationCompat.Action(
        )
            pendingIntent
            "Previous Chapter",
            R.drawable.ic_skip_previous_chapter,
        ACTION_PREVIOUS_CHAPTER -> NotificationCompat.Action(
    return when (action) {
    
    )
        PendingIntent.FLAG_IMMUTABLE
        intent,
        0, 
        this, 
    val pendingIntent = PendingIntent.getService(
    }
        this.action = action
    val intent = Intent(this, MediaPlayerService::class.java).apply {
private fun createAction(action: String): NotificationCompat.Action {

const val ACTION_NEXT_CHAPTER = "next_chapter"
const val ACTION_PREVIOUS_CHAPTER = "previous_chapter"
```kotlin
**Add Custom Actions**:

```
)
    loadArtworkAsync(currentBook, chapter)
notification.setLargeIcon(
// Better artwork loading

)
    false
    currentPosition.toInt(),
    totalDuration.toInt(),
notification.setProgress(
// Show progress bar

notification.addAction(createAction(ACTION_NEXT_CHAPTER))
notification.addAction(createAction(ACTION_PREVIOUS_CHAPTER))
// Add chapter navigation buttons
```kotlin

**Enhanced Notification**:

### Phase 4: Additional Improvements (Optional, 4 hours)

---

- [ ] Android 13+ notification permission
- [ ] Notification expanded/collapsed
- [ ] Lock screen notification
- [ ] Background playback
- [ ] Notification action while chapter changing
- [ ] Manual seek to different chapter
- [ ] Rapid chapter changes
- [ ] Chapter change during playback
**Test Scenarios**:

| Android 14 (API 34) | targetSdk |
| Android 13 (API 33) | POST_NOTIFICATIONS |
| Android 12 (API 31) | Material You |
| Android 11 (API 30) | Notification changes |
| Android 8.1 (API 27) | minSdk |
|-----------------|------|
| Android Version | Test |

**Test Matrix**:

### Phase 3: Test All Android Versions (4 hours)

---

```
}
    }
        }
            delay(5000)  // Check every 5 seconds
            updateNotification()
        while (isPlaying) {
    viewModelScope.launch {
private fun startNotificationUpdates() {
// Periodic updates during playback

}
    }
        updateNotification()  // Check if chapter changed
    ) {
        reason: Int
        newPosition: Player.PositionInfo,
        oldPosition: Player.PositionInfo,
    override fun onPositionDiscontinuity(
private val playerListener = object : Player.Listener {
// In player listener
```kotlin

**Update on Position Changes**:

```
}
    }
        startForeground(NOTIFICATION_ID, notification)
        
            .build()
            .addAction(createAction(ACTION_SKIP_FORWARD))
            .addAction(createAction(ACTION_PLAY_PAUSE))
            .addAction(createAction(ACTION_SKIP_BACKWARD))
            )
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(mediaSession.sessionToken)
                androidx.media.app.NotificationCompat.MediaStyle()
            .setStyle(
            .setLargeIcon(loadArtwork(currentBook))
            .setSmallIcon(R.drawable.ic_notification)
            .setSubText("${formatTime(player.currentPosition)} / ${formatTime(currentBook.duration)}")
            .setContentText(chapter?.title ?: currentBook.author)  // Show chapter
            .setContentTitle(currentBook.title)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
    private fun showNotification(chapter: Chapter?) {
    
    }
        }
            currentPosition < chapter.endTimeOffset
            currentPosition >= chapter.startTimeOffset &&
        return chapters.firstOrNull { chapter ->
        val currentPosition = player.currentPosition
    private fun getCurrentChapter(): Chapter? {
    
    }
        }
            showNotification(chapter)
            currentChapter = chapter
        if (chapter != currentChapter) {
        // Only update if chapter changed
        
        val chapter = getCurrentChapter()
    private fun updateNotification() {
    
    private var currentChapter: Chapter? = null
    
class MediaPlayerService : MediaBrowserService() {
```kotlin

**Listen for Chapter Changes**:

### Phase 2: Implement Chapter Change Updates (6 hours)

---

```
- [ ] Close button works
- [ ] Skip buttons work
- [ ] Play/Pause button works
### Actions

- [ ] ❌ Does NOT update on chapter change
- [ ] Updates on seek
- [ ] Updates on track change
- [ ] Updates on play/pause
### Updates

- [ ] Shows album art
- [ ] Shows correct author
- [ ] Shows correct book title
- [ ] Notification shows when playing
### Basic Display

## Notification Testing
```markdown
**Test Current Behavior**:

- What data is shown?
- What triggers updates?
- When is it updated?
- Where is notification built?
**Current Implementation Check**:

```
grep -r "MediaStyle" app/src/main --include="*.kt"
grep -r "NotificationCompat" app/src/main --include="*.kt"
grep -r "Notification" app/src/main/java/*/features/player --include="*.kt"
```bash
**Find Notification Code**:

### Phase 1: Audit Current Notification (4 hours)

## Implementation Plan

---

- Missing chapter navigation in notification
- Looks unpolished
- Confusion about playback state
- Poor user experience
**Impact**:

- May confuse users about current position
- User sees outdated chapter info
- Notification NOT updated on chapter change
- Notification shows current track/book
**Current State**:

TODO mentions notification not updated when chapter changes, causing stale information to display during playback.

## Problem Statement

---

**Status**: Planning - Awaiting Approval
**Created**: 2025-11-28  
**Priority**: 🟡 Medium (UX)  
**Task ID**: M6  


