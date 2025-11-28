# Glossary

This glossary explains common Android, Kotlin, and architecture terms used throughout Chronicle.

## Android Terms

### Activity
A single screen in an Android app with a user interface. Chronicle has one Activity (`MainActivity`) that hosts multiple Fragments.

### Fragment
A reusable portion of UI that represents a screen or part of a screen. Each main screen in Chronicle is a Fragment (HomeFragment, LibraryFragment, etc.).

### Service
A component that runs in the background without a user interface. `MediaPlayerService` plays audio in the background.

### Intent
A message that requests an action from another app component. Used for navigation and communication between components.

### Context
Provides access to application resources and system services. Activities and Services are Contexts.

### Layout
XML files that define the user interface structure. Located in `res/layout/`.

### RecyclerView
An efficient view for displaying large lists or grids. Used to display book lists in Chronicle.

### ViewHolder
Pattern for efficiently recycling views in a RecyclerView.

### Notification
Message displayed outside the app's UI, typically in the status bar. Used for playback controls.

### Manifest (AndroidManifest.xml)
XML file that describes essential information about the app to the Android system, including permissions and components.

### APK (Android Package Kit)
The compiled app package that gets installed on Android devices.

## Architecture Terms

### MVVM (Model-View-ViewModel)
Architecture pattern that separates UI (View) from business logic (ViewModel) and data (Model).

### Repository Pattern
Design pattern where a Repository class acts as a single source of truth for data, abstracting data sources from the rest of the app.

### Dependency Injection (DI)
Design pattern where dependencies are provided to a class rather than the class creating them itself. Dagger 2 handles this in Chronicle.

### Singleton
Design pattern ensuring only one instance of a class exists for the entire application lifetime.

### Observer Pattern
Design pattern where objects (observers) watch another object (subject) for changes. LiveData uses this pattern.

## Kotlin Terms

### Data Class
A Kotlin class primarily designed to hold data. Automatically generates equals(), hashCode(), toString(), and copy() methods.

### Coroutine
Kotlin's way of handling asynchronous programming. Allows writing async code that looks sequential.

### Suspend Function
A function that can be paused and resumed, used with coroutines. Marked with `suspend` keyword.

### Extension Function
A way to add new functions to existing classes without modifying them. Example: `String.toUpperCase()`.

### Null Safety
Kotlin's type system that prevents null pointer exceptions by distinguishing nullable types (`Type?`) from non-nullable types (`Type`).

### Lambda
An anonymous function that can be passed as a parameter. Example: `{ item -> doSomething(item) }`.

### Companion Object
A singleton object associated with a class, similar to static members in Java.

## Android Jetpack Terms

### LiveData
An observable data holder that respects the lifecycle of Android components. Automatically stops updates when the UI is inactive.

### ViewModel
A class that holds UI-related data and survives configuration changes (like screen rotation).

### Room
SQLite database library that provides an abstraction layer over SQLite for easier database access.

### WorkManager
API for scheduling background tasks that need guaranteed execution.

### Data Binding
Library that allows binding UI components in layouts to data sources using declarative format.

## Dagger 2 Terms

### Component
An interface that defines which dependencies can be provided and where they can be injected.

### Module
A class that provides dependencies. Contains methods annotated with `@Provides`.

### Scope
Defines the lifetime of a dependency (e.g., `@Singleton`, `@ActivityScope`).

### Inject
Annotation that marks where dependencies should be provided.

## Media Playback Terms

### ExoPlayer
Google's media player library for Android that plays audio and video.

### MediaSession
Android framework class that allows apps to communicate with media controllers and the system.

### MediaBrowser / MediaBrowserService
Android framework for browsing media content and controlling playback from other apps.

### Audio Focus
Android system that manages which app should play audio at any given time.

### Notification Channel
Categorizes notifications for user control. Required on Android 8.0+.

## Networking Terms

### Retrofit
Type-safe HTTP client for Android that converts API calls into Java/Kotlin interfaces.

### OkHttp
HTTP client library that Retrofit uses under the hood.

### JSON
JavaScript Object Notation - text format for data exchange between server and client.

### API (Application Programming Interface)
Set of endpoints that allow the app to communicate with the Plex server.

### Endpoint
A specific URL on a server that performs a specific function (e.g., `/library/albums`).

## Database Terms

### DAO (Data Access Object)
Interface that defines methods for accessing the database. Room uses DAOs.

### Entity
A class that represents a database table. Annotated with `@Entity`.

