# StorageNAS

StorageNAS is an Android app for sending files to a NAS over SFTP, with queue management, retry controls, and ZeroTier-aware connectivity routing.

## Audience guide

This README is structured for multiple audiences:

- **App users**: understand what the app does and what is required to run it.
- **Developers**: build and test the project locally.
- **Maintainers/contributors**: understand dependency constraints (especially ZeroTier/libzt).

## What the app does

- Uploads files/folders to NAS destinations over SFTP (SSHJ).
- Maintains a persistent upload queue with task status (`PENDING`, `QUEUED`, `UPLOADING`, `SUCCESS`, `FAILED`, `CANCELLED`).
- Supports retrying failed uploads, stopping active uploads, and clearing queue entries.
- Uses WorkManager for background upload execution and foreground notifications for long-running transfers.
- Supports Android share intents (`SEND`, `SEND_MULTIPLE`) so uploads can be started from other apps.
- Includes ZeroTier connection controls with route strategies:
  - `SYSTEM_ROUTE_FIRST`
  - `AUTO_FALLBACK`
  - `EMBEDDED_ONLY`

## libzt (ZeroTier SDK) requirement

Short answer: **with the current codebase, `libzt` is effectively mandatory for building the app**.

- Place vendor AAR in `app/libs/` (recommended name: `libzt.aar`).
- The Gradle script can resolve `libs/libzt.aar` or another `*.aar` filename containing `libzt`/`zerotier`.

### Why this is mandatory today

Even though some logic checks runtime availability, the app still contains direct compile-time references to `com.zerotier.sockets` classes (for example in SFTP socket integration and ZeroTier event handling). Because of that, the project needs the SDK AAR present during compilation.

### Why this might appear optional in Gradle logs

`app/build.gradle.kts` currently logs warnings that suggest embedded features can be disabled when the AAR is missing. That message is misleading for the current implementation because direct imports still require the classes at compile time.

## For app users

- Configure NAS host, port, and credentials in Settings.
- Optionally configure ZeroTier network settings, depending on network topology.
- Queue uploads from inside the app or via Android share intents.
- Monitor progress and retry failed uploads from the Queue screen.

## For developers

- Keep vendor dependencies out of public commits unless licensing explicitly allows redistribution.
- Ensure `app/libs/libzt.aar` (or equivalent matching `libzt`/`zerotier`) is present before compiling.
- Use the build/test command below for baseline verification.

## Requirements

- Android Studio (latest stable)
- JDK 11+
- Android SDK configured via local `local.properties`
- ZeroTier vendor SDK AAR in `app/libs/` (see section above)

## Build and test

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --no-daemon --console=plain
```

## Security notes

- No local machine absolute paths should be committed.
- No hardcoded credentials should be committed.
- Runtime credentials/tokens are user-provided through app settings.

## Publishing manually to GitHub (no local push)

See [`PUBLISHING.md`](PUBLISHING.md) for a safe workflow that avoids pushing to unintended remotes.
