---
id: cu-227
title: "Delete the dead styles, then decide whether colors.xml can ever be retired"
status: In Review
assignee: []
created_date: '2026-09-07'
updated_date: '2026-09-08'
labels:
  - R2
  - debt
  - ui
milestone: m-2
dependencies: []
priority: low
---

## Why this is two halves, not one task

Promoted from draft-209. The Compose migration's retirement list ends with *"`ChronicleTheme`'s
duplication of `colors.xml`, and `ChronicleThemeTest` with it"*, assuming the XML palette dies with
the last layout. Audited after the last layout went: **it did not**, and it probably cannot. But a
genuinely dead pile of `styles.xml` sits next to that question and can go regardless.

Doing the cheap half first means the hard half is not blocking an easy deletion — the draft's own
recommendation.

## Half one — the dead styles (do this first, independent)

Verified 2026-09-08 by grep across `app/src` for `.kt` and `.xml`, excluding `styles.xml` itself.

**Zero references:** `TextAppearance.Body1`, `.Body2`, `.Button`, `.SectionHeader`,
`.RoundedRectInput`, `.SleepTimerCountdown`, `ToolbarTheme`, `ProgressSliderTooltip`,
`Widget.BottomNavigationView`.

**Referenced only from inside `styles.xml`:** `TextAppearance.Title`,
`ProgressSliderTextAppearance`, `FilterChipGroup`.

**Both `values-land/` files are dead:** `currently_playing_seekbar_margin_top` and
`currently_playing_artwork_visibility` have no consumer — only a comment in `PlayerScreen.kt`
recording the decision the latter used to encode.

Also found: the three `res/color` selectors (`chip_background_color`, `chip_same_background_color`,
`material_text_input_layout_outline`) have **no Kotlin consumer at all** — they are reachable only
through `AppTheme`'s `chipStyle`/`chipGroupStyle`, which no Compose screen uses. They may fall with
`FilterChip`/`FilterChipGroup`, which would make this half bigger than draft-209 estimated. Confirm
before deleting: `chipStyle` on `AppTheme` is inherited by any Material view, and `MainActivity` is
still an `AppCompatActivity`.

## Half two — the palette question

`colors.xml` is still the authority for three consumers that are **not screens**:

| consumer | detail |
|---|---|
| 10 drawables | `ic_play_button_large_colored`, `ic_pause_button_large_colored`, `book_cover_missing_placeholder`, `not_listened_overlay`, `ic_not_played_dog_ear`, `option_background_checked` / `_unchecked`, `rounded_rect_form`, `ic_image_white`, `ic_person_white` |
| 3 `res/color` selectors | as above |
| `styles.xml` → `AppTheme` | the **window theme**, set in `AndroidManifest.xml` and the debug manifest |

So the duplication is **not migration residue**. It is a Compose mirror of a palette the framework
still owns, and `ChronicleThemeTest` is what stops the two drifting — delete it and every Compose
screen is free to keep an old cyan while every drawable changes. The original reason for the Kotlin
literals also still holds: a `@Preview` and a Compose UI test render with no Android theme attached,
so `colorResource` there either fails or silently returns a stock Material colour.

**The likely honest outcome is "decline, and record why."** `AppTheme` names `@color/colorPrimary`,
`colorPrimaryDark` and `colorAccent` directly (verified 2026-09-08), and decision-22 states
AppCompat and Material explicitly do **not** retire — `MainActivity` stays an `AppCompatActivity`
for theming, day/night and `ComposeView` hosting. While that holds, a window theme is required and
must name colours in XML. If so, the right result is to **strike the item from the retirement list**
rather than leave it looking like unfinished work.

**`ColorContrastTest` reads `colors.xml` as its source of truth** for the WCAG-AA floor, including
the recorded measurement behind `textError = #FF8A80` (5.69:1 on `colorPrimary`, against 3.81:1 for
the `#FF4444` it replaced). Whatever is decided, that guard must keep measuring the values that
actually ship.

