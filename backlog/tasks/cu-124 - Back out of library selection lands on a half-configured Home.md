---
id: cu-124
title: Back out of library selection lands on a half-configured Home
status: Done
assignee: []
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels: [R1, ui, auth, trust]
dependencies: []
priority: high
milestone: m-1
---

## Description

Owner-reported during the cu-73 live pass (session 4), immediately after re-authenticating
following a password change: *"i went back without finishing the selection and i ended up in home
with a logged account ... super confusing for a user"*.

### What happens

1. Re-login via **Settings → ACCOUNT → Sign in again** → OAuth completes.
2. The app routes to **CHOOSE LIBRARY** (see [[DRAFT-125]] for why the list is empty).
3. Press **Back** without selecting anything.
4. **You land on Home, apparently signed in and working** — cover art loads, the library renders,
   books are playable.

But the app's own state disagrees:

```
Navigator: Login event changed to LOGGED_IN_NO_LIBRARY_CHOSEN
```

and the prefs have **no library at all** — `library_id` and `library_name` are absent from
`Chronicle.xml`, while `server_name`/`server_id` survived.

So the user sees a working Home screen while the app believes onboarding is unfinished. Nothing
tells them a step was skipped, and nothing prevents leaving it skipped.

### Why it is confusing rather than merely untidy

The Home content is drawn from **Room**, which still holds the previous session's 196 books. So the
app looks fully configured precisely *because* the old data survived — the emptier the cache, the
more obviously broken it would look. A user in this state has no way to tell that library selection
never completed, and the next sync or refresh will behave as though no library is chosen.

It also interacts badly with [[DRAFT-123]]: there is no "login expired" message, so the user may
not know why they were sent to a picker in the first place.

### Fix direction (not decided)

Two candidate readings, and the choice is a product decision:

1. **Back out of library selection should be a dead end** — either block Back at the picker
   (onboarding is not optional), or treat Back as "abandon sign-in" and return to the logged-out
   state, rather than silently landing on a Home the app does not consider configured.
2. **Or make Home honest** — allow the state, but surface a persistent banner ("Finish choosing a
   library to sync") that routes back to the picker.

Option 2 is friendlier given [[DRAFT-125]] can make the picker genuinely unusable through no fault
of the user: trapping someone in a picker that lists nothing would be worse than letting them out.
A banner plus a working escape is probably the right combination.

Worth checking whether other partial states (`LOGGED_IN_NO_SERVER_CHOSEN`, `LOGGED_IN_NO_USER_CHOSEN`)
have the same hole — the log shows the app passing through `NO_SERVER_CHOSEN` too during this flow.

## Acceptance Criteria

- [x] Backing out of library selection leaves the app in a state that is either consistent
      (configured) or visibly incomplete — never a working-looking Home with
      `LOGGED_IN_NO_LIBRARY_CHOSEN` underneath
- [x] The same is checked for `LOGGED_IN_NO_SERVER_CHOSEN` and `LOGGED_IN_NO_USER_CHOSEN`
- [x] A user in a partial state has a visible route back to finishing it
- [x] Test coverage for the navigation decision at each partial login state
- [x] Verified on device by backing out of the picker, not only in unit tests

## Related

- [[cu-73]] — found during the live pass
- [[DRAFT-125]] — the empty picker that makes backing out likely in the first place
- [[DRAFT-123]] — no "login expired" message, so the user does not know why they are here


## Implementation Notes

**Option 1 from the draft, not option 2.** Backing out of onboarding now leaves the app rather
than showing a banner over a half-configured Home.

The draft leaned toward the banner, reasoning that trapping someone in a picker would be worse
when [[cu-125]] could make it unusable through no fault of theirs. That concern is real but is now
**addressed at its source**: cu-125 makes the picker explain a connection failure and name the
remedy, so the user is no longer stuck there with a misleading message. With that fixed, the
simpler answer is the honest one — nothing was chosen, so there is nothing to show.

`MainActivityViewModel.isOnboarding` is true for all three partial states
(`LOGGED_IN_NO_USER_CHOSEN`, `NO_SERVER_CHOSEN`, `NO_LIBRARY_CHOSEN`), which also covers the
draft's question about whether the other two had the same hole. They did: the back handler's
fall-through was per-*screen*, not per-state.

**The bug was invisible in proportion to how much cached data existed.** Home rendered the previous
session's 196 books from Room, so the app looked fully configured; on a fresh install the same code
would have shown an empty Home and looked obviously wrong. Worth remembering when judging "it looks
fine" on a populated device.

**Three tests** on the state itself. The back-handler branch is in `MainActivity`, which has no
unit coverage (see [[cu-123]]'s note on that structural gap), so what is pinned is the condition it
reads. Regression-checked on device that ordinary back from a tab still returns to Home and stays
in the app.
