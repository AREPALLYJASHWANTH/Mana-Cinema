package com.manacinema.app.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.manacinema.app.MainActivity
import com.manacinema.app.database.AppDatabase
import com.manacinema.app.database.DownloadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import com.manacinema.app.R

class DownloadService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val activeDownloads = mutableMapOf<String, Job>()
    
    private lateinit var appDatabase: AppDatabase

    override fun onCreate() {
        super.onCreate()
        appDatabase = AppDatabase.getDatabase(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Any code path may have been started via startForegroundService(); we
        // MUST call startForeground() within ~5s or the system kills us with an
        // ANR/crash. Establish it immediately for every action, then process.
        ensureForeground()

        val action = intent?.action
        val downloadId = intent?.getStringExtra(EXTRA_DOWNLOAD_ID)

        if (action == ACTION_RESUME_ALL) {
            resumeInterruptedDownloads()
        } else if (action != null && downloadId != null) {
            when (action) {
                ACTION_START -> {
                    val title = intent.getStringExtra(EXTRA_TITLE) ?: "Download"
                    val poster = intent.getStringExtra(EXTRA_POSTER) ?: ""
                    val url = intent.getStringExtra(EXTRA_URL) ?: ""
                    val meta = DownloadMeta(
                        contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE) ?: "movie",
                        seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: "",
                        season = intent.getIntExtra(EXTRA_SEASON, 0),
                        episode = intent.getIntExtra(EXTRA_EPISODE, 0),
                        episodeTitle = intent.getStringExtra(EXTRA_EPISODE_TITLE) ?: "",
                        subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE_URL) ?: "",
                        quality = intent.getStringExtra(EXTRA_QUALITY) ?: "",
                        // Fall back to the row id so pre-quality callers still populate it.
                        contentId = intent.getStringExtra(EXTRA_CONTENT_ID) ?: downloadId,
                        isPremiumContent = intent.getBooleanExtra(EXTRA_IS_PREMIUM, false)
                    )
                    startDownload(downloadId, title, poster, url, meta)
                }
                ACTION_PAUSE -> pauseDownload(downloadId)
                ACTION_RESUME -> resumeDownload(downloadId)
                ACTION_CANCEL -> cancelDownload(downloadId)
                ACTION_DELETE -> cancelDownload(downloadId)
                else -> checkStopSelf()
            }
        } else {
            checkStopSelf()
        }
        // Re-deliver the last intent if the OS restarts us mid-download so queued
        // work resumes instead of being silently dropped.
        return START_REDELIVER_INTENT
    }

    private var isForeground = false

    private fun ensureForeground() {
        if (isForeground) return
        val notification = buildNotification("Mana Cinema", 0, "Preparing downloads...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
    }

    private data class DownloadMeta(
        val contentType: String = "movie",
        val seriesId: String = "",
        val season: Int = 0,
        val episode: Int = 0,
        val episodeTitle: String = "",
        val subtitleUrl: String = "",
        val quality: String = "",
        val contentId: String = "",
        val isPremiumContent: Boolean = false
    )

    private fun startDownload(id: String, title: String, poster: String, url: String, meta: DownloadMeta = DownloadMeta()) {
        // Duplicate prevention: ignore if already downloading in-memory.
        if (activeDownloads.containsKey(id)) return

        // Start lazily so the job is registered in activeDownloads BEFORE its
        // body (and its finally block) can run. Otherwise a fast-completing job
        // removes its id before registration, leaving a stale entry that keeps
        // the service foregrounded forever.
        val job = serviceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                // Initialize download or resume
                var downloadEntity = appDatabase.appDao().getDownloadById(id)
                // Already finished — don't re-download the same content.
                if (downloadEntity?.status == "COMPLETED" && !downloadEntity.localUri.isNullOrBlank()) {
                    updateNotification(title, 100, "Already downloaded")
                    return@launch
                }
                if (downloadEntity == null) {
                    downloadEntity = DownloadEntity(
                        id = id,
                        title = title,
                        poster = poster,
                        remoteUrl = url,
                        localUri = null,
                        status = "PENDING",
                        progress = 0,
                        totalBytes = 0,
                        downloadedBytes = 0,
                        contentType = meta.contentType,
                        seriesId = meta.seriesId,
                        season = meta.season,
                        episode = meta.episode,
                        episodeTitle = meta.episodeTitle,
                        subtitleUrl = meta.subtitleUrl,
                        quality = meta.quality,
                        contentId = meta.contentId.ifBlank { id },
                        isPremiumContent = meta.isPremiumContent
                    )
                    appDatabase.appDao().insertOrUpdateDownload(downloadEntity)
                }

                performDownload(downloadEntity)
            } catch (e: Exception) {
                e.printStackTrace()
                appDatabase.appDao().getDownloadById(id)?.let {
                    appDatabase.appDao().insertOrUpdateDownload(it.copy(status = "FAILED"))
                }
                updateNotification(title, 0, "Download Failed")
            } finally {
                activeDownloads.remove(id)
                checkStopSelf()
            }
        }
        activeDownloads[id] = job
        job.start()
    }

    private suspend fun performDownload(entity: DownloadEntity) {
        var currentEntity = entity

        // Validate the source URL. We never substitute a sample video: a bad URL
        // must surface as a real FAILED state so the user sees the actual problem.
        val targetUrlStr = currentEntity.remoteUrl.trim()
        if (targetUrlStr.isEmpty() || !targetUrlStr.startsWith("http")) {
            android.util.Log.e("DownloadService", "Invalid download URL for ${currentEntity.id}")
            currentEntity = currentEntity.copy(status = "FAILED")
            appDatabase.appDao().insertOrUpdateDownload(currentEntity)
            updateNotification(currentEntity.title, 0, "Invalid download link")
            return
        }

        // Derive a stable local filename from the URL extension (falls back to mp4).
        // The entity id already carries the quality suffix, so 720p and 1080p of the
        // same title produce different files instead of overwriting each other.
        val cleanUrl = targetUrlStr.substringBefore("?").substringBefore("#")
        val urlExtension = cleanUrl.substringAfterLast("/", "").substringAfterLast(".", "")
        val extension = if (urlExtension.isNotBlank() && urlExtension.length <= 5) urlExtension else "mp4"
        val fileName = "${currentEntity.id}.$extension"
        val extDir = getExternalFilesDir(null)
        if (extDir == null) {
            android.util.Log.e("DownloadService", "No external storage available")
            currentEntity = currentEntity.copy(status = "FAILED")
            appDatabase.appDao().insertOrUpdateDownload(currentEntity)
            updateNotification(currentEntity.title, 0, "No storage available")
            return
        }
        val downloadDir = File(extDir, "downloads")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        val targetFile = File(downloadDir, fileName)
        var downloadedBytes = if (targetFile.exists()) targetFile.length() else 0L

        val availableBytes = android.os.StatFs(downloadDir.absolutePath).availableBytes
        val expectedBytes = currentEntity.totalBytes
        if (expectedBytes > 0L && availableBytes < (expectedBytes - downloadedBytes).coerceAtLeast(0L)) {
            android.util.Log.e("DownloadService", "Insufficient storage for ${currentEntity.id}")
            currentEntity = currentEntity.copy(status = "FAILED")
            appDatabase.appDao().insertOrUpdateDownload(currentEntity)
            updateNotification(currentEntity.title, currentEntity.progress, "Insufficient storage")
            return
        }

        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var responseCode = 0

            // Establish the connection with automatic retry for transient failures
            // (timeouts, 5xx, 429). Redirects — including http<->https, which
            // HttpURLConnection refuses to follow automatically — are handled
            // manually inside openWithRedirects().
            var attempt = 0
            var connected = false
            while (attempt <= MAX_RETRIES && isActive && !connected) {
                try {
                    connection = openWithRedirects(targetUrlStr, downloadedBytes)
                    responseCode = connection.responseCode

                    // Server ignored our Range and is sending the whole file again:
                    // restart cleanly from byte 0 to avoid a corrupt file.
                    if (responseCode == HttpURLConnection.HTTP_OK && downloadedBytes > 0) {
                        downloadedBytes = 0
                        if (targetFile.exists()) targetFile.delete()
                    }

                    if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_PARTIAL) {
                        connected = true
                    } else if (isTransientHttp(responseCode) && attempt < MAX_RETRIES) {
                        connection.disconnect()
                        attempt++
                        updateNotification(currentEntity.title, currentEntity.progress, "Retrying... ($attempt)")
                        kotlinx.coroutines.delay(1500L * attempt)
                    } else {
                        throw Exception("HTTP $responseCode")
                    }
                } catch (e: Exception) {
                    connection?.disconnect()
                    val transient = e is java.net.SocketTimeoutException ||
                        e is java.net.UnknownHostException ||
                        e is java.net.ConnectException ||
                        e is java.io.InterruptedIOException
                    if (transient && attempt < MAX_RETRIES && isActive) {
                        attempt++
                        updateNotification(currentEntity.title, currentEntity.progress, "Retrying... ($attempt)")
                        kotlinx.coroutines.delay(1500L * attempt)
                    } else {
                        android.util.Log.e("DownloadService", "Download connection failed for ${currentEntity.id}", e)
                        currentEntity = currentEntity.copy(status = "FAILED")
                        appDatabase.appDao().insertOrUpdateDownload(currentEntity)
                        updateNotification(currentEntity.title, 0, "Download Failed")
                        return@withContext
                    }
                }
            }

            if (!connected || connection == null) {
                // Paused/cancelled mid-retry, or exhausted retries.
                if (isActive) {
                    currentEntity = currentEntity.copy(status = "FAILED")
                    appDatabase.appDao().insertOrUpdateDownload(currentEntity)
                    updateNotification(currentEntity.title, 0, "Download Failed")
                }
                return@withContext
            }

            val conn = connection!!
            val remoteTotal = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                downloadedBytes + conn.contentLengthLong
            } else {
                conn.contentLengthLong
            }

            if (remoteTotal > 0L) {
                val availableBytes = android.os.StatFs(downloadDir.absolutePath).availableBytes
                val remainingBytes = (remoteTotal - downloadedBytes).coerceAtLeast(0L)
                if (availableBytes < remainingBytes) {
                    currentEntity = currentEntity.copy(status = "FAILED")
                    appDatabase.appDao().insertOrUpdateDownload(currentEntity)
                    updateNotification(currentEntity.title, currentEntity.progress, "Insufficient storage")
                    conn.disconnect()
                    return@withContext
                }
            }

            // Already fully downloaded — just publish and finish.
            if (remoteTotal > 0 && downloadedBytes == remoteTotal) {
                val publicUri = publishToPublicStorage(targetFile, fileName)
                val subUri = if (currentEntity.subtitleUrl.isNotBlank())
                    downloadSubtitle(currentEntity.subtitleUrl, currentEntity.id) ?: "" else ""
                currentEntity = currentEntity.copy(
                    status = "COMPLETED",
                    progress = 100,
                    localUri = publicUri,
                    totalBytes = remoteTotal,
                    downloadedBytes = downloadedBytes,
                    subtitleLocalUri = subUri
                )
                appDatabase.appDao().insertOrUpdateDownload(currentEntity)
                updateNotification(currentEntity.title, 100, "Download Complete")
                conn.disconnect()
                return@withContext
            }

            currentEntity = currentEntity.copy(
                status = "DOWNLOADING",
                totalBytes = if (remoteTotal > 0) remoteTotal else 0L,
                downloadedBytes = downloadedBytes,
                localUri = targetFile.absolutePath
            )
            appDatabase.appDao().insertOrUpdateDownload(currentEntity)

            var lastUpdate = System.currentTimeMillis()
            var lastBytes = downloadedBytes
            var paused = false

            val randomAccessFile = RandomAccessFile(targetFile, "rw")
            randomAccessFile.seek(downloadedBytes)

            try {
                val inputStream: InputStream = conn.inputStream
                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (!isActive) {
                        paused = true
                        currentEntity = currentEntity.copy(status = "PAUSED", downloadedBytes = downloadedBytes)
                        appDatabase.appDao().insertOrUpdateDownload(currentEntity)
                        break
                    }

                    randomAccessFile.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 2000 || downloadedBytes == remoteTotal) {
                        val elapsed = (now - lastUpdate).coerceAtLeast(1)
                        val speedBps = ((downloadedBytes - lastBytes) * 1000) / elapsed
                        lastUpdate = now
                        lastBytes = downloadedBytes

                        val progress = if (remoteTotal > 0) ((downloadedBytes * 100) / remoteTotal).toInt() else 0
                        currentEntity = currentEntity.copy(
                            downloadedBytes = downloadedBytes,
                            progress = progress
                        )
                        appDatabase.appDao().insertOrUpdateDownload(currentEntity)
                        updateNotification(currentEntity.title, progress, buildProgressText(speedBps, downloadedBytes, remoteTotal))
                    }
                }
                inputStream.close()
            } finally {
                randomAccessFile.close()
                conn.disconnect()
            }

            if (!paused && isActive && remoteTotal > 0 && downloadedBytes >= remoteTotal) {
                // Verify the file on disk matches the expected size before
                // publishing. A truncated file (e.g. connection dropped without an
                // error) would otherwise be treated as a valid offline download.
                val onDisk = if (targetFile.exists()) targetFile.length() else 0L
                if (onDisk < remoteTotal) {
                    android.util.Log.e(
                        "DownloadService",
                        "Corrupt download ${currentEntity.id}: $onDisk/$remoteTotal bytes"
                    )
                    currentEntity = currentEntity.copy(
                        status = "FAILED",
                        downloadedBytes = onDisk,
                        progress = ((onDisk * 100) / remoteTotal).toInt()
                    )
                    appDatabase.appDao().insertOrUpdateDownload(currentEntity)
                    updateNotification(currentEntity.title, currentEntity.progress, "Download incomplete — retry")
                    return@withContext
                }
                val publicUri = publishToPublicStorage(targetFile, fileName)
                val subUri = if (currentEntity.subtitleUrl.isNotBlank())
                    downloadSubtitle(currentEntity.subtitleUrl, currentEntity.id) ?: "" else ""
                currentEntity = currentEntity.copy(
                    status = "COMPLETED",
                    progress = 100,
                    localUri = publicUri,
                    downloadedBytes = remoteTotal,
                    subtitleLocalUri = subUri
                )
                appDatabase.appDao().insertOrUpdateDownload(currentEntity)
                updateNotification(currentEntity.title, 100, "Download Complete")
            } else if (!paused && isActive && remoteTotal <= 0) {
                // Server never sent a length; treat a finished stream as complete.
                val publicUri = publishToPublicStorage(targetFile, fileName)
                val subUri = if (currentEntity.subtitleUrl.isNotBlank())
                    downloadSubtitle(currentEntity.subtitleUrl, currentEntity.id) ?: "" else ""
                currentEntity = currentEntity.copy(
                    status = "COMPLETED",
                    progress = 100,
                    localUri = publicUri,
                    totalBytes = downloadedBytes,
                    downloadedBytes = downloadedBytes,
                    subtitleLocalUri = subUri
                )
                appDatabase.appDao().insertOrUpdateDownload(currentEntity)
                updateNotification(currentEntity.title, 100, "Download Complete")
            }
        }
    }

    // Opens a connection, following up to 5 redirects manually. HttpURLConnection
    // will NOT auto-follow a redirect that switches protocol (http<->https), which
    // many CDNs use for signed links, so we resolve the chain ourselves and carry
    // the Range header through each hop.
    private fun openWithRedirects(startUrl: String, rangeStart: Long): HttpURLConnection {
        var currentUrl = startUrl
        var redirects = 0
        while (true) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                applyStreamingHeaders(this, currentUrl)
                if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
                connectTimeout = 15000
                readTimeout = 20000
                connect()
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || redirects >= 5) return conn
                // Resolve relative redirects against the current URL.
                currentUrl = try {
                    java.net.URL(URL(currentUrl), location).toString()
                } catch (_: Exception) {
                    location
                }
                redirects++
                continue
            }
            return conn
        }
    }

    private fun isTransientHttp(code: Int): Boolean =
        code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504

    // Attach the same identifying headers ExoPlayer sends, so CDNs that gate on
    // User-Agent / Referer behave identically for streaming and downloading.
    private fun applyStreamingHeaders(conn: HttpURLConnection, urlStr: String) {
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 ManaCinema/4.0"
        )
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Connection", "keep-alive")
        try {
            val u = URL(urlStr)
            val origin = "${u.protocol}://${u.host}"
            conn.setRequestProperty("Referer", "$origin/")
            conn.setRequestProperty("Origin", origin)
        } catch (_: Exception) {
        }
    }

    // Fetches the external subtitle into app-private storage next to the video.
    // Best-effort: a failure here must never fail the video download, so callers
    // ignore the null return. Kept in private storage (not MediaStore) because
    // subtitle sidecars are small and only the player needs to read them.
    private fun downloadSubtitle(subtitleUrl: String, downloadId: String): String? {
        val url = subtitleUrl.trim()
        if (url.isEmpty() || !url.startsWith("http")) return null
        return try {
            val clean = url.substringBefore("?").substringBefore("#")
            val ext = clean.substringAfterLast("/", "").substringAfterLast(".", "")
                .let { if (it.equals("vtt", true)) "vtt" else "srt" }
            val extDir2 = getExternalFilesDir(null) ?: return null
            val subDir = File(extDir2, "subtitles")
            if (!subDir.exists()) subDir.mkdirs()
            val subFile = File(subDir, "$downloadId.$ext")

            val conn = openWithRedirects(url, 0L)
            try {
                if (conn.responseCode !in 200..299) {
                    android.util.Log.e("DownloadService", "Subtitle fetch HTTP ${conn.responseCode} for $downloadId")
                    return null
                }
                conn.inputStream.use { input ->
                    subFile.outputStream().use { out -> input.copyTo(out) }
                }
            } finally {
                conn.disconnect()
            }
            if (subFile.length() > 0) subFile.absolutePath else null
        } catch (e: Exception) {
            android.util.Log.e("DownloadService", "Subtitle download failed for $downloadId", e)
            null
        }
    }

    private fun buildProgressText(speedBps: Long, done: Long, total: Long): String {
        val speed = formatBytes(speedBps) + "/s"
        if (total <= 0 || speedBps <= 0) return "Downloading... $speed"
        val remainingSec = (total - done) / speedBps
        val eta = when {
            remainingSec >= 3600 -> "${remainingSec / 3600}h ${(remainingSec % 3600) / 60}m left"
            remainingSec >= 60 -> "${remainingSec / 60}m ${remainingSec % 60}s left"
            else -> "${remainingSec}s left"
        }
        return "$speed · $eta"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    // Moves the finished staging file into public storage so it survives uninstall and is visible
    // to the system. API 29+ uses MediaStore (scoped storage); older devices use the public Movies dir.
    private fun publishToPublicStorage(stagingFile: File, fileName: String): String {
        if (!stagingFile.exists()) return stagingFile.absolutePath
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = applicationContext.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeForFile(fileName))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Mana Cinema")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, values)
                    ?: return stagingFile.absolutePath
                resolver.openOutputStream(itemUri)?.use { out ->
                    stagingFile.inputStream().use { input -> input.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                stagingFile.delete()
                itemUri.toString()
            } else {
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "Mana Cinema"
                )
                if (!publicDir.exists()) publicDir.mkdirs()
                val dest = File(publicDir, fileName)
                stagingFile.inputStream().use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                stagingFile.delete()
                // Make the file visible to the gallery / media apps on legacy storage.
                try {
                    android.media.MediaScannerConnection.scanFile(
                        applicationContext,
                        arrayOf(dest.absolutePath),
                        arrayOf(mimeTypeForFile(fileName)),
                        null
                    )
                } catch (_: Exception) {
                }
                dest.absolutePath
            }
        } catch (e: Exception) {
            android.util.Log.e("DownloadService", "Failed to publish to public storage", e)
            stagingFile.absolutePath
        }
    }

    private fun mimeTypeForFile(fileName: String): String {
        return when (fileName.substringAfterLast(".", "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "mpeg", "mpg" -> "video/mpeg"
            else -> "video/mp4"
        }
    }

    private fun pauseDownload(id: String) {
        activeDownloads[id]?.cancel()
        activeDownloads.remove(id)
        serviceScope.launch {
            appDatabase.appDao().getDownloadById(id)?.let {
                appDatabase.appDao().insertOrUpdateDownload(it.copy(status = "PAUSED"))
                updateNotification(it.title, it.progress, "Download Paused")
            }
            checkStopSelf()
        }
    }

    private fun resumeDownload(id: String) {
        serviceScope.launch {
            val entity = appDatabase.appDao().getDownloadById(id)
            if (entity != null) {
                startDownload(entity.id, entity.title, entity.poster, entity.remoteUrl, entity.toMeta())
            }
        }
    }

    // Called on app/service restart: any download left in DOWNLOADING/PENDING was
    // interrupted by a kill, so re-queue it. Partial bytes on disk are reused via
    // the Range request in performDownload().
    private fun resumeInterruptedDownloads() {
        serviceScope.launch {
            val active = appDatabase.appDao().getActiveDownloads()
            if (active.isEmpty()) {
                checkStopSelf()
                return@launch
            }
            active.forEach { entity ->
                startDownload(entity.id, entity.title, entity.poster, entity.remoteUrl, entity.toMeta())
            }
        }
    }

    private fun DownloadEntity.toMeta() = DownloadMeta(
        contentType = contentType,
        seriesId = seriesId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        subtitleUrl = subtitleUrl,
        quality = quality,
        contentId = contentId,
        isPremiumContent = isPremiumContent
    )

    private fun cancelDownload(id: String) {
        activeDownloads[id]?.cancel()
        activeDownloads.remove(id)
        serviceScope.launch {
            val entity = appDatabase.appDao().getDownloadById(id)
            val uri = entity?.localUri
            if (uri != null) {
                try {
                    if (uri.startsWith("content://")) {
                        applicationContext.contentResolver.delete(android.net.Uri.parse(uri), null, null)
                    } else {
                        val file = File(uri)
                        if (file.exists()) file.delete()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DownloadService", "Failed to delete download file", e)
                }
            }
            val subUri = entity?.subtitleLocalUri
            if (!subUri.isNullOrBlank()) {
                try {
                    val subFile = File(subUri)
                    if (subFile.exists()) subFile.delete()
                } catch (e: Exception) {
                    android.util.Log.e("DownloadService", "Failed to delete subtitle file", e)
                }
            }
            appDatabase.appDao().deleteDownload(id)
            checkStopSelf()
        }
    }

    private fun checkStopSelf() {
        if (activeDownloads.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
            stopSelf()
        }
    }

    private fun buildNotification(title: String, progress: Int, contentText: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mana Cinema: $title")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Make sure you have a valid icon
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(title: String, progress: Int, contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification(title, progress, contentText)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_CANCEL = "ACTION_CANCEL"
        const val ACTION_DELETE = "ACTION_DELETE"
        const val ACTION_RESUME_ALL = "ACTION_RESUME_ALL"

        const val EXTRA_DOWNLOAD_ID = "EXTRA_DOWNLOAD_ID"
        const val EXTRA_TITLE = "EXTRA_TITLE"
        const val EXTRA_POSTER = "EXTRA_POSTER"
        const val EXTRA_URL = "EXTRA_URL"
        const val EXTRA_CONTENT_TYPE = "EXTRA_CONTENT_TYPE"
        const val EXTRA_SERIES_ID = "EXTRA_SERIES_ID"
        const val EXTRA_SEASON = "EXTRA_SEASON"
        const val EXTRA_EPISODE = "EXTRA_EPISODE"
        const val EXTRA_EPISODE_TITLE = "EXTRA_EPISODE_TITLE"
        const val EXTRA_SUBTITLE_URL = "EXTRA_SUBTITLE_URL"
        const val EXTRA_QUALITY = "EXTRA_QUALITY"
        const val EXTRA_CONTENT_ID = "EXTRA_CONTENT_ID"
        const val EXTRA_IS_PREMIUM = "EXTRA_IS_PREMIUM"

        private const val CHANNEL_ID = "DownloadsChannel"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_RETRIES = 3

        fun startAction(
            context: Context,
            id: String,
            title: String,
            poster: String,
            url: String,
            subtitleUrl: String = "",
            quality: String = "",
            contentId: String = "",
            isPremiumContent: Boolean = false
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DOWNLOAD_ID, id)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_POSTER, poster)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_CONTENT_TYPE, "movie")
                putExtra(EXTRA_SUBTITLE_URL, subtitleUrl)
                putExtra(EXTRA_QUALITY, quality)
                putExtra(EXTRA_CONTENT_ID, contentId.ifBlank { id })
                putExtra(EXTRA_IS_PREMIUM, isPremiumContent)
            }
            launch(context, intent)
        }

        fun startEpisodeAction(
            context: Context,
            id: String,
            title: String,
            poster: String,
            url: String,
            seriesId: String,
            season: Int,
            episode: Int,
            episodeTitle: String,
            subtitleUrl: String = "",
            quality: String = "",
            contentId: String = "",
            isPremiumContent: Boolean = false
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DOWNLOAD_ID, id)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_POSTER, poster)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_CONTENT_TYPE, "webseries")
                putExtra(EXTRA_SERIES_ID, seriesId)
                putExtra(EXTRA_SEASON, season)
                putExtra(EXTRA_EPISODE, episode)
                putExtra(EXTRA_EPISODE_TITLE, episodeTitle)
                putExtra(EXTRA_SUBTITLE_URL, subtitleUrl)
                putExtra(EXTRA_QUALITY, quality)
                putExtra(EXTRA_CONTENT_ID, contentId.ifBlank { id })
                putExtra(EXTRA_IS_PREMIUM, isPremiumContent)
            }
            launch(context, intent)
        }

        fun controlAction(context: Context, id: String, actionType: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = actionType
                putExtra(EXTRA_DOWNLOAD_ID, id)
            }
            // RESUME may need to (re)start a stopped service into the foreground
            // to run a download; the others target an already-running service but
            // startForegroundService is safe in all cases since onStartCommand
            // always calls startForeground() immediately.
            launch(context, intent)
        }

        // Re-queues any downloads left mid-flight when the app/service was killed.
        fun resumeInterrupted(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME_ALL
            }
            launch(context, intent)
        }

        private fun launch(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
