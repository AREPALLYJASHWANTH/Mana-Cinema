/**
 * Mana Cinema backend (Firebase Functions v2).
 *
 * Global region matches the Realtime Database (asia-southeast1) so every trigger
 * and callable runs co-located with the data it touches (P0-2e).
 */
const { onValueCreated, setGlobalOptions } = require("firebase-functions/v2/database");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { logger } = require("firebase-functions");
const admin = require("firebase-admin");
const { adminTmdb } = require("./tmdb");

setGlobalOptions({ region: "asia-southeast1" });

admin.initializeApp();

const DEVICE_STALE_MS = 45 * 86400000; // same window as getPlaybackSources
const FCM_MULTICAST_CHUNK = 500;

function isBanned(value) {
  return value === true || value === "true" || value === 1;
}

/** Every token a user currently has registered: the multi-device map, plus the legacy single field for older app builds. */
function tokensForUser(record) {
  const tokens = new Set();
  if (record && record.fcmToken) tokens.add(record.fcmToken);
  if (record && record.fcmTokens && typeof record.fcmTokens === "object") {
    Object.keys(record.fcmTokens).forEach((token) => tokens.add(token));
  }
  return Array.from(tokens);
}

function collectUsers(usersSnapshotVal) {
  const users = [];
  if (!usersSnapshotVal) return users;
  Object.keys(usersSnapshotVal).forEach((uid) => {
    const record = usersSnapshotVal[uid];
    const tokens = tokensForUser(record);
    if (tokens.length === 0) return;
    users.push({
      uid,
      email: (record.email || "").toLowerCase(),
      tokens,
      banned: isBanned(record.isBanned),
    });
  });
  return users;
}

function targetTokens(users, notification) {
  if (notification.target === "specific") {
    // Legacy targeted records (old admin builds) still resolve emails/UIDs here.
    // New admin builds never write a target into /notifications (see
    // user_notifications below), so this protects older data without promoting it.
    const entries = String(notification.targetEmails || "")
      .split(",")
      .map((entry) => entry.trim().toLowerCase())
      .filter(Boolean);
    const matched = users.filter(
      (user) => entries.includes(user.email) || entries.includes(user.uid)
    );
    return matched.flatMap((user) => user.tokens.map((token) => ({ token, uid: user.uid })));
  }
  return users
    .filter((user) => !user.banned)
    .flatMap((user) => user.tokens.map((token) => ({ token, uid: user.uid })));
}

/** Data payload + platform config for one FCM message (R4). Never exceeds 4 KB. */
function messageCommon(notification) {
  const imageUrl =
    typeof notification.imageUrl === "string" &&
    notification.imageUrl.startsWith("https://") &&
    notification.imageUrl.length <= 1000
      ? notification.imageUrl
      : "";
  return {
    data: {
      id: notification.id || "",
      title: notification.title || "",
      message: notification.message || "",
      imageUrl,
      movieId: notification.movieId || "",
    },
    android: {
      priority: "high",
      notification: Object.assign(
        {
          channelId: "mana_cinema_default",
        },
        imageUrl ? { imageUrl } : {}
      ),
    },
  };
}

function notificationFields(notification) {
  return {
    notification: {
      title: notification.title || "Mana Cinema",
      body: notification.message || "",
    },
  };
}

/** Removes dead tokens from per-response send errors (R5). */
async function cleanStaleTokens(staleTokensByUid) {
  const db = admin.database();
  const cleanupWrites = Object.entries(staleTokensByUid).map(([uid, tokens]) => {
    const updates = {};
    tokens.forEach((token) => {
      updates[`users/${uid}/fcmTokens/${token}`] = null;
      updates[`users/${uid}/fcmToken`] = null;
    });
    return db.ref().update(updates);
  });
  await Promise.all(cleanupWrites);
}

/**
 * Sends one notification value to a fixed list of {token, uid} targets using
 * sendEachForMulticast in chunks of 500 (R5 — never more than ~60s in one call).
 */
async function sendToTargets(notification, targets) {
  let sent = 0;
  let failed = 0;
  const staleTokensByUid = {};

  for (let i = 0; i < targets.length; i += FCM_MULTICAST_CHUNK) {
    const chunk = targets.slice(i, i + FCM_MULTICAST_CHUNK);
    const multicast = Object.assign(
      { tokens: chunk.map((t) => t.token) },
      notificationFields(notification),
      messageCommon(notification)
    );
    try {
      const response = await admin.messaging().sendEachForMulticast(multicast);
      sent += response.successCount;
      failed += response.failureCount;
      response.responses.forEach((res, idx) => {
        if (res.success) return;
        const code = res.error && res.error.code;
        if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
          const uid = chunk[idx].uid;
          staleTokensByUid[uid] = staleTokensByUid[uid] || [];
          staleTokensByUid[uid].push(chunk[idx].token);
        } else {
          logger.warn(`Send failed for ${chunk[idx].uid}: ${res.error && res.error.message}`);
        }
      });
    } catch (error) {
      failed += chunk.length;
      logger.warn(`Multicast chunk failed: ${error && error.message}`);
    }
  }

  await cleanStaleTokens(staleTokensByUid);
  return { sent, failed };
}

