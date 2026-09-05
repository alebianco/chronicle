---
id: DRAFT-171
title: Crash reporting behind per-report confirmation
status: Draft
assignee: []
labels: [trust, feature]
dependencies: []
priority: low
---

## Description

Owner decision 2026-09-05, recorded as [[decision-20]]: crash reporting **only after a user prompt
and confirmation**.

The chosen shape is **opt-in once, but every individual report still needs a tap**, with the payload
viewable before sending. Consent to the *feature* is deliberately not consent to a *standing upload
channel* — an "enable once, upload silently" design is exactly the telemetry shape decision-20 bars,
and the distinction is the whole point of the decision.

Accepted costs, already argued in the ADR: a crash loop can nag, and crashes the user cannot be
bothered to send are never seen. That is the price of no unattended uploads.

## The thing to get right

**The payload must carry a stack trace and build metadata and nothing identifying the library** — no
book titles, no server names, no URLs, no tokens.

This is a solved problem here rather than a new one: `TokenLoggingTest` and `CollectionLoggingTest`
already fail the build on a log line interpolating a token or a collection, for exactly this class of
leak (cu-134 measured 3.38 MB of book data across 2920 log lines). The same scrubbing discipline —
and ideally the same build gate — extends to a crash payload.

Choice of backend is open and should be argued in the task: a self-hosted or keyless endpoint would
avoid a proprietary SDK entirely and sit better with principle 7 than Crashlytics, which is Firebase
and is named in the ban.

## Acceptance Criteria

- [ ] Off by default; the setting states what a report contains
- [ ] Every individual crash requires a tap to send, with the payload viewable first
- [ ] Nothing uploads unattended, ever — pinned by a test
- [ ] A report carries no book title, server name, URL or token — build-gated in the manner of `TokenLoggingTest`
- [ ] Declining, or disabling, loses only this feature
- [ ] Backend choice argued in the task; Firebase/Crashlytics is barred by principle 7
