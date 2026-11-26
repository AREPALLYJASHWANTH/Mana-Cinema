/**
 * Grants the admin=true custom claim to a firebase auth user (P0-6).
 *
 * Usage:
 *   GOOGLE_APPLICATION_CREDENTIALS=/path/service-account.json \
 *     node server/set-admin-claim.js admin@example.com
 *
 * Prints a success line only. The service-account file must stay OUTSIDE the
 * project (path supplied via env), never committed.
 */
const admin = require("firebase-admin");

async function main() {
  const email = process.argv[2];
  if (!email) {
    console.error("Usage: node server/set-admin-claim.js <admin-username-or-email>");
    process.exit(1);
  }
  if (!process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    console.error("GOOGLE_APPLICATION_CREDENTIALS must point to the service-account JSON.");
    process.exit(1);
  }
  admin.initializeApp({ credential: admin.credential.applicationDefault() });

  let user;
  try {
    if (email.includes("@")) {
      user = await admin.auth().getUserByEmail(email);
    } else {
      user = await admin.auth().getUser(email);
    }
  } catch (e) {
    console.error("No Firebase user found for that identity.");
    process.exit(1);
  }

  await admin.auth().setCustomUserClaims(user.uid, {
    ...(user.customClaims || {}),
    admin: true,
  });
  // The panel refreshes its token on next sign-in; force a token invalidation so
  // the claim appears on the very next login.
  await admin.auth().revokeRefreshTokens(user.uid);
  console.log("Admin claim set for:", user.email || user.uid);
  process.exit(0);
}

main().catch((e) => {
  console.error("Failed to set admin claim.");
  process.exit(1);
});
