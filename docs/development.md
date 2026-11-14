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

## Authentication and live updates

The server sits behind a login proxy, so a refused request means one of three things, and
`ApiError` tells them apart: a `401` or a bare non-JSON `403` (the proxy rejecting the token) is a
rejected credential; a `403` with a JSON `detail` is the app refusing that one request and leaves the
person signed in; the `detail` `user_disabled` ends the session, and so does a `401` with the `detail`
`device_revoked` (the server removed this phone from its list of devices), which is not renewed first
since a new token would belong to the same phone. Every request carries `X-Device-Name`
(`DeviceName` sanitises it to at most 64 characters; `DeviceNames` in `:app` picks it).

- A signed-in session is an `AuthMode.Oidc` token set. Nothing is renewed ahead of time except a
  token whose provider stated a lifetime; the normal trigger is a refusal. `TokenRefresher` renews single-flight (the provider rotates refresh tokens, so two
  simultaneous renewals would leave one caller holding a retired token) and stores the new set
  before using it. A rejected refresh token ends the session; an unreachable provider does not.
- `ApiClient` renews and resends a refused request once. `SseClient` does the same for the event
  stream, reconnecting with the last event id so the server replays what was missed (or answers
  `resync`), backing off with jitter, and treating a stream silent for three ping intervals as dead.
- `LiveEventHub` turns each event into a `LiveInvalidation` (the table lives in
  `LiveInvalidation.from`), holding mail bursts for 500 ms so a store refreshes once.
- Two things need Android and are interfaces in `:kit`: `TokenStore` (where the credential
  survives a restart) and `Connectivity` (the device's network path).

## Signing in and the session

`SessionController` (in `:kit`) owns whether the app has a credential and what is built on it: one
`ApiClient`, one live stream and one `MeStore` per sign-in, closed and replaced on the next. It is
the only place that decides between the signed-out and signed-in screens.

- **Browser sign-in.** The person types the server address; the app reads `GET /api/auth/config`,
  discovers the provider, and opens the authorization address in a Chrome Custom Tab. The provider
  redirects to `mailverdict://auth/callback`, which `RedirectActivity` hands to the controller. The
  controller checks scheme, host and path against the redirect it asked for, then `SignInFlow`
  checks `state` before the code is exchanged. A sign-in the process did not survive (killed
  during the browser leg) reports that it did not complete.
- **Where the credential lives.** `SealedFileTokenStore` writes it to `files/auth.bin`, sealed with
  AES-GCM under a key held in the AndroidKeyStore (`AndroidKeystoreKey`). A file that cannot be
  opened (altered, or its key gone) reads as signed out. The server address is not a secret and is
  kept in `AppPreferences` next to the theme and which overview sections are collapsed.
- **Ending the session.** A credential the server refuses for good (after the renewal in
  *Authentication and live updates*) or a disabled user clears the stored credential and shows the
  sign-in screen with a notice; signing out does the same without one, and also asks the provider to
  revoke the sign-in (the `revocation_endpoint` of its discovery document, RFC 7009), best effort and
  bounded, so the stored refresh token stops working. A `device_revoked` notice says the device was
  signed out.

The Robolectric tests cover the screens and the redirect plumbing; the real Keystore and the Custom
Tab need a device or emulator. A stand-in server for the emulator only needs `GET /api/auth/config`,
an OIDC discovery document, an authorize page that links to the redirect, and a token endpoint
(plus a revocation endpoint, if sign-out is to be seen revoking);
debug builds accept cleartext to `127.0.0.1`, so `adb reverse` is enough to reach one.

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

## The mail list and the reader

The list's stores live in `:kit` (`maillist/`, `reader/`, `cache/`), its screens in `:app`
(`maillist/`, `reader/`). What the list guarantees is tested on the real screen in
`MailListScrollTest`: the row at the top of the viewport and its offset survive rows arriving or
leaving above it, every row lays out at the one height it was given however long its text, a
different list (another filter, grouping or jump to the newest mail) starts at the top, and a
return from the reader restores the place by row identity. A reader resting at the very top of a
list that starts at the newest message sees new mail arrive there; anyone further down is told how
many rows landed above.

A reader page loads under one rule (`ReaderRequestPolicy`): inline attachments of the server are
fetched by the app with its credential, remote content is blocked until the person asks for
images, and then only images pass. Page script is off.

Every action on a message goes through `ActionQueue` (`kit/actions`): one intent per action, kept
in a file under the app's files directory (one per server, removed on sign-out), delivered one request
at a time per account and in order for any one message. An intent's id is its idempotency key, so
every retry is the same request to the server; a move names the folder the message was seen in, so
it never follows a message somewhere else. What a list or the reader shows is the server's data with
the outstanding intents applied (`IntentProjection`). Tests drive the queue in virtual time against
`FakeTransport`.

The offline cache keeps the newest page of each list visited and each conversation opened under
the app's cache directory, one directory per server, and is emptied on sign-out. Fixture mode keeps
nothing.

## Search, alerts and notifications

Search keeps its logic in `:kit` (`search/`): `SearchStore` debounces typing, cancels what a newer
query supersedes, asks twice in semantic mode (the text list shows until the answer by meaning
replaces it) and falls back to the text list when the server answers `503` to a semantic query.
`SearchBackend` is the seam the store's timing tests run against in virtual time.

Notifications use the UnifiedPush connector, so a distributor app (ntfy, Sunup) delivers them
instead of Google's service. The server already sends Web Push to the subscriptions a signed-in user
registers, so the app registers the distributor's endpoint and key set as one:

- `PushController` (`:kit`, `push/`) is the state machine: it reads the server's VAPID key, asks the
  distributor for an endpoint (`UnifiedPush.register` with the key), and posts the endpoint to
  `POST /api/alerts/subscriptions`. `PushRegistrar` keeps what the server holds in step with what
  the phone has, and a subscription the server no longer lists is registered again on the next start.
- `MailVerdictPushService` (`:app`, `push/`) receives the distributor's calls in a process that may
  have been started just for them, and `PushNotifier` turns the decrypted payload
  (`{title, body, url, tag}`) into a notification on the mail or system channel. A tap opens the
  message through the same place resolver the mail list uses, or the alert list for an alert that
  names none.
- Signing out unregisters from the distributor and forgets the subscription; the server removes
  the endpoint when it next fails to deliver to it.

To see it work end to end, run a devstack with an `ENCRYPTION_KEY` set (the server cannot send push
without one), an ntfy server reachable from both the host and the device (`adb reverse
tcp:<port> tcp:<port>`), the ntfy app as the distributor with that address as its default server,
and send a test notification from the notification settings screen.

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
