package com.manacinema.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CachedMovieEntity::class,
        CachedSeriesEntity::class,
        LocalWatchlistEntity::class,
        ContinueWatchingEntity::class,
        DownloadEntity::class,
        RatingEntity::class
    ],
    version = 10,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v5 -> v6: the `downloads` table gained web-series metadata columns.
        // We recreate the table (rather than ALTER ... ADD COLUMN DEFAULT) so the
        // resulting columns carry NO SQL default, matching the Room entity exactly.
        // Existing rows are copied; new columns get literal defaults inline.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS downloads_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        poster TEXT NOT NULL,
                        remoteUrl TEXT NOT NULL,
                        localUri TEXT,
                        status TEXT NOT NULL,
                        progress INTEGER NOT NULL,
                        totalBytes INTEGER NOT NULL,
                        downloadedBytes INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        contentType TEXT NOT NULL,
                        seriesId TEXT NOT NULL,
                        season INTEGER NOT NULL,
                        episode INTEGER NOT NULL,
                        episodeTitle TEXT NOT NULL,
                        subtitleUrl TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO downloads_new (
                        id, title, poster, remoteUrl, localUri, status, progress,
                        totalBytes, downloadedBytes, timestamp,
                        contentType, seriesId, season, episode, episodeTitle, subtitleUrl
                    )
                    SELECT
                        id, title, poster, remoteUrl, localUri, status, progress,
                        totalBytes, downloadedBytes, timestamp,
                        'movie', '', 0, 0, '', ''
                    FROM downloads
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE downloads")
                db.execSQL("ALTER TABLE downloads_new RENAME TO downloads")
            }
        }

        // v6 -> v7: `downloads` gained `subtitleLocalUri`; `cached_movies` gained
        // `subtitleUrl`. Both tables are recreated so the new columns carry no SQL
        // default. `downloads` rows are preserved; `cached_movies` is a rebuildable
        // cache but we preserve it too rather than dropping user-visible data.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS downloads_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        poster TEXT NOT NULL,
                        remoteUrl TEXT NOT NULL,
                        localUri TEXT,
                        status TEXT NOT NULL,
                        progress INTEGER NOT NULL,
                        totalBytes INTEGER NOT NULL,
                        downloadedBytes INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        contentType TEXT NOT NULL,
                        seriesId TEXT NOT NULL,
                        season INTEGER NOT NULL,
                        episode INTEGER NOT NULL,
                        episodeTitle TEXT NOT NULL,
                        subtitleUrl TEXT NOT NULL,
                        subtitleLocalUri TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO downloads_new (
                        id, title, poster, remoteUrl, localUri, status, progress,
                        totalBytes, downloadedBytes, timestamp,
                        contentType, seriesId, season, episode, episodeTitle,
                        subtitleUrl, subtitleLocalUri
                    )
                    SELECT
                        id, title, poster, remoteUrl, localUri, status, progress,
                        totalBytes, downloadedBytes, timestamp,
                        contentType, seriesId, season, episode, episodeTitle,
                        subtitleUrl, ''
                    FROM downloads
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE downloads")
                db.execSQL("ALTER TABLE downloads_new RENAME TO downloads")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_movies_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        poster TEXT NOT NULL,
                        backdrop TEXT NOT NULL,
                        category TEXT NOT NULL,
                        rating TEXT NOT NULL,
                        year TEXT NOT NULL,
                        genre TEXT NOT NULL,
                        link TEXT NOT NULL,
                        castJson TEXT NOT NULL,
                        director TEXT NOT NULL,
                        storyline TEXT NOT NULL,
                        trailerUrl TEXT NOT NULL,
                        isPremium INTEGER NOT NULL,
                        isNew INTEGER NOT NULL,
                        qualityBadge TEXT NOT NULL,
                        subtitleUrl TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO cached_movies_new (
                        id, title, poster, backdrop, category, rating, year, genre,
                        link, castJson, director, storyline, trailerUrl,
                        isPremium, isNew, qualityBadge, subtitleUrl
                    )
                    SELECT
                        id, title, poster, backdrop, category, rating, year, genre,
                        link, castJson, director, storyline, trailerUrl,
                        isPremium, isNew, qualityBadge, ''
                    FROM cached_movies
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE cached_movies")
                db.execSQL("ALTER TABLE cached_movies_new RENAME TO cached_movies")
            }
        }

        // v7 -> v8: quality-aware video sources and downloads (Phase 35).
        //   cached_movies + videoSourcesJson  (serialized List<VideoSource>)
        //   downloads     + quality, contentId, isPremiumContent
        // Plain ALTER ... ADD COLUMN with a DEFAULT is used here because Room only
        // objects to a SQL default when the entity field has none — these fields do
        // have Kotlin defaults, and NOT NULL DEFAULT matches what Room expects for
        // a non-null property with a default value. Existing rows are preserved.
        //
        // Existing downloads keep their original id (no quality suffix) and get
        // contentId backfilled from that id, so previously downloaded titles stay
        // playable and stay visible in the Downloads screen.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_movies ADD COLUMN videoSourcesJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE downloads ADD COLUMN quality TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN contentId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN isPremiumContent INTEGER NOT NULL DEFAULT 0")
                // Backfill contentId: movies used the bare movie id as the row id,
                // episodes used "<seriesId>_s<season>e<episode>".
                db.execSQL("UPDATE downloads SET contentId = id WHERE contentId = ''")
            }
        }

        // v8 -> v9: Add createdAt column to cached_movies for newest-first ordering.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_movies ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v9 -> v10: Add createdAt column to cached_series for newest-first ordering.
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_series ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "infinity_movie_database"
                )
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                // Real migrations preserve all data for the v5 -> v6 -> v7 path
                // (the only transitions this codebase actually shipped). Schemas for
                // versions 1-4 are unknown (no exported schema history), so they
                // can't be migrated safely; fall back to a rebuild for those only,
                // instead of crash-looping. v5+ data is never destroyed.
                .fallbackToDestructiveMigrationFrom(1, 2, 3, 4)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
