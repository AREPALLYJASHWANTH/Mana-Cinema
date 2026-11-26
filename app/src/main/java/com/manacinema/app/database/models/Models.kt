package com.manacinema.app.models

data class CastMember(
    val name: String = "",
    val photo: String = ""
)

/**
 * Splits a multi-category field into its parts.
 *
 * Admin writes categories as a `categories` array (canonical) and, for backward
 * compatibility with already-installed app builds, ALSO mirrors them into the
 * legacy comma-joined `category` string. The repository normalises both shapes
 * into `category`, so every consumer must compare against this list instead of
 * doing string equality on the whole field — "Action, Drama" is not "Action".
 */
fun splitCategories(raw: String): List<String> =
    raw.split(",", "|", "/")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

/**
 * One playable/downloadable source for a single quality (Phase 35).
 *
 * `streamUrl` and `downloadUrl` are deliberately separate: a source may be
 * streamable but not downloadable, or vice versa. Empty means "not available for
 * that operation" and the UI must reflect that rather than substituting the other.
 */
data class VideoSource(
    val quality: String = "",
    val streamUrl: String = "",
    val downloadUrl: String = "",
    val fileSize: String = "",
    val format: String = "",
    val server: String = "",
    val enabled: Boolean = true
) {
    /**
     * Sort weight in pixels of height, so ordering is numeric and never
     * alphabetical ("1080p" must outrank "480p"). Unknown labels sort last.
     */
    val heightRank: Int
        get() = QUALITY_RANK[normalizeQuality(quality)] ?: 0

    companion object {
        /** Canonical labels, ascending. "4K" and "2160p" are the same rung. */
        private val QUALITY_RANK = mapOf(
            "360p" to 360, "480p" to 480, "720p" to 720,
            "1080p" to 1080, "1440p" to 1440, "2160p" to 2160
        )

        /** Accepts "4k", "2160P", "1080", "hd" etc. and returns a canonical label. */
        fun normalizeQuality(raw: String): String {
            val q = raw.trim().lowercase()
            if (q.isEmpty()) return ""
            if (q == "4k" || q == "2160" || q == "2160p" || q == "uhd") return "2160p"
            if (q == "2k" || q == "1440" || q == "1440p") return "1440p"
            if (q == "fhd" || q == "1080" || q == "1080p") return "1080p"
            if (q == "hd" || q == "720" || q == "720p") return "720p"
            if (q == "sd" || q == "480" || q == "480p") return "480p"
            if (q == "360" || q == "360p") return "360p"
            return raw.trim()
        }

        /** Display label: 2160p is shown as 4K, everything else as-is. */
        fun displayLabel(quality: String): String =
            if (normalizeQuality(quality) == "2160p") "4K" else normalizeQuality(quality)
    }
}

/**
 * Sources that are enabled and actually usable, best quality first.
 * `requireDownload = true` keeps only sources that have a real download URL.
 */
fun List<VideoSource>.usable(requireDownload: Boolean = false): List<VideoSource> =
    filter { src ->
        src.enabled && (if (requireDownload) src.downloadUrl.isNotBlank() else src.streamUrl.isNotBlank())
    }.sortedByDescending { it.heightRank }

/**
 * Shared shape for anything a poster-card slider can render — [Movie] and [WebSeries] both
 * implement it, so `PosterCard`/`MovieSliderRow` in HomeSliders.kt work on either without
 * duplicating the composable per content type.
 */
interface PosterItem {
    val id: String
    val title: String
    val poster: String
    val backdrop: String
    val rating: String
    val year: String
    val isPremium: Boolean
    val isNew: Boolean
    val qualityBadge: String
}

