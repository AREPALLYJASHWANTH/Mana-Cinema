# Mana Cinema Audit Report

## Security and premium-chain repair pass (2026-10-18)

### Implemented

- Cloud Functions now provide admin-token-only, GET-only TMDB proxying; transactional `registerDevice`, install-bound `getPlaybackSources`, server-created Mana IDs, broadcast FCM chunking, and per-user notification delivery. Added Admin SDK scripts for the first admin claim and private-premium-source migration (dry-run by default).
- RTDB and Firestore rules were updated for owner-scoped user notifications, private sources, restore requests, notification image validation/indexes, premium comments, and the approved access-rule corrections.
- Admin `vault.bin` was decrypted to `/tmp/opencode/admin/index.html`, updated with TMDB proxy routing, premium movie/episode source separation, restore request review, targeted notifications, category retagging, episode skip fields, and XSS-safe data rendering, then re-encrypted. Node inline-script parse and AES decrypt/cmp round-trip pass. Ciphertext MD5: `2f8baf2a9ddb0e43fa13d5979ff1a764`.
- Android repository/ViewModel changes wire regional callables, install ID checks, Mana ID allocation, private-source resolution for play/download, targeted notification merging, restore-request submission, and removal of destructive clear-all catalog controls. Premium request records include Mana ID; premium rating UI is labeled for the user's own rating.
- Notification permission is deferred until onboarding/main UI, with a default channel, manifest channel metadata, and monochrome status icon. `google-services.json` was removed as approved; Gradle now requires `FIREBASE_API_KEY`, passed from the CI secret.
- All approved Ask-First selections were applied: user notifications node, indexes/createdAt, child read-rule removal, Firestore comment key allowlist, HTTPS image rules, google-services file removal, Crashlytics mappings request (plugin attempt reverted due incompatible plugin inputs), rating label, and inline-script CSP strategy.

### Verification and blockers

- Passed: `node --check` for Cloud Functions and migration/admin scripts; `new Function()` parse for the decrypted admin inline JavaScript; JSON parsing for RTDB rules, Firebase config, and Functions package metadata; `git diff --check`; admin vault decrypt/byte comparison.
- Android build was attempted with `bash gradlew clean testDebugUnitTest assembleDebug --stacktrace --console=plain`. Gradle 8.7 starts, but task dependency resolution stops because no Android SDK/`ANDROID_HOME` is available at the configured shared-storage path. No Kotlin compilation or Android tests ran.
- After removing the incompatible Crashlytics Gradle plugin, `bash gradlew tasks --console=plain` passes. A fresh build attempt still stops before compilation with `SDK location not found` at `/mnt/sdcard/AndroidIDEProjects/Mana cinema user /local.properties`.
- Shared storage retains `gradlew` as mode `0660`/Git mode `100644`; direct `./gradlew` fails. CI workflow now chmods the wrapper before invoking it.
- Crashlytics runtime collection remains enabled. The Crashlytics Gradle plugin was tested then removed: it warns/fails to configure mapping upload without the Google Services plugin, which this app intentionally omits to keep generated Firebase config resources out of the APK. Mapping upload remains pending a compatible code-only setup.
- No emulator/device or Firebase production project was available, so callable behavior, notification delivery, restore approval, premium playback/download, and rules deployment remain unverified live.

### Deployment and operator actions

- Rotate the TMDB API key exposed in the old admin HTML; configure the replacement as Functions secret `TMDB_API_KEY` before deploying Functions.
- Add GitHub Actions repository secret `FIREBASE_API_KEY`; configure local `secrets.properties` in AndroidIDE for builds.
- Correct/register the admin Firebase Web App `appId` in the Firebase console if the current Android-style ID does not match an existing web app.
- Back up RTDB, run `server/migrate-premium-sources.js` dry-run, review output, then explicitly run with `--apply`; deploy Functions before deploying rules.
- Set the first admin custom claim with `server/set-admin-claim.js`, build/install both APKs in AndroidIDE, then deploy rules and ship the user app after both clients support the new notification/source data paths.
- Required AndroidIDE verification: `bash gradlew clean testDebugUnitTest assembleDebug --stacktrace --console=plain`, then install both apps and test free/premium movie+episode playback/download, device-limit denial, broadcast/targeted notifications, Mana ID, restore approval/rejection, and TMDB proxy access.

