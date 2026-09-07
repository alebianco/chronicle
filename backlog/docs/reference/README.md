---
id: reference-README
title: Chronicle App Documentation
type: reference
created_date: '2026-09-01'
---

# Chronicle App Documentation

Welcome to the Chronicle Audiobook Player documentation. This guide will help you understand the project structure and architecture.

## Documentation Index

**Standards and process** (the constitution set — start here):

- **[Constitution](./00-constitution.md)** — development principles, conventions, testing, definition of done, never-touch list
- **[Enforced rules](./09-enforced-rules.md)** — the build gates; generated from the guard tests themselves
- **[Tech stack](./10-tech-stack.md)** — versions, architecture, build variants
- **[Verify loop](./11-verify-loop.md)** — `verify.sh`, the coverage ratchet, release and instrumented tests
- **[Agent memory](./12-agent-memory.md)** — the auto-memory loop, why there is no automatic writer, and how to correct a wrong memory

**How the code works:**

1. **[Project Overview](./01-project-overview.md)** - High-level introduction to the app and its features
2. **[Architecture](./02-architecture.md)** - Understanding the app's architectural patterns
3. **[Project Structure](./03-project-structure.md)** - How the code is organized
4. **[Key Components](./04-key-components.md)** - Important classes and their responsibilities
5. **[Data Flow](./05-data-flow.md)** - How data moves through the application
6. **[Adding New Features](./06-adding-features.md)** - Guide for implementing new functionality
7. **[Visual Architecture Guide](./07-visual-guide.md)** - Diagrams and visual representations
8. **[Glossary](./08-glossary.md)** - Terms and concepts explained

Technical debt and the improvement roadmap live as tracked tasks in [`../../tasks/`](../../tasks/), with optional deep-reference in [`../analysis/`](../analysis/).

## Quick Start

If you're new to the project, we recommend reading the documentation in order:

1. Start with the **Project Overview** to understand what Chronicle does
2. Read the **Architecture** document to learn about the design patterns used
3. Review the **Project Structure** to navigate the codebase
4. Dive into **Key Components** when you need to modify specific parts
5. Use **Data Flow** to understand how information moves through the app
6. Reference **Adding New Features** when implementing new functionality

## Getting Help

- Check the [main README](../../../README.md) for build instructions
- Review [CONTRIBUTING.md](../../../CONTRIBUTING.md) for contribution guidelines
- See existing code examples in the `features/` directory

## Key Technologies

- **Language**: Kotlin 2.2.10 (minSdk 27, target/compileSdk 36)
- **UI**: **Compose**, all of it ([[decision-22]]). No layouts, no Fragments, no ViewBinding. DataBinding was removed and LiveData too — UI state is `StateFlow`.
- **Async**: Coroutines with an injected `DispatcherProvider` (never `Dispatchers.*` directly, never `GlobalScope`)
- **Dependency Injection**: Dagger 2.57.2, hand-rolled components, via **KSP** (not KAPT)
- **Database**: Room 2.8.1 — **five** databases, each with its own version and migration list
- **Media Playback**: Media3 1.11.0 (ExoPlayer + MediaSession + Cast)
- **Networking**: Ktor + Ktorfit over the OkHttp engine (decision-24)
- **Serialization**: kotlinx-serialization (`@Serializable`), through the shared `ChronicleJson`
- **Image Loading**: **Coil 3** (Fresco and Glide were both removed)
- **Downloads**: Fetch2

## Platform notes

- Foreground services: `mediaPlayback` for `MediaPlayerService`, `dataSync` for the WorkManager
  foreground service; `android:foregroundServiceType` is set in `app/src/main/AndroidManifest.xml`.
- Notifications: `POST_NOTIFICATIONS` declared, runtime request required on API 33+; channels are
  created in `application/ChronicleApplication.kt`.
- Exact alarms: not used; no `SCHEDULE_EXACT_ALARM`.
- Media permissions: no `READ_MEDIA_AUDIO` — the app streams and manages app-scoped downloads.
- Cleartext HTTP is refused app-wide (`res/xml/network_security_config.xml`), with a debug-only
  loopback exception for the mock server.

## Quick Commands

`./verify.sh` **is** the definition of "the build is fine" — run it, not the individual Gradle
tasks:

```zsh
./verify.sh            # full gate: ktlint, unit tests, coverage ratchet, debug APK, lint, release compile
./verify.sh --quick    # inner loop: ktlint + unit tests + coverage
./verify.sh --format   # ktlintFormat first, then the full gate
```

Instrumented tests are opt-in (`./verify.sh --instrumented`, two Gradle Managed Devices).
See [`/CLAUDE.md`](../../../CLAUDE.md) §Verify loop for what each stage catches and why.
