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

## Promoted from a draft, 2026-09-05 — the factual half checked

The reclamation claim holds: `CachedFileManager.refreshTrackDownloadedStatus` does delete files for
`Audiobook`s no longer in the database, and it runs from `ChronicleApplication` on every launch. So
an orphaned download really is reclaimed, silently, at the next start.

**The open question is a product one and is left for the owner**: should the onboarding picker
prompt at all? The draft's own first item asks it, and the answer decides whether this is UI work or
a won't-do. Reaching that screen from a *failed re-auth* is the case that makes "always prompt" feel
wrong — the user did not choose to switch anything.

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
- [ ] Or: the decision is recorded that onboarding deliberately does not ask, with the reasoning
- [ ] The wording matches Settings, wherever the question is asked
- [ ] Test coverage for the chosen behaviour

## Related

- [[cu-126]] — fixed the catalogue half; this is the consent half it deferred
- [[cu-124]] — a failed re-auth can drop a user into this picker without intending a switch
- [[cu-85]] — the "do not silently un-cache" instinct
