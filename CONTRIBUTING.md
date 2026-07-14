# Contributing

## How to contribute code to the project

 - Comment on the corresponding issue that you are working on it- so we
   don't get multiple developers working on the same thing
 - Follow the instructions at [How to Contribute to a GitHub
   Project](https://gist.github.com/MarcDiethelm/7303312) for instructions
   on how to make changes and then open a pull request
 - Include tests if possible!

## How to report a bug

 - Ensure that you're using the latest version of the app
 - Check if someone has already reported the bug
 - Provide the following information:
    - Detailed steps explaining how to reproduce the bug (if possible)
    - Android version and device name/variant
    - Any information about your server that you think might be relevant

## Building the app

 - Fork the repo 
 - In Android Studio:
   - File -> New -> Project from Version Control
   - Enter url of your fork
   - Run app (green play button)

## Release Builds & ProGuard

Chronicle uses R8 code shrinking and obfuscation for release builds.

### ProGuard Rules

All ProGuard rules are in `app/proguard-rules.pro`. These rules are critical for release builds.

Update rules when:
- Adding a new library that uses reflection/annotations
- Adding new data models for JSON/Room
- Adding new Dagger components/modules
- Release build crashes but debug works

### Testing Release Builds

Before any release, test the release APK:

```zsh
./test_release_build.sh
```

Checklist:
1. Build compiles
2. APK installs
3. App launches
4. Login works
5. Major features (library, playback, downloads) work
6. No crashes in logcat

### Common Issues

- `ClassNotFoundException`: Add `-keep` rule for the class/package
- JSON parsing fails: Keep model classes / Moshi adapters
- Dagger injection fails: Ensure generated components/modules are kept
