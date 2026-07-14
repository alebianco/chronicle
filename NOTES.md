# Notes

## Self build

The old `freeAsInBeer` product flavor and its `signFreeAsInBeerReleaseBundle` task **no longer exist** — there are no product flavors.

- Debug install: `./gradlew installDebug` (or `adb install -r app/build/outputs/apk/debug/app-debug.apk`)
- Release build + R8 verification: `./test_release_build.sh` — see CONTRIBUTING.md "Release Builds & ProGuard" for signing.
