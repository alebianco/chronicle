---
id: cu-130
title: Ask about downloads when switching library at login
status: To Do
assignee: []
created_date: '2026-09-03'
labels: [R2, ux, downloads]
dependencies: [cu-126]
priority: medium
milestone: m-2
---

## Decision taken, 2026-09-05

The owner chose: **prompt at the login picker, except after a failed re-auth.** Option 3 (warn at
deletion time instead) was considered and not taken — the question belongs where the user makes the
choice, not later when a background pass acts on it.

**The signal already exists, so this is smaller than the task implies.** [[cu-126]] made
`IPlexLoginRepo.chooseLibrary` return `replacedDifferentLibrary`, and `ChooseLibraryViewModel`
already branches on it (`if (!replacedDifferentLibrary) return`) before clearing the catalogue. That
flag is false in *both* cases that must not prompt:

- **A first-ever choice** — `previous == null`, so there are no downloads to ask about. Already
  documented in `chooseLibrary`.
- **A failed re-auth** — the library is unchanged, so `previous.id == plexLibrary.id`. Note
  [[cu-172]] (2026-09-05) makes this path rarer still: `showLogin()` no longer wipes the chosen
  library, so a re-auth usually does not reach this picker at all.

So the prompt slots into `ChooseLibraryViewModel.chooseLibrary` at the point it currently clears the
repositories, gated on the flag that is already computed. **Do not add a second "was this a
re-auth?" signal** — the existing one is sufficient, and a parallel flag would be one more thing to
keep in agreement.

**Reuse the Settings copy verbatim**: `@string/prompt_clear_downloads_allow_retain` — *"Would you
like to keep your downloaded files?"* — so the decision reads identically wherever it is met.

## Promoted from a draft, 2026-09-05 — the factual half checked

The reclamation claim holds: `CachedFileManager.refreshTrackDownloadedStatus` does delete files for
`Audiobook`s no longer in the database, and it runs from `ChronicleApplication` on every launch. So
an orphaned download really is reclaimed, silently, at the next start.

~~The open question is a product one and is left for the owner~~ — **answered above.** It is UI
work, and the re-auth case is excluded by a flag that already exists.

## Description

Split out of [[cu-126]], which fixed the data half: switching to a different library at the login
picker now clears the previous library's cached catalogue, so the app no longer shows a union of
two libraries.

What it does **not** do is ask about downloaded files. Settings' "Current library" prompts
*"Would you like to keep your downloaded files?"* before switching; the login picker does not.

The asymmetry is deliberate for now — deleting a multi-gigabyte download without asking is worse
than leaving it, and the files are eventually reclaimed by the existing orphan pass
(`CachedFileManager`, "deletes files for `Audiobook`s no longer in the database"). But the *same
decision* still gets two different answers depending on which screen the user reached it from, and
that reclamation happens silently, later, as a side effect of a choice nobody was warned about.

## Why it was not done in cu-126

The Settings prompt is `showOptionsMenu(...)` with a bottom-sheet chooser wired into
`SettingsViewModel`. `ChooseLibraryFragment` is an onboarding screen with no such affordance, so
this is UI work rather than a one-line reuse.

## What to work out

1. Whether onboarding should prompt at all, or whether reaching the picker *from a failed re-auth*
   (the [[cu-124]] path) is different from reaching it deliberately. A user who did not intend to
   switch libraries should probably not be asked about deleting downloads.
2. If it prompts: reuse the Settings copy and the same two options, so the decision reads
   identically wherever it is met.
3. Whether the orphan reclamation should warn before deleting a large file regardless of path —
   which would cover this and any future case, and might be the better fix.

Option 3 is worth considering first: it addresses the silence at the point where the deletion
actually happens, rather than at each screen that can lead there.

## Acceptance Criteria

- [ ] A library switch never deletes downloaded files without the user having been asked, from any
      entry point
- [x] ~~Or: the decision is recorded that onboarding deliberately does not ask~~ — not taken; it prompts, gated on `replacedDifferentLibrary`
- [ ] The wording matches Settings, wherever the question is asked
- [ ] Test coverage for the chosen behaviour

## Related

- [[cu-126]] — fixed the catalogue half; this is the consent half it deferred
- [[cu-124]] — a failed re-auth can drop a user into this picker without intending a switch
- [[cu-85]] — the "do not silently un-cache" instinct
