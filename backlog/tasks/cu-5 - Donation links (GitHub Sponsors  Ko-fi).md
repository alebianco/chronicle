---
id: cu-5
title: Donation links (GitHub Sponsors / Ko-fi)
status: In Progress
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

### Blocked: donation links

The first acceptance criterion cannot be met without owner input. Three open questions:

1. **The URLs.** A GitHub Sponsors handle and/or Ko-fi page. These must not be guessed — a wrong
   donation link in a shipped app sends real money to a stranger.
2. **Whether to ship one at all.** D9 permits "zero-obligation donations only". Deciding to ship *no*
   donation link is a legitimate reading, in which case this becomes docs-only and the criterion should
   be struck rather than met.
3. **Placement.** Settings entry (consistent with the rest), README only, or both.

When the URLs arrive the remaining work is small: one `PreferenceModel` beside Credits plus a README
line, with no tiers, perks, or reward language per D9 / CVR §5.2.

## Acceptance Criteria

- [ ] Links live with zero obligations stated — **blocked: needs donation URLs from owner, or a decision not to ship any**
- [x] About screen credits upstream and influences — Credits entry added; fork identity corrected
