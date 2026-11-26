package com.manacinema.app.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cached_movies")
data class CachedMovieEntity(
    @PrimaryKey val id: String,
    val title: String,
    val poster: String,
    val backdrop: String,
    val category: String,
    val rating: String,
    val year: String,
    val genre: String,
    val link: String,
    val castJson: String,   // Serialized list of CastMember as JSON
    val director: String,
    val storyline: String,
    val trailerUrl: String = "",
    val isPremium: Boolean = false,
    val isNew: Boolean = false,
    val qualityBadge: String = "",
    val subtitleUrl: String = "",
    // Serialized List<VideoSource> (see MovieRepository.sourcesToJson). Stored as
    // JSON rather than a child table because this is a rebuildable cache and the
    // sources are only ever read as a whole list.
    val videoSourcesJson: String = "[]",
    // Creation timestamp for newest-first ordering (milliseconds since epoch).
    val createdAt: Long = 0L
)

@Entity(tableName = "cached_series")
data class CachedSeriesEntity(
    @PrimaryKey val id: String,
    val title: String,
    val poster: String,
    val backdrop: String,
    val category: String,
    val rating: String,
    val year: String,
    val genre: String,
    val storyline: String,
    val episodesJson: String, // Serialized list of episodes as JSON for simple local database storage
    val castJson: String = "[]",
    val director: String = "",
    val trailerUrl: String = "",
    val isPremium: Boolean = false,
    val isNew: Boolean = false,
    val qualityBadge: String = "",
    // Creation timestamp for newest-first ordering (milliseconds since epoch).
    val createdAt: Long = 0L
)

@Entity(tableName = "ratings")
data class RatingEntity(
    @PrimaryKey val compositeId: String, // "${profileId}_${contentId}"
    val profileId: String,
    val contentId: String,
    val score: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_watchlist")
data class LocalWatchlistEntity(
    @PrimaryKey val compositeId: String, // format: "${profileId}_${id}"
    val profileId: String,
    val id: String,
    val title: String,
    val poster: String,
    val backdrop: String,
    val type: String
)

@Entity(tableName = "continue_watching")
data class ContinueWatchingEntity(
    @PrimaryKey val compositeId: String, // format: "${profileId}_${contentId}"
    val profileId: String,
    val contentId: String,
    val title: String,
    val poster: String,
    val type: String,
    val progress: Float,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    // For quality-aware downloads this is "<contentId>_<quality>", so the same
    // movie can exist at 720p and 1080p simultaneously without colliding.
    @PrimaryKey val id: String,
    val title: String,
    val poster: String,
    val remoteUrl: String,
    val localUri: String?, // file path representation
    val status: String,    // "PENDING", "DOWNLOADING", "PAUSED", "COMPLETED", "FAILED"
    val progress: Int,     // 0 to 100
    val totalBytes: Long,
    val downloadedBytes: Long,
    val timestamp: Long = System.currentTimeMillis(),
    // Web series metadata (movies leave these at defaults).
    val contentType: String = "movie", // "movie" or "webseries"
    val seriesId: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val episodeTitle: String = "",
    val subtitleUrl: String = "",
    // Local path of the downloaded subtitle file, set on completion. Empty when
    // the title has no subtitle or the subtitle download failed (non-fatal).
    val subtitleLocalUri: String = "",
    // Selected quality label ("1080p"), shown in the Downloads list. Empty for
    // rows created before quality selection existed.
    val quality: String = "",
    // Id of the movie/series this download belongs to, without the quality suffix.
    // Lets the app find "is this title downloaded in any quality".
    val contentId: String = "",
    // True when the source was premium at download time, so offline playback can
    // re-check entitlement instead of trusting the file's presence on disk.
    val isPremiumContent: Boolean = false
)

@Dao
interface AppDao {
    @Query("SELECT * FROM cached_movies")
    fun getAllMovies(): Flow<List<CachedMovieEntity>>

    @Query("DELETE FROM cached_movies")
    suspend fun clearMovies()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(movies: List<CachedMovieEntity>)

    @Query("SELECT * FROM cached_series")
    fun getAllSeries(): Flow<List<CachedSeriesEntity>>

    @Query("DELETE FROM cached_series")
    suspend fun clearSeries()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(series: List<CachedSeriesEntity>)

    @Query("SELECT * FROM local_watchlist WHERE profileId = :profileId")
    fun getWatchlistForProfile(profileId: String): Flow<List<LocalWatchlistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToWatchlist(item: LocalWatchlistEntity)

    @Query("DELETE FROM local_watchlist WHERE compositeId = :compositeId")
    suspend fun removeFromWatchlistByCompositeId(compositeId: String)

    @Query("SELECT EXISTS(SELECT * FROM local_watchlist WHERE compositeId = :compositeId)")
    suspend fun isWatchlisted(compositeId: String): Boolean

    @Query("SELECT * FROM continue_watching WHERE profileId = :profileId ORDER BY timestamp DESC")
    fun getContinueWatchingForProfile(profileId: String): Flow<List<ContinueWatchingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(entity: ContinueWatchingEntity)

    @Query("DELETE FROM continue_watching WHERE compositeId = :compositeId")
    suspend fun deleteProgressByCompositeId(compositeId: String)

    @Query("SELECT * FROM downloads ORDER BY timestamp DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOADING' OR status = 'PENDING'")
    suspend fun getActiveDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getDownloadById(id: String): DownloadEntity?

    /** All downloads for one title, across every quality. */
    @Query("SELECT * FROM downloads WHERE contentId = :contentId")
    suspend fun getDownloadsForContent(contentId: String): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDownload(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownload(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRating(rating: RatingEntity)

    @Query("SELECT * FROM ratings WHERE compositeId = :compositeId LIMIT 1")
    suspend fun getRating(compositeId: String): RatingEntity?

    @Query("SELECT * FROM ratings WHERE contentId = :contentId")
    fun getRatingsForContent(contentId: String): Flow<List<RatingEntity>>

    @Query("SELECT * FROM ratings WHERE profileId = :profileId")
    fun getRatingsForProfile(profileId: String): Flow<List<RatingEntity>>
}
