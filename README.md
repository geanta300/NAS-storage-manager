# StorageNAS

StorageNAS is an Android app for uploading, syncing, and browsing NAS files over SFTP, with optional ZeroTier-assisted connectivity.

## Project status

This repository is prepared for **manual GitHub publishing**.
It is intentionally configured so local build/cache artifacts and heavy vendored folders are not included in commits.

## Requirements

- Android Studio (latest stable)
- JDK 11+
- Android SDK configured via local `local.properties`

## Build and test

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --no-daemon --console=plain
```

## Optional embedded ZeroTier SDK

If you want embedded ZeroTier support, place the vendor AAR at:

- `app/libs/libzt.aar`

If the AAR is not present, embedded features are disabled and the project still builds.

## Security notes

- No local machine absolute paths should be committed.
- No hardcoded credentials should be committed.
- Runtime credentials/tokens are user-provided through app settings.

## Publishing manually to GitHub (no local push)

See [`PUBLISHING.md`](PUBLISHING.md) for a safe workflow that avoids pushing to unintended remotes.
