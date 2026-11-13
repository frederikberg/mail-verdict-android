# Development

## Setup

- JDK 21.
- The Android SDK with platform 37 and its build-tools; `ANDROID_HOME` points at it, or
  `local.properties` (gitignored) carries `sdk.dir=<path>`. Gradle installs a missing platform
  once the licences are accepted.
- Nothing else: the Gradle wrapper pins Gradle, and every dependency resolves from Google's Maven
  repository and Maven Central.

## Modules

| Module | Kind | Holds |
|---|---|---|
| `:contract` | JVM | The `@Serializable` models of the server's API and the one JSON configuration (`ContractJson`). No behaviour. |
| `:kit` | JVM | Everything the screens need that is not Android: networking, stores, formatting, and the fixture interceptor that serves canned JSON. |
| `:app` | Android | Compose screens, `AppGraph` (the constructor wiring), the activity, the theme, and the debug source set. |

The split keeps logic where a plain JUnit test reaches it in seconds. A view is thin enough when no
bug in it could be caught by a unit test; the two things that need Android itself (token storage,
the browser leg of sign-in) are interfaces in `:kit` with the real implementation in `:app`.

## Checks

```bash
./gradlew ktlintCheck                      # style; ktlintFormat fixes what it can
./gradlew :contract:test :kit:test         # JVM tests, seconds
./gradlew :app:recordRoborazziDebug        # every :app unit test, and writes the screenshots
./gradlew :app:lintDebug :app:assembleDebug
tooling/test-release-scripts.sh            # the release scripts against broken input
```

Pull requests run all of these (`.github/workflows/ci.yml`) behind one aggregate check, `CI ok`.

`:app` unit tests run on the JVM under Robolectric with native graphics. The screenshot tests
write their PNGs under `app/build/outputs/roborazzi/`, but only from the record task;
`testDebugUnitTest` alone passes without writing them. Screenshots are recorded for a reader to
look at, not compared against a baseline. `tooling/screenshots.sh` re-records them and copies them
to `docs/images/`.

## Fixture mode

A debug build started with the launch flag serves canned data from `:kit`'s `Fixtures` instead of
reaching a server:

```bash
adb shell am start -n com.frederikberg.mailverdict/.MainActivity --ez fixture_mode true
```

Release builds ignore the flag.

## Debug server

A debug build listens on `127.0.0.1:8765` for `/health`, `/state` and `/logs?level=WARN`.

```bash
adb forward tcp:8765 tcp:8765
curl localhost:8765/state
```

The server and its in-memory log live in `app/src/debug/` and are not part of a release build.
Debug builds also accept cleartext HTTP to `127.0.0.1` and `localhost`, so a development server
reached through `adb reverse` works without TLS.

## The API contract

The server publishes `docs/api-contract/openapi.json` and `sse-events.json`. A snapshot of both
is vendored under `contract/src/test/resources/api-contract/`, together with the commit it was
taken from (`SYNCED_AT`). `ContractTest` checks that every model's fields exist in its schema and
that every required property is modelled; a model not registered in that test is not checked.

```bash
tooling/sync-contract.sh <mail-verdict-sha>
```

is the only writer of the snapshot.

## Releasing

The version is `appVersionName` in `gradle.properties`; the app's `versionCode` is derived from it
(`major * 10000 + minor * 100 + patch`), so every upgrade is accepted by Android.

1. Move the `[Unreleased]` entries of `CHANGELOG.md` into a dated `[X.Y.Z]` section and set
   `appVersionName` to `X.Y.Z`. `tooling/check_release_versions.sh vX.Y.Z` verifies both.
2. Merge that through a pull request once `CI ok` is green.
3. Tag the merge commit, `git tag -a vX.Y.Z -m "Release vX.Y.Z" && git push origin vX.Y.Z`.

The tag runs `.github/workflows/release.yml`: it re-checks the versions, builds the release APK
signed with the keystore held in the repository's Actions secrets, verifies the signature and the
version inside the APK, and publishes a GitHub Release whose notes are the changelog section. A tag
containing `-` is marked as a pre-release.

A release build needs `RELEASE_KEYSTORE_FILE` (a path) and `RELEASE_KEYSTORE_PASSWORD` in the
environment and fails without them rather than producing an unsigned APK. The key alias is
`release`. Debug builds sign with the SDK's debug key.

Updates install over an existing install only when the signature is the same and `versionCode` is
higher, so the keystore is never regenerated casually: a new key means uninstalling the app first.
