---
id: DRAFT-154
title: "Follow-along mode: a synced transcript on screen"
status: Draft
assignee: []
labels: [R4, differentiator, research-needed]
dependencies: []
priority: low
milestone: m-4
---

## The idea

Owner's idea, 2026-09-04. A **follow-along mode**: the book's text scrolls on screen in time with
the audio, highlighting the current line the way a lyrics view does. Generated **locally**, most
likely by a small speech-recognition model on device, so no audio ever leaves the household.

Filed as a draft rather than a task because the interesting question is not the UI — it is whether
on-device transcription of a 40-hour audiobook is *practical*, and that needs measuring before
anything is designed.

## Why it fits here

- **A genuine differentiator.** No Plex audiobook client does this (RESEARCH_FINDINGS §3 compares
  Prologue, Plexamp, Smart AudioBook Player, Audible, Libby — none has it). It is the kind of thing
  §3.1 calls "one memorable signature element".
- **It is an accessibility feature as much as a nicety.** Reading along supports comprehension for
  a hard-of-hearing listener, a non-native speaker, or anyone in a noisy room — and it pairs with
  cu-47.
- **Local generation is the only route consistent with the project's principles.** A cloud
  transcription service is a proprietary SDK sending the household's library to a third party,
  which principle 7 and decision-9 rule out. If it cannot run on device, the honest answer is that
  the feature does not happen.

## What has to be answered before this is a task

Roughly in the order that would kill it soonest.

1. **Is on-device ASR fast enough to be worth it?** `whisper.cpp` and the `tiny`/`base` models are
   the obvious candidates. The number that matters is the **real-time factor** on the household's
   actual phone and tablet: at 1× a 40-hour book takes 40 hours, which is useless; at 0.1× it is
   four hours of background work per book, which might be acceptable overnight on a charger.
   Measure before designing anything.
2. **What does it cost the battery and the storage?** A transcript is small; the *generation* is
   not. And a model file is tens to hundreds of MB shipped in the APK or downloaded on demand.
3. **Licence.** `whisper.cpp` is MIT and the OpenAI weights are MIT, which is GPLv3-compatible —
   but that must be confirmed per model, not assumed, and a bundled model file has to be
   attributed (principle 3, principle 4).
4. **Is alignment good enough to look right?** Word-level timestamps drifting by a second read as
   broken in a lyrics-style view. Whisper's segment timestamps are coarse; forced alignment
   (e.g. WhisperX-style) is a second stage with its own cost. **A wrong highlight is worse than no
   highlight**, so decide the accuracy bar before building the UI.
5. **Where does the transcript live?** It is derived data, not something a server holds — so a new
   Room table, or a JSON sidecar per book. Note the D8/cu-17 rule: anything not re-derivable from
   the source must be exportable. A transcript *is* re-derivable (slowly), so it may be a cache
   rather than an export, which is a decision to make explicitly.
6. **What happens to a book that is only partly transcribed?** Playback must not wait on it, and
   the UI has to be honest about coverage — the same obligation `FacetList.unknownCount` carries
   for a partial index (cu-24).

## The cheaper alternative to price first

**Some audiobooks ship with a real text.** Where the household owns the ebook, aligning known text
to audio is a much easier problem than transcribing from nothing — that is forced alignment, not
recognition, and it is faster and far more accurate. Worth checking whether that covers enough of
the library to be the primary path, with ASR as the fallback for books with no text. This may turn
out to be the whole feature.

## Explicitly not in scope

- Anything that sends audio off the device.
- Any use of a transcript beyond the household's own listening. Whatever the text is derived from
  is someone's copyrighted work; this is a private accessibility aid for a book the user already
  owns, not a corpus to search, share, or publish.
- Shipping a transcript with an export or a backup, unless research says regenerating one is
  genuinely impractical.

## Related

- **cu-47** (accessibility) — the framing this belongs under as much as "delight".
- **cu-37** (metadata enrichment) — different shape: that enriches *about* a book from external
  sources, this derives *from* the audio locally. Don't merge them.
- RESEARCH_FINDINGS §3.1 — the design brief a follow-along view would have to meet.
