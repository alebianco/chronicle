---
id: 01-project-overview
title: Project Overview
type: reference
created_date: '2026-09-01'
---

# Project Overview

## What is Chronicle?

Chronicle is an Android audiobook player designed specifically for Plex media servers. It allows users to stream or download audiobooks hosted on their personal Plex server.

## Core Features

### Playback Features
- **Streaming & Download**: Stream audiobooks directly or download for offline playback
- **Playback Speed Control**: Adjust speed from 0.5x to 3.0x
- **Auto-Rewind**: Automatically rewind when resuming after a pause
- **Per-book Playback Speed**: a book can keep its own speed, overriding the global preference
  (cu-20). `NO_SPEED_OVERRIDE` (0f) means "follow the global setting"
- **Sleep Timer**: a duration or **end-of-chapter**, which stores the chapter id rather than a
  computed deadline, so a seek or speed change cannot desync it (cu-21)
- **Skip Silence**: retuned for narration — ExoPlayer's defaults collapse pauses shorter than the
  gaps between ordinary words (cu-88)
- **Chapter Navigation**: chapters resolve table-first with a column fallback (cu-49/cu-82), and
  offsets carry their frame in the **type** so a book-frame value cannot be passed where a
  track-frame one belongs (cu-136)
- **Human-readable progress**: `6h 12m` for a span, `32:10` inside a chapter — never
  `47:12:33/52:04:11` (cu-19)

### Library & Content
- **Plex Integration**: Connect to any Plex server with audiobook libraries
- **Multi-Format Support**: Plays mp3, m4a, and m4b files
- **Collections**: Browse audiobooks by collections
- **Search**: local Damerau-Levenshtein fuzzy search over title, author, narrator and series
  (cu-25) — deliberately **not** `/hubs/search`, which omits Style/Mood and so cannot answer a
  narrator or series query at all
- **Narrator & Series facets**: read from Plex's `Style`/`Mood` tags (the Audnexus convention) and
  seeded at refresh time so they fill in for books nobody has opened (cu-143/cu-145)
- **Series ordering**: the index is parsed from `titleSort` against eight built-in patterns, which
  are **user-configurable data** rather than constants (cu-146/cu-147/cu-148), with a tester screen
  that reports every rule's verdict (cu-151)
- **Bookmarks with notes**: stored in their own database so a Plex rescan cannot delete them (cu-22)
- **Recently Added/Listened**: Quick access to recent content, plus a Continue Listening shelf (cu-18)

### Sync & Progress
- **Progress Sync**: Automatically sync listening progress to Plex server
- **Multi-Device Support**: Continue where you left off on any device
- **Offline Mode**: Access downloaded books without internet connection

### Additional Features
- **Android Auto Support**: playback control while driving. The browse tree is no longer keyed on
  localized strings (cu-99). Known gap: the Auto seek bar spans the track rather than the current
  chapter (cu-165)
- **Managed Users**: Support for Plex managed user accounts
- **Accessibility**: TalkBack labels, 48dp touch targets, font scaling and contrast (cu-47), with
  `ContentDescriptionTest` failing the build on an undescribed image
- **Settings backup**: an open JSON format carrying settings *and* bookmarks, import being additive
  and idempotent rather than replace-all (cu-22)

## User Experience Flow

1. **Login**: User authenticates with Plex account
2. **Server Selection**: Choose which Plex server to connect to
3. **Library Selection**: Pick the audiobook library to use
4. **Home Screen**: Browse recently added, recently listened, and downloaded books
5. **Book Details**: View information, chapters, and start playback
6. **Playback**: Mini player at bottom for quick control, full player for detailed controls
7. **Download**: Option to cache books locally for offline listening

## Technical Capabilities

- Supports Android API 27+ (Android 8.1 Oreo and above), target/compileSdk 36
- Handles large audiobook libraries efficiently — profiled against the household's 196-book server
  (cu-51), with paged loading and linear scans
- **UI state is `StateFlow`** throughout; there is no `LiveData` and no Compose (cu-52). `postValue`
  is banned by a build gate
- Background playback with notification controls
- Media session integration for external controls (Bluetooth, Android Auto)
- Automatic progress scrobbling to Plex server
- Local caching with storage management

## Target Audience

Chronicle is built for:
- Audiobook enthusiasts who use Plex
- Users who want to stream their personal audiobook collection
- People who travel and need offline access to their books
- Android Auto users who want safe listening while driving

## Design Philosophy

- **Plex-First**: Designed specifically for Plex's audiobook capabilities
- **Offline-Ready**: Full support for downloaded content
- **Simple & Clean**: Focused UI without unnecessary complexity
- **Performance**: Optimized for large libraries and long playback sessions

