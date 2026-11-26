package com.manacinema.app.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.google.android.gms.cast.framework.CastButtonFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Formatter
import java.util.Locale
import kotlin.math.roundToInt

// ── Brand accents (Netflix / Hotstar inspired) ──────────────────────────────
private val AccentRed = Color(0xFFE50914)
private val AccentRedSoft = Color(0x33E50914)
private val PanelBgSolid = Color(0xFF141414)

private fun argbToComposeColor(argb: Int): Color = Color(
    red = AndroidColor.red(argb) / 255f,
    green = AndroidColor.green(argb) / 255f,
    blue = AndroidColor.blue(argb) / 255f,
    alpha = AndroidColor.alpha(argb) / 255f
)

// ── Public entry ────────────────────────────────────────────────────────────

@Composable
fun VideoPlayerView(
    videoUrl: String,
    title: String = "",
    initialProgress: Float = 0f,
    onProgressUpdate: (Float) -> Unit = {},
    onBackClick: () -> Unit,
    onMinimizeClick: ((Long) -> Unit)? = null,
    onNextEpisodeClick: (() -> Unit)? = null,
    // Returns a fresh stream URL (from Firebase) when the current signed link has
    // expired (403/404/410). Null = no refresh available. Not used for local files.
    onRefreshUrl: (suspend () -> String?)? = null,
    // External subtitle track (http(s) URL or local file path). Empty = none.
    subtitleUrl: String = "",
    // --- Series mode (optional). When null the player behaves exactly as before. ---
    seriesContext: SeriesPlayerContext? = null,
    modifier: Modifier = Modifier
) {
    OTTVideoPlayer(
        videoUrl = videoUrl,
        title = title,
        initialProgress = initialProgress,
        onProgressUpdate = onProgressUpdate,
        onBackClick = onBackClick,
        onMinimizeClick = onMinimizeClick,
        onNextEpisodeClick = onNextEpisodeClick,
        onRefreshUrl = onRefreshUrl,
        subtitleUrl = subtitleUrl,
        seriesContext = seriesContext,
        modifier = modifier
    )
}

/**
 * Everything the Netflix-style series player needs that is not derivable from the
 * current episode itself. All callbacks are optional — missing metadata simply hides
 * the related UI instead of crashing.
 */
data class SeriesPlayerContext(
    val seriesTitle: String = "",
    val seriesPoster: String = "",
    val seriesId: String = "",
    val currentEpisode: com.manacinema.app.models.Episode? = null,
    val allEpisodes: List<com.manacinema.app.models.Episode> = emptyList(),
    /** contentId ("series_sXeY") -> 0f..1f progress for the watched-bar in the list. */
    val watchedProgress: Map<String, Float> = emptyMap(),
    val nextEpisode: com.manacinema.app.models.Episode? = null,
    val onSelectEpisode: ((com.manacinema.app.models.Episode) -> Unit)? = null,
    val onPrevEpisodeClick: (() -> Unit)? = null
)

enum class OrientationMode {
    SENSOR, PORTRAIT, LANDSCAPE
}

/** Fixed quality caps for adaptive streams (Auto = no cap). */
enum class StreamQuality(val label: String, val maxHeight: Int) {
    AUTO("Auto", Int.MAX_VALUE),
    P360("360p", 360),
    P480("480p", 480),
    P720("720p", 720),
    P1080("1080p", 1080)
}

enum class AspectMode(val label: String, val resizeMode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
}

private enum class PlayerLoadingState(val message: String) {
    IDLE(""),
    LOADING("Movie Loading..."),
    CONNECTING("Connecting to server..."),
    PREPARING("Preparing playback..."),
    BUFFERING("Buffering video..."),
    RETRYING("Reconnecting..."),
    READY(""),
    ERROR("Unable to play this movie")
}

data class SubtitleStyleConfig(
    val sizeSp: Float = 18f,
    val textColor: Int = AndroidColor.WHITE,
    val backgroundColor: Int = AndroidColor.TRANSPARENT,
    val edgeType: Int = CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
    val edgeColor: Int = AndroidColor.BLACK
)

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** Returns only scheme and host; media paths and signed query parameters stay private. */
private fun safeUrlOrigin(rawUrl: String?): String {
    if (rawUrl.isNullOrBlank()) return "unknown"
    return try {
        val uri = android.net.Uri.parse(rawUrl)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: "unknown"
        val host = uri.host?.lowercase(Locale.ROOT) ?: return "$scheme://unknown-host"
        "$scheme://$host"
    } catch (_: Exception) {
        "unknown"
    }
}

/** Finds the request URI from a Media3 data-source failure without logging it. */
private fun failedRequestOrigin(error: Throwable): String {
    var cause: Throwable? = error
    val visited = HashSet<Throwable>()
    while (cause != null && visited.add(cause)) {
        if (cause is androidx.media3.datasource.HttpDataSource.HttpDataSourceException) {
            return safeUrlOrigin(cause.dataSpec.uri.toString())
        }
        cause = cause.cause
    }
    return "unknown"
}

/**
 * Restores the system screen brightness to the original value or system default (-1f).
 * Call this when leaving the video player to ensure brightness is not permanently modified.
 */
fun restoreBrightness(activity: Activity?, originalBrightness: Float?) {
    activity?.window?.let { window ->
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        if (originalBrightness != null && originalBrightness >= 0f) {
            window.attributes = window.attributes.apply {
                screenBrightness = originalBrightness
            }
        } else {
            window.attributes = window.attributes.apply {
                screenBrightness = -1f
            }
        }
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }
}

fun formatTime(timeMs: Long): String {
    if (timeMs < 0) return "00:00"
    val totalSeconds = timeMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    val formatter = Formatter(StringBuilder(), Locale.getDefault())
    return if (hours > 0) {
        formatter.format("%d:%02d:%02d", hours, minutes, seconds).toString()
    } else {
        formatter.format("%02d:%02d", minutes, seconds).toString()
    }
}

/**
 * Direct video container and streaming formats resolver.
 */
fun getMimeTypeFromUrl(url: String): String? {
    val lower = url.lowercase().trim().substringBefore("?").substringBefore("#")
    return when {
        lower.endsWith(".m3u8") -> "application/x-mpegURL"
        lower.endsWith(".mpd") -> "application/dash+xml"
        lower.endsWith(".ism") || lower.endsWith(".ism/manifest") -> "application/vnd.ms-sstr+xml"
        lower.endsWith(".mp4") -> "video/mp4"
        lower.endsWith(".mkv") -> "video/x-matroska"
        lower.endsWith(".webm") -> "video/webm"
        lower.endsWith(".avi") -> "video/x-msvideo"
        lower.endsWith(".mov") -> "video/quicktime"
        lower.endsWith(".m4v") -> "video/x-m4v"
        lower.endsWith(".mpeg") || lower.endsWith(".mpg") -> "video/mpeg"
        lower.endsWith(".wmv") -> "video/x-ms-wmv"
        lower.endsWith(".flv") -> "video/x-flv"
        lower.endsWith(".3gp") -> "video/3gpp"
        lower.endsWith(".ogv") -> "video/ogg"
        lower.endsWith(".ts") -> "video/mp2t"
        lower.endsWith(".m2ts") -> "video/mp2t"
        lower.endsWith(".vob") -> "video/dvd"
        lower.endsWith(".asf") -> "video/x-ms-asf"
        else -> null
    }
}

/** Builds one same-endpoint HTTPS candidate without altering path or signed query data. */
internal fun httpsAlternativeFor(rawUrl: String): String? {
    val value = rawUrl.trim()
    val uri = try { java.net.URI(value) } catch (_: Exception) { return null }
    if (!uri.isAbsolute || uri.isOpaque || uri.host.isNullOrBlank() ||
        !uri.scheme.equals("http", ignoreCase = true)
    ) return null
    val schemeEnd = value.indexOf(':')
    if (schemeEnd <= 0) return null
    return "https" + value.substring(schemeEnd)
}

/** Embed pages are identified by provider/path, never merely by a missing suffix. */
fun isEmbedPageUrl(url: String): Boolean {
    val uri = try { java.net.URI(url.trim()) } catch (_: Exception) { return false }
    val host = uri.host.orEmpty().lowercase(Locale.ROOT)
    val path = uri.path.orEmpty().lowercase(Locale.ROOT)
    return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" ||
        host == "vimeo.com" || host.endsWith(".vimeo.com") ||
        host == "streamimdb.com" || host.endsWith(".streamimdb.com") ||
        path.endsWith(".html") || path.contains("/embed/") || path.contains("/iframe/")
}

/** Builds an external subtitle track without assuming English or trusting malformed URLs. */
private fun buildSubtitleConfiguration(rawUrl: String): MediaItem.SubtitleConfiguration? {
    val value = rawUrl.trim()
    if (value.isBlank()) return null
    val uri = try {
        if (value.startsWith("/")) android.net.Uri.fromFile(java.io.File(value))
        else android.net.Uri.parse(value).takeIf {
            it.scheme?.lowercase(Locale.ROOT) in setOf("http", "https", "file", "content")
        }
    } catch (_: Exception) {
        null
    } ?: return null

    val path = uri.lastPathSegment.orEmpty().lowercase(Locale.ROOT)
    val mime = when {
        path.substringBefore("?").endsWith(".vtt") -> MimeTypes.TEXT_VTT
        path.substringBefore("?").endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        else -> return null
    }
    val language = Regex("(?:^|[_-])(en|hi|te|ta|bn|kn|ml|mr)(?:[_-]|$)")
        .find(path)?.groupValues?.getOrNull(1)
    return MediaItem.SubtitleConfiguration.Builder(uri)
        .setMimeType(mime)
        .apply { language?.let { setLanguage(it) } }
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .build()
}

@OptIn(UnstableApi::class)
private fun applyStreamQuality(trackSelector: DefaultTrackSelector, quality: StreamQuality) {
    val builder = trackSelector.buildUponParameters()
        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
    if (quality == StreamQuality.AUTO) {
        builder
            .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            .setForceHighestSupportedBitrate(false)
    } else {
        // Cap height; width open so vertical/portrait encodes still match.
        builder
            .setMaxVideoSize(Int.MAX_VALUE, quality.maxHeight)
            .setForceHighestSupportedBitrate(false)
    }
    trackSelector.setParameters(builder)
}

