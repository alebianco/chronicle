---
id: decision-15
title: No monetization of any kind, ever
date: '2026-08-30'
status: accepted
supersedes: decision-9
---

## Context

Owner decision, 2026-08-30, given directly when asked to supply donation URLs for cu-5.

[[decision-9]] left three doors open: zero-obligation donations (GitHub Sponsors / Ko-fi), dormant IAP
plumbing, and an explicit revisit path to a paid Play listing if four conditions were all met. The
owner has closed all of them.

## Decision

**No monetary compensation flows to this project from any source, in any form.** Specifically:

- **No donations.** No GitHub Sponsors, Ko-fi, Liberapay, Patreon, crypto address, or "buy me a coffee"
  link — not in the app, not in the README, not in release notes, not in the repo metadata.
- **No paid distribution.** The app is never sold, on Google Play or any other store. No paid tier, no
  one-time unlock, no subscription.
- **No in-app purchases.** The dormant IAP plumbing is never activated.
- **No indirect monetization.** No ads, no analytics-for-revenue, no sponsorships, no affiliate links,
  no paid placement, no "pro" branding.
- **No revisit triggers.** [[decision-9]]'s conditional path to a EUR 5.99–7.99 unlock is withdrawn.
  This is not "not yet" — it is a permanent position, like the DRM won't-do in [[decision-14]].

The project is a household tool released as a gift under GPLv3. That is the whole model.

## Consequences

- **cu-5's donation criterion is struck, not deferred.** Its attribution half (Credits entry, fork
  identity) stands as delivered.
- The premium gating still present in the code becomes dead weight to remove: `prefsRepo.isPremium`
  guards in `CurrentlyPlayingViewModel` (playback speed) and `AudiobookDetailsViewModel` (offline
  downloads), the "upgrade to premium" Settings entry, `ChronicleBillingManager`, the Google-IAP
  dependency, and the associated strings. Note `defaultIsPremium = true` already unlocks everything in
  practice, so removal is a simplification with no behavioural change — but the code currently *claims*
  features are paid, which is now false. Tracked as a follow-up task.
- Removing the billing dependency also drops a Google proprietary SDK, which [[decision-12]] rule 7
  discourages anyway.
- Zero fiscal, tax and compliance overhead permanently — no P.IVA question, no income to declare, no
  store account obligations.
- Anyone may still fork and sell under GPLv3; that is their right and not this project's concern.

**Removing billing/IAP code remains owner-sign-off territory per CLAUDE.md.** This decision authorises
the direction; the removal still lands as its own reviewed task.
