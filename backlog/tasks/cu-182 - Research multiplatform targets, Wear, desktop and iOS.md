---
id: cu-182
title: "Research multiplatform targets: Wear, desktop and iOS"
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R4
  - architecture
  - research
milestone: m-4
dependencies:
  - cu-181
priority: low
---

## Description

Research-only. **Produces an ADR, not code**, and exists so the question is answered from
measurement rather than re-litigated each time a library is mentioned.

## What was already measured (2026-09-06)

Portability of the 32,508 lines in `app/src/main`:

| | lines | |
|---|---:|---|
| pure Kotlin (portable today) | 7,710 | 23.7% |
| Android framework | 24,122 | 74.2% |
| Room/entity only | 676 | 2.1% |

The Android-bound three quarters, by what it touches:

| | lines | |
|---|---:|---|
| UI (views, fragments) | 12,790 | 53.0% |
| other platform (Context, Uri, prefs) | 3,957 | 16.4% |
| WorkManager | 3,324 | 13.8% |
| media/playback (ExoPlayer, MediaSession) | 2,431 | 10.1% |
| lifecycle/ViewModel | 1,620 | 6.7% |

**The conclusion this points to, to be confirmed or overturned:** the ~24% that is portable is
already the best-tested code in the tree (`data/model` is the highest-coverage package), so KMP
would buy sharing where sharing is least needed. The unportable part — a background media service,
audio focus, lock-screen transport, offline downloads, Android Auto — *is* the app, and iOS has no
shared abstraction for it (AVPlayer, `MPNowPlayingInfoCenter`); it gets written twice.

## Wear OS is the separate, much stronger case

Wear **is** Android: same Media3, same Room, same WorkManager. A Wear companion is a module in this
repo sharing everything but the UI, needing **no KMP, no Koin, no Decompose**. It should be assessed
on its own merits and not bundled with the desktop/iOS question — and it is the one target with a
plausible household use (controlling playback without the phone).

## Questions to answer

1. **Wear OS** — what would a companion actually share? Does Media3 session control across devices
   (`MediaBrowser` to a phone-hosted session) do what a household would want?
2. **KMP for the ~24%** — cost of `commonMain` extraction against the benefit, given that code is
   already well tested. Note cu-181 may move ViewModels toward portability; re-measure after it.
3. **iOS / desktop** — what a second playback engine really costs, honestly stated.
4. **Decompose** — its UI layer is Compose Multiplatform, so it depends entirely on cu-181's
   outcome. Assess only if cu-181 went ahead.
5. **Koin** — the usual KMP-driven reason to leave Dagger. Note the prior assessment: Koin trades
   Dagger's compile-time graph validation for runtime resolution, which is a downgrade for an
   agent-maintained repo whose safety net is `verify.sh`. Revisit **only** if KMP is actually adopted.

## Acceptance Criteria

- [ ] Re-measure the portability split (cu-181 will have changed it) and record the numbers
- [ ] An ADR in `backlog/decisions/` per target — Wear, desktop, iOS — each with an explicit
      go / no-go and its reasoning
- [ ] Wear assessed **independently** of KMP, since it needs none of it
- [ ] If any target is a go, follow-up implementation tasks filed; if not, the ADR records why so
      the question is not reopened without new evidence

## Notes

Depends on cu-181 because a Compose migration changes the portable fraction materially — Compose
Multiplatform is the only route by which the 53% that is UI could ever be shared. Researching
multiplatform before that lands would measure a stack we are leaving.