## Acceptance Criteria

**Half one — done 2026-09-08**

- [x] Every style with zero references deleted, and the `values-land/` pair with them —
      `styles.xml` goes from **15 styles to 1**
- [x] Whether the three `res/color` selectors and `FilterChip`/`FilterChipGroup` are genuinely dead
      is **established rather than assumed** — established, and they are dead; see below
- [x] `./verify.sh` green
- [x] The app **launched on a device** — installed 2026-09-08 on the tablet (versionName
      `0.55.0-debug`, `lastUpdateTime` matching the install), launched, `topResumedActivity`
      confirmed, and logcat clean: no `Resources$NotFoundException`, no `InflateException`, no
      fatal. The window theme resolves with `chipStyle`/`chipGroupStyle` gone
- [x] Both orientations screenshotted — home in portrait and landscape, settings and a pushed
      sub-screen in landscape. **Nothing visible changed**, which is the expected result: the
      accent cyan, section headers, the text-input outline and the monospace list all render as
      before, including on the screen whose `material_text_input_layout_outline` selector was
      deleted

**Half two**

- [ ] A recorded adopt/decline on whether `colors.xml` can be retired, with the window-theme
      constraint stated either way
- [ ] If declined: the retirement-list item struck, and `ChronicleThemeTest` documented as a
      **permanent** drift guard rather than transitional scaffolding
- [ ] `ColorContrastTest` measures whatever ends up canonical
- [ ] If any drawable is ported, the known trap is respected: `Icon` flattens a two-colour drawable
      to a silhouette, which is how a play button once shipped as a bare circle

## Notes

Draft-209 was written expecting a deletion and found a constraint instead. Promoted as-is because
"this cannot be retired, here is why" is a **successful** result under cu-194's rule — the same
standard that made cu-220's decline a valid outcome. What would be wrong is leaving the retirement
list implying work that nobody can do.

## Half one result (2026-09-08)

**`styles.xml` went from 15 styles to 1.** Only `AppTheme` survives, and its own comment now records
why it cannot go: `MainActivity` is an `AppCompatActivity` and `AndroidManifest.xml` names the theme
for the window background before any composable exists.

**The chip chain was deader than the draft thought, and that was worth checking rather than
assuming.** `FilterChip` showed 6 external references — but every one is
`androidx.compose.material3.FilterChip`, a different widget that takes no theme attribute from XML.
So `AppTheme`'s `chipStyle` / `chipGroupStyle`, the `FilterChip` / `FilterChipGroup` styles and all
three `res/color` selectors went together. `views/ChipGroupExt.kt` went with them — a Material
`ChipGroup` extension with **zero callers**, left behind by the Compose migration.

**One style the draft's audit missed:** `TextAppearance.Subtitle.Settings`. Its only reference is a
*comment* in `SettingsScreen.kt` recording that the View style uppercased the text — the comment is
kept, the style is gone.

Both `values-land/` files went, and with them the two now-orphaned base entries in `values/dimens.xml`
and `values/integers.xml`. `res/values-land/` and `res/color/` are both empty and no longer exist.

**`FrameworkFreeCoreTest` caught the `ChipGroupExt.kt` deletion**, and correctly — its list is
committed rather than computed precisely so that a file leaving is visible in a diff and carries a
reason, and it asserts on *missing* files as well as impure ones. The entry was removed with a
comment saying it was deleted rather than demoted. A guard behaving exactly as designed, and a
useful reminder that "delete a dead file" is not always a free action here.

**Release APK: 7,363,728 → 7,309,165 bytes, −54,563.** A real saving, unlike the dependency removal
in cu-228 which was byte-for-byte identical.

**Half two — the palette question — is untouched and still open.** Nothing here changed `colors.xml`,
`ChronicleColors` or `ChronicleThemeTest`. The window-theme constraint that makes the answer probably
"decline" is now written into `styles.xml` itself, where the next reader will find it.