### Migration
Code that updates the database schema when the app version changes.

### Query
A request to retrieve or modify data in the database.

### Primary Key
A unique identifier for each row in a database table.

## Reactive Programming Terms

### Observable
An object that emits data that other objects can observe/listen to.

### Observer
An object that listens for data emissions from an Observable.

### Stream
A sequence of data elements that can be processed.

### Reactive
Programming paradigm focused on data flows and propagation of changes.

## Threading Terms

### Main Thread (UI Thread)
The thread where all UI updates must happen. Blocking this thread freezes the UI.

### Background Thread
A thread for executing long-running operations without blocking the UI.

### Dispatcher
Determines what thread or thread pool a coroutine runs on (Main, IO, Default).

### Synchronous
Operations that block execution until complete.

### Asynchronous
Operations that don't block execution; the result comes later via callback or coroutine.

## Git/Version Control Terms

### Branch
An independent line of development in Git.

### Commit
A snapshot of changes in the repository.

### Pull Request (PR)
A request to merge code changes from one branch into another.

### Merge
Combining changes from different branches.

## Build System Terms

### Gradle
Build automation tool used by Android projects.

### Build Variant
Different versions of the app (debug vs release).

### ProGuard / R8
Tools that shrink, optimize, and obfuscate code for release builds.

### Dependency
External library or module that the project uses.

### KSP (Kotlin Symbol Processing)
Annotation processing tool for Kotlin, used by Room and Dagger.

## Testing Terms

### Unit Test
Tests individual components in isolation (e.g., testing a single function).

### Instrumented Test
Tests that run on an Android device or emulator.

### Mock
A fake object used in testing to replace real dependencies.

### Test Double
Generic term for any fake object used in testing (mocks, stubs, fakes).

## Chronicle-Specific Terms

### Audiobook
A book in audio format. The main content type in Chronicle.

### Track
An individual audio file, usually a chapter or part of a chapter.

### Chapter
A logical division of an audiobook, may span multiple tracks.

### Collection
A group of related audiobooks (e.g., a book series).

### Scrobble
Syncing playback progress to the Plex server.

### Cache / Download
Storing audiobook files locally for offline playback.

### Offline Mode
Using Chronicle without internet, only accessing cached content.

### Progress
How far into an audiobook the user has listened (in milliseconds).

### Plex Server
A media server application that hosts audiobook files.

### Library
A collection of audiobooks on a Plex server.

### Managed User
A Plex user account managed by the server admin, often for family members.

## Common Annotations

### @Inject
Marks a constructor, field, or method for dependency injection.

### @Singleton
Indicates only one instance should exist for the application lifetime.

### @Entity
Marks a class as a database table in Room.

### @Dao
Marks an interface as a Data Access Object in Room.

### @Query
Defines a database query method in Room DAO.

### @GET, @POST, etc.
Retrofit annotations for HTTP methods.

### @Parcelize
Kotlin annotation that automatically implements Android's Parcelable interface.

## Acronyms

- **API**: Application Programming Interface
- **APK**: Android Package Kit
- **DAO**: Data Access Object
- **DI**: Dependency Injection
- **HTTP**: Hypertext Transfer Protocol
- **IO**: Input/Output
- **JSON**: JavaScript Object Notation
- **KSP**: Kotlin Symbol Processing
- **MVVM**: Model-View-ViewModel
- **SDK**: Software Development Kit
- **SQL**: Structured Query Language
- **UI**: User Interface
- **URI**: Uniform Resource Identifier
- **URL**: Uniform Resource Locator
- **VM**: ViewModel
- **XML**: Extensible Markup Language

## Resources for Learning More

### Kotlin
- [Kotlin Documentation](https://kotlinlang.org/docs/home.html)
- [Kotlin Koans](https://kotlinlang.org/docs/koans.html) - Interactive exercises

### Android
- [Android Developer Guides](https://developer.android.com/guide)
- [Android Codelabs](https://codelabs.developers.google.com/?cat=Android)

### Architecture
- [Guide to App Architecture](https://developer.android.com/topic/architecture)
- [Android Architecture Components](https://developer.android.com/topic/libraries/architecture)

### Libraries Used
- [Dagger 2](https://dagger.dev/)
- [Room](https://developer.android.com/training/data-storage/room)
- [Retrofit](https://square.github.io/retrofit/)
- [Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
- [ExoPlayer](https://exoplayer.dev/)