### Residual risks

- Restore approval is a manual support action based on a user-entered Mana ID; verify the old account's premium subscription and expiry before approving. The old subscription is copied, not revoked.
- Existing production data needs migration before private-source read rules are enforced; rules deployment order is security-sensitive.
- The workspace had unrelated pre-existing tracked deletions/modifications in both projects; they were left untouched.

## Playback and catalog repair pass (2026-10-14)

### Confirmed defects corrected

- Extensionless and signed CDN URLs were unconditionally treated as embed pages. Routing now selects WebView only for known embed providers, explicit HTML pages, or embed/iframe paths; other URLs, including extensionless URLs, go to Media3.
- Two independent centered loading overlays could appear together. The duplicate compact spinner was removed, leaving one informative loading overlay; center transport controls are hidden until the player is ready or idle.
- Playback Retry previously called `prepare()` without rebuilding an errored/cleared source. Retry now recreates the current MediaItem with detected MIME type and subtitle configuration before preparing.
- Movie and series Firebase listeners emitted successful empty lists when the database could not initialize. They now close with an error, allowing the ViewModel error/retry path to distinguish an unavailable service from an empty catalog.

### Security and existing capabilities inspected

- The manifest references `@xml/network_security_config`, with global cleartext disabled and only localhost plus `6rga.a0p3.com` allowed for HTTP. HTTPS remains under system trust anchors; no certificate or hostname verification bypass was found.
- Media3 1.2.1 includes ExoPlayer, HLS, DASH, SmoothStreaming, and UI modules. Container extensions are hints only; actual codec support depends on device decoders. No format compatibility claim is made without device tests.
- The v31 system splash theme and single `MainActivity` launch path are present. Firebase is guarded against duplicate default-app initialization in `FirebaseInitializer`.
- User Panel Firebase catalog paths are `movies` and `webseries`; season/episode relationships are represented within `webseries/{id}/episodes`. Admin Panel source is not present in this project directory, so schema cross-check remains pending.

### Verification and deliverables

- Added JVM routing regression tests for extensionless signed URLs, provider embeds, and HTML player pages.
- `bash ./gradlew clean testDebugUnitTest assembleDebug` was attempted. It did not reach compilation: the configured AAPT2 path `/data/data/com.termux/files/usr/bin/aapt2` does not exist. Direct `./gradlew` also fails because shared storage does not preserve executable permission.
- No device, real streaming endpoints, Firebase production data, or Logcat were available. Playback HTTP redirects/status handling, codec/track behavior, downloads, PiP, fullscreen, notifications, catalog visibility, and network recovery require AndroidIDE/device QA.
- A verified project backup was created at `../Mana cinema user backup 20261014.tar.gz` (200 MB, tar integrity check passed).
- No debug APK or clean ZIP was produced because the Android build could not run. AndroidIDE: open this project, select a valid installed SDK/build-tools (platform 34), ensure AAPT2 is available, then run `bash ./gradlew clean testDebugUnitTest assembleDebug` or use the IDE Build APK action.
- Admin Panel audit remains pending because matching source is unavailable in this project directory.

## 2026-10-12 implementation pass

Implemented anonymous Firebase identity bootstrapping, Mana ID allocation, private playback callable scaffolding, admin WebView asset-origin hardening, CSP, TMDB proxy routing, release-signing failure checks, backup disablement, and external service-account path handling. The admin vault was decrypted, updated, and re-encrypted with the existing AES-256-CBC key/IV.

Verification is environment-limited: this workspace has no valid Android SDK (`local.properties` points to a missing shared-storage path), and Node/npm are unavailable. Both Gradle assemble attempts therefore require rerunning on AndroidIDE or a machine with SDK platform 34/build tools. Existing full-catalog listeners remain and should be paginated in a separate performance pass. Premium data migration into `private_sources` must be completed against the deployed database before enforcing private-source rules.