data class Movie(
    override val id: String = "",
    override val title: String = "",
    override val poster: String = "",
    override val backdrop: String = "",
    // Comma-joined multi-category string (legacy shape, still written by admin for
    // backward compatibility). Read it through `categoryList`, never with ==.
    val category: String = "",
    override val rating: String = "",
    override val year: String = "",
    val genre: String = "",
    val link: String = "",
    val cast: List<CastMember> = emptyList(),
    val director: String = "",
    val storyline: String = "",
    val trailerUrl: String = "",
    override val isPremium: Boolean = false,
    override val isNew: Boolean = false,
    override val qualityBadge: String = "",
    // External subtitle track (.srt/.vtt). Empty when the title has none or when
    // only embedded tracks are used. May be a local file path for offline playback.
    val subtitleUrl: String = "",
    // Per-quality sources (Phase 35). Empty for legacy movies that only have `link`;
    // `streamSources`/`downloadSources` fall back to `link` in that case.
    val videoSources: List<VideoSource> = emptyList(),
    // Creation timestamp (milliseconds since epoch). Set by admin panel using
    // Firebase ServerValue.TIMESTAMP. Used for newest-first ordering.
    val createdAt: Long = 0L
) : PosterItem {
    val categoryList: List<String> get() = splitCategories(category)

    /**
     * Playable sources, best first. Legacy movies with only `link` yield a single
     * synthetic source labelled from `qualityBadge`, so the picker logic is uniform
     * and no caller needs to special-case the old schema.
     */
    val streamSources: List<VideoSource>
        get() = videoSources.usable().ifEmpty {
            if (isPremium && videoSources.isNotEmpty()) videoSources
            else
            if (link.isBlank()) emptyList()
            else listOf(VideoSource(quality = qualityBadge.ifBlank { "Default" }, streamUrl = link))
        }

    /**
     * Downloadable sources, best first. Legacy `link` is reused here because the
     * existing download manager already streams that exact URL to disk.
     */
    val downloadSources: List<VideoSource>
        get() = videoSources.usable(requireDownload = true).ifEmpty {
            if (link.isBlank()) emptyList()
            else listOf(VideoSource(quality = qualityBadge.ifBlank { "Default" }, downloadUrl = link))
        }
}

data class Episode(
    val season: Int = 1,
    val episode: Int = 1,
    val title: String = "",
    val link: String = "",
    val subtitleUrl: String = "",
    val thumbnail: String = "",
    // Per-episode premium gate (Phase 4). Independent of the series-level flag:
    // a free series may contain premium episodes and vice versa.
    val isPremium: Boolean = false,
    val videoSources: List<VideoSource> = emptyList(),
    // Optional skip-window metadata in SECONDS. -1 = not provided (button hidden).
    // Admin panel writes introStart/introEnd/recapStart/recapEnd when known.
    val introStartSec: Int = -1,
    val introEndSec: Int = -1,
    val recapStartSec: Int = -1,
    val recapEndSec: Int = -1,
    // Runtime length in seconds when the admin knows it (episode list UI only).
    val durationSec: Int = 0
) {
    val hasIntroSkip: Boolean get() = introStartSec >= 0 && introEndSec > introStartSec
    val hasRecapSkip: Boolean get() = recapStartSec >= 0 && recapEndSec > recapStartSec
    val streamSources: List<VideoSource>
        get() = videoSources.usable().ifEmpty {
            if (videoSources.isNotEmpty()) videoSources
            else if (link.isBlank()) emptyList() else listOf(VideoSource(quality = "Default", streamUrl = link))
        }

    val downloadSources: List<VideoSource>
        get() = videoSources.usable(requireDownload = true).ifEmpty {
            if (link.isBlank()) emptyList() else listOf(VideoSource(quality = "Default", downloadUrl = link))
        }
}

data class WebSeries(
    override val id: String = "",
    override val title: String = "",
    override val poster: String = "",
    override val backdrop: String = "",
    val category: String = "",
    override val rating: String = "",
    override val year: String = "",
    val genre: String = "",
    val storyline: String = "",
    val episodes: List<Episode> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val director: String = "",
    val trailerUrl: String = "",
    override val isPremium: Boolean = false,
    override val isNew: Boolean = false,
    override val qualityBadge: String = "",
    // Creation timestamp for newest-first ordering (milliseconds since epoch).
    val createdAt: Long = 0L
) : PosterItem {
    val categoryList: List<String> get() = splitCategories(category)

    /**
     * An episode is gated when the series is premium OR the episode itself is.
     * Series-level premium still locks every episode (existing behaviour), but a
     * free series can now carry individually premium episodes.
     */
    fun isEpisodeLocked(episode: Episode): Boolean = isPremium || episode.isPremium
}

