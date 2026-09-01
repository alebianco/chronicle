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
- **Sleep Timer**: Set timer to stop playback after a duration or at chapter end
- **Skip Silence**: Automatically skip silent sections in audio files
- **Chapter Navigation**: Jump between chapters easily

### Library & Content
- **Plex Integration**: Connect to any Plex server with audiobook libraries
- **Multi-Format Support**: Plays mp3, m4a, and m4b files
- **Collections**: Browse audiobooks by collections
- **Search**: Find books by title or author
- **Recently Added/Listened**: Quick access to recent content

### Sync & Progress
- **Progress Sync**: Automatically sync listening progress to Plex server
- **Multi-Device Support**: Continue where you left off on any device
- **Offline Mode**: Access downloaded books without internet connection

### Additional Features
- **Android Auto Support**: Basic playback control while driving (no voice commands yet)
- **Managed Users**: Support for Plex managed user accounts

## User Experience Flow

1. **Login**: User authenticates with Plex account
2. **Server Selection**: Choose which Plex server to connect to
3. **Library Selection**: Pick the audiobook library to use
4. **Home Screen**: Browse recently added, recently listened, and downloaded books
5. **Book Details**: View information, chapters, and start playback
6. **Playback**: Mini player at bottom for quick control, full player for detailed controls
7. **Download**: Option to cache books locally for offline listening

## Technical Capabilities

- Supports Android API 27+ (Android 8.1 Oreo and above)
- Handles large audiobook libraries efficiently
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