Date: 2026-08-19

## Scope

Inspected the Android user panel under `app/`, the standalone Admin panel at
`adminpanel/admin-1.html`, Firebase initialization and access paths, Room
schemas/migrations, and the Gradle wrapper/build configuration.

Live TV was removed on 2026-09-07 per product decision — see "Live TV removal"
below for what was taken out. It is no longer an immutable boundary.

## End-to-end feature flows

### Catalog and playback

`admin-1.html` writes movie records to RTDB `movies` and series records to
`webseries`. Movie and episode records support legacy `link`/`videoUrl` values
and quality-aware `videoSources`.

`MovieRepository` listens to `movies` and `webseries`, parses tolerant legacy
and current field shapes into `Movie`, `WebSeries`, and `Episode`, and refreshes
expired movie/episode URLs from the same RTDB nodes. It writes catalog caches to
Room `cached_movies` and `cached_series`.

`MovieViewModel` starts/restarts the repository listeners after authentication
changes, exposes catalog, category, profile, watchlist, history, premium, ad,
maintenance, notification, and update state, and persists network results to
Room.

`MainScreens.kt` renders home, category, search, detail, series detail,
watchlist, and downloads views. User playback actions navigate to `PLAY_MOVIE`
or `PLAY_SERIES`; Media3/ExoPlayer consumes the selected stream source. Progress
is saved locally and mirrored to Firestore profile history.

### Profiles and user state

Profiles, watchlists, history, and ratings use Firestore paths under
`users/{uid}/profiles/{profileId}`. Ban state, subscriptions, device records,
FCM tokens, and user records use RTDB under `users/{uid}`.

### Premium flow

Admin plan/payment data is read from RTDB `plans` and
`app_settings/payment`. The Android plan/payment screens submit
`premium_requests` records; the Admin panel reviews and approves/rejects them.
Approved subscription state is read from `users/{uid}/subscription`.

### Operations flow

Admin maintenance writes `maintenance`, notifications write `notifications`,
ads write `ads`, app update settings write `app_settings/version`, and avatar
settings use `app_settings/avatars`. Android repository listeners feed these
through `MovieViewModel` into the corresponding UI gates and screens.

### Missing or incomplete chain findings

- The Android user panel has no Live TV model, repository flow, ViewModel state,
  navigation route, screen, or playback pipeline. This gap is now moot — Live TV
  was removed from the Admin panel too (see below), so there is no Live TV
  surface anywhere in the project.
- The standalone Admin panel has no automated tests or type-checked build
  boundary; its Firebase CRUD is runtime JavaScript.
- Firebase security rules and deployed data were not present in this workspace,
  so rule correctness and production schema contents could not be verified.
- `secrets.properties` contains a Firebase API key in the workspace. It must be
  treated as sensitive and rotated if it has been exposed or is not ignored by
  the hosting workflow.
- Release signing uses the debug signing config in `app/build.gradle.kts`.
  This is unsuitable for production release distribution, but was not changed
  because signing changes are outside the requested build repair.

## Live TV removal (2026-09-07)

Live TV was removed end-to-end per product decision. This supersedes the
"immutable boundary" this report previously described.

Removed:
- Admin nav links: `nav-manage-livetv`, `nav-add-livetv`.
- Admin views: `manage-livetv-view`, `add-livetv-view`, and their form fields
  (`livetv-edit-id`, `livetv-name`, `livetv-logo`, `livetv-stream`,
  `livetv-category`).
- Admin CSS: `.livetv-grid`, `.livetv-card` and its variants.
- Admin functions: `clearLiveTvForm`, `loadLiveTvChannels`,
  `handleSaveLiveTv`, `editLiveTvChannel`, and the `loadLiveTvChannels()` init
  call and `showView` routing branches for Live TV.
- Admin dashboard stat card `total-livetv-stat`.
- Realtime Database `livetv` node and its rule block — `database.rules.json`
  (promoted from `database.rules.non-livetv.json`, which already excluded it)
  is now the canonical rules file, wired up via the new `firebase.json`.
- The Android app already had no Live TV code prior to this pass.

