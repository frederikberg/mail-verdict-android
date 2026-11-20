# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- The alerts System tab lists the writes PostIMAP gave up on, a send that never left included, with the
  server's wording (what failed, the message subject, the error, and a note while the app still shows
  the change as applied), the account when there are several, each dismissable and covered by Dismiss
  all. The bell count refreshes when the stream announces a notification.
- A notification of mail read elsewhere is withdrawn when the app comes to the front and when the
  stream reports a dismissal or a resync, not only when Google's push service asks for it. A phone on
  the helper-app path gets this while the app is open and not in the background.
- Writing mail: a new message from any list's Compose button, Reply, Reply all and Forward under each
  message of a conversation and in the reader's menu, and `mailto:` links in a message. Recipients,
  subject, threading headers and the quote follow the web app's rules, including a message sent from
  one of the account's own addresses being answered to whoever it went to. From offers the account's
  identities, and a reply goes out as the address the message was sent to. Recipients are suggested
  from the server's contacts as you type.
- Drafts: leaving a composer with unsaved changes saves it as a draft on the server, and a message in
  Drafts opens in the composer with its recipients, text, quote and files, replacing itself when
  saved or sent. What is being written is also kept on the phone every second, and offered back if
  the app ended before it was saved; it is removed on sign-out.
- Undo send: a send held by the server's undo window shows a countdown with Undo over every screen,
  and Undo reopens the composer with everything it held.
- Attachments from the system file picker, sent with the message; a forward carries the original's
  files along.

### Changed

- The vendored API contract is synced to MailVerdict 9.3.1.

## [0.3.0] - 2026-11-14

### Changed

- Signing out revokes the phone's own row in the server's list of sign-ins and deletes its push
  subscription before the credential is dropped, best effort and without delaying the sign-out. The
  vendored API contract is synced to MailVerdict 9.0.1.

## [0.2.0] - 2026-11-14

### Added

- A tag also builds the signed app bundle and delivers the build to Firebase App Distribution testers
  and to the Google Play internal testing track, each once its credential is configured; without
  one the step is skipped with a notice. The Firebase project configuration is written from a
  repository secret for the build.
- Every request names the phone in an `X-Device-Name` header (the name set in the phone's settings,
  else manufacturer and model), so the server's list of signed-in devices can show it.
- Signing out also asks the identity provider to revoke the sign-in, so the stored refresh token is
  not good anywhere afterwards. The request is best effort and never delays the sign-out.
- A device the server has removed from its list is signed out with its own message, without a token
  renewal first.
- Notifications through Google's push service where Google Play services exist, over the push relay
  the iPhone app uses and with the same sealed alerts: nothing to install. The helper-app path stays
  for phones without Google services, and a phone that used it is moved over on the first start. A
  build without the Firebase project configuration uses the helper-app path only.
- Notifications of mail read elsewhere are withdrawn when the server says so.

### Removed

- Signing in with a pasted access token. A copy of the app that still holds one signs out on its next
  start.

### Changed

- The vendored API contract is synced to MailVerdict 8.0.0, and folders carry whether mail arriving in
  them goes through the pipeline (`pipeline_enabled`, true when absent).

## [0.1.0] - 2026-11-14

### Added

- Project skeleton: the `:contract`, `:kit` and `:app` modules, a Material 3 shell with dynamic
  colour, a fixture mode that serves canned data, and a debug-only HTTP server for driving a
  debug build.
- Pull-request checks and a tag-triggered release that publishes a signed APK.
- Models for the mail, search, alert, push and outbox parts of the server's API, checked against
  the vendored contract snapshot field by field.
- A REST client with typed errors, request retries for reads, and one place that decides when a
  refused request ends the session.
- Sign-in with the server's identity provider (authorization code with PKCE) and tokens that renew
  themselves: one renewal however many requests are refused at once, and an unreachable provider
  never signs anyone out.
- The live-update stream with reconnects that resume from the last event, a watchdog for a silent
  connection, and an invalidation table that tells each store what an event changed.
- The sign-in screen: the server address, "Sign in" through a Chrome Custom Tab that returns to
  the app on `mailverdict://auth/callback`, and an Advanced mode for a pasted token. A refused
  credential brings the screen back and says so.
- The session lives in the Android Keystore: the token set is sealed with a non-exportable AES-GCM
  key and survives a restart; signing out removes it.
- Navigation, the mailbox overview (unified views, then each account's folders in lead order with
  unread counts, collapsible, refreshed live), an offline capsule, and settings with theme, the
  signed-in user and sign-out.
- The message list for a folder or a unified view: grouped by conversation or flat, an unread
  filter, pull to refresh, live updates, selection by long press, and pages loaded two screens before
  the end. Rows have one fixed height, the place of the row at the top survives rows arriving
  above it, and a different list starts at the top.
- The reader: a conversation newest first with the opened message expanded, HTML bodies in a web
  view with script off and remote images hidden until asked for (inline images are fetched with the
  app's own credential), plain-text bodies drawn natively, attachments opened through the system,
  and the message marked read on opening.
- Triage: mark read or unread, star, archive, delete, move to a folder, move to or out of Junk,
  from a swipe (right archives, left deletes), from a selection of messages, and from the reader's
  menu; deleting for good asks first. Every action shows at once, comes with an Undo where one is
  possible, and goes through a queue that survives a restart, sends in order, retries with backoff,
  waits while offline or while the sign-in is refused, and never moves a message that is no longer
  where the action saw it.
- The newest page of each list visited and every conversation opened are kept on the device and
  shown when the server cannot be reached; signing out removes them.
- Search: text search narrowed by field (subject, from, to, body) and by time window, and search by
  meaning with a "Less likely matches" divider between the two tiers. A server that cannot search by
  meaning gets the text results with a note saying so. Typing is debounced, and results are
  keyed by message so a new query starts at the top.
- Alerts: the list of new-mail alerts and of everything else the server raises, each dismissable one
  by one or all at once, and a bell on the overview with the server's own count.
- Notifications through UnifiedPush, with no Google services: the helper app the person already
  uses delivers the server's Web Push, a tap opens the message in its list, and the notification
  settings screen explains the helper app, turns notifications on and off, mutes the mail or system
  channel, and sends a test notification.
