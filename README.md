# Chronicle Unabridged

An Android audiobook player for your own self-hosted library — Plex first, with Audiobookshelf and local files on the roadmap. Stream or download your books, keep your place across every device, and listen the way a book wants to be listened to.

> **Unabridged** — the complete, uncut edition. Every feature is free; nothing is paywalled.

This is a friendly fork of [**mattttvaughn/chronicle**](https://github.com/mattttvaughn/chronicle), continued as a personal, household-first project and developed largely through AI coding agents (see [`CLAUDE.md`](./CLAUDE.md)).

## Why this fork exists

Chronicle is the only native, open-source, purpose-built Plex audiobook player on Android — but upstream development is intermittent and a handful of long-standing reliability issues (progress loss, downloads, reconnection) sit unfixed. This fork exists to make Chronicle a **rock-solid daily driver for a self-hosted household**: reliability first (never lose your place, never fail a download), then the comfort and design an audiobook app deserves, then reach beyond Plex to other DRM-free backends.

The guiding metric is *zero interventions* — days per month where a household member has to do anything other than play, pause, and pick a book. Priorities, decisions, and the full roadmap live in [`backlog/`](./backlog/README.md).

## Acknowledgements

Chronicle was created by **[Matt Vaughn (@mattttvaughn)](https://github.com/mattttvaughn)**, who built and open-sourced the original app. All of the hard early work — the playback engine, Plex integration, Android Auto, the offline downloader — is his. This fork stands entirely on that foundation, and it remains **GPLv3** as he released it.

Thanks also to:

- **[fabiogermann](https://github.com/fabiogermann)** and the **[Chronicle Epilogue](https://www.chronicleapp.net/)** fork — an actively-maintained continuation whose fixes (progress reporting, connection resiliency, offline playback, Android Auto) are a reference and, where ported, are credited in the commit history (`Ported-from:` trailers). *Epilogue's own name, logo, and branding are theirs and are not used here.*
- The wider community of Chronicle forks and contributors whose patches informed this work (see the fork-harvest notes in [`backlog/docs/research/`](./backlog/docs/research/)).
- **[Prologue](https://prologue.audio)** (iOS), **Pocket Casts**, and **Libby** — the design bar this fork measures itself against.
- The **[Plex Audiobook Guide](https://github.com/seanap/Plex-Audiobook-Guide)** community for the tagging conventions that make audiobooks work on Plex at all.

### Naming & branding

The code is GPLv3 and free to use, study, modify, and share. The **"Chronicle Unabridged" name and its icon/wordmark are this project's own** and are not part of the GPLv3 grant — please don't reuse them for a different app. Likewise, upstream's and Epilogue's branding remain theirs. (See [`backlog/decisions/decision-7`](./backlog/decisions/).)

## Features

Working today (inherited from upstream):

- Stream or download audiobooks from your Plex server (mp3, m4a, m4b)
- Progress sync across devices
- Adjustable playback speed, auto-rewind, sleep timer, skip-silence
- Chapter navigation
- Basic Android Auto (playback; no voice commands yet)

On the roadmap (see [`backlog/`](./backlog/README.md)): reliability overhaul (progress, downloads, reconnection), Continue-Listening & chapter-aware UI, a cover-tinted redesign, Audiobookshelf and local-file/WebDAV backends, and client-built metadata enrichment (narrator/series/moods). **DRM stores (Audible, etc.) are permanently out of scope** — this is a player for audiobooks you own.

## Screenshots

<p float="left">
<img src="https://raw.githubusercontent.com/mattttvaughn/chronicle/develop/images/home.png" alt="Home screen" height="200">
<img src="https://raw.githubusercontent.com/mattttvaughn/chronicle/develop/images/library.png" alt="Library screen" height="200">
<img src="https://raw.githubusercontent.com/mattttvaughn/chronicle/develop/images/currentlyplaying.png" alt="Player" height="200">
</p>

*(Upstream screenshots; the Unabridged redesign is a roadmap item.)*

## Documentation & workflow

Everything that isn't code lives as plain markdown under [`backlog/`](./backlog/README.md) — no dependency on any single git host ([file over app](https://stephango.com/file-over-app)). [`CLAUDE.md`](./CLAUDE.md) at the root is the single source of truth for building and contributing.

- **[Backlog & workflow](./backlog/README.md)** — how work is tracked (tasks, drafts, decisions)
- **[Reference docs](./backlog/docs/reference)** — project overview, architecture, data flow, key components, glossary
- **[Decisions](./backlog/decisions)** — product decisions and technical ADRs
- **[Research](./backlog/docs/research)** — the ownership/modernization study and the commercial-viability report

## Contributing

This project is developed primarily by one person working through AI coding agents; the workflow, verify loop, and conventions are documented in [`CLAUDE.md`](./CLAUDE.md) and [`CONTRIBUTING.md`](./CONTRIBUTING.md). Issues and patches are welcome.

### Android version support

- Minimum: Android 8.1 (API 27)
- Target: Android 14 (API 34) — moving to API 36, see [`backlog/tasks`](./backlog/tasks/)

### Developer notes — Ktlint

Ktlint enforces the [Kotlin style guide](https://developer.android.com/kotlin/style-guide) via the [ktlint gradle plugin](https://github.com/jlleitschuh/ktlint-gradle):

```
./gradlew ktlintCheck    # report to app/build/reports/ktlint
./gradlew ktlintFormat   # autoformat
```

A pre-commit git hook runs `ktlintCheck` and prompts you to `ktlintFormat` if it finds violations. The full agent/human verify loop is in [`CLAUDE.md`](./CLAUDE.md).

## License

GPLv3, inherited from upstream [mattttvaughn/chronicle](https://github.com/mattttvaughn/chronicle). See [`LICENSE`](./LICENSE).
