# MailVerdict for Android

[![CI](https://github.com/frederikberg/mail-verdict-android/actions/workflows/ci.yml/badge.svg)](https://github.com/frederikberg/mail-verdict-android/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/frederikberg/mail-verdict-android)](https://github.com/frederikberg/mail-verdict-android/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Native Android client for [MailVerdict](https://github.com/frederikberg/mail-verdict), a
self-hosted mail client with an AI spam layer. The app talks to a MailVerdict server over its REST
and SSE API; it never speaks IMAP or SMTP itself, and the web UI of the server is the reference
for what it ports.

The project is early: signing in (through the server's identity provider, or with a pasted token),
the mailbox overview, the message list and the reader work. There is no release yet.

## Building

JDK 21 and the Android SDK are all it takes; `docs/development.md` has the setup, the commands for
each kind of check, and how the modules fit together.

```bash
./gradlew :app:assembleDebug
```

## Docs

- [docs/development.md](docs/development.md): setup, modules, tests, fixture mode, the debug
  server, the API contract snapshot, and how a release is cut.

## License

[MIT](LICENSE)