data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoURL: String = "",
    val gender: String = "male",
    val createdAt: Long = 0L
)

data class WatchlistItem(
    val id: String = "",
    val title: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val type: String = "movie"
)

data class ContinueWatching(
    val contentId: String = "",
    val title: String = "",
    val poster: String = "",
    val type: String = "",
    val progress: Float = 0f
)

// Written to Firebase path "movie_requests" — field names must match the admin panel.
data class MovieRequest(
    val id: String = "",
    val movieName: String = "",
    val movieYear: String = "",
    val requestedBy: String = "",
    val timestamp: Long = 0L
)

data class UserNotification(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val icon: String = "",
    // Admin writes the picture as either `image` or `imageUrl`; the repository
    // normalises both into this one field.
    val imageUrl: String = "",
    // Set when the admin attached a movie, enabling tap-to-open deep linking.
    val movieId: String = "",
    val moviePoster: String = "",
    val target: String = "all",          // "all" or "specific"
    val targetEmails: List<String> = emptyList(),
    val timestamp: Long = 0L
)

data class MovieProfile(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val isKids: Boolean = false,
    val preferredGenre: String = "All"
)

// ------------------ SUBSCRIPTION / PREMIUM ------------------

// Firebase path "plans" (pushed children).
data class Plan(
    val id: String = "",
    val name: String = "",
    val price: String = "",
    val days: Int = 0,
    val devices: Int = 1,
    val description: String = ""
)

// Firebase path "app_settings/payment".
data class PaymentInfo(
    val upiId: String = "",
    val qrCodeUrl: String = ""
)

// Written to Firebase path "premium_requests" by the app; admin approves/rejects.
data class PremiumRequest(
    val userId: String = "",
    val shortId: String = "",
    val userEmail: String = "",
    val planName: String = "",
    val planDays: Int = 0,
    val planPrice: String = "",
    val deviceLimit: Int = 1,
    val utr: String = "",
    val status: String = "pending",   // "pending" | "approved" | "rejected"
    val rejectReason: String = "",
    val timestamp: Long = 0L
)

// Firebase path "users/{uid}/subscription".
data class Subscription(
    val isPremium: Boolean = false,
    val planName: String = "",
    val expiryDate: Long = 0L,
    val deviceLimit: Int = 1
) {
    /**
     * Expiry is authoritative, not the `isPremium` flag alone: an admin-set flag
     * that was never cleared must not grant access past expiryDate.
     */
    val isActive: Boolean
        get() = isPremium && expiryDate > System.currentTimeMillis()
}

/**
 * Result of registering this installation against the plan's device limit
 * (Phase 5). Firebase path "users/{uid}/devices/{installationId}".
 */
enum class DeviceStatus {
    /** Registered (or already known) and within the limit. */
    ALLOWED,

    /** Limit reached and this installation is not one of the registered ones. */
    LIMIT_REACHED,

    /** Not premium, or the check could not reach Firebase — do not gate on this. */
    UNKNOWN
}

// ------------------ ADS ------------------

// Firebase path "ads" (pushed children).
data class AdItem(
    val id: String = "",
    val title: String = "",
    val type: String = "image",   // "image" or "video"
    val mediaUrl: String = "",
    val targetUrl: String = "",
    val placement: String = "all",
    val duration: Int = 0,
    val isActive: Boolean = false
)

// ------------------ APP VERSION / FORCE UPDATE ------------------

// Firebase path "app_settings/version".
data class AppVersionInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val forceUpdate: Boolean = false,
    val apkUrl: String = "",
    val changelog: String = ""
)

// ------------------ RATINGS ------------------

data class Rating(
    val contentId: String = "",
    val profileId: String = "",
    val score: Int = 0,
    val timestamp: Long = 0L
)

// ------------------ COMMENTS ------------------

// Firebase path "content_comments/{contentId}/comments/{autoId}".
data class Comment(
    val id: String = "",
    val contentId: String = "",
    val userId: String = "",
    val userName: String = "",
    val avatarUrl: String = "",
    val text: String = "",
    val timestamp: Long = 0L
)

/** Soft cap on comment length so the composer stays a quick reaction, not an essay. */
const val MAX_COMMENT_WORDS = 60
