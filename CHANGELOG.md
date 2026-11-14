# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

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