@OptIn(UnstableApi::class)
private fun applySubtitleStyle(playerView: PlayerView?, style: SubtitleStyleConfig) {
    val subtitleView = playerView?.subtitleView ?: return
    subtitleView.setStyle(
        CaptionStyleCompat(
            style.textColor,
            style.backgroundColor,
            AndroidColor.TRANSPARENT,
            style.edgeType,
            style.edgeColor,
            Typeface.DEFAULT_BOLD
        )
    )
    subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, style.sizeSp)
    subtitleView.setBottomPaddingFraction(0.08f)
}

// ── Main player ─────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun OTTVideoPlayer(
    videoUrl: String,
    title: String,
    initialProgress: Float = 0f,
    onProgressUpdate: (Float) -> Unit = {},
    onBackClick: () -> Unit,
    onMinimizeClick: ((Long) -> Unit)? = null,
    onNextEpisodeClick: (() -> Unit)? = null,
    onRefreshUrl: (suspend () -> String?)? = null,
    subtitleUrl: String = "",
    seriesContext: SeriesPlayerContext? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val coroutineScope = rememberCoroutineScope()

    // Screen / playback UI state
    // Start in the device's actual orientation. The control must never claim a
    // forced landscape state before the player has measured the display.
    var orientationMode by remember {
        mutableStateOf(
            when {
                context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ->
                    OrientationMode.LANDSCAPE
                else -> OrientationMode.PORTRAIT
            }
        )
    }
    var showControls by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    var isLocked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var resumeAfterPause by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var loadingState by remember { mutableStateOf(PlayerLoadingState.LOADING) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var bufferedPosition by remember { mutableStateOf(0L) }
    var lastReportedProgress by remember(videoUrl) { mutableFloatStateOf(-1f) }
    var aspectMode by remember { mutableStateOf(AspectMode.FIT) }
    val playbackPrefs = remember { context.getSharedPreferences("infinity_movie_prefs", Context.MODE_PRIVATE) }
    var playbackSpeed by remember {
        mutableStateOf(playbackPrefs.getFloat("playback_speed", 1f))
    }
    var streamQuality by remember {
        mutableStateOf(
            when (playbackPrefs.getString("stream_quality", "Auto")) {
                "360p" -> StreamQuality.P360
                "480p" -> StreamQuality.P480
                "720p" -> StreamQuality.P720
                "1080p" -> StreamQuality.P1080
                else -> StreamQuality.AUTO
            }
        )
    }

    // Seek preview
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewMs by remember { mutableStateOf(0L) }

    // Gestures
    var showBrightness by remember { mutableStateOf(false) }
    var brightnessLevel by remember {
        mutableStateOf(
            activity?.window?.attributes?.screenBrightness
                ?.takeIf { it >= 0f } ?: -1f
        )
    }
    var showVolume by remember { mutableStateOf(false) }
    var volumeLevel by remember {
        mutableStateOf(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        )
    }
    var showDoubleTapLeft by remember { mutableStateOf(false) }
    var showDoubleTapRight by remember { mutableStateOf(false) }
    var doubleTapAccumLeft by remember { mutableStateOf(10) }
    var doubleTapAccumRight by remember { mutableStateOf(10) }

    // Sleep timer
    var sleepTimerMinutes by remember { mutableStateOf(0) }
    var sleepTimeRemainingSeconds by remember { mutableStateOf(0) }

    // Cast
    val castContext = remember {
        try {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(context)
        } catch (e: Exception) {
            android.util.Log.e("VideoPlayerView", "CastContext unavailable", e)
            null
        }
    }
    var isCasting by remember { mutableStateOf(false) }
    var castDeviceName by remember { mutableStateOf("") }

    // Security
    var isScreenshotProtected by remember { mutableStateOf(false) }

    // Next episode
    var autoPlayCountdownSeconds by remember { mutableStateOf(-1) }
    var nextEpisodeTriggered by remember { mutableStateOf(false) }
    var showNextEpisodeCancelled by remember(videoUrl) { mutableStateOf(false) }

    // Netflix-style episode selection bottom sheet
    var showEpisodeList by remember { mutableStateOf(false) }

    // Tracks
    var audioTrackGroups by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var textTrackGroups by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var videoTrackGroups by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var selectedAudioTrackId by remember { mutableStateOf<String?>(null) }
    var selectedSubtitleTrackId by remember { mutableStateOf<String?>(null) }

    // Restore and persist track selections per content
    val trackPrefs = remember { context.getSharedPreferences("infinity_movie_prefs", Context.MODE_PRIVATE) }

    // Dialogs / sheets
    var showTrackDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showHelpSettings by remember { mutableStateOf(false) }
    var showSubtitleStyleDialog by remember { mutableStateOf(false) }
    var showAspectToast by remember { mutableStateOf<String?>(null) }

    // Subtitle styling
    var subtitleStyle by remember {
        mutableStateOf(
            SubtitleStyleConfig(
                sizeSp = trackPrefs.getFloat("subtitle_size_sp", 18f),
                textColor = trackPrefs.getInt("subtitle_text_color", AndroidColor.WHITE),
                backgroundColor = trackPrefs.getInt("subtitle_background_color", AndroidColor.TRANSPARENT),
                edgeType = trackPrefs.getInt("subtitle_edge_type", CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW),
                edgeColor = trackPrefs.getInt("subtitle_edge_color", AndroidColor.BLACK)
            )
        )
    }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    var useWebViewPlayer by remember { mutableStateOf(false) }
    var currentVideoUrl by remember(videoUrl) { mutableStateOf(videoUrl) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    // When the player was defeated by a security/closed policy failure (e.g. an
    // insecure HTTP link on a host that Android blocks), retrying the same URL
    // can never succeed and must not burn the user's time. We keep a dedicated
    // message for that case so the UI can show "Go Back" instead of "Retry".
    var isCleartextBlockedError by remember { mutableStateOf(false) }
    var httpsAlternativeAttempted by remember(videoUrl) { mutableStateOf(false) }
    var usingHttpsAlternative by remember(videoUrl) { mutableStateOf(false) }
    var retryCount by remember(videoUrl) { mutableStateOf(0) }
    var hasSeeked by remember { mutableStateOf(false) }
    // Guards against refreshing the signed URL more than once per source, so an
    // always-403 link can't loop forever.
    var urlRefreshAttempted by remember(videoUrl) { mutableStateOf(false) }
    val currentInitialProgress by rememberUpdatedState(initialProgress)
    val currentNextEpisodeClick by rememberUpdatedState(onNextEpisodeClick)

    LaunchedEffect(videoUrl) {
        if (videoUrl.isBlank()) {
            playbackError = "No video source was provided for this title."
        }
    }

    // Auto-hide controls (keep visible while seeking / dialogs open)
    LaunchedEffect(showControls, controlsInteraction, isPlaying, isBuffering, isLocked, useWebViewPlayer, isSeeking, showHelpSettings) {
        if (isSeeking || showHelpSettings) return@LaunchedEffect
        if (isBuffering) {
            showControls = true
            return@LaunchedEffect
        }
        if (showControls && (isPlaying || useWebViewPlayer) && !isLocked) {
            delay(3000)
            showControls = false
        }
        if (isLocked && showControls) {
            delay(3000)
            showControls = false
        }
    }

    LaunchedEffect(showAspectToast) {
        if (showAspectToast != null) {
            delay(1200)
            showAspectToast = null
        }
    }

    // Orientation
    LaunchedEffect(orientationMode) {
        try {
            activity?.requestedOrientation = when (orientationMode) {
                OrientationMode.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoPlayerView", "Failed to set orientation", e)
        }
    }

    // Immersive fullscreen + notch (edge-to-edge cutout)
    // Capture original brightness once per player session for reliable restoration
    val originalBrightness = remember(context) {
        activity?.window?.attributes?.screenBrightness
    }
    DisposableEffect(context) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = originalOrientation
            restoreBrightness(activity, originalBrightness)
        }
    }

    // Also restore brightness on lifecycle pause (covers cases where DisposableEffect
    // disposal is delayed, e.g., during AnimatedContent transitions or when minimized)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                restoreBrightness(activity, originalBrightness)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(isScreenshotProtected) {
        activity?.window?.let { window ->
            if (isScreenshotProtected) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(playbackSpeed) {
        playbackPrefs.edit().putFloat("playback_speed", playbackSpeed).apply()
    }

    // Sleep timer countdown
    LaunchedEffect(sleepTimerMinutes) {
        if (sleepTimerMinutes > 0) {
            sleepTimeRemainingSeconds = sleepTimerMinutes * 60
            while (sleepTimeRemainingSeconds > 0 && isActive) {
                delay(1000)
                sleepTimeRemainingSeconds--
            }
            if (isActive) {
                sleepTimerMinutes = 0
                Toast.makeText(context, "Sleep Timer finished. Paused playback.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Next-episode countdown: reveal the Netflix card in the last ~30s, and let it
    // count down to auto-play unless the user cancels. State resets per source.
    val nextEpThreshold = 30
    LaunchedEffect(duration, currentPosition, onNextEpisodeClick) {
        if (showNextEpisodeCancelled) return@LaunchedEffect
        if (onNextEpisodeClick != null && duration > 0 && !isSeeking) {
            val secondsLeft = (duration - currentPosition) / 1000
            if (secondsLeft in 1..nextEpThreshold && autoPlayCountdownSeconds == -1 && !nextEpisodeTriggered) {
                autoPlayCountdownSeconds = secondsLeft.toInt().coerceAtMost(15)
            } else if (secondsLeft > nextEpThreshold) {
                autoPlayCountdownSeconds = -1
            }
        }
    }

    LaunchedEffect(autoPlayCountdownSeconds) {
        if (autoPlayCountdownSeconds > 0) {
            while (autoPlayCountdownSeconds > 0) {
                delay(1000)
                autoPlayCountdownSeconds--
            }
            if (onNextEpisodeClick != null && !nextEpisodeTriggered) {
                nextEpisodeTriggered = true
                autoPlayCountdownSeconds = -1
                onNextEpisodeClick()
            }
        }
    }

    // ExoPlayer (tuned for OTT streaming startup + rebuffer)
    val trackSelector = remember { DefaultTrackSelector(context) }

    // HTTP factory is hoisted so its request headers can be refreshed per source
    // (Referer/Origin change when episodes live on a different CDN host).
    val httpFactory = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 ManaCinema/4.0"
            )
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
    }

    // Derive Referer/Origin from the source host. Many CDNs gate signed links on
    // these headers; the download path already sends them, so the player must
    // match or streams that download fine will 403/404/410.
    fun applyStreamHeadersFor(url: String) {
        val headers = HashMap<String, String>().apply {
            put("Accept", "*/*")
            // identity avoids gzip on already-compressed media and keeps Range/
            // Content-Length semantics predictable across CDNs.
            put("Accept-Encoding", "identity")
            put("Connection", "keep-alive")
            try {
                val u = java.net.URL(url.trim())
                if (u.protocol.startsWith("http")) {
                    val origin = "${u.protocol}://${u.host}"
                    put("Referer", "$origin/")
                    put("Origin", origin)
                }
            } catch (_: Exception) {
            }
        }
        httpFactory.setDefaultRequestProperties(headers)
    }

    val exoPlayer = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2_500,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        applyStreamHeadersFor(videoUrl)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply { playWhenReady = true }
    }

    // Apply quality when user changes preset and persist it
    LaunchedEffect(streamQuality) {
        applyStreamQuality(trackSelector, streamQuality)
        playbackPrefs.edit().putString("stream_quality", streamQuality.label).apply()
    }

    // Apply subtitle style to PlayerView
    LaunchedEffect(subtitleStyle, playerViewRef) {
        applySubtitleStyle(playerViewRef, subtitleStyle)
        trackPrefs.edit()
            .putFloat("subtitle_size_sp", subtitleStyle.sizeSp)
            .putInt("subtitle_text_color", subtitleStyle.textColor)
            .putInt("subtitle_background_color", subtitleStyle.backgroundColor)
            .putInt("subtitle_edge_type", subtitleStyle.edgeType)
            .putInt("subtitle_edge_color", subtitleStyle.edgeColor)
            .apply()
    }

    // When tracks become available, re-apply the last saved override for this video URL if present
    LaunchedEffect(currentVideoUrl, audioTrackGroups, textTrackGroups) {
        fun applySavedTrack(type: Int, groups: List<Tracks.Group>, prefKeyBase: String) {
            if (groups.isEmpty()) return
            val savedId = trackPrefs.getString("${prefKeyBase}_${currentVideoUrl}", null) ?: return
            for (group in groups) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val id = format.id ?: format.label ?: "${group.mediaTrackGroup}_$i"
                    if (id == savedId) {
                        try {
                            trackSelector.setParameters(
                                trackSelector.buildUponParameters()
                                    .setOverrideForType(
                                        TrackSelectionOverride(
                                            group.mediaTrackGroup,
                                            i
                                        )
                                    )
                            )
                        } catch (_: Exception) {}
                        return
                    }
                }
            }
        }
        applySavedTrack(C.TRACK_TYPE_AUDIO, audioTrackGroups, "audio_track")
        applySavedTrack(C.TRACK_TYPE_TEXT, textTrackGroups, "subtitle_track")
    }

    // After every track change event, persist the active selections
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                audioTrackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                textTrackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                videoTrackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }

                fun persistSelection(type: Int, groups: List<Tracks.Group>, prefKeyBase: String) {
                    for (group in groups) {
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i)) {
                                val format = group.getTrackFormat(i)
                                val id = format.id ?: format.label ?: "${group.mediaTrackGroup}_$i"
                                trackPrefs.edit()
                                    .putString("${prefKeyBase}_${currentVideoUrl}", id)
                                    .apply()
                                return
                            }
                        }
                    }
                }
                persistSelection(C.TRACK_TYPE_AUDIO, audioTrackGroups, "audio_track")
                persistSelection(C.TRACK_TYPE_TEXT, textTrackGroups, "subtitle_track")
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                audioTrackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                textTrackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                videoTrackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                loadingState = when (state) {
                    Player.STATE_BUFFERING -> PlayerLoadingState.BUFFERING
                    Player.STATE_READY -> PlayerLoadingState.READY
                    Player.STATE_IDLE -> PlayerLoadingState.IDLE
                    else -> PlayerLoadingState.PREPARING
                }
                if (state == Player.STATE_READY) {
                    playbackError = null
                    retryCount = 0
                    isBuffering = false
                    if (exoPlayer.playWhenReady) showControls = false
                    // Resume playback from saved progress
                    if (currentInitialProgress > 0.01f && currentInitialProgress < 0.98f && !hasSeeked) {
                        val dur = exoPlayer.duration
                        if (dur > 0) {
                            exoPlayer.seekTo((dur * currentInitialProgress).toLong())
                            hasSeeked = true
                        }
                    }
                }
                if (state == Player.STATE_ENDED) {
                    isBuffering = false
                    if (currentNextEpisodeClick != null && !nextEpisodeTriggered) {
                        nextEpisodeTriggered = true
                        currentNextEpisodeClick?.invoke()
                    } else {
                        showControls = true
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    isBuffering = false
                    if (!isSeeking) showControls = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Log only schemes and hostnames. Logging the throwable can expose
                // full signed media URLs through nested exception messages.
                android.util.Log.e(
                    "VideoPlayerView",
                    "Playback failed source=${safeUrlOrigin(currentVideoUrl)} " +
                        "request=${failedRequestOrigin(error)} code=${error.errorCodeName} " +
                        "cause=${error.cause?.javaClass?.simpleName ?: "none"}"
                )
                isBuffering = false
                loadingState = PlayerLoadingState.ERROR

                val lower = currentVideoUrl.lowercase().trim()
                val isLocal = currentVideoUrl.startsWith("/") ||
                    lower.startsWith("file://") ||
                    lower.startsWith("content://")

                // Cleartext (plain HTTP) on a host not allowlisted in the network
                // security config is a policy decision, not a transient failure.
                // Retrying the same insecure link can never succeed, so surface a
                // clear explanation and offer "Go Back" only (no Retry).
                val isCleartextBlocked =
                    error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
                if (isCleartextBlocked) {
                    val alternative = if (!httpsAlternativeAttempted && !isLocal) {
                        httpsAlternativeFor(currentVideoUrl)
                    } else null
                    if (alternative != null) {
                        httpsAlternativeAttempted = true
                        usingHttpsAlternative = true
                        playbackError = null
                        isBuffering = true
                        loadingState = PlayerLoadingState.CONNECTING
                        retryCount = 0
                        currentVideoUrl = alternative
                        return
                    }
                    isCleartextBlockedError = true
                    playbackError = (if (usingHttpsAlternative) {
                        "The HTTPS stream references an insecure HTTP resource that is blocked on your device." +
                            "\n\nThe provider must serve the playlist and all media segments over HTTPS."
                    } else {
                        "This link uses insecure HTTP that is blocked on your device." +
                            "\n\nAsk the provider to serve this stream over HTTPS."
                    }) + "\n\nCode: ${error.errorCodeName}"
                    return
                }
                isCleartextBlockedError = false

                if (usingHttpsAlternative) {
                    val status = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                        ?.responseCode
                    val reason = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                            "The HTTPS endpoint returned HTTP $status."
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                            "The HTTPS endpoint could not be reached or its TLS connection failed."
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                            "The HTTPS endpoint did not return a valid media stream."
                        else -> "The HTTPS endpoint could not play this stream (${error.errorCodeName})."
                    }
                    playbackError = "$reason\n\nAsk the provider for a verified HTTPS stream URL."
                    return
                }

                val httpStatus = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                val isExpiredLink = !isLocal &&
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                    (httpStatus == null || httpStatus == 403 || httpStatus == 404 || httpStatus == 410)

                if (isExpiredLink && onRefreshUrl != null && !urlRefreshAttempted) {
                    urlRefreshAttempted = true
                    isBuffering = true
                    coroutineScope.launch {
                        val fresh = try { onRefreshUrl.invoke() } catch (_: Exception) { null }
                        if (!fresh.isNullOrBlank() && fresh != currentVideoUrl) {
                            // Reassigning currentVideoUrl re-triggers the source loader,
                            // which re-applies headers and rebuilds the MediaItem.
                            retryCount = 0
                            isCleartextBlockedError = false
                            currentVideoUrl = fresh
                        } else {
                            isBuffering = false
                            playbackError = "This link has expired and could not be refreshed. " +
                                "Please try again later.\n\nCode: ${error.errorCodeName}"
                        }
                    }
                    return
                }

                // Only transient network/IO errors are worth retrying on the SAME
                // real source. We never swap to a different (sample) video, so the
                // user always sees the stream they asked for or a truthful error.
                val isTransient = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    PlaybackException.ERROR_CODE_TIMEOUT -> true
                    else -> false
                }

                if (isTransient && !isLocal && retryCount < 3) {
                    retryCount++
                    loadingState = PlayerLoadingState.RETRYING
                    coroutineScope.launch {
                        delay(1500L * retryCount)
                        try {
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = true
                        } catch (_: Exception) { }
                    }
                    return
                }

                val friendly = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Network problem. Check your connection and try again."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "The video server rejected the request (link may be expired or restricted)."
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                        "Video not found on the server."
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                        "This video format is not supported on your device."
                    else -> error.localizedMessage ?: "Unable to play this video."
                }
                 playbackError = friendly
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Cast session
    DisposableEffect(castContext) {
        val sessionManager = castContext?.sessionManager
        val loadOnRemote: (com.google.android.gms.cast.framework.CastSession) -> Unit = { session ->
            val lower = currentVideoUrl.lowercase().trim()
            val isLocalSource = currentVideoUrl.startsWith("/") ||
                lower.startsWith("file://") ||
                lower.startsWith("content://")
            if (isLocalSource) {
                // A Chromecast can't reach a file stored on this device. Stay on the
                // local player instead of switching to a broken remote surface.
                isCasting = false
                exoPlayer.play()
                Toast.makeText(context, "Downloaded files can't be cast — playing on device.", Toast.LENGTH_LONG).show()
            } else try {
                val metadata = com.google.android.gms.cast.MediaMetadata(
                    com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE
                ).apply {
                    putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, title)
                }
                val mediaInfo = com.google.android.gms.cast.MediaInfo.Builder(currentVideoUrl)
                    .setStreamType(com.google.android.gms.cast.MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(getMimeTypeFromUrl(currentVideoUrl) ?: "video/mp4")
                    .setMetadata(metadata)
                    .build()
                val request = com.google.android.gms.cast.MediaLoadRequestData.Builder()
                    .setMediaInfo(mediaInfo)
                    .setAutoplay(true)
                    .setCurrentTime(exoPlayer.currentPosition)
                    .build()
                session.remoteMediaClient?.load(request)
                exoPlayer.pause()
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayerView", "Cast load failed", e)
            }
        }
        val sessionListener =
            object : com.google.android.gms.cast.framework.SessionManagerListener<
                com.google.android.gms.cast.framework.CastSession
                > {
                override fun onSessionStarted(
                    session: com.google.android.gms.cast.framework.CastSession,
                    sessionId: String
                ) {
                    isCasting = true
                    castDeviceName = session.castDevice?.friendlyName ?: "Cast device"
                    loadOnRemote(session)
                }

                override fun onSessionResumed(
                    session: com.google.android.gms.cast.framework.CastSession,
                    wasSuspended: Boolean
                ) {
                    isCasting = true
                    castDeviceName = session.castDevice?.friendlyName ?: "Cast device"
                }

                override fun onSessionEnded(
                    session: com.google.android.gms.cast.framework.CastSession,
                    error: Int
                ) {
                    isCasting = false
                    castDeviceName = ""
                    exoPlayer.play()
                }

                override fun onSessionSuspended(
                    session: com.google.android.gms.cast.framework.CastSession,
                    reason: Int
                ) { }

                override fun onSessionStarting(session: com.google.android.gms.cast.framework.CastSession) {}
                override fun onSessionStartFailed(
                    session: com.google.android.gms.cast.framework.CastSession,
                    error: Int
                ) {
                    isCasting = false
                    castDeviceName = ""
                }

                override fun onSessionEnding(session: com.google.android.gms.cast.framework.CastSession) {}
                override fun onSessionResuming(
                    session: com.google.android.gms.cast.framework.CastSession,
                    sessionId: String
                ) {}

                override fun onSessionResumeFailed(
                    session: com.google.android.gms.cast.framework.CastSession,
                    error: Int
                ) {}
            }
        sessionManager?.addSessionManagerListener(
            sessionListener,
            com.google.android.gms.cast.framework.CastSession::class.java
        )
        sessionManager?.currentCastSession?.let { existing ->
            if (existing.isConnected) {
                isCasting = true
                castDeviceName = existing.castDevice?.friendlyName ?: "Cast device"
            }
        }
        onDispose {
            sessionManager?.removeSessionManagerListener(
                sessionListener,
                com.google.android.gms.cast.framework.CastSession::class.java
            )
        }
    }

    LaunchedEffect(sleepTimerMinutes, sleepTimeRemainingSeconds) {
        if (sleepTimerMinutes > 0 && sleepTimeRemainingSeconds == 0) {
            exoPlayer.pause()
        }
    }

    val webView = remember {
        android.webkit.WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = false
            settings.databaseEnabled = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                    isBuffering = newProgress < 85
                }
            }
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean = false
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayerView", "WebView cleanup failed", e)
            }
        }
    }

    // Source loader
    LaunchedEffect(currentVideoUrl) {
        hasSeeked = false
        playbackError = null
        loadingState = if (currentVideoUrl.isBlank()) PlayerLoadingState.ERROR else PlayerLoadingState.CONNECTING
        nextEpisodeTriggered = false
        autoPlayCountdownSeconds = -1

        val urlLower = currentVideoUrl.lowercase().trim()
        // Local files and MediaStore content URIs are always native ExoPlayer sources.
        val isLocalSource = currentVideoUrl.startsWith("/") ||
            urlLower.startsWith("file://") ||
            urlLower.startsWith("content://")
        val isEmbedUrl = !isLocalSource && isEmbedPageUrl(currentVideoUrl)

        if (currentVideoUrl.isNotBlank() && isEmbedUrl) {
            useWebViewPlayer = true
            isBuffering = false
            loadingState = PlayerLoadingState.READY
            exoPlayer.pause()
        } else {
            useWebViewPlayer = false
            // Refresh Referer/Origin for this source before (re)preparing so a
            // per-episode CDN host still authorizes the signed link.
            if (!isLocalSource) applyStreamHeadersFor(currentVideoUrl)
            if (currentVideoUrl.isNotBlank()) {
                isBuffering = true
                loadingState = PlayerLoadingState.PREPARING
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                val uri = if (currentVideoUrl.startsWith("/")) {
                    android.net.Uri.fromFile(java.io.File(currentVideoUrl))
                } else {
                    // Handles http(s)://, content:// and file:// uniformly.
                    android.net.Uri.parse(currentVideoUrl)
                }
                val builder = MediaItem.Builder().setUri(uri)
                getMimeTypeFromUrl(currentVideoUrl)?.let { builder.setMimeType(it) }
                buildSubtitleConfiguration(subtitleUrl)?.let { builder.setSubtitleConfigurations(listOf(it)) }
                exoPlayer.setMediaItem(builder.build())
                exoPlayer.prepare()
                exoPlayer.play()
            } else {
                isBuffering = false
                playbackError = "No streaming link is available for this title."
            }
        }
    }

    // Progress loop
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            delay(500)
            if (exoPlayer.playbackState != Player.STATE_IDLE && !isCasting) {
                duration = exoPlayer.duration.coerceAtLeast(0L)
                if (!isSeeking) {
                    currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                }
                bufferedPosition = exoPlayer.bufferedPosition.coerceAtLeast(0L)
                if (duration > 0 && !isSeeking) {
                    val progress = currentPosition.toFloat() / duration
                    if (kotlin.math.abs(progress - lastReportedProgress) >= 0.005f) {
                        lastReportedProgress = progress
                        onProgressUpdate(progress)
                    }
                }
            }
        }
    }

    // Lifecycle / PiP on background
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    resumeAfterPause = isPlaying && !isCasting
                    // PiP is entered only through the explicit PiP action. A normal
                    // back/navigation event must not unexpectedly create a second
                    // visible player or keep audio running in the background.
                    exoPlayer.playWhenReady = false
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (resumeAfterPause && !isCasting) {
                        exoPlayer.playWhenReady = true
                    }
                    resumeAfterPause = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    fun seekBy(deltaMs: Long) {
        val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        exoPlayer.seekTo(target)
        currentPosition = target
        showControls = true
        controlsInteraction++
    }

    fun interactWithControls() {
        showControls = true
        controlsInteraction++
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null && !isCasting) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                activity.enterPictureInPictureMode(params)
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayerView", "PiP failed", e)
            }
        }
    }

    // ── Root UI ─────────────────────────────────────────────────────────────
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (!useWebViewPlayer) {
                    Modifier
                        .pointerInput(isLocked) {
                            // Long-press => temporary 2x speed until release
                            var speedBeforeHold = 1f
                            detectTapGestures(
                                onPress = {
                                    if (isLocked) return@detectTapGestures
                                    speedBeforeHold = playbackSpeed
                                    val released = tryAwaitRelease()
                                    // On release after a long-press-induced 2x, restore
                                    if (released && playbackSpeed == 2f && speedBeforeHold != 2f) {
                                        playbackSpeed = speedBeforeHold
                                        exoPlayer.setPlaybackSpeed(speedBeforeHold)
                                    }
                                },
                                onLongPress = {
                                    if (!isLocked && playbackSpeed != 2f) {
                                        playbackSpeed = 2f
                                        exoPlayer.setPlaybackSpeed(2f)
                                    }
                                }
                            )
                        }
                        .pointerInput(isLocked, duration) {
                            detectTapGestures(
                                onTap = { showControls = !showControls },
                                onDoubleTap = { offset ->
                                    if (!isLocked) {
                                        val isLeft = offset.x < size.width / 2f
                                        if (isLeft) {
                                            doubleTapAccumLeft =
                                                if (showDoubleTapLeft) doubleTapAccumLeft + 10 else 10
                                            doubleTapAccumRight = 10
                                            showDoubleTapLeft = true
                                            showDoubleTapRight = false
                                            seekBy(-10_000)
                                            coroutineScope.launch {
                                                delay(700)
                                                showDoubleTapLeft = false
                                                doubleTapAccumLeft = 10
                                            }
                                        } else {
                                            doubleTapAccumRight =
                                                if (showDoubleTapRight) doubleTapAccumRight + 10 else 10
                                            doubleTapAccumLeft = 10
                                            showDoubleTapRight = true
                                            showDoubleTapLeft = false
                                            seekBy(10_000)
                                            coroutineScope.launch {
                                                delay(700)
                                                showDoubleTapRight = false
                                                doubleTapAccumRight = 10
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        .pointerInput(isLocked) {
                            // Vertical drag: left = brightness, right = volume
                            detectVerticalDragGestures(
                                onDragStart = { },
                                onDragEnd = {
                                    showBrightness = false
                                    showVolume = false
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    if (isLocked) return@detectVerticalDragGestures
                                    change.consume()
                                    val isLeftSide = change.position.x < size.width / 2f
                                    val delta = -dragAmount / size.height.toFloat()
                                    if (isLeftSide) {
                                        showBrightness = true
                                        showVolume = false
                                        // Initialize from system default if not set
                                        val currentBrightness = if (brightnessLevel < 0f) 0.5f else brightnessLevel
                                        brightnessLevel = (currentBrightness + delta).coerceIn(0.01f, 1f)
                                        activity?.window?.let { window ->
                                            val attrs = window.attributes
                                            attrs.screenBrightness = brightnessLevel
                                            window.attributes = attrs
                                        }
                                    } else {
                                        showVolume = true
                                        showBrightness = false
                                        volumeLevel = (volumeLevel + delta).coerceIn(0f, 1f)
                                        val maxVol =
                                            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        audioManager.setStreamVolume(
                                            AudioManager.STREAM_MUSIC,
                                            (volumeLevel * maxVol).roundToInt(),
                                            0
                                        )
                                    }
                                }
                            )
                        }
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.pressed }) showControls = true
                            }
                        }
                    }
                }
            )
    ) {
        // ── Surface ─────────────────────────────────────────────────────────
        when {
            isCasting -> CastSurface(
                title = title,
                castDeviceName = castDeviceName,
                isPlaying = isPlaying,
                onRewind = { seekBy(-10_000) },
                onForward = { seekBy(10_000) },
                onPlayPause = {
                    if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                },
                onDisconnect = { castContext?.sessionManager?.endCurrentSession(true) }
            )

            useWebViewPlayer -> {
                AndroidView(
                    factory = { webView },
                    update = { view ->
                        if (view.url != currentVideoUrl) view.loadUrl(currentVideoUrl)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            else -> {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            resizeMode = aspectMode.resizeMode
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            playerViewRef = this
                            applySubtitleStyle(this, subtitleStyle)
                        }
                    },
                    update = { pv ->
                        pv.resizeMode = aspectMode.resizeMode
                        playerViewRef = pv
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // WebView floating chrome
        AnimatedVisibility(
            visible = useWebViewPlayer && showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CircleIconButton(Icons.Default.ArrowBack, "Back", onClick = {
                    restoreBrightness(activity, originalBrightness)
                    onBackClick()
                })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircleIconButton(Icons.Default.Refresh, "Native") {
                        useWebViewPlayer = false
                        retryCount = 0
                        playbackError = null
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }
                }
            }
        }

        // Double-tap feedback
        DoubleTapSeekBadge(
            visible = showDoubleTapLeft,
            isForward = false,
            seconds = doubleTapAccumLeft,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp)
        )
        DoubleTapSeekBadge(
            visible = showDoubleTapRight,
            isForward = true,
            seconds = doubleTapAccumRight,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 48.dp)
        )

        // Skip Intro / Recap — driven by episode metadata when provided
        val sc = seriesContext
        val curEp = sc?.currentEpisode
        val posSec = (currentPosition / 1000L).toInt()
        val durSec = (duration / 1000L).toInt()

        if (curEp != null && curEp.hasIntroSkip &&
            posSec >= curEp.introStartSec && posSec < curEp.introEndSec &&
            !isLocked && !isCasting
        ) {
            SkipChip(
                label = "SKIP INTRO",
                accent = false,
                onClick = {
                    val target = curEp.introEndSec.toLong() * 1000L
                    exoPlayer.seekTo(target)
                    currentPosition = target
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 110.dp, end = 24.dp)
            )
        } else if (curEp != null && curEp.hasRecapSkip &&
            posSec >= curEp.recapStartSec && posSec < curEp.recapEndSec &&
            !isLocked && !isCasting
        ) {
            SkipChip(
                label = "SKIP RECAP",
                accent = false,
                onClick = {
                    val target = curEp.recapEndSec.toLong() * 1000L
                    exoPlayer.seekTo(target)
                    currentPosition = target
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 110.dp, end = 24.dp)
            )
        } else if (seriesContext == null &&
            duration > 90_000 && currentPosition in 10_000..90_000 && !isLocked && !isCasting
        ) {
            // Legacy heuristic for movies (no series metadata available)
            SkipChip(
                label = "SKIP INTRO",
                accent = false,
                onClick = {
                    exoPlayer.seekTo(90_000)
                    currentPosition = 90_000
                    Toast.makeText(context, "Intro skipped", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 110.dp, end = 24.dp)
            )
        }

        // Skip Credits (last 10%) — only when there is no metadata-driven skip
        if (curEp?.hasIntroSkip != true && curEp?.hasRecapSkip != true &&
            duration > 180_000 &&
            currentPosition > duration * 0.9f &&
            currentPosition < duration - 10_000 &&
            !isLocked && !isCasting
        ) {
            SkipChip(
                label = "SKIP CREDITS",
                accent = true,
                onClick = {
                    val target = duration - 2_000
                    exoPlayer.seekTo(target)
                    currentPosition = target
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 110.dp, end = 24.dp)
            )
        }

        // Netflix-style Next Episode card (thumbnail, S/E info, countdown, cancel)
        if (autoPlayCountdownSeconds > 0 && !isLocked && !isCasting && !showNextEpisodeCancelled) {
            NextEpisodeCard(
                seconds = autoPlayCountdownSeconds,
                totalSeconds = 15,
                seriesContext = seriesContext,
                onPlayNow = {
                    if (onNextEpisodeClick != null && !nextEpisodeTriggered) {
                        nextEpisodeTriggered = true
                        autoPlayCountdownSeconds = -1
                        onNextEpisodeClick()
                    }
                },
                onCancel = {
                    showNextEpisodeCancelled = true
                    autoPlayCountdownSeconds = -1
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 120.dp, end = 24.dp)
            )
        }

        // Informative loading overlay keeps the user aware of network/player work.
        if (loadingState != PlayerLoadingState.READY &&
            loadingState != PlayerLoadingState.IDLE &&
            playbackError == null && !useWebViewPlayer && !isCasting
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = AccentRed,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(42.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(loadingState.message, color = Color.White, fontSize = 14.sp)
                if (loadingState == PlayerLoadingState.RETRYING) {
                    Spacer(Modifier.height(4.dp))
                    Text("Attempt $retryCount of 3", color = Color.LightGray, fontSize = 12.sp)
                }
            }
        }

        // Aspect mode toast
        AnimatedVisibility(
            visible = showAspectToast != null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                text = showAspectToast.orEmpty(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .background(Color.Black.copy(0.75f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        // Error overlay (rendered above all chrome so controls never overlap it)
        if (playbackError != null) {
            PlaybackErrorOverlay(
                message = playbackError.orEmpty(),
                allowRetry = !isCleartextBlockedError,
                onRetry = {
                    isCleartextBlockedError = false
                    retryCount = 0
                    playbackError = null
                    isBuffering = true
                    loadingState = PlayerLoadingState.RETRYING
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    val retryUri = if (currentVideoUrl.startsWith("/")) {
                        android.net.Uri.fromFile(java.io.File(currentVideoUrl))
                    } else android.net.Uri.parse(currentVideoUrl)
                    val retryItem = MediaItem.Builder().setUri(retryUri).apply {
                        getMimeTypeFromUrl(currentVideoUrl)?.let { setMimeType(it) }
                        buildSubtitleConfiguration(subtitleUrl)?.let { setSubtitleConfigurations(listOf(it)) }
                    }.build()
                    exoPlayer.setMediaItem(retryItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                },
                onBack = {
                    restoreBrightness(activity, originalBrightness)
                    onBackClick()
                }
            )
        }

        // Brightness / volume HUD
        if (showBrightness) {
            GestureSliderIndicator(
                icon = Icons.Default.BrightnessMedium,
                level = brightnessLevel,
                label = "Brightness",
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 28.dp)
            )
        }
        if (showVolume) {
            GestureSliderIndicator(
                icon = if (volumeLevel <= 0.01f) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                level = volumeLevel,
                label = "Volume",
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 28.dp)
            )
        }

        // ── Premium control chrome ──────────────────────────────────────────
        // When an error is shown, suppress all interactive chrome so the
        // PlaybackErrorOverlay has the full screen to itself (no overlap, no
        // stale seek/playback controls drawing underneath).
        AnimatedVisibility(
            visible = showControls && !useWebViewPlayer && playbackError == null,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(220))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { showControls = false })
                    }
            ) {
                // Gradient scrims (Netflix-style)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(0.85f), Color.Transparent)
                            )
                        )
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(0.9f))
                            )
                        )
                )

                if (isLocked) {
                    // Lock-only chrome
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Screen Locked",
                            color = Color.White.copy(0.85f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(10.dp))
                        IconButton(
                            onClick = { isLocked = false; showControls = true },
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color.Black.copy(0.7f), CircleShape)
                                .border(1.dp, Color.White.copy(0.25f), CircleShape)
                        ) {
                            Icon(
                                Icons.Default.LockOpen,
                                "Unlock",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Tap to unlock", color = Color.White.copy(0.55f), fontSize = 11.sp)
                    }
                } else {
                    // ── Top title bar ───────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            restoreBrightness(activity, originalBrightness)
                            onBackClick()
                        }) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                        }
                        if (onMinimizeClick != null && !isCasting) {
                            IconButton(onClick = {
                                restoreBrightness(activity, originalBrightness)
                                onMinimizeClick(currentPosition)
                            }) {
                                Icon(Icons.Default.FullscreenExit, "Minimize", tint = Color.White)
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = title.ifBlank { "Now Playing" },
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subtitleLine = buildString {
                                append(streamQuality.label)
                                if (playbackSpeed != 1f) append(" · ${playbackSpeed}x")
                                seriesContext?.currentEpisode?.let { ep ->
                                    append(" · S${ep.season} E${ep.episode}")
                                }
                            }
                            Text(
                                text = subtitleLine,
                                color = Color.White.copy(0.65f),
                                fontSize = 11.sp
                            )
                        }

                        if (castContext != null) {
                            AndroidView(
                                factory = { ctx ->
                                    // MediaRouteButton reads colorPrimary via AppCompat's
                                    // TintTypedArray. Theme.MovieHunt extends the plain platform
                                    // theme (not Theme.AppCompat), so that attribute is unresolved
                                    // and defaults to 0 (transparent), which crashes
                                    // ColorUtils.calculateContrast() with "background can not be
                                    // translucent: #0". Wrapping just this context avoids touching
                                    // the app-wide theme.
                                    val themedCtx = android.view.ContextThemeWrapper(
                                        ctx,
                                        androidx.appcompat.R.style.Theme_AppCompat_NoActionBar
                                    )
                                    androidx.mediarouter.app.MediaRouteButton(themedCtx).apply {
                                        CastButtonFactory.setUpMediaRouteButton(
                                            ctx.applicationContext,
                                            this
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .padding(6.dp)
                            )
                        }

                        // Help & Settings
                        IconButton(onClick = { showHelpSettings = true }) {
                            Icon(Icons.Outlined.HelpOutline, "Help & Settings", tint = Color.White)
                        }
                        IconButton(onClick = { showSleepTimerDialog = true }) {
                            Icon(
                                if (sleepTimerMinutes > 0) Icons.Default.Timer else Icons.Outlined.Timer,
                                "Sleep Timer",
                                tint = if (sleepTimerMinutes > 0) AccentRed else Color.White
                            )
                        }
                        if (videoTrackGroups.isNotEmpty()) {
                            IconButton(onClick = { showQualityDialog = true }) {
                                Icon(Icons.Default.HighQuality, "Quality", tint = Color.White)
                            }
                        }
                        if (audioTrackGroups.isNotEmpty() || textTrackGroups.isNotEmpty()) {
                            IconButton(onClick = { showTrackDialog = true }) {
                                Icon(Icons.Default.Subtitles, "Audio & Subtitles", tint = Color.White)
                            }
                        }
                        if (seriesContext != null && seriesContext.allEpisodes.isNotEmpty()) {
                            IconButton(onClick = { showEpisodeList = true }) {
                                Icon(Icons.Default.ViewList, "Episodes", tint = Color.White)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isCasting) {
                            IconButton(onClick = { enterPip() }) {
                                Icon(Icons.Default.PictureInPictureAlt, "PiP", tint = Color.White)
                            }
                        }
                    }

                    // Sleep timer badge
                    if (sleepTimerMinutes > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = 56.dp)
                                .background(Color.Black.copy(0.65f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Timer,
                                null,
                                tint = AccentRed,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            val m = sleepTimeRemainingSeconds / 60
                            val s = sleepTimeRemainingSeconds % 60
                            Text(
                                "Sleep ${String.format(Locale.US, "%02d:%02d", m, s)}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // ── Center transport ────────────────────────────────────
                    // Only render play/seek controls when there is playable media
                    // (duration known). Hides redundant controls on a failed or
                    // unloaded source.
                    val hasPlayableMedia = duration > 0
                    if (loadingState == PlayerLoadingState.READY && !isSeeking && hasPlayableMedia) Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(36.dp)
                    ) {
                        IconButton(
                            onClick = { interactWithControls(); seekBy(-10_000) },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                Icons.Default.Replay10,
                                "Rewind 10s",
                                tint = Color.White,
                                modifier = Modifier.size(42.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                interactWithControls()
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            modifier = Modifier
                                .size(78.dp)
                                .shadow(12.dp, CircleShape)
                                .background(AccentRed, CircleShape)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }

                        IconButton(
                            onClick = { interactWithControls(); seekBy(10_000) },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                Icons.Default.Forward10,
                                "Forward 10s",
                                tint = Color.White,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }

                    // ── Bottom tray: seek preview + tools ───────────────────
                    // Hide the entire bottom chrome when no media is loaded so
                    // seek/duration/speed controls never float over an error
                    // screen or an empty surface.
                    if (hasPlayableMedia) Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        // Seek bar with preview bubble + buffer track
                        SeekBarWithPreview(
                            positionMs = if (isSeeking) seekPreviewMs else currentPosition,
                            bufferedMs = bufferedPosition,
                            durationMs = duration,
                            isSeeking = isSeeking,
                            onSeekStart = {
                                isSeeking = true
                                seekPreviewMs = currentPosition
                                interactWithControls()
                            },
                            onSeek = { ms ->
                                seekPreviewMs = ms
                                currentPosition = ms
                            },
                            onSeekEnd = {
                                exoPlayer.seekTo(seekPreviewMs)
                                currentPosition = seekPreviewMs
                                isSeeking = false
                                interactWithControls()
                            }
                        )

                        Spacer(Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                IconButton(onClick = { isLocked = true }) {
                                    Icon(Icons.Default.Lock, "Lock", tint = Color.White)
                                }
                                IconButton(onClick = {
                                    aspectMode = when (aspectMode) {
                                        AspectMode.FIT -> AspectMode.FILL
                                        AspectMode.FILL -> AspectMode.ZOOM
                                        AspectMode.ZOOM -> AspectMode.FIT
                                    }
                                    showAspectToast = aspectMode.label
                                }) {
                                    Icon(Icons.Default.AspectRatio, "Aspect", tint = Color.White)
                                }
                                IconButton(onClick = {
                                    orientationMode = when (orientationMode) {
                                        OrientationMode.SENSOR -> OrientationMode.PORTRAIT
                                        OrientationMode.PORTRAIT -> OrientationMode.LANDSCAPE
                                        OrientationMode.LANDSCAPE -> OrientationMode.SENSOR
                                    }
                                }) {
                                    Icon(Icons.Default.ScreenRotation, "Rotate", tint = Color.White)
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (seriesContext != null && seriesContext.onPrevEpisodeClick != null) {
                                    IconButton(onClick = { seriesContext.onPrevEpisodeClick?.invoke() }) {
                                        Icon(Icons.Default.SkipPrevious, "Previous Episode", tint = Color.White)
                                    }
                                }
                                if (seriesContext != null && seriesContext.allEpisodes.isNotEmpty()) {
                                    TextButton(onClick = { showEpisodeList = true }) {
                                        Icon(Icons.Default.ViewList, null, modifier = Modifier.size(16.dp), tint = Color.White)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Episodes", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (onNextEpisodeClick != null) {
                                    IconButton(onClick = {
                                        if (!nextEpisodeTriggered) {
                                            nextEpisodeTriggered = true
                                            onNextEpisodeClick()
                                        }
                                    }) {
                                        Icon(Icons.Default.SkipNext, "Next Episode", tint = Color.White)
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Secure toggle chip — fixed width so the
                                // "SECURE" label never wraps vertically on
                                // narrow screens. Icon-only when protected
                                // actually conveys the state at a glance.
                                Surface(
                                    onClick = {
                                        isScreenshotProtected = !isScreenshotProtected
                                        Toast.makeText(
                                            context,
                                            if (isScreenshotProtected) "Screenshot protection on"
                                            else "Screenshot protection off",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isScreenshotProtected) AccentRedSoft else Color.Transparent,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isScreenshotProtected) AccentRed else Color.White.copy(0.25f)
                                    ),
                                    // Fixed width keeps the touch target usable and
                                    // prevents the label from wrapping.
                                    modifier = Modifier.width(88.dp)
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            if (isScreenshotProtected) Icons.Default.Security
                                            else Icons.Default.NoEncryption,
                                            null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        // Single-line label; ellipsis guards long locales.
                                        Text(
                                            "SECURE",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                TextButton(
                                    onClick = { showSpeedDialog = true },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                                ) {
                                    Icon(
                                        Icons.Default.Speed,
                                        null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color.White
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "${playbackSpeed}x",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (textTrackGroups.isNotEmpty() || subtitleUrl.isNotBlank()) {
                                    TextButton(
                                        onClick = { showSubtitleStyleDialog = true },
                                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                                    ) {
                                        Icon(
                                            Icons.Default.ClosedCaption,
                                            null,
                                            modifier = Modifier.size(16.dp),
                                            tint = Color.White
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("CC", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Dialogs ─────────────────────────────────────────────────────────
        if (showSleepTimerDialog) {
            SleepTimerDialog(
                selectedMinutes = sleepTimerMinutes,
                onSelect = {
                    sleepTimerMinutes = it
                    showSleepTimerDialog = false
                },
                onDismiss = { showSleepTimerDialog = false }
            )
        }

        if (showQualityDialog) {
            QualityDialog(
                selected = streamQuality,
                videoTrackGroups = videoTrackGroups,
                trackSelector = trackSelector,
                availableQualities = availableStreamQualities(videoTrackGroups),
                onSelectPreset = {
                    streamQuality = it
                    applyStreamQuality(trackSelector, it)
                    showQualityDialog = false
                },
                onSelectTrack = { group, index ->
                    streamQuality = StreamQuality.AUTO // manual track → treat as custom
                    trackSelector.setParameters(
                        trackSelector.buildUponParameters()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    )
                    showQualityDialog = false
                },
                onDismiss = { showQualityDialog = false }
            )
        }

        if (showTrackDialog) {
            AudioSubtitleDialog(
                audioTrackGroups = audioTrackGroups,
                textTrackGroups = textTrackGroups,
                trackSelector = trackSelector,
                onDismiss = { showTrackDialog = false },
                onOpenStyle = {
                    showTrackDialog = false
                    showSubtitleStyleDialog = true
                }
            )
        }

        if (showSpeedDialog) {
            SpeedDialog(
                selected = playbackSpeed,
                onSelect = {
                    playbackSpeed = it
                    exoPlayer.setPlaybackSpeed(it)
                    showSpeedDialog = false
                },
                onDismiss = { showSpeedDialog = false }
            )
        }

        if (showSubtitleStyleDialog) {
            SubtitleStyleDialog(
                style = subtitleStyle,
                onChange = {
                    subtitleStyle = it
                    applySubtitleStyle(playerViewRef, it)
                },
                onDismiss = { showSubtitleStyleDialog = false }
            )
        }

        if (showEpisodeList && seriesContext != null) {
            EpisodeListSheet(
                seriesContext = seriesContext,
                title = title,
                onSelect = { ep ->
                    showEpisodeList = false
                    seriesContext.onSelectEpisode?.invoke(ep)
                },
                onDismiss = { showEpisodeList = false }
            )
        }

        if (showHelpSettings) {
            HelpSettingsSheet(
                isScreenshotProtected = isScreenshotProtected,
                onToggleSecure = { isScreenshotProtected = it },
                onOpenQuality = {
                    showHelpSettings = false
                    showQualityDialog = true
                },
                onOpenAudioSubs = {
                    showHelpSettings = false
                    showTrackDialog = true
                },
                onOpenSubtitleStyle = {
                    showHelpSettings = false
                    showSubtitleStyleDialog = true
                },
                onOpenSpeed = {
                    showHelpSettings = false
                    showSpeedDialog = true
                },
                onOpenSleep = {
                    showHelpSettings = false
                    showSleepTimerDialog = true
                },
                onLock = {
                    showHelpSettings = false
                    isLocked = true
                    showControls = true
                },
                onPip = {
                    showHelpSettings = false
                    enterPip()
                },
                onDismiss = { showHelpSettings = false }
            )
        }
    }
}

// ── UI building blocks ──────────────────────────────────────────────────────

@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .background(Color.Black.copy(0.6f), CircleShape)
    ) {
        Icon(icon, contentDescription, tint = Color.White)
    }
}

@Composable
private fun DoubleTapSeekBadge(
    visible: Boolean,
    isForward: Boolean,
    seconds: Int,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.75f),
        exit = fadeOut() + scaleOut(targetScale = 0.75f),
        modifier = modifier
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.Black.copy(0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isForward) Icons.Default.FastForward else Icons.Default.FastRewind,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${seconds}s",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SkipChip(
    label: String,
    accent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (accent) AccentRed else Color.Black.copy(0.82f),
            contentColor = Color.White
        ),
        border = if (!accent) BorderStroke(1.dp, Color.White.copy(0.45f)) else null,
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = modifier
    ) {
        Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun NextEpisodeCard(
    seconds: Int,
    totalSeconds: Int,
    seriesContext: SeriesPlayerContext?,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val next = seriesContext?.nextEpisode
    val poster = next?.thumbnail?.ifBlank { seriesContext?.seriesPoster ?: "" } ?: ""

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D).copy(0.96f)),
        border = BorderStroke(1.dp, Color.White.copy(0.15f)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.width(300.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (poster.isNotBlank()) {
                    coil.compose.AsyncImage(
                        model = poster,
                        contentDescription = null,
                        modifier = Modifier
                            .size(width = 52.dp, height = 72.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1A1A1A))
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Next Episode",
                        color = Color.White.copy(0.7f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        next?.let { "S${it.season} E${it.episode} • ${it.title.ifBlank { "Episode ${it.episode}" }}" }
                            ?: "Up Next",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
                    CircularProgressIndicator(
                        progress = 1f - (seconds / totalSeconds.toFloat()),
                        color = AccentRed,
                        trackColor = Color.White.copy(0.18f),
                        strokeWidth = 3.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                    Text("$seconds", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Play Now",
                    color = AccentRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(onClick = onPlayNow)
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Cancel",
                    color = Color.White.copy(0.75f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun CastSurface(
    title: String,
    castDeviceName: String,
    isPlaying: Boolean,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onPlayPause: () -> Unit,
    onDisconnect: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(PanelBgSolid),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(Icons.Default.CastConnected, null, tint = AccentRed, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                "Casting: $title",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text("Connected to $castDeviceName", color = Color.LightGray)
            Spacer(Modifier.height(32.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onRewind,
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.DarkGray.copy(0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Replay10, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(72.dp)
                        .background(AccentRed, CircleShape)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(
                    onClick = onForward,
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.DarkGray.copy(0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Forward10, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Icon(Icons.Default.Cast, null)
                Spacer(Modifier.width(8.dp))
                Text("Disconnect Casting")
            }
        }
    }
}

@Composable
private fun SeekBarWithPreview(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    isSeeking: Boolean,
    onSeekStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekEnd: () -> Unit
) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (durationMs > 0) formatTime(positionMs) else "--:--",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (durationMs > 0) formatTime(durationMs) else "--:--",
                color = Color.White.copy(0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isSeeking) 52.dp else 36.dp),
            contentAlignment = Alignment.Center
        ) {
            // Seek preview bubble follows scrub position
            if (isSeeking) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                ) {
                    val bubbleX = (maxWidth * progress) - 40.dp
                    Surface(
                        color = Color.Black.copy(0.9f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, AccentRed.copy(0.75f)),
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .offset(x = bubbleX.coerceIn(0.dp, (maxWidth - 80.dp).coerceAtLeast(0.dp)))
                    ) {
                        Column(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                formatTime(positionMs),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            if (durationMs > 0) {
                                val pct = ((positionMs * 100f) / durationMs).toInt()
                                Text("$pct%", color = Color.White.copy(0.6f), fontSize = 10.sp)
                            }
                        }
                    }
                }
            }

            // Buffer track under slider
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (isSeeking) 5.dp else 3.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(0.18f))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(buffered)
                        .background(Color.White.copy(0.35f))
                )
            }

            Slider(
                value = progress,
                onValueChange = { v ->
                    if (!isSeeking) onSeekStart()
                    val ms = ((v.coerceIn(0f, 1f)) * durationMs).toLong()
                    onSeek(ms)
                },
                onValueChangeFinished = onSeekEnd,
                colors = SliderDefaults.colors(
                    thumbColor = AccentRed,
                    activeTrackColor = AccentRed,
                    inactiveTrackColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun PlaybackErrorOverlay(
    message: String,
    allowRetry: Boolean = true,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    // The overlay claims the entire surface with a blocking pointer input so the
    // underlying PlayerView and control chrome can neither receive touches nor
    // draw over the error. safeDrawingPadding() keeps buttons inside insets.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(0.94f))
            .pointerInput(Unit) { detectTapGestures { } }
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 500.dp)
                .padding(horizontal = 24.dp)
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = AccentRed, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(14.dp))
            Text(
                "Playback Error",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(message, color = Color.LightGray, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onBack,
                    border = BorderStroke(1.dp, Color.White),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Go Back") }
                if (allowRetry) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
fun GestureSliderIndicator(
    icon: ImageVector,
    level: Float,
    label: String,
    modifier: Modifier = Modifier
) {
    val displayLevel = if (level < 0f) 0.5f else level
    val displayText = if (level < 0f) "Auto" else "${(level * 100).toInt()}%"
    Column(
        modifier = modifier
            .width(56.dp)
            .height(190.dp)
            .background(Color.Black.copy(0.75f), RoundedCornerShape(28.dp))
            .border(1.dp, Color.White.copy(0.12f), RoundedCornerShape(28.dp))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Box(
            Modifier
                    .width(6.dp)
                    .weight(1f)
                    .padding(vertical = 12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(0.25f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(displayLevel.coerceIn(0f, 1f))
                    .align(Alignment.BottomCenter)
                    .background(AccentRed)
            )
        }
        Text(
            displayText,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Dialogs ─────────────────────────────────────────────────────────────────

@Composable
private fun SleepTimerDialog(
    selectedMinutes: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val timers = listOf(
        "Off" to 0,
        "10 min" to 10,
        "15 min" to 15,
        "30 min" to 30,
        "45 min" to 45,
        "60 min" to 60
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Timer, null, tint = AccentRed) },
        title = { Text("Sleep Timer") },
        text = {
            Column {
                Text(
                    "Playback pauses when the timer ends.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                timers.forEach { (label, mins) ->
                    val selected = selectedMinutes == mins
                    TextButton(onClick = { onSelect(mins) }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                label,
                                color = if (selected) AccentRed else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (selected) Icon(Icons.Default.Check, null, tint = AccentRed)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun availableStreamQualities(videoTrackGroups: List<Tracks.Group>): List<StreamQuality> {
    val heights = videoTrackGroups
        .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).height } }
        .filter { it > 0 }
        .toSet()

    if (heights.isEmpty()) return listOf(StreamQuality.AUTO)

    val presets = heights.mapNotNull { height ->
        when {
            height <= 400 -> StreamQuality.P360
            height <= 540 -> StreamQuality.P480
            height <= 800 -> StreamQuality.P720
            height <= 1080 -> StreamQuality.P1080
            else -> null
        }
    }.distinct()

    return listOf(StreamQuality.AUTO) + presets.distinct()
}

@OptIn(UnstableApi::class)
@Composable
private fun QualityDialog(
    selected: StreamQuality,
    videoTrackGroups: List<Tracks.Group>,
    trackSelector: DefaultTrackSelector,
    availableQualities: List<StreamQuality>,
    onSelectPreset: (StreamQuality) -> Unit,
    onSelectTrack: (Tracks.Group, Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HighQuality, null, tint = AccentRed)
                Spacer(Modifier.width(8.dp))
                Text("Stream Quality")
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    Text(
                        "Available qualities",
                        fontWeight = FontWeight.Bold,
                        color = AccentRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                items(availableQualities.size) { i ->
                    val q = availableQualities[i]
                    val isSel = selected == q
                    Surface(
                        onClick = { onSelectPreset(q) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSel) AccentRedSoft else Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSel,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = AccentRed)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    q.label + if (q == StreamQuality.AUTO) " (Adaptive)" else "",
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                )
                                if (q == StreamQuality.AUTO) {
                                    Text(
                                        "Adjusts to your network automatically",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                if (videoTrackGroups.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Available tracks",
                            fontWeight = FontWeight.Bold,
                            color = AccentRed,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                    videoTrackGroups.forEach { group ->
                        for (i in 0 until group.length) {
                            if (!group.isTrackSupported(i)) continue
                            val format = group.getTrackFormat(i)
                            val height = format.height
                            val label = when {
                                !format.label.isNullOrEmpty() -> format.label!!
                                height > 0 -> {
                                    val fps =
                                        if (format.frameRate > 0) " @ ${format.frameRate.toInt()}fps"
                                        else ""
                                    "${height}p$fps"
                                }
                                format.bitrate > 0 -> "${format.bitrate / 1000} kbps"
                                else -> "Track ${i + 1}"
                            }
                            val override = trackSelector.parameters.overrides[group.mediaTrackGroup]
                            val isOverride = override != null && override.trackIndices.contains(i)
                            item {
                                TextButton(
                                    onClick = { onSelectTrack(group, i) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            label,
                                            color = if (isOverride) AccentRed
                                            else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (isOverride) FontWeight.Bold
                                            else FontWeight.Normal
                                        )
                                        if (group.isTrackSelected(i)) {
                                            Text(
                                                "LIVE",
                                                color = AccentRed,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun AudioSubtitleDialog(
    audioTrackGroups: List<Tracks.Group>,
    textTrackGroups: List<Tracks.Group>,
    trackSelector: DefaultTrackSelector,
    onDismiss: () -> Unit,
    onOpenStyle: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Subtitles, null, tint = AccentRed)
                Spacer(Modifier.width(8.dp))
                Text("Audio & Subtitles")
            }
        },
        text = {
            LazyColumn {
                if (audioTrackGroups.isNotEmpty()) {
                    item {
                        Text(
                            "Audio",
                            fontWeight = FontWeight.Bold,
                            color = AccentRed,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    audioTrackGroups.forEach { group ->
                        for (i in 0 until group.length) {
                            val fmt = group.getTrackFormat(i)
                            val locale = fmt.language?.let { Locale(it) }
                            val name = locale?.displayName
                                ?: fmt.label
                                ?: fmt.language
                                ?: "Audio ${i + 1}"
                            val selected = group.isTrackSelected(i)
                            item {
                                TextButton(
                                    onClick = {
                                        trackSelector.setParameters(
                                            trackSelector.buildUponParameters()
                                                .setOverrideForType(
                                                    TrackSelectionOverride(group.mediaTrackGroup, i)
                                                )
                                        )
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            name,
                                            color = if (selected) AccentRed
                                            else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (selected) FontWeight.Bold
                                            else FontWeight.Normal
                                        )
                                        if (selected) {
                                            Icon(Icons.Default.Check, null, tint = AccentRed, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(12.dp)) }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Subtitles", fontWeight = FontWeight.Bold, color = AccentRed)
                        TextButton(onClick = onOpenStyle) {
                            Text("Customize", color = AccentRed, fontSize = 12.sp)
                        }
                    }
                }

                item {
                    val offSelected = !textTrackGroups.any { g ->
                        trackSelector.parameters.overrides.containsKey(g.mediaTrackGroup)
                    } && textTrackGroups.none { g ->
                        (0 until g.length).any { g.isTrackSelected(it) }
                    }
                    TextButton(
                        onClick = {
                            trackSelector.setParameters(
                                trackSelector.buildUponParameters()
                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            )
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Off",
                                color = if (offSelected) AccentRed else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (offSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (offSelected) Icon(Icons.Default.Check, null, tint = AccentRed, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                if (textTrackGroups.isEmpty()) {
                    item {
                        Text(
                            "Subtitles unavailable for this movie.",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                        )
                    }
                } else {
                    textTrackGroups.forEach { group ->
                        for (i in 0 until group.length) {
                            val fmt = group.getTrackFormat(i)
                            val locale = fmt.language?.let { Locale(it) }
                            val name = locale?.displayName
                                ?: fmt.label
                                ?: fmt.language
                                ?: "Subtitle ${i + 1}"
                            val selected = group.isTrackSelected(i)
                            item {
                                TextButton(
                                    onClick = {
                                        trackSelector.setParameters(
                                            trackSelector.buildUponParameters()
                                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                .setOverrideForType(
                                                    TrackSelectionOverride(group.mediaTrackGroup, i)
                                                )
                                        )
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            name,
                                            color = if (selected) AccentRed
                                            else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (selected) FontWeight.Bold
                                            else FontWeight.Normal
                                        )
                                        if (selected) {
                                            Icon(Icons.Default.Check, null, tint = AccentRed, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun SpeedDialog(
    selected: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Speed, null, tint = AccentRed)
                Spacer(Modifier.width(8.dp))
                Text("Playback Speed")
            }
        },
        text = {
            Column {
                speeds.forEach { speed ->
                    val isSel = speed == selected
                    TextButton(onClick = { onSelect(speed) }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${speed}x" + if (speed == 1f) " (Normal)" else "",
                                color = if (isSel) AccentRed else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                            if (isSel) Icon(Icons.Default.Check, null, tint = AccentRed)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleStyleDialog(
    style: SubtitleStyleConfig,
    onChange: (SubtitleStyleConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val sizes = listOf(14f to "S", 18f to "M", 22f to "L", 28f to "XL")
    val colors = listOf(
        AndroidColor.WHITE to "White",
        AndroidColor.YELLOW to "Yellow",
        AndroidColor.CYAN to "Cyan",
        AndroidColor.GREEN to "Green"
    )
    val backgrounds = listOf(
        AndroidColor.TRANSPARENT to "None",
        AndroidColor.argb(160, 0, 0, 0) to "Dim",
        AndroidColor.BLACK to "Solid"
    )
    val edges = listOf(
        CaptionStyleCompat.EDGE_TYPE_NONE to "None",
        CaptionStyleCompat.EDGE_TYPE_OUTLINE to "Outline",
        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW to "Shadow",
        CaptionStyleCompat.EDGE_TYPE_RAISED to "Raised"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ClosedCaption, null, tint = AccentRed)
                Spacer(Modifier.width(8.dp))
                Text("Subtitle Style")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Size", fontWeight = FontWeight.Bold, color = AccentRed, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sizes.forEach { (sp, label) ->
                        val sel = style.sizeSp == sp
                        FilterChip(
                            selected = sel,
                            onClick = { onChange(style.copy(sizeSp = sp)) },
                            label = { Text(label) }
                        )
                    }
                }

                Text("Text color", fontWeight = FontWeight.Bold, color = AccentRed, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { (c, label) ->
                        val sel = style.textColor == c
                        FilterChip(
                            selected = sel,
                            onClick = { onChange(style.copy(textColor = c)) },
                            label = { Text(label) }
                        )
                    }
                }

                Text("Background", fontWeight = FontWeight.Bold, color = AccentRed, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    backgrounds.forEach { (c, label) ->
                        val sel = style.backgroundColor == c
                        FilterChip(
                            selected = sel,
                            onClick = { onChange(style.copy(backgroundColor = c)) },
                            label = { Text(label) }
                        )
                    }
                }

                Text("Edge", fontWeight = FontWeight.Bold, color = AccentRed, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    edges.forEach { (e, label) ->
                        val sel = style.edgeType == e
                        FilterChip(
                            selected = sel,
                            onClick = { onChange(style.copy(edgeType = e)) },
                            label = { Text(label) }
                        )
                    }
                }

                // Live preview strip
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black, RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Sample subtitle preview",
                        color = argbToComposeColor(style.textColor),
                        fontSize = style.sizeSp.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .background(
                                argbToComposeColor(style.backgroundColor),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun HelpSettingsSheet(
    isScreenshotProtected: Boolean,
    onToggleSecure: (Boolean) -> Unit,
    onOpenQuality: () -> Unit,
    onOpenAudioSubs: () -> Unit,
    onOpenSubtitleStyle: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSleep: () -> Unit,
    onLock: () -> Unit,
    onPip: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null, tint = AccentRed)
                Spacer(Modifier.width(8.dp))
                Text("Help & Settings")
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Text(
                        "Gestures",
                        fontWeight = FontWeight.Bold,
                        color = AccentRed,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                item { HelpRow(Icons.Default.TouchApp, "Tap", "Show / hide controls") }
                item { HelpRow(Icons.Default.Replay10, "Double-tap left", "Rewind 10 seconds") }
                item { HelpRow(Icons.Default.Forward10, "Double-tap right", "Forward 10 seconds") }
                item { HelpRow(Icons.Default.BrightnessMedium, "Swipe left side", "Brightness") }
                item { HelpRow(Icons.Default.VolumeUp, "Swipe right side", "Volume") }

                item {
                    Text(
                        "Player settings",
                        fontWeight = FontWeight.Bold,
                        color = AccentRed,
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                    )
                }
                item {
                    SettingsActionRow(Icons.Default.HighQuality, "Stream quality", "Auto · 360p–1080p", onOpenQuality)
                }
                item {
                    SettingsActionRow(Icons.Default.Subtitles, "Audio & subtitles", "Multi-audio tracks", onOpenAudioSubs)
                }
                item {
                    SettingsActionRow(Icons.Default.ClosedCaption, "Subtitle style", "Size, color, background", onOpenSubtitleStyle)
                }
                item {
                    SettingsActionRow(Icons.Default.Speed, "Playback speed", "0.25x – 3x", onOpenSpeed)
                }
                item {
                    SettingsActionRow(Icons.Default.Timer, "Sleep timer", "Auto-pause", onOpenSleep)
                }
                item {
                    SettingsActionRow(Icons.Default.Lock, "Screen lock", "Hide controls", onLock)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    item {
                        SettingsActionRow(Icons.Default.PictureInPictureAlt, "Picture-in-Picture", "Watch while multitasking", onPip)
                    }
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggleSecure(!isScreenshotProtected) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Security, null, tint = Color.White.copy(0.9f), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Screenshot protection", fontWeight = FontWeight.Medium)
                            Text(
                                if (isScreenshotProtected) "Enabled" else "Disabled",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isScreenshotProtected,
                            onCheckedChange = onToggleSecure,
                            colors = SwitchDefaults.colors(checkedTrackColor = AccentRed)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun HelpRow(icon: ImageVector, title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White.copy(0.85f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White.copy(0.9f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(0.5f))
    }
}

// ── Netflix-style Episode List Bottom Sheet ─────────────────────────────────

@Composable
private fun EpisodeListSheet(
    seriesContext: SeriesPlayerContext,
    title: String,
    onSelect: (com.manacinema.app.models.Episode) -> Unit,
    onDismiss: () -> Unit
) {
    val currentEp = seriesContext.currentEpisode
    val seasons = remember(seriesContext.allEpisodes) {
        seriesContext.allEpisodes.map { it.season }.distinct().sorted().ifEmpty { listOf(1) }
    }
    var selectedSeason by remember(seriesContext.seriesTitle, seriesContext.allEpisodes) {
        mutableStateOf(currentEp?.season ?: seasons.first())
    }
    val episodesForSeason = remember(seriesContext.allEpisodes, selectedSeason) {
        seriesContext.allEpisodes.filter { it.season == selectedSeason }.sortedBy { it.episode }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    seriesContext.seriesTitle.ifBlank { title },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                currentEp?.let {
                    Text(
                        "S${it.season} E${it.episode} • ${it.title.ifBlank { "Episode ${it.episode}" }}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Season selector
                if (seasons.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons.size) { idx ->
                            val s = seasons[idx]
                            val cnt = seriesContext.allEpisodes.count { it.season == s }
                            val sel = s == selectedSeason
                            Surface(
                                onClick = { selectedSeason = s },
                                shape = RoundedCornerShape(20.dp),
                                color = if (sel) AccentRedSoft else Color.Transparent,
                                border = BorderStroke(1.dp, if (sel) AccentRed else Color.White.copy(0.25f))
                            ) {
                                Text(
                                    "Season $s ($cnt)",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(episodesForSeason.size) { idx ->
                        val ep = episodesForSeason[idx]
                        val id = "${seriesContext.seriesId}_s${ep.season}e${ep.episode}"
                        val watched = seriesContext.watchedProgress[id] ?: 0f
                        val isCurrent = ep.season == currentEp?.season && ep.episode == currentEp?.episode
                        Surface(
                            onClick = { if (!isCurrent) onSelect(ep) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isCurrent) AccentRedSoft else Color.White.copy(0.04f),
                            border = BorderStroke(1.dp, if (isCurrent) AccentRed else Color.White.copy(0.08f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                                val thumb = ep.thumbnail.ifBlank { seriesContext.seriesPoster }
                                if (thumb.isNotBlank()) {
                                    coil.compose.AsyncImage(
                                        model = thumb,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(width = 72.dp, height = 48.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF1A1A1A))
                                    )
                                    Spacer(Modifier.width(10.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "E${ep.episode} • ${ep.title.ifBlank { "Episode ${ep.episode}" }}",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (ep.durationSec > 0) {
                                        val m = ep.durationSec / 60
                                        val s = ep.durationSec % 60
                                        Text(
                                            "${m}m ${s}s".trim(),
                                            color = Color.White.copy(0.65f),
                                            fontSize = 11.sp
                                        )
                                    }
                                    if (watched > 0f && watched < 1f) {
                                        Spacer(Modifier.height(4.dp))
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(3.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(Color.White.copy(0.15f))
                                        ) {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth(watched.coerceIn(0f, 1f))
                                                    .fillMaxHeight()
                                                    .background(AccentRed)
                                            )
                                        }
                                    }
                                }
                                if (isCurrent) {
                                    Icon(Icons.Default.PlayCircle, null, tint = AccentRed, modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