Not removed / intentionally left: the notification-icon picker's `fa-tv`
option in the admin panel was relabeled ("TV / Broadcast") rather than
deleted, since it's a generic FontAwesome icon choice for push notifications,
unrelated to the Live TV feature itself.

## Build repair and verification

The declared versions are compatible in principle: Gradle 8.7, AGP 8.4.2,
Kotlin 1.9.22/KSP 1.9.22-1.0.17, Java 17 compilation targets, and Java 21
runtime. The wrapper distribution itself runs successfully when invoked as
`bash ./gradlew`.

Observed build results (prior audit pass):

- `bash ./gradlew --version`: passed, Gradle 8.7 on JVM 21/aarch64.
- `bash ./gradlew clean`: passed.
- `bash ./gradlew assembleDebug`: blocked before compilation because the SDK
  path in `local.properties` does not exist and no `ANDROID_HOME` or
  `ANDROID_SDK_ROOT` is configured in this environment.
- `./gradlew --version`: blocked by mode `0660` on `gradlew` (`Permission
  denied`) when accessed from shared storage.

The wrapper executable permission was attempted at mode `0750`, but the
Android shared-storage mount preserves mode `0660`; consequently direct
`./gradlew` execution remains an environment limitation and `bash ./gradlew`
is the verified invocation here. This 2026-09-07 pass (Live TV removal, FCM
auto-send Cloud Function, multi-device token support, and Web Series slider
rework) was reviewed by manual inspection and brace/paren balance checks in
the same environment-limited sandbox — run `assembleDebug` on a machine with
the Android SDK configured before shipping.


To complete APK verification, install/provide an Android SDK with platform 34,
build-tools, and platform-tools, then set `ANDROID_HOME` or update the local
machine-only `sdk.dir` value and run:

```text
./gradlew --version
./gradlew clean
./gradlew assembleDebug
```

## Production readiness pass (2026-10-03)

### Critical fixes applied

- **Comments**: Firestore rules for `content_comments/{contentId}/comments/{commentId}` now enforce owner-only create/update/delete with admin moderation. Repository writes `userId`, `contentId`, `text`, `timestamp`, `userName`, `avatarUrl`.
- **Ratings**: Firestore `collectionGroup("ratings")` aggregate reader added; `movies/{id}` and `webseries/{id}` are read-only to all clients (aggregate updates must go through a trusted Cloud Function / admin SDK).
- **Guest movie requests**: `movie_requests` rules now match `requestedByUid == auth.uid` (anonymous users) or `requestedByEmail == auth.token.email`; repository writes both fields plus `isGuest`.
- **Admin TMDB**: Cloud Function `adminTmdb` proxies `/api/admin/tmdb/...` to `https://api.themoviedb.org/3` with `TMDB_API_KEY` in Secret Manager and optional `ADMIN_PROXY_TOKEN`; admin WebView `shouldInterceptRequest` routes to the deployed function.

### Security hardening

- Removed `server/service-account.json` and `server/fcm-server-key.txt` from the workspace; added to `.gitignore`.
- `secrets.properties` (containing `FIREBASE_API_KEY`) is git-ignored; key must be rotated if it has been shared.
- Cleartext HTTP blocked globally (`usesCleartextTraffic=false`, `cleartextTrafficPermitted=false`); localhost-only exception kept for debugging.
- Release signing now uses `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` from `secrets.properties` or environment; missing values cause a build error for release instead of silently using debug keys.
- Notifications no longer expose `targetEmails` to clients; payload is marked `pushed` by the Cloud Function only.

### Remaining warnings

- No Android SDK is available in this environment (`sdk.dir` points to an AndroidIDE path on a different device), so `assembleDebug` / `assembleRelease` could not be run here. Balanced-parenthesis checks pass for all modified files.
- The encrypted `vault.bin` in `Mana cinema admin` was not modified (only the host `MainActivity.kt` was changed).
- Cloud Functions must be deployed (`firebase deploy --only functions`) with `TMDB_API_KEY` and `ADMIN_PROXY_TOKEN` set for TMDB to work.
- Firebase Auth custom claim `admin=true` must be set via server/provision.js for admin panel access.
- Device limit race condition can still be bypassed in the tiny window between `get()` and `setValue()`; for strict enforcement move this to a Realtime Database transaction or the premium-approval Cloud Function.
- `getAggregateRating` uses a `collectionGroup("ratings")` scan; for large user bases, maintain a `ratingSum`/`ratingCount` on the content document via Cloud Functions instead.