/**
 * Broadcast push: triggered by writes to RTDB `notifications/{notificationId}`.
 * Only BROADCAST records (target != "specific") are honored here; targeted
 * notifications live under user_notifications (below) and never leak recipient
 * lists to every client.
 */
exports.sendNotificationOnCreate = onValueCreated(
  "/notifications/{notificationId}",
  async (event) => {
    const notification = event.data.val();
    const notificationId = event.params.notificationId;

    if (!notification || notification.pushed === true) {
      return null;
    }

    const db = admin.database();
    const usersSnap = await db.ref("users").get();
    const users = collectUsers(usersSnap.val());
    const targets = notification.target === "specific" ? [] : targetTokens(users, notification);

    logger.info(`Broadcast notification ${notificationId}: ${targets.length} device(s).`);

    const { sent, failed } = await sendToTargets(notification, targets);

    await event.data.ref.update({
      pushed: true,
      sentCount: sent,
      failedCount: failed,
      sentAt: admin.database.ServerValue.TIMESTAMP,
    });

    logger.info(`Broadcast notification ${notificationId}: sent=${sent} failed=${failed}`);
    return null;
  }
);

/**
 * Targeted push: admin writes one record per recipient under
 * user_notifications/{uid}/{notificationId}. This trigger sends that record to
 * exactly that UID's devices — no `users` tree scan, no recipient leakage.
 */
exports.userNotificationsOnCreate = onValueCreated(
  "/user_notifications/{uid}/{notificationId}",
  async (event) => {
    const uid = event.params.uid;
    const notification = event.data.val();

    if (!notification || notification.pushed === true) {
      return null;
    }

    const db = admin.database();
    const userSnap = await db.ref(`users/${uid}`).get();
    const user = userSnap.val();
    if (!user || isBanned(user.isBanned)) {
      // Keep the record (the app merges it into its notification list) but do not push.
      return null;
    }
    const records = collectUsers({ [uid]: user });
    const targets = records.flatMap((u) => u.tokens.map((token) => ({ token, uid })));

    const { sent, failed } = await sendToTargets(notification, targets);

    await event.data.ref.update({
      pushed: true,
      sentCount: sent,
      failedCount: failed,
      sentAt: admin.database.ServerValue.TIMESTAMP,
    });

    logger.info(`Targeted notification ${event.params.notificationId} for ${uid}: sent=${sent} failed=${failed}`);
    return null;
  }
);

// ------------------ PREMIUM CHAIN (P0-2) ------------------

/** Active (non-stale) device entries of a user record. */
function activeDevices(userVal) {
  const devices = (userVal && userVal.devices) || {};
  return Object.entries(devices).filter(
    ([installId, d]) => installId && d && Number(d.lastSeen || 0) > Date.now() - DEVICE_STALE_MS
  );
}

/**
 * Registers one installation for the caller, enforcing the subscription's device
 * limit transactionally and pruning idle devices (P0-2b).
 *
 * The old client-side RTDB transaction was DENIED by the rules (writes cascade
 * down only; only devices/$installationId is writable), so premium users always
 * saw "device limit reached". This callable performs the same logic with Admin
 * SDK and returns a plain status instead of throwing for the limit.
 */
exports.registerDevice = onCall(async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Authentication is required.");
  const uid = request.auth.uid;
  const installId = String((request.data && (request.data.installId || request.data.installationId)) || "");
  if (!installId || installId.length > 128) {
    throw new HttpsError("invalid-argument", "A valid installId is required.");
  }

  const db = admin.database();
  const user = (await db.ref(`users/${uid}`).get()).val() || {};
  const sub = user.subscription || {};
  if (isBanned(user.isBanned)) throw new HttpsError("permission-denied", "Account is restricted.");
  if (sub.isPremium !== true || Number(sub.expiryDate || 0) <= Date.now()) {
    throw new HttpsError("permission-denied", "Premium subscription required.");
  }
  const limit = Math.max(1, Number(sub.deviceLimit || 1));

  const result = await db.ref(`users/${uid}/devices`).transaction((current) => {
    const now = Date.now();
    const devices = current || {};
    // Prune devices idle longer than the playback window first.
    Object.keys(devices).forEach((id) => {
      if (!devices[id] || Number(devices[id].lastSeen || 0) <= now - DEVICE_STALE_MS) {
        delete devices[id];
      }
    });
    if (devices[installId]) {
      devices[installId].lastSeen = now;
      return devices;
    }
    if (Object.keys(devices).length >= limit) {
      return undefined; // abort — limit reached
    }
    devices[installId] = { registeredAt: now, lastSeen: now };
    return devices;
  });

  if (!result.committed) return { status: "LIMIT_REACHED" };
  return { status: "ALLOWED" };
});

