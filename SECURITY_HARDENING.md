# Firebase Security Hardening

Live TV remains intentionally removed.

## Authorization model

The admin panel must verify Firebase custom claims and all privileged writes must be rules-protected. The WebView now blocks file URL access, uses an Android asset origin for its encrypted page, restricts top-level navigation, disables backup, and sets FLAG_SECURE. Vault encryption is obfuscation only.

Set the claim only from a trusted Admin SDK environment, for example a secured
Cloud Function or controlled deployment script. Never set it from client code
or from an RTDB-writable path.

## Protected operations

The non-Live-TV RTDB policy restricts content CRUD, categories, maintenance,
notifications, plans, ads, bans, subscriptions, app settings, and premium
approval/rejection to the Admin custom claim. User-created movie and premium
requests are constrained to the authenticated user's email/UID and pending
state. User FCM tokens and device registrations are owner-only.

Firestore profile documents and their `watchlist`, `history`, and `ratings`
subcollections are owner-only, with Admin claim access for support operations.

## Credential audit

- No FCM server key, service-account JSON, private key, or admin password was
  found in the Android source or Admin panel.
- Firebase web configuration and Android Firebase API keys are public client
  identifiers. They do not grant database access when rules are correct, but
  Firebase API-key restrictions should still be configured where supported.
- TMDB access is intended to use the `adminTmdb` Cloud Function with its key in Secret Manager. Deploy and verify admin-token authorization before using it.
- Android already reads `FIREBASE_API_KEY` from `secrets.properties`,
  `local.properties`, or the environment into `BuildConfig`; keep those files
  outside source control and never put server credentials there.

## Required rotations and deployment steps

1. Rotate the Firebase API key if the workspace or APK was distributed beyond
   the intended audience. This is defense in depth; rules are the real access
   control.
2. Rotate any TMDB key previously held in browser `localStorage`, then deploy
   the authenticated server proxy before enabling TMDB import.
3. A service-account JSON was present locally and has been removed from the workspace. Rotate its key in Google Cloud immediately; local deletion does not remove git history or revoke credentials.
4. Initialize `users/{uid}/isBanned` from Admin SDK/Cloud Functions; the Android
   client no longer writes this privileged field.
5. Set `admin: true` as a Firebase Auth custom claim only through Admin SDK.
6. Use Firebase Emulator Suite allow/deny tests before deploying the current complete rule files.
7. Migrate premium stream links into `private_sources` before rules deployment; RTDB ancestor read grants cannot be undone by child rules.
