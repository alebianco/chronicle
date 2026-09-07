---
name: chronicle-compose-device-only-defects
description: "Compose migrations ship defects no test can see — tint, clipping, two-colour drawables, and silently dropped controls; always screenshot on a device"
metadata: 
  node_type: memory
  type: project
  originSessionId: dd59db19-e160-45b1-bcb7-a627a38e3c6f
  modified: 2026-09-06T15:30:44.811Z
---

Every screen migrated to Compose in the 2026-09-06 session shipped at least one
defect that **passed the whole test suite**. The semantics tree is correct and only
the pixels are wrong, so `createComposeRule` assertions cannot see any of it.

The four found, all on a device:

- **Icon tint.** Drawables named `_white` carry a *black* fill. `Icon` without an
  explicit `tint` renders them invisible on a dark surface.
- **`Icon` vs `Image`.** `ic_play_button_large_colored` is **two-colour** — accent
  circle plus white glyph — and `Icon` flattens everything to one `tint`, giving a
  bare circle with no triangle. Two-colour drawables need `Image`.
- **Clipping by the View parent.** A `ComposeView` inside `CollapsingToolbarLayout`
  needs `layout_collapseMode="parallax"` and a top margin of one action bar, or the
  first row renders behind the pinned toolbar. The clipping happens outside Compose.
- **Silently dropped controls.** A control living only in the old XML disappears with
  it while its ViewModel plumbing survives, so nothing fails. Lost the player's
  bookmark button and nearly the details screen's series-navigation tap.

**Also:** Material3 typography does not inherit the View style's `android:textAllCaps`,
so section headers silently changed case. Only caught by comparing against a
screenshot taken *before* the migration.

**A fifth, which crashes rather than misrenders.** A `LazyColumn` inside a
`wrap_content` `ComposeView` throws outright — *"Vertically scrollable component was
measured with an infinity maximum height constraints"*. A scrolling Compose body must
be the `CoordinatorLayout`'s **scrolling child**
(`app:layout_behavior="@string/appbar_scrolling_view_behavior"`, `match_parent`), not a
view inside `CollapsingToolbarLayout` — which supersedes the `parallax` advice above
for any body that scrolls itself.

**And one structural lesson.** Extract the shared decision as a **pure function before
forking a renderer**: `Audiobook.progressState()` and `chapterRows(chapters, active)`
exist because two screens render the same thing and must not drift. Doing it first also
gives the behaviour a framework-free test, which is the only kind that fails for the
right reason. Migrating the chapter list this way exposed a real data defect the View
layer had hidden for months — a chapter spanning a track boundary was stored twice,
so a book read "Ch 8 of 10" — because the old adapter was rendering a column that had
been empty since cu-49.

**How to apply:** screenshot every migrated screen in both orientations before
committing, and diff against a pre-migration capture of the same screen. Where a
task is `In Review` pending a device pass (cu-175 was), do that pass on the *old*
screen first — afterwards any difference is ambiguous between the refactor and the
rewrite.

Related: [[chronicle-compose-adopted]], [[chronicle-device-check-catches-wiring]].
