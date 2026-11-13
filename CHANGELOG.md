# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

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
