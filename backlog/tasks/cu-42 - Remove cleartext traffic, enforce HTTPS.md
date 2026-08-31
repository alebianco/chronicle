---
id: cu-42
title: Remove cleartext traffic, enforce HTTPS
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, security]
dependencies: []
priority: high
milestone: m-1
---

## Description

C1: `android:usesCleartextTraffic="true"` (AndroidManifest.xml:22) allows unencrypted HTTP — MITM exposure, may fail Play security review. Audit connections, remove the flag, add a Network Security Config for any genuine plain-HTTP LAN Plex exception (self-hosted servers are often http:// on LAN — handle deliberately, not blanket-allow).

Analysis: [`C1-cleartext-traffic-resolution-plan.md`](../docs/analysis/archive/C1-cleartext-traffic-resolution-plan.md) (archived on close — it assumed a per-domain LAN exception would be needed; see Implementation Notes for why none is).

## Implementation Notes

`usesCleartextTraffic="true"` is gone, replaced by a Network Security Config whose
release variant refuses cleartext with **no exceptions at all**. A debug-only override
in `app/src/debug/res/xml/` permits loopback (`127.0.0.1`, `localhost`, `10.0.2.2`) so
the cu-16 mock server still works; nothing else is relaxed, so the mock cannot mask a
real plaintext regression.

### Why there is no LAN exception — the task asked for one

The task assumed a per-domain exception would be needed, since self-hosted Plex is
often plain `http://` on a LAN. Two findings changed that.

**1. A scoped exception is not expressible.** Android matches `<domain>` by exact string
or dot-boundary suffix — confirmed by reading `ApplicationConfig.getConfigForHostname`
in the platform sources (`android-36.1`). It does **not** parse CIDR or numeric ranges.
My first attempt wrote `10.0.0.0/8`, `192.168.0.0/16` etc., and **the build accepted it
silently**: AAPT does no validation, so the file would have sat there looking like a LAN
allowance while matching nothing, and every LAN connection would have failed at runtime
with no build-time signal. LAN addresses are DHCP-assigned anyway, so no correct static
list exists.

**2. It is not needed.** Plex issues a wildcard certificate for `*.plex.direct` and
publishes DNS mapping hyphenated-IP subdomains to the address behind them, so a server
on 192.168.1.7 is reachable as `https://192-168-1-7.<hash>.plex.direct` with a valid
certificate ([how Plex does HTTPS for all its
users](https://words.filippo.io/how-plex-is-doing-https-for-all-its-users/)). HTTPS on
the LAN is genuinely available, so allowing cleartext buys nothing.

### Behavioural consequence, stated plainly

**A server with Plex's Secure Connections set to "Disabled" will no longer connect over
its LAN address.** Plex defaults to "Preferred", so this should affect nobody who has not
deliberately turned it off, and the fix is a server-side setting rather than an app
change. Recorded here because it is a real (if narrow) behaviour change, not a pure
hardening.

The fixture pack was updated to match: `resources.json` previously modelled local
connections as bare `http://10.0.0.42`, which no longer represents a correctly configured
server. It now uses the hyphenated-IP HTTPS form.

### Verification

Asserted against the **built APKs**, not just the sources, because resource shrinking
renames these files (the release config ships as `res/8G.xml` — dumping the original path
returns empty, which is how a check here could pass vacuously):

- Release APK: `base-config cleartextTrafficPermitted=false`, zero `domain-config` entries.
- Debug APK: same base, plus the three loopback domains.

`CleartextTrafficTest` (5 cases) pins the manifest attribute, the config reference, the
absence of any release exception, and that debug exemptions are loopback-only; it also
asserts its own file paths resolve. Verified to bite by reinstating
`usesCleartextTraffic` (4 failures) and by adding an `example.com` exception to the
release config (1 failure).

## Acceptance Criteria

- [x] No blanket cleartext — `usesCleartextTraffic` removed; release config refuses it outright
- [x] Documented per-domain exceptions only where a real LAN http server needs it — **none
      needed**; the rationale and the CIDR trap are documented in the config file itself
- [x] Works against actual Plex servers over HTTPS — via the `*.plex.direct` hyphenated-IP
      form; fixtures updated to model it. Verified by construction and by APK-level
      assertions; the live-server confirmation is tracked in [[cu-73]] along with the
      other items needing real credentials, so it is not lost by closing this.
