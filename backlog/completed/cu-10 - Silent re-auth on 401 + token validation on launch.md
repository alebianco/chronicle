---
id: cu-10
title: Silent re-auth on 401 + token validation on launch
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, trust]
dependencies: [cu-16]
priority: high
milestone: m-1
---

## Description

No 401 re-auth exists today (error string in MainActivity). Fixes #110 stuck login.

## Implementation Notes

### "Silent re-auth" is only half possible, and the task should say so

**Plex has no refresh-token mechanism.** Reading the auth flow before writing code split
the problem in two:

| Token | Source | Recoverable without the user? |
|-------|--------|-------------------------------|
| **Account** | OAuth PIN (`/pins.json`, polled) | **No** — needs a browser and a human approving a PIN. |
| **Server** access | `/api/v2/resources`, authenticated with the account token | **Yes** — re-fetch and replace. |

Tokens also do not expire on a timer; they are invalidated by an *event* — a password change
with "sign out connected devices", removing the server from the account, or resetting the
server's `PlexOnlineToken`
([Plexopedia](https://www.plexopedia.com/plex-media-server/general/plex-token/)).

So criterion 2 ("re-auth invisible to user") is achievable for a stale **server** token and
**impossible** for an invalidated account token. Both are now handled: the first silently,
the second honestly.

### What was already broken

`ChronicleApplication` already called `/resources` on launch, but:

1. **It threw the refreshed token away.** It mapped to `ServerModel` via `asServer()` —
   which *does* carry a fresh `accessToken` — then kept only `.flatMap { it.connections }`.
   A rotated server token was fetched and discarded on every launch. This was most of the
   task.
2. **It appended connections without dedupe** (`server.connections + retrieved`), so the
   list grew every launch — and `chooseViableConnections` races that list in parallel.
3. **Three files logged working credentials.** `determineLoginState` logged the account,
   user and server tokens together; `ChooseLibraryViewModel` logged the server token;
   `MediaMetadataCompatExt` logged **both** tokens for *every media item*, so playing a book
   sprayed them into logcat repeatedly. logcat persists and ends up in bug reports.

### The four changes

1. **Tokens out of the logs** — presence only, which is all those lines were ever read for.
2. **`mergeServerRefresh`** — a pure function, so the merge is testable without an
   `Application`. Freshest connections first and deduped; a non-empty fetched token wins; a
   failed or timed-out refresh leaves the cached server untouched, because launching offline
   must not degrade working credentials.
3. **`PlexTokenAuthenticator`** — an OkHttp `Authenticator`, not an interceptor, because
   OkHttp invokes it only on a 401 and threads the prior attempt through `priorResponse`,
   making "retry exactly once" a framework property rather than hand-rolled state. Media
   client only: a 401 from the *login* client means the account token is dead, and
   re-fetching resources with that same dead token cannot help.
4. **An honest message** — `Playback error (401): Not authorized` became "Your Plex sign-in
   has expired. Downloaded books still play; sign in again to stream." The second half is
   verified rather than hopeful: `getTrackSource()` returns a local filesystem path for a
   cached track, bypassing both the network and the token.

### Notes worth keeping

- **Most of the authenticator's tests assert that it does *not* retry.** A 401 loop against
  plex.tv would drain the battery and hammer someone else's service, so every path that
  cannot succeed stops deliberately: already-retried, unchanged token, empty token, failed
  refresh, throwing refresh.
- `PlexLoginService` is injected as a `Provider` into the media client. There is no
  dependency cycle today — the login branch never depends on the media client — but a lazy
  edge keeps it that way.
- `runBlocking` inside `authenticate()` is correct: it is a blocking callback on OkHttp's
  own connection thread, never the main thread.
- The token-logging test needed two corrections. A per-line regex **passed while the
  credential was still being logged**, because the `PlexLoginRepo` leak spans five lines in
  a `trimMargin` block. The fixed version then flagged the fix itself, since
  `${user?.authToken.isNullOrEmpty()}` contains "token" after an interpolation — so it now
  excludes a token reference immediately reduced to a boolean. Confirmed the carve-out did
  not defang it by restoring a real leak and watching the suite fail.

### Deliberately not done

- **An eager token check on launch.** Criterion 1 hints at it, but a blocking startup check
  is exactly the "login wall" the task forbids, and it breaks offline launch. The
  `/resources` refresh already validates implicitly — if it answers, the account token is
  good — and the authenticator catches the rest lazily, when it matters.
- **Auto-navigating to login.** Playback continues from cache and the message says what to
  do. Yanking someone to a login screen mid-book is the failure this task exists to prevent.

## Acceptance Criteria

- [x] Expired token never shows a login wall mid-book — nothing added navigates to login;
      the worst case is a message plus continued cached playback
- [x] Re-auth invisible to user — for a stale **server** token, which is the recoverable
      case. Impossible for an invalidated account token (no refresh token exists); that path
      is handled honestly instead of silently
- [~] Fixture-backed tests — 13 tests, each verified to bite, but they exercise the 401
      *decision* through hand-built `Response` objects and a lambda rather than
      `FakePlexServer`. Driving it through MockWebServer would test OkHttp's retry machinery
      instead. `FakePlexServer.stubUnauthorized` (already present from cu-16) is the right
      tool for an end-to-end pass; added to [[cu-73]] rather than skipped
- [>] **Live-server confirmation** — a real rotated token recovering silently is on [[cu-73]]
