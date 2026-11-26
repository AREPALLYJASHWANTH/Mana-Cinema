/**
 * TMDB proxy Cloud Function (P0-4).
 *
 * The admin panel (origin https://appassets.androidplatform.net) calls
 *   /api/admin/tmdb/search/{type}?query=...
 *   /api/admin/tmdb/{type}/{id}?append_to_response=credits
 *   /api/admin/tmdb/tv/{id}/season/{n}
 *
 * Access rules:
 *  - ALWAYS requires a verified Firebase ID token with the admin=true claim;
 *    the old ADMIN_PROXY_TOKEN path is gone.
 *  - Only GET is allowed.
 *  - The TMDB URL (and therefore the secret key) is never logged.
 */

const { onRequest } = require("firebase-functions/v2/https");
const { logger } = require("firebase-functions");
const admin = require("firebase-admin");

const TMDB_BASE = "https://api.themoviedb.org/3";

/** Frontend origin of the admin panel (WebViewAssetLoader). */
const ALLOWED_ORIGIN = "https://appassets.androidplatform.net";

function sendCors(req, res) {
  res.set("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
  res.set("Vary", "Origin");
  if (req.method === "OPTIONS") {
    res.set("Access-Control-Allow-Methods", "GET");
    res.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    res.set("Access-Control-Max-Age", "600");
    res.status(204).send("");
    return true;
  }
  return false;
}

/**
 * Verifies the Bearer ID token and checks the admin claim.
 * Returns the decoded token, or null after sending the error response.
 */
async function requireAdmin(req, res) {
  const authorization = req.headers.authorization || "";
  if (!authorization.startsWith("Bearer ")) {
    res.status(401).json({ error: "Authorization Bearer token required" });
    return null;
  }
  try {
    const decoded = await admin.auth().verifyIdToken(authorization.slice(7), true);
    if (decoded.admin !== true) {
      res.status(403).json({ error: "Admin access required" });
      return null;
    }
    return decoded;
  } catch (_) {
    res.status(401).json({ error: "Invalid or expired ID token" });
    return null;
  }
}

exports.adminTmdb = onRequest({
  region: "asia-southeast1",
  timeoutSeconds: 30,
  maxInstances: 10,
  secrets: ["TMDB_API_KEY"],
  cors: false, // CORS fully handled here (single allow-listed origin)
}, async (req, res) => {
  if (sendCors(req, res)) return;

  if (req.method !== "GET") {
    res.status(405).json({ error: "Method not allowed" });
    return;
  }

  const decoded = await requireAdmin(req, res);
  if (!decoded) return; // an error response was already sent exactly once

  const tmdbKey = process.env.TMDB_API_KEY;
  if (!tmdbKey) {
    logger.error("TMDB_API_KEY not configured");
    res.status(500).json({ error: "TMDB proxy misconfigured" });
    return;
  }

  const parts = req.path.split("/").filter(Boolean); // ["api","admin","tmdb", ...]
  const idx = parts.indexOf("tmdb");
  const rest = idx >= 0 ? parts.slice(idx + 1) : parts;

  let tmdbPath;
  // NOTE: the season branch MUST be matched before the generic {type}/{id}
  // branch, otherwise tv/{id}/season/{n} returns show details (route bug).
  if (rest.length >= 4 && rest[0] === "tv" && rest[2] === "season") {
    const id = rest[1];
    const season = rest[3];
    tmdbPath = `/tv/${encodeURIComponent(id)}/season/${encodeURIComponent(season)}?language=en-US`;
  } else if (rest.length >= 3 && rest[0] === "search" && (rest[1] === "movie" || rest[1] === "tv")) {
    const type = rest[1];
    const query = typeof req.query.query === "string" ? req.query.query : "";
    if (!query) {
      res.status(400).json({ error: "A query parameter is required" });
      return;
    }
    tmdbPath = `/search/${type}?query=${encodeURIComponent(query)}&language=en-US&page=1`;
  } else if (rest.length >= 2 && (rest[0] === "movie" || rest[0] === "tv")) {
    const type = rest[0];
    const id = rest[1];
    const append = typeof req.query.append_to_response === "string" ? req.query.append_to_response : "";
    tmdbPath = `/${type}/${encodeURIComponent(id)}?language=en-US${append ? `&append_to_response=${encodeURIComponent(append)}` : ""}`;
  } else {
    res.status(400).json({ error: "Unknown endpoint" });
    return;
  }

  try {
    // The key is appended here and NEVER written to logs by this handler.
    const url = `${TMDB_BASE}${tmdbPath}&api_key=${encodeURIComponent(tmdbKey)}`;
    const tmdbRes = await fetch(url, { method: "GET" });
    const body = await tmdbRes.text();
    res.status(tmdbRes.status).set("Content-Type", "application/json").send(body);
  } catch (e) {
    logger.error("TMDB proxy error", e && e.message);
    res.status(502).json({ error: "TMDB proxy error" });
  }
});
