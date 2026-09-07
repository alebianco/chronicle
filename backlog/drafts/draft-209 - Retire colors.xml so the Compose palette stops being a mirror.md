---
id: DRAFT-209
title: "Retire colors.xml so the Compose palette stops being a mirror"
status: Draft
assignee: []
labels: [R3, debt, ui]
dependencies: []
priority: low
milestone: m-3
---

## Why this is a draft and not just the last row of a checklist

The Compose migration's retirement list ends with *"`ChronicleTheme`'s duplication of
`colors.xml`, and `ChronicleThemeTest` with it"*, on the assumption that the XML palette dies with
the last layout.

Audited 2026-09-07, after the last layout went: **it did not.** Zero `res/layout` XML files remain,
yet `colors.xml` is still the authority for three consumers that are not screens:

| consumer | detail |
|---|---|
| 10 drawables | `ic_play_button_large_colored`, `ic_pause_button_large_colored`, `book_cover_missing_placeholder`, `not_listened_overlay`, `ic_not_played_dog_ear`, `option_background_checked` / `_unchecked`, `rounded_rect_form`, `ic_image_white`, `ic_person_white` |
| 3 `res/color` selectors | `chip_background_color`, `chip_same_background_color`, `material_text_input_layout_outline` |
| `styles.xml` → `AppTheme` | the **window theme**, set in `AndroidManifest.xml` and the debug manifest |

So the duplication is not migration residue. It is a Compose mirror of a palette the framework
still owns, and `ChronicleThemeTest` is what stops the two drifting — delete it and every Compose
screen is free to keep an old cyan while every drawable changes. The original reason for the
literals also still holds: a `@Preview` and a Compose UI test render with no Android theme
attached, so `colorResource` there either fails or silently returns a stock Material colour.

**`ColorContrastTest` reads `colors.xml` as its source of truth** for the WCAG-AA floor, including
the recorded measurement behind `textError = #FF8A80` (5.69:1 on `colorPrimary`, against 3.81:1 for
the `#FF4444` it replaced). Whatever happens, that guard must keep measuring the values that
actually ship.

## The actual shape of the work

1. Port the 10 drawables and 3 selectors to Compose (`Icon` tints, `painterResource` with explicit
   colour, or vector composables) — noting the migration already learned that `Icon` flattens a
   two-colour drawable to a silhouette, which is how a play button shipped as a bare circle.
2. Decide what `AppTheme` becomes. `MainActivity` stays an `AppCompatActivity` — decision-22 says
   AppCompat/Material explicitly do **not** retire — so a window theme is still needed for the
   splash background and system bars. This is the part that may simply not be retirable.
3. Move `ColorContrastTest` onto whatever becomes canonical, so the accessibility floor is measured
   against the shipped palette rather than an orphaned file.
4. Only then does `ChronicleColors` stop being a mirror, and `ChronicleThemeTest` stop being needed.

## Also found in the same audit — possibly a cheaper separate task

`styles.xml` is now mostly unreferenced. Live: **`AppTheme`** and **`FilterChip`** (reached via
`AppTheme`'s `chipStyle`). Zero references: `TextAppearance.Body1`, `.Body2`, `.Button`,
`.SectionHeader`, `.RoundedRectInput`, `.SleepTimerCountdown`, `ToolbarTheme`,
`ProgressSliderTooltip`, `Widget.BottomNavigationView`. Referenced only from inside `styles.xml`
itself: `TextAppearance.Title`, `ProgressSliderTextAppearance`, `FilterChipGroup`.

Both `values-land/` files are dead: `currently_playing_seekbar_margin_top` and
`currently_playing_artwork_visibility` have no consumer — only a comment in `PlayerScreen.kt`
recording the decision the latter used to encode.

That deletion is independent of the palette question and much smaller. It may be worth doing first,
as its own task, so this draft is not blocked on the harder half.

## What would settle it

Whether the window theme can stop naming `@color/…` at all. If it cannot — and it probably cannot,
while an `AppCompatActivity` is the host — then the honest outcome is **"decline, and record why"**:
keep the duplication, keep `ChronicleThemeTest` as its guard, and strike the item from the
retirement list rather than leaving it to look like unfinished work.
