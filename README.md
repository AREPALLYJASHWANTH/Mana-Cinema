# Mana Cinema

Mana Cinema is a native Android application for browsing and watching movies and web series. It is built with Kotlin and Jetpack Compose, with Firebase-backed catalog and account functionality, local Room persistence, and Media3 playback. This README documents the Android app and repository workflows established by the supplied inspection results; it does not imply that remote services are deployed or currently available.

## Verified capabilities

- Browse movie and series catalog sections, including category/genre, trending, new-release, top-item, and recommendation rows. Kid profiles filter recommendations to family-oriented tags.
- Create and select profiles with kid and preferred-genre attributes. Profile data is stored through Firestore for authenticated users.
- Keep a per-profile watchlist and continue-watching progress locally in Room; repository code also synchronizes watchlist and history to Firestore when applicable.
- Stream movies and episodes from enabled, usable sources, with quality normalization and legacy-link fallback. The player routes recognized embed pages through WebView and other media URLs through native playback.
- Use subtitles, quality caps for adaptive streams, aspect modes, picture-in-picture, cast controls, minimized playback, and series episode navigation.
- Download movies and episodes for local playback. Download status is persisted in Room; the service supports progress, retry/redirect/range handling, subtitle metadata, and resumption after connectivity returns.
- Use Firebase Messaging and OneSignal notification integrations, Chromecast, and bundled Bengali, Hindi, Tamil, and Telugu translations. VPN detection can block/exit the app when a VPN is active.
- Premium flags on a series or episode determine episode lock status in the app. Actual entitlement behavior depends on backend data and was not verified at runtime.

## Architecture

`MainActivity` hosts the Compose UI and coordinates onboarding, theme/localization composition, connectivity/offline state, interrupted-download resumption, and VPN checks. `ManaCinemaApplication` initializes crash logging, Firebase, OneSignal, notification channels, and Coil image loading.

`MovieViewModel` exposes `StateFlow` UI state. `MovieRepository` coordinates Firebase Realtime Database, Firestore, callable Functions, and Room. Room `AppDatabase` (version 10) persists cached catalog content, watchlists, continue-watching progress, downloads, and ratings. `VideoPlayerView` wraps Media3 ExoPlayer. `DownloadService` is a non-exported foreground data-sync service. Firebase messaging and OneSignal handle notification integrations; `CastOptionsProvider` configures Google Cast's default media receiver.

The repository also contains Firebase Functions and rules, plus two standalone administrative Node.js scripts. The scripts are operational tools, not a general-purpose server: one previews/applies a premium-source migration, and one grants an admin custom claim. See **Backend and administrative tooling** before using them.

## Prerequisites

- JDK 17.
- Android SDK platform 34, build-tools 34.0.0, and platform-tools. Set up the SDK for your environment (for local builds, ensure Gradle can locate it, for example through Android Studio/`ANDROID_HOME`).
- The included Gradle wrapper (Gradle 8.7); no separate Gradle installation is needed.
- A `FIREBASE_API_KEY` value for Gradle configuration. The app build requires this even for local compilation/tests. Obtain an appropriate value through your project’s authorized configuration; do not commit it.
- For device/instrumented tests, a connected or running Android device/emulator. For Firebase-backed runtime features, appropriate Firebase project configuration and backend data are also needed.

The app module is `:app`, with application ID/namespace `com.manacinema.app`, minSdk 26, targetSdk 34, Java/Kotlin target 17, and Compose enabled.

## Local setup and build

From the repository root, provide `FIREBASE_API_KEY` by one of the supported sources. Gradle checks `secrets.properties`, then `local.properties`, then the `FIREBASE_API_KEY` environment variable. The files are intended to remain local and are gitignored. For example, using an environment variable in the current shell:

```sh
export FIREBASE_API_KEY='<your-authorized-value>'
./gradlew assembleDebug
```

Alternatively, add a local `FIREBASE_API_KEY` property to `secrets.properties` or `local.properties`; do not publish either file or its contents. Firebase initialization is handled in app code; the Google Services Gradle plugin is not applied. Do not invent or copy Firebase credentials from other sources.

Useful commands:

```sh
# Build the debug APK
./gradlew assembleDebug

# Run JVM unit tests
./gradlew testDebugUnitTest

# Run instrumented tests on a connected device/emulator
./gradlew connectedDebugAndroidTest

# Build the debug APK and copy it to the repository root as Mana_Cinema.apk
./gradlew copyApk
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. `copyApk` depends on `assembleDebug` and copies that APK to `Mana_Cinema.apk` at the project root. The tests include JVM coverage for video-source quality normalization/filtering, legacy link fallback, premium episode locking, and URL routing, plus an instrumented Room migration test.

The Gradle test task names above follow the Android plugin conventions and declared configuration; they were not executed as part of the inspection. Migration-test comments indicate historical exported baselines for versions 1–6 were unavailable. Schemas 7, 8, and 10 are present; no v9 schema JSON was found in the inspected tree. The visible instrumented test validates a v7 baseline and v7→v8. Treat the full historical migration chain as incompletely validated. Destructive migration fallback is limited to database versions 1–4.

## Services and configuration

**Required to configure/build:** `FIREBASE_API_KEY` must be present for Gradle configuration. Release assembly additionally requires release-signing configuration and an existing keystore.

**Required for corresponding runtime features, not for the basic local debug build:** Firebase services/backend configuration and data are needed for remote catalog, profile/account synchronization, callable-function operations, and messaging. OneSignal configuration is needed for that notification integration; Google Cast support requires a Cast-compatible environment. Inspection does not establish live service availability, production deployment, or entitlement outcomes. Storage access policy cannot be inferred: no Firebase Storage rules file or mapping was found in the inspected configuration.

### Firebase sign-in troubleshooting

The client signs in anonymously before loading the catalog; the Realtime Database rules require an authenticated Firebase user. If startup reports a Firebase connection/authentication failure, check that **Authentication → Sign-in method → Anonymous** is enabled for the configured Firebase project, then verify that `FIREBASE_API_KEY` belongs to that project and permits Firebase Authentication. Network restrictions, VPN/firewall settings, and temporary Firebase rate limits can also prevent sign-in. The app reports known Firebase error codes with specific guidance after its bounded retries.

Firebase Functions use Node.js 20, with source under `functions/`; `firebase.json` maps Realtime Database and Firestore rules and declares a Functions predeploy install command. Functions include notification triggers, callables for device registration/playback sources/Mana ID, and an admin-authenticated TMDB proxy. The proxy uses a Firebase Functions secret named `TMDB_API_KEY`; its value is not included here. No deploy, emulator, or project-selection commands were established by the inspection, so this README does not prescribe deployment steps.

## Release workflow

The Android CI workflow runs on pushes to `main` or `master` and manual dispatch. It provisions JDK 17 and Android SDK 34 components, runs debug unit tests and assembles the debug APK, then uploads the APK and available reports.

The release workflow runs for pushed tags matching `v*`. It runs `testDebugUnitTest` and `assembleRelease`, publishes the release APK as a workflow artifact, and creates a GitHub Release with the APK attached. It can also be manually dispatched from the `main` branch with a tag input, which rebuilds the source at that tag and uploads the APK to an existing release or creates the release if absent. For an automatic release, push a tag such as `v1.0.1` with `git tag v1.0.1 && git push origin v1.0.1`. The release APK path is `app/build/outputs/apk/release/app-release.apk`. Release builds enable minification and resource shrinking. The inspected workflow retains artifacts for 30 days.

The current Android Gradle configuration still sets `versionCode = 1` and `versionName = "1.0"`; the Git tag labels the GitHub Release but does not change those values inside the APK. Update the app version fields before producing an APK intended to upgrade an existing installation.

Configure these GitHub Actions secrets for release workflow operation:

- `FIREBASE_API_KEY`
- `RELEASE_KEYSTORE_BASE64` — base64-encoded JKS keystore; workflow decodes it to a temporary file.
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

The workflow passes the decoded keystore path as `RELEASE_STORE_FILE`. Locally, release signing reads `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` from properties or environment, and fails if required values are missing or the keystore does not exist. Never commit credentials or keystores.

## Backend and administrative tooling

The standalone scripts under `server/` require a resolvable `firebase-admin` installation and Application Default Credentials supplied with `GOOGLE_APPLICATION_CREDENTIALS`. There is no root package manifest or verified script-specific dependency installation command. Confirm the target project and database before any operation.

The premium-source migration defaults to a dry run; adding `--apply` writes changes after exporting movie and series snapshots to a JSON backup beside the script. Protect and verify that backup, inspect the planned paths, and retain it securely. The dry-run output shows only the first 30 planned paths. Series path construction depends on episode season/episode metadata, so review data for inconsistencies before applying. The admin-claim script accepts an email or UID, merges `admin: true` into existing claims, and revokes refresh tokens; this can invalidate existing sessions. Neither script was verified by successful live execution.

## Caveats

- Findings are based on inspection results, not an executed build, test suite, deployment, or live backend check.
- Remote-service behavior and current Firebase Console/deployment state are not established by repository configuration alone.
- Historical Room migration validation is incomplete as described above.
- The manifest requests network, notification, wake-lock, foreground data-sync, and older-version external-storage permissions; cleartext traffic is disabled by default, and app backup is disabled.
