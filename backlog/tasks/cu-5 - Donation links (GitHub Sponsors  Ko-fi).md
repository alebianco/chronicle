---
id: cu-5
title: Attribution and credits (donation links struck)
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R0, governance]
dependencies: []
priority: low
milestone: m-0
---

## Description

Per D9: links in README/About — no tiers, no promised perks, no reward language (reclassification risk, CVR §5.2). About screen credits upstream (mattttvaughn) + Epilogue lineage per D12 rule 4.

## Progress (2026-08-30) — attribution done, donation links BLOCKED on owner

### Done: attribution and fork identity

The second acceptance criterion is met, and two factual errors were fixed along the way — both wrong
regardless of what is decided about donations:

- **The app linked to upstream's repo.** The GitHub settings entry pointed at
  `github.com/mattttvaughn/chronicle`, so anyone tapping "Chronicle is open source!" landed on a
  different project. Now points at `github.com/alebianco/chronicle` (this fork's `origin`).
- **The app linked to a subreddit this fork does not own.** `r/ChronicleApp` belongs to the upstream
  project; sending users there for bug reports directs them somewhere that cannot help them. Entry
  removed.
- **Added a Credits entry** in its place (D12 rule 4), shown via the existing bottom-chooser rather
  than a new screen. It credits Matt Vaughn as the original author, notes this fork stays GPLv3,
  acknowledges the fabiogermann Epilogue fork as a reference while explicitly disclaiming its branding,
  and credits the seanap Plex Audiobook Guide community.

No new Activity or layout was added: a full About screen would be DataBinding work that cu-58 has to
convert straight afterwards. The bottom-chooser reuses what exists.

### Donation links: struck, not deferred

Owner decision 2026-08-30, recorded as [[decision-15]]: **no donations and no monetary compensation of
any kind, anywhere** — including never selling the app on any store. This supersedes D9's
"zero-obligation donations only" and withdraws its revisit triggers.

So the first acceptance criterion is **struck rather than met**. There is nothing left to build here:
the correct implementation of "add donation links" is to add none.

Follow-up: the code still contains premium gating that is now provably false advertising in reverse —
it claims playback speed and offline downloads are paid features. See [[cu-60]].

## Acceptance Criteria

- [x] ~~Links live with zero obligations stated~~ — **struck by [[decision-15]]**: no donations, ever
- [x] About screen credits upstream and influences — Credits entry added; fork identity corrected