/**
 * Returns playback/download sources for premium content. Caller must hold an
 * active subscription AND be the registered device identified by installId
 * (P0-2b) — endpoint hand-out without device binding would let one premium
 * account feed an arbitrary number of unregistered devices.
 */
exports.getPlaybackSources = onCall(async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Authentication is required.");
  const { contentId, season, episode, installId, installationId } = request.data || {};
  if (typeof contentId !== "string" || !contentId || contentId.length > 128) {
    throw new HttpsError("invalid-argument", "A valid contentId is required.");
  }
  const deviceKey = typeof installId === "string" ? installId : installationId;
  if (typeof deviceKey !== "string" || !deviceKey || deviceKey.length > 128) {
    throw new HttpsError("invalid-argument", "A valid installId is required.");
  }

  const db = admin.database();
  const user = (await db.ref(`users/${request.auth.uid}`).get()).val() || {};
  const sub = user.subscription || {};
  if (isBanned(user.isBanned)) throw new HttpsError("permission-denied", "Account is restricted.");
  if (sub.isPremium !== true || Number(sub.expiryDate || 0) <= Date.now()) {
    throw new HttpsError("permission-denied", "Premium subscription required.");
  }
  const registered = activeDevices(user);
  const limit = Math.max(1, Number(sub.deviceLimit || 1));
  if (registered.length > limit) {
    throw new HttpsError("permission-denied", "Device limit exceeded for this plan.");
  }
  const own = registered.find(([id]) => id === deviceKey);
  if (!own) {
    throw new HttpsError("permission-denied", "Register this device before streaming premium content.");
  }
  const lastSeen = Number((own[1] && own[1].lastSeen) || 0);
  if (lastSeen > Date.now() - 60000) {
    // Refresh the device heartbeat best-effort; ignore failures.
    db.ref(`users/${request.auth.uid}/devices/${deviceKey}/lastSeen`)
      .set(Date.now())
      .catch(() => {});
  }

  let path = `private_sources/${contentId}`;
  if (season != null && episode != null) path += `/seasons/${season}/episodes/${episode}`;
  const node = (await db.ref(path).get()).val();
  if (!node) throw new HttpsError("not-found", "Playback sources unavailable.");
  const sources = Array.isArray(node.videoSources) ? node.videoSources : Object.values(node.videoSources || {});
  return {
    sources: sources
      .filter((s) => s && s.enabled !== false && (typeof s.streamUrl === "string" || typeof s.downloadUrl === "string"))
      .map((s) => ({
        quality: String(s.quality || ""),
        streamUrl: String(s.streamUrl || ""),
        downloadUrl: String(s.downloadUrl || ""),
        fileSize: String(s.fileSize || ""),
        format: String(s.format || ""),
        server: String(s.server || ""),
      })),
  };
});

/**
 * Mana ID: claims a unique short id for the caller transactionally and writes
 * it to users/{uid}/shortId (P0-5). Idempotent — callers may repeat it safely.
 */
exports.ensureManaId = onCall(async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Authentication is required.");
  const uid = request.auth.uid;
  const db = admin.database();

  const existing = (await db.ref(`users/${uid}/shortId`).get()).val();
  if (typeof existing === "string" && existing.length >= 4) {
    // Keep short_ids in sync in case a previous run died between the two writes.
    const claimed = (await db.ref(`short_ids/${existing}`).get()).val();
    if (!claimed) await db.ref(`short_ids/${existing}`).set(uid).catch(() => {});
    return { shortId: existing };
  }

  for (let attempt = 0; attempt < 10; attempt++) {
    const generated =
      "MC-" + Math.random().toString(36).slice(2, 8).toUpperCase().padEnd(6, "0").slice(0, 6);
    const result = await db.ref(`short_ids/${generated}`).transaction((current) => {
      if (current != null) return undefined; // taken — abort, caller retries
      return uid;
    });
    if (result.committed) {
      await db.ref(`users/${uid}/shortId`).set(generated);
      return { shortId: generated };
    }
  }
  throw new HttpsError("unavailable", "Could not allocate a Mana ID right now, try again.");
});

exports.adminTmdb = adminTmdb;
