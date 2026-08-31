---
id: cu-78
title: Chapter serialization crashes on a delimiter in the title
status: In Review
assignee: []
created_date: '2026-08-31'
labels: [R1, trust]
dependencies: []
priority: high
milestone: m-1
---

## Description

Found while writing tests for cu-44. `ChapterListConverter` — the Room type converter that
persists every chapter list — is a hand-rolled serializer joining fields with `©` (U+00A9) and
records with `®` (U+00AE). The upstream comment reads "a little yikes but funny".

**A chapter title containing either character makes the decoder throw.** Not corrupt — throw:

- `®` in a title splits one record into two, leaving a record with a single field, so
  `split[1].toLong()` raises `IndexOutOfBoundsException`.
- `©` in a title shifts every field by one, so `"false"` lands where a `Long` is expected and
  `toLong()` raises `NumberFormatException`.

Room surfaces the exception while reading the row, so the user sees **a crash when opening the
book**, not a wrong chapter title. Both cases are pinned by `ChapterListConverterTest`.

### Why this is reachable, not theoretical

Chapter titles come from the Plex server and are arbitrary strings. `©` in particular appears in
real metadata — a rights line or an imported tag ("© 2019 Macmillan Audio") is entirely ordinary
in audiobook chapter data, and `®` shows up in trademarked series names.

Worse, the failure is **persistent**: once the bad row is written, every subsequent read of that
book throws until the row is deleted. Clearing app data or re-syncing that book is the only user
recourse, and neither is discoverable.

### Options

1. **Escape the delimiters** on write and unescape on read. Smallest change, keeps the existing
   column format, needs no migration — existing rows decode unchanged because escaping only
   affects titles that would otherwise break. **Recommended.**
2. **Serialize as JSON** via the Moshi instance already in the graph. Cleaner and self-describing,
   but changes the stored format, so it needs either a migration or a decoder that accepts both.
3. **Normalise titles on ingest**, stripping the delimiters. Loses data silently and does not fix
   rows already written.

Whichever is chosen, the decoder should also **fail soft**: a malformed record should be skipped
with a log rather than propagating out of Room, so one bad chapter cannot make a book unopenable.

### Interaction with cu-49

[[cu-49]] moves chapters into their own table, which would replace this format wholesale. That is
the better end state but is a schema change behind [[cu-71]] and [[cu-13]]. This bug should be
fixed before then — it is a crash, and the fix is small.

## Implementation Notes

Option 1 (escape the delimiters) was taken, plus fail-soft decoding. No migration needed:
escaping only changes titles that would previously have broken, so every existing row decodes
unchanged — pinned by the legacy-record test.

The escape character is U+241B (SYMBOL FOR ESCAPE), chosen because it is not plausible in
audiobook metadata. It is itself escaped first, or unescaping would be ambiguous.

Fail-soft matters as much as the escaping: `toChapterList` now skips a malformed record with a
log instead of letting the exception leave the type converter. Throwing from there is what turned
one bad chapter into an unopenable book, and a row written by some future bug should degrade to
"one chapter missing" rather than "book is gone".

12 tests, verified to bite: removing the escaping fails six of them, removing the fail-soft
handling fails the salvage case.

## Acceptance Criteria

- [x] A chapter title containing `©` or `®` round-trips intact — including both in one title,
      and a title containing the escape character itself
- [x] `ChapterListConverterTest`'s two throwing cases became round-trip assertions
- [x] Rows written by the current format still decode — no migration required
- [x] A malformed record is skipped with a log rather than throwing out of Room
- [x] Verify loop green; coverage rose
