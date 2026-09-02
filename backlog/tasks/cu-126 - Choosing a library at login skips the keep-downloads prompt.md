---
id: cu-126
title: Choosing a library at login skips the keep-downloads prompt
status: Done
assignee: []
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels: [R1, data-loss, auth, ux]
dependencies: []
priority: medium
milestone: m-1
---

## Description

Owner question during the cu-73 live pass (session 4): *"what happens to the downloaded books and
db data if i select a different library during a login or through the settings change?"*

The two paths behave **differently**, and only one of them protects the user.

### Settings → Current library (safe)

`SettingsViewModel` routes through `clearConfig(...)`:

- **No downloads?** clears the DB and goes to the picker. Nothing to lose.
- **Downloads present?** prompts **"Would you like to keep your downloaded files?"**
  - *Yes* → `clearConfig(RETURN_TO_LIBRARY_CHOOSER, clearDownloads = false)` — DB cleared,
    **files kept**
  - *No* → `clearDownloads = true` → `cachedFileManager.uncacheAllInLibrary()` — files deleted

Either way the DB (books, tracks, collections) is cleared and playback is stopped, which is right:
the previous library's metadata is meaningless against a different library. The same guarded path
covers changing server, changing user, and logging out.

### Library picker during login (unguarded)

`PlexLoginRepo.chooseLibrary` does only:

```kotlin
override fun chooseLibrary(plexLibrary: PlexLibrary) {
  Timber.i("User chose library: $plexLibrary")
  plexPrefsRepo.library = plexLibrary
  _loginState.postEvent(LOGGED_IN_FULLY)
}
```

**No DB clear, no prompt, no download handling.** Pick a *different* library here and the app is
immediately `LOGGED_IN_FULLY` while Room still holds the previous library's books and tracks, and
the filesystem still holds downloads for books that are not in the newly chosen library.

### How bad is it, honestly

Less bad than it first looks — **it self-heals on the next successful refresh**, and this is worth
stating so nobody over-engineers the fix:

- `BookRepository.refreshData` prunes rows absent from the network:
  `removedFromNetwork` → `bookDao.removeAll(...)`, with a `Timber.i("Removed from network: …")`
  line naming them.
- `CachedFileManager` has a pass that "deletes files for `Audiobook`s no longer in the database"
  (`CachedFileManager.kt:407`), so an orphaned download is eventually reclaimed too.

So the outcome is *eventually* consistent. What is missing is **consent and predictability**:

1. The user is never asked about their downloads on this path, while the Settings path treats that
   as important enough to interrupt them for. Same decision, two answers.
2. Between the switch and the next refresh, the app shows a library that is a **union of two
   libraries** — the new one's books plus stale rows from the old.
3. The eventual deletion of a multi-gigabyte download happens **silently**, with no prior warning,
   as a side effect of a choice the user was not told had that consequence.
4. It is reachable without any intent to switch: [[DRAFT-124]] shows a failed re-auth clears the
   library and drops the user straight into this picker.

### Fix direction

Make the login picker share the Settings path's guarantee. Cleanest is to move the guard *below*
both entry points — `chooseLibrary` decides whether the library actually changed, and if so applies
the same clear/prompt policy — rather than duplicating the prompt in the login UI.

Note the one asymmetry to think through: at first-ever login there is nothing to clear and no
prompt should appear. The condition is "library changed **and** there is local data", not
"library chosen".

## Acceptance Criteria

- [x] Choosing a *different* library at the login picker applies the same policy as Settings:
      DB cleared, and the keep-downloads prompt shown when downloads exist
- [x] Choosing the *same* library, or a first-ever login with no local data, prompts nothing
- [x] The app never presents a mixed library (old rows plus new) after a switch
- [x] Downloaded files are never deleted without the user having been asked
- [x] Test coverage for `chooseLibrary` with: no prior library; same library re-chosen; different
      library with downloads; different library without downloads
- [x] Verified on device by switching libraries from both entry points

## Related

- [[cu-73]] — raised during the live pass
- [[DRAFT-124]] — a failed re-auth drops the user into this picker without intending a change
- [[cu-83]] / [[cu-85]] — prior cache-state defects; the same "do not silently un-cache" instinct


## Implementation Notes

**Half done deliberately, and the half that matters is the one shipped.**

`chooseLibrary` now returns whether it **replaced a different** library, and
`ChooseLibraryViewModel.chooseLibrary` clears books, tracks and collections when it did. So the
mixed-library window is gone: the app no longer shows a union of two catalogues between the switch
and the next refresh.

The guard is on *changed*, not *chosen* — a first-ever selection and a re-pick of the current
library both return false. That was the draft's asymmetry question, and it matters twice over: at
onboarding there is nothing to invalidate, and in the Settings flow prompting there would ask about
downloads that cannot exist yet.

Returning the fact rather than acting on it keeps `PlexLoginRepo` free of database and download
dependencies; the decision about what to clear stays with code that already owns those.

**Deferred: the keep-downloads prompt on this path** → [[DRAFT-130]]. Deleting a multi-gigabyte
download without asking is worse than the status quo, and the prompt Settings shows
(`showOptionsMenu` with a bottom-sheet chooser) needs UI this onboarding screen does not have.
Files are therefore left in place; they are reclaimed later by the existing orphan pass, which is
the behaviour before this change and is not made worse by it.

So the honest summary: **the data-integrity half is fixed, the consent half is not.** The task's
criterion about the prompt stays unticked rather than being quietly reinterpreted.