## OneSignal & push notification audit (2026-10-13)

Scope: verify OneSignal in the User Panel and Admin Panel, remove the welcome dialog, and confirm the admin→user push workflow.

### Findings

- **User Panel OneSignal — integrated.** `OneSignal:5.9.2` in `app/build.gradle.kts`; `OneSignalManager.initialize()` called from `ManaCinemaApplication`; `login(uid)`/`logout()` from `MovieViewModel`; `POST_NOTIFICATIONS` declared. App ID is a single shared app: `01d0e8d5-9ea8-48c6-86f1-56ff2281f928`. OneSignal is registration-only in this app (no REST/dashboard send integration); actual delivery uses FCM.
- **Admin Panel OneSignal — not integrated, and not required.** The Admin Panel sends notifications through the existing secure FCM pipeline: admin HTML UI writes `notifications/{id}` (RTDB rules require `auth.token.admin == true`), the `sendNotificationOnCreate` Cloud Function sends via `firebase-admin` messaging using per-`uid` FCM tokens. No OneSignal REST key or service-account credential exists in either app or repo.
- **Welcome dialog removed.** `Your OneSignal SDK integration is complete!` was custom code in `OneSignalManager.kt`, not SDK sample UI. It and its activity hooks were removed; OneSignal init, observer, login/logout, and a one-time permission request remain (`requestPermissionIfNeeded`, gated per install).
- **FCM token registration bug fixed.** `AppFirebaseMessagingService` previously wrote tokens to `users/installations/{installationId}/fcmTokens/...`, which the RTDB rules deny (self-write only under `users/{uid}`) and the Cloud Function ignores. It now writes `users/{uid}/fcmTokens/{token}/updatedAt` + legacy `users/{uid}/fcmToken`, and `flushPendingToken()` replays any token captured before anonymous sign-in.

### Files changed

- `app/src/main/java/com/manacinema/app/notifications/OneSignalManager.kt` (dialog removed; one-time permission; lastSubscriptionId)
- `app/src/main/java/com/manacinema/app/MainActivity.kt` (dropped OneSignal activity hooks)
- `app/src/main/java/com/manacinema/app/firebase/AppFirebaseMessagingService.kt` (per-uid token registration + pending flush)
- `app/src/main/java/com/manacinema/app/ui/MovieViewModel.kt` (flush pending FCM token after sign-in; unused import removed)

### Verification status (environment-limited)

- OneSignal 5.9.2 APIs re-verified against the SDK at `/root/.gradle/caches/modules-2/files-2.1/com.onesignal/` (`PushSubscriptionChangedState.getCurrent().getId()`, etc.).
- `assembleDebug` still blocked in this Termux/Bionic host before Kotlin compilation: AAPT2 cannot run (`Specified AAPT2 executable does not exist: /data/data/com.termux/files/usr/bin/aapt2`; the SDK's Linux `aapt2` binary is incompatible with this host). Rebuild in AndroidIDE or a machine with SDK platform 34/build-tools, or point `android.aapt2FromMavenOverride` at a working aapt2.
- No real device test performed; device registration and dashboard delivery must be verified on-device (steps below).

### Manual on-device verification steps

1. Build/install the User Panel; grant notification permission → confirm the system prompt appears once and is not re-forced after denial.
2. Confirm no `Your OneSignal SDK integration is complete!` dialog appears.
3. Check the RTDB `users/{uid}/fcmTokens` contains the device token (`fcmTokens` map, not `users/installations`).
4. In the Admin Panel → Send Notification, broadcast to a non-banned user and to a specific email/UID; verify FCM delivery while the app is foregrounded, backgrounded, and on tap.
