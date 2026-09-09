# LT Phone

English | [한국어](README_KR.md)

LT Phone is a fork of [ActivityWatch Android](https://github.com/ActivityWatch/aw-android)
that lets users manage activity records from their own Android device. Data is stored on the
device by default and is sent to a compatible self-hosted server only when the user explicitly
provides a server address and authentication credentials.

This repository is published as a portfolio and source-code showcase. LT Phone is not distributed
through Google Play; installable packages are provided only through this repository's GitHub Releases.

## Features

- Records app usage and AFK state in local ActivityWatch buckets
- Records page URLs and titles from supported browsers when Accessibility access is enabled
- Records media metadata when Notification access is enabled
- Provides a privacy mode that pauses sensitive watchers and cleans the selected interval
- Optionally pushes data to a self-hosted server using HMAC-SHA256 authentication
- Monitors watcher health and protects the push secret with Android Keystore

## What this fork adds and changes

The comparison baseline is `ActivityWatch/aw-android` at `v0.14.0b2`. Comparing paths and Git blob
hashes against the published tree shows 33 files that exist only in this fork and 38 upstream files
with content changes. The additions include 15 new production Kotlin files; content changes cover
25 existing Kotlin/Java files, including three tests. The public tree also excludes 12 upstream files,
while three other files differ only in executable permissions.

These figures describe the scope of the fork. They do not claim that entire modified files were
written from scratch. Code inherited from upstream is attributed to the ActivityWatch contributors;
the additions and modifications described below are attributed to the LT Phone fork.

- **Browser tracking accuracy**

  Uses a time-based settling rule for window titles that arrive before their new URL, preventing a
  title from being attached to the previous page. Address-bar prompts and search terms are rejected
  as URLs. Window-title lookup and URL persistence are also moved off the accessibility callback's
  main thread.

  Key files: [`BrowserSessionTracker.kt`](mobile/src/main/java/net/activitywatch/android/watcher/BrowserSessionTracker.kt),
  [`UrlExtraction.kt`](mobile/src/main/java/net/activitywatch/android/watcher/UrlExtraction.kt),
  [`WebWatcher.kt`](mobile/src/main/java/net/activitywatch/android/watcher/WebWatcher.kt)

- **App, session, and media collection**

  Improves app-session parsing and collection intervals, adds screen/lock-state-based AFK tracking,
  and refines media-state handling and YouTube Shorts interval tracking.

  Key files: [`SessionParser.kt`](mobile/src/main/java/net/activitywatch/android/parser/SessionParser.kt),
  [`UsageStatsWatcher.kt`](mobile/src/main/java/net/activitywatch/android/watcher/UsageStatsWatcher.kt),
  [`InteractionWatcher.kt`](mobile/src/main/java/net/activitywatch/android/watcher/InteractionWatcher.kt),
  [`MediaWatcher.kt`](mobile/src/main/java/net/activitywatch/android/watcher/MediaWatcher.kt)

- **Privacy mode**

  Prevents sensitive watchers from querying or recording during a selected interval, cleans that
  interval, schedules expiry, and provides a Quick Settings tile.

  Key files: seven new Kotlin files under
  [`privacy/`](mobile/src/main/java/net/activitywatch/android/privacy/)

- **Optional server push**

  Sends incremental updates only to a server configured by the user. It implements HMAC-SHA256
  authentication, retry and cursor management, and Android Keystore protection for the shared secret.

  Key files: six new Kotlin files under [`push/`](mobile/src/main/java/net/activitywatch/android/push/)
  and [`PROTOCOL.md`](lt-docs/PROTOCOL.md)

- **Watcher health and lifecycle**

  Adds checks that confirm watchers are producing events and connects them to background-service,
  alarm, and worker scheduling.

  Key files: [`WatcherHealth.kt`](mobile/src/main/java/net/activitywatch/android/watcher/WatcherHealth.kt),
  [`BackgroundService.kt`](mobile/src/main/java/net/activitywatch/android/BackgroundService.kt),
  [`AlarmReceiver.kt`](mobile/src/main/java/net/activitywatch/android/watcher/AlarmReceiver.kt)

- **Public release preparation**

  Separates the application ID and branding, disables Android backup, and adds tests, build/install,
  privacy/release documentation, and release-sanitization scripts.

  Key files: [`AndroidManifest.xml`](mobile/src/main/AndroidManifest.xml), [`BUILDING.md`](BUILDING.md),
  [`PRIVACY.md`](PRIVACY.md), [`RELEASE.md`](RELEASE.md), and [`lt-docs/`](lt-docs/)

Regression tests reproduce browser title/URL transitions, address-bar prompt filtering, and watcher
health decisions. See the [architecture document](lt-docs/ARCHITECTURE.md) for the detailed structure
and design boundaries.

## Installation

Download the APK and its SHA-256 checksum from [Releases](../../releases). Google Play installation
is not supported. See [INSTALL.md](INSTALL.md) for installation steps and permission details.

To build from source, follow [BUILDING.md](BUILDING.md).

## Privacy

The repository and release APK do not contain the developer's browsing history, YouTube history,
ActivityWatch exports, server address, authentication secret, or signing key. The data collected by
the app and the conditions for transmission are documented in [PRIVACY.md](PRIVACY.md).

Pre-publication privacy audits are performed with local tooling stored outside this repository. Do
not copy audit rules or inputs, keystores, real data exports, or personal APKs into the `aw-android`
directory.

## Build and release documentation

- Development builds: [BUILDING.md](BUILDING.md)
- GitHub Releases: [RELEASE.md](RELEASE.md)
- Architecture and push protocol: [lt-docs/README.md](lt-docs/README.md)
- Original project documentation: [README.upstream.md](README.upstream.md)

## Upstream project and license

This project is based on `v0.14.0b2` of
[ActivityWatch/aw-android](https://github.com/ActivityWatch/aw-android). Most of the foundation was
written by ActivityWatch contributors. This fork uses the separate application ID
`net.lifetrainer.awphone` and adds optional server push, privacy mode, an AFK watcher, and watcher
health monitoring.

The project remains licensed under [MPL-2.0](LICENSE). LT Phone is not an official ActivityWatch app
and is not endorsed by the ActivityWatch project.
