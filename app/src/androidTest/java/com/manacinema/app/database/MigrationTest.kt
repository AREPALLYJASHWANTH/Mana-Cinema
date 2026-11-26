package com.manacinema.app.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration validation for the Room database.
 *
 * IMPORTANT — historical schemas were unavailable:
 * This project shipped versions 1-6 WITHOUT `exportSchema = true`, so no schema
 * JSON files exist for versions 1 through 6. `MigrationTestHelper.createDatabase()`
 * can only seed a database from an EXPORTED schema JSON, so we cannot start a
 * migration test at v5 or v6 — those baselines don't exist on disk.
 *
 * Schema export is now enabled (see AppDatabase `exportSchema = true` and the
 * `room.schemaLocation` KSP arg). The first build generates `app/schemas/.../7.json`,
 * which is committed as the BASELINE for all future migration tests. When the next
 * schema version (8, 9, ...) is added, its JSON is generated and committed too, and
 * new tests below should validate 7->8, 8->9, etc. using those exported schemas.
 *
 * The runtime migration chain for v5 -> v6 -> v7 in AppDatabase is unchanged and
 * still runs on real devices; it simply can't be unit-tested here without the
 * historical baselines. `fallbackToDestructiveMigrationFrom(1..4)` covers the
 * unknown pre-v5 schemas.
 *
 * This test therefore validates that opening the current schema (v7) via the
 * exported baseline succeeds. It starts failing usefully the moment a future
 * migration is added without a matching exported schema or without being wired
 * into the chain.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * Baseline: create the database at the earliest exported version (v7) and
     * confirm it opens and matches the exported schema. Also seeds a row per table
     * that later migrations must preserve, so future N->N+1 tests can build on this.
     */
    @Test
    fun createV7Baseline_opensAndMatchesExportedSchema() {
        helper.createDatabase(dbName, 7).use { db ->
            // A downloads row with full web-series + subtitle metadata.
            db.execSQL(
                """
                INSERT INTO downloads (
                    id, title, poster, remoteUrl, localUri, status, progress,
                    totalBytes, downloadedBytes, timestamp,
                    contentType, seriesId, season, episode, episodeTitle,
                    subtitleUrl, subtitleLocalUri
                ) VALUES (
                    'ep1', 'S1E1', 'poster', 'http://x/e.mp4', '/data/e.mp4',
                    'COMPLETED', 100, 1000, 1000, 123,
                    'webseries', 'series1', 1, 1, 'Pilot',
                    'http://x/e.srt', '/data/e.srt'
                )
                """.trimIndent()
            )
            // A cached movie carrying the subtitleUrl column added in v7.
            db.execSQL(
                """
                INSERT INTO cached_movies (
                    id, title, poster, backdrop, category, rating, year, genre,
                    link, castJson, director, storyline, trailerUrl,
                    isPremium, isNew, qualityBadge, subtitleUrl
                ) VALUES (
                    'm1', 'Cached', 'p', 'b', 'cat', '8', '2024', 'Drama',
                    'http://x/m.mp4', '[]', 'Dir', 'story', '', 0, 0, 'HD',
                    'http://x/m.srt'
                )
                """.trimIndent()
            )

            // Read back to prove the v7 schema accepts every current column.
            db.query("SELECT subtitleLocalUri FROM downloads WHERE id = 'ep1'").use { c ->
                assert(c.moveToFirst()) { "seed download row missing" }
                assert(c.getString(0) == "/data/e.srt")
            }
            db.query("SELECT subtitleUrl FROM cached_movies WHERE id = 'm1'").use { c ->
                assert(c.moveToFirst()) { "seed cached_movies row missing" }
                assert(c.getString(0) == "http://x/m.srt")
            }
        }
    }

    // -------------------------------------------------------------------------
    // FUTURE MIGRATIONS: when you bump to v8, commit app/schemas/.../8.json and
    // add a test here, e.g.:
    //
    // @Test
    // fun migrate7To8_preservesData() {
    //     helper.createDatabase(dbName, 7).use { db -> /* seed rows */ }
    //     helper.runMigrationsAndValidate(dbName, 8, true, AppDatabase.MIGRATION_7_8)
    //         .use { db -> /* assert rows survived */ }
    // }
    // -------------------------------------------------------------------------

    /**
     * v7 -> v8 (Phase 35): quality-aware sources + downloads. Proves no data is
     * lost, the new columns exist with correct defaults, and a legacy download
     * (no quality suffix) has its contentId backfilled so it stays visible.
     */
    @Test
    fun migrate7To8_preservesDataAndAddsQualityColumns() {
        helper.createDatabase(dbName, 7).use { db ->
            db.execSQL(
                """
                INSERT INTO downloads (
                    id, title, poster, remoteUrl, localUri, status, progress,
                    totalBytes, downloadedBytes, timestamp,
                    contentType, seriesId, season, episode, episodeTitle,
                    subtitleUrl, subtitleLocalUri
                ) VALUES (
                    'legacyMovie', 'Old Movie', 'poster', 'http://x/m.mp4', '/data/m.mp4',
                    'COMPLETED', 100, 2000, 2000, 111,
                    'movie', '', 0, 0, '',
                    '', ''
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO cached_movies (
                    id, title, poster, backdrop, category, rating, year, genre,
                    link, castJson, director, storyline, trailerUrl,
                    isPremium, isNew, qualityBadge, subtitleUrl
                ) VALUES (
                    'm1', 'Cached', 'p', 'b', 'cat', '8', '2024', 'Drama',
                    'http://x/m.mp4', '[]', 'Dir', 'story', '', 0, 0, 'HD',
                    'http://x/m.srt'
                )
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(dbName, 8, true, AppDatabase.MIGRATION_7_8).use { db ->
            // Legacy download preserved, with quality-aware columns backfilled.
            db.query("SELECT quality, contentId, isPremiumContent FROM downloads WHERE id = 'legacyMovie'").use { c ->
                assert(c.moveToFirst()) { "legacy download row lost across 7->8" }
                assert(c.getString(0) == "") { "quality should default to empty" }
                assert(c.getString(1) == "legacyMovie") { "contentId must be backfilled from id" }
                assert(c.getInt(2) == 0) { "isPremiumContent should default to 0" }
            }
            // Cached movie preserved and gained the videoSourcesJson column.
            db.query("SELECT videoSourcesJson FROM cached_movies WHERE id = 'm1'").use { c ->
                assert(c.moveToFirst()) { "cached_movies row lost across 7->8" }
                assert(c.getString(0) == "[]") { "videoSourcesJson should default to '[]'" }
            }
        }
    }
}
