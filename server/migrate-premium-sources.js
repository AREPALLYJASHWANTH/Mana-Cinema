/**
 * One-off data migration (P0-2a): moves premium stream sources out of the
 * PUBLIC nodes into private_sources/.
 *
 * Before: premium movies/series still carried link + videoSources (and episode
 * sources) in public `movies/{id}` / `webseries/{id}` — readable by every
 * signed-in client via the cascading `movies` .read rule.
 *
 * After:
 *   movies/{id}           → availableQualities only (NO link / videoSources)
 *   webseries/{id}        → same, plus per-episode availableQualities
 *   private_sources/{movieId}/videoSources
 *   private_sources/{seriesId}/seasons/{s}/episodes/{e}/videoSources
 *
 * Episode paths reuse the ORIGINAL episode keys stored in the DB (the admin
 * panel writes episodes as an array → numeric keys), so existing indexes are
 * never shifted or re-created.
 *
 * Usage:
 *   GOOGLE_APPLICATION_CREDENTIALS=/path/service-account.json \
 *     node server/migrate-premium-sources.js           # dry-run (default)
 *     node server/migrate-premium-sources.js --apply   # real write
 *
 * A JSON backup of `movies` and `webseries` is exported before any write.
 */
const admin = require("firebase-admin");
const fs = require("fs");
const path = require("path");

const APPLY = process.argv.includes("--apply");

function isPremium(v) {
  return v === true || v === "true" || v === 1 || v === "1";
}

function sourcesOf(node) {
  if (!node) return [];
  const vs = node.videoSources;
  if (Array.isArray(vs)) return vs.filter(Boolean);
  if (vs && typeof vs === "object") return Object.values(vs).filter(Boolean);
  return [];
}

function qualitiesOf(sources) {
  const set = new Set();
  sources.forEach((s) => {
    if (s && s.enabled !== false && s.quality) set.add(String(s.quality));
  });
  return Array.from(set);
}

async function main() {
  if (!process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    console.error("GOOGLE_APPLICATION_CREDENTIALS must point to the service-account JSON.");
    process.exit(1);
  }
  admin.initializeApp({ credential: admin.credential.applicationDefault() });
  const db = admin.database();

  const moviesSnap = await db.ref("movies").get();
  const seriesSnap = await db.ref("webseries").get();

  if (APPLY) {
    // Export a full backup BEFORE touching anything.
    const backupPath = path.join(__dirname, `backup-premium-sources-${Date.now()}.json`);
    fs.writeFileSync(
      backupPath,
      JSON.stringify({ exportedAt: new Date().toISOString(), movies: moviesSnap.val(), webseries: seriesSnap.val() }, null, 2)
    );
    console.log("Backup written:", backupPath);
  } else {
    console.log("DRY RUN (no writes). Re-run with --apply to perform the migration.");
  }

  const updates = {};
  let moviesMigrated = 0;
  let seriesMigrated = 0;

  moviesSnap.forEach((child) => {
    const m = child.val() || {};
    const key = child.key;
    if (!isPremium(m.isPremium)) return;
    const sources = sourcesOf(m);
    if (sources.length === 0 && !m.link) return;
    updates[`private_sources/${key}/videoSources`] = sources;
    updates[`movies/${key}/availableQualities`] = qualitiesOf(sources);
    updates[`movies/${key}/link`] = null;
    updates[`movies/${key}/videoSources`] = null;
    moviesMigrated++;
  });

  seriesSnap.forEach((child) => {
    const key = child.key;
    const s = child.val() || {};
    if (!s.episodes) return;
    const seriesPremium = isPremium(s.isPremium);
    const seriesQualities = new Set();
    let touched = false;

    child.child("episodes").forEach((epSnap) => {
      const ep = epSnap.val() || {};
      if (!epSnap.key) return;
      if (!seriesPremium && !isPremium(ep.isPremium)) return;
      const sources = sourcesOf(ep);
      if (sources.length === 0 && !ep.link) return;
      const base = `private_sources/${key}/seasons/${ep.season}/episodes/${ep.episode}`;
      updates[`${base}/videoSources`] = sources;
      updates[`webseries/${key}/episodes/${epSnap.key}/availableQualities`] = qualitiesOf(sources);
      updates[`webseries/${key}/episodes/${epSnap.key}/link`] = null;
      updates[`webseries/${key}/episodes/${epSnap.key}/videoSources`] = null;
      qualitiesOf(sources).forEach((q) => seriesQualities.add(q));
      touched = true;
    });

    if (touched) {
      if (seriesPremium && seriesQualities.size > 0) {
        updates[`webseries/${key}/availableQualities`] = Array.from(seriesQualities);
      }
      seriesMigrated++;
    }
  });

  console.log(`Premium movies to migrate: ${moviesMigrated}, series: ${seriesMigrated}.`);
  if (moviesMigrated === 0 && seriesMigrated === 0) {
    console.log("Nothing to migrate.");
    process.exit(0);
  }

  if (APPLY) {
    await db.ref().update(updates);
    console.log(`Applied ${Object.keys(updates).length} writes.`);
  } else {
    const keys = Object.keys(updates);
    console.log(`Would write ${keys.length} paths. First 30:`);
    keys.slice(0, 30).forEach((k) => {
      const v = updates[k];
      const shown = Array.isArray(v)
        ? `[${v.length} sources]`
        : v === null ? "null" : typeof v === "object" ? "{...}" : JSON.stringify(v);
      console.log("  ", k, shown);
    });
  }
  process.exit(0);
}

main().catch((e) => {
  console.error("Migration failed:", e && e.message);
  process.exit(1);
});
