package com.manacinema.app.repository

import android.content.Context
import com.manacinema.app.database.*
import com.manacinema.app.models.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class MovieRepository(context: Context) {

    private val appContext = context.applicationContext

    private val repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    private val dbInstance: FirebaseDatabase? by lazy {
        try {
            FirebaseDatabase.getInstance(com.manacinema.app.firebase.FirebaseInitializer.databaseUrl)
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "FirebaseDatabase initialization failed", e)
            null
        }
    }
    private val appDao = AppDatabase.getDatabase(context).appDao()

    suspend fun ensureManaId(uid: String, cached: String?): String? {
        if (uid.isBlank() || uid == "guest_user") return cached
        return try {
            val result = com.google.firebase.functions.FirebaseFunctions
                .getInstance("asia-southeast1")
                .getHttpsCallable("ensureManaId")
                .call(emptyMap<String, Any>())
                .await().data as? Map<*, *>
            result?.get("shortId")?.toString()?.takeIf { it.isNotBlank() } ?: cached
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Mana ID creation failed", e)
            cached
        }
    }

    suspend fun migrateGuestRoomDataToUser(uid: String) {
        // Local rows use profile IDs as their ownership key; guest profile IDs remain
        // stable through migration, so no destructive Room rewrite is necessary.
        ensureUserRecord(uid, "Viewer", "")
    }

    // Firestore instance initialized safely
    private val firestoreInstance: FirebaseFirestore? by lazy {
        try {
            FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Firestore initialization failed", e)
            null
        }
    }

    // ------------------ LOCAL CACHE SYNC ------------------
    val localMoviesFlow: Flow<List<CachedMovieEntity>> = appDao.getAllMovies()
    val localSeriesFlow: Flow<List<CachedSeriesEntity>> = appDao.getAllSeries()

    fun getLocalWatchlistFlow(profileId: String): Flow<List<LocalWatchlistEntity>> {
        return appDao.getWatchlistForProfile(profileId)
    }

    fun getLocalContinueWatchingFlow(profileId: String): Flow<List<ContinueWatchingEntity>> {
        return appDao.getContinueWatchingForProfile(profileId)
    }

    fun getAllDownloads(): Flow<List<DownloadEntity>> {
        return appDao.getAllDownloads()
    }

    suspend fun saveProgress(profileId: String, contentId: String, title: String, poster: String, type: String, progress: Float) {
        val composite = "${profileId}_${contentId}"
        appDao.saveProgress(
            ContinueWatchingEntity(
                compositeId = composite,
                profileId = profileId,
                contentId = contentId,
                title = title,
                poster = poster,
                type = type,
                progress = progress,
                timestamp = System.currentTimeMillis()
            )
        )
        syncHistoryToFirestore(profileId, contentId, title, poster, type, progress)
    }

    suspend fun deleteProgress(profileId: String, contentId: String) {
        val composite = "${profileId}_${contentId}"
        appDao.deleteProgressByCompositeId(composite)
        deleteHistoryFromFirestore(profileId, contentId)
    }

    suspend fun toggleWatchlist(profileId: String, id: String, title: String, poster: String, backdrop: String, type: String) {
        val composite = "${profileId}_${id}"
        val isAlreadyWatchlisted = appDao.isWatchlisted(composite)
        if (isAlreadyWatchlisted) {
            appDao.removeFromWatchlistByCompositeId(composite)
            deleteWatchlistFromFirestore(profileId, id)
        } else {
            appDao.addToWatchlist(
                LocalWatchlistEntity(
                    compositeId = composite,
                    profileId = profileId,
                    id = id,
                    title = title,
                    poster = poster,
                    backdrop = backdrop,
                    type = type
                )
            )
            saveWatchlistToFirestore(profileId, id, title, poster, backdrop, type)
        }
    }

    suspend fun isWatchlisted(profileId: String, id: String): Boolean {
        val composite = "${profileId}_${id}"
        return appDao.isWatchlisted(composite)
    }

    // ------------------ FIRESTORE PROFILE SYSTEMS ------------------

    fun getProfilesFromFirestore(uid: String): Flow<List<MovieProfile>> = callbackFlow {
        val fs = firestoreInstance
        if (fs == null || uid.isEmpty() || uid == "guest_user") {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val ref = fs.collection("users").document(uid).collection("profiles")
        val registration = ref.addSnapshotListener { snapshot, error ->
            if (error != null) {
                android.util.Log.e("MovieRepository", "Error getting profiles", error)
                trySend(emptyList())
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val list = snapshot.documents.mapNotNull { doc ->
                    try {
                        MovieProfile(
                            id = doc.getString("id") ?: doc.id,
                            name = doc.getString("name") ?: "",
                            avatarUrl = doc.getString("avatarUrl") ?: "",
                            isKids = doc.getBoolean("isKids") ?: false,
                            preferredGenre = doc.getString("preferredGenre") ?: "All"
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                trySend(list)
            } else {
                trySend(emptyList())
            }
        }
        awaitClose { registration.remove() }
    }

    fun saveProfileToFirestore(uid: String, profile: MovieProfile) {
        val fs = firestoreInstance ?: return
        if (uid.isEmpty() || uid == "guest_user") return
        val map = mapOf(
            "id" to profile.id,
            "name" to profile.name,
            "avatarUrl" to profile.avatarUrl,
            "isKids" to profile.isKids,
            "preferredGenre" to profile.preferredGenre
        )
        fs.collection("users").document(uid).collection("profiles").document(profile.id)
            .set(map)
            .addOnFailureListener { e ->
                android.util.Log.e("MovieRepository", "Firestore save profile failed", e)
            }
    }

    fun deleteProfileFromFirestore(uid: String, profileId: String) {
        val fs = firestoreInstance ?: return
        if (uid.isEmpty() || uid == "guest_user") return
        fs.collection("users").document(uid).collection("profiles").document(profileId)
            .delete()
            .addOnFailureListener { e ->
                android.util.Log.e("MovieRepository", "Firestore delete profile failed", e)
            }
    }

    private fun getCurrentUserUid(): String? {
        return com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
    }

    private fun getCurrentUserEmail(): String? {
        // User panel doesn't use Firebase Auth - profiles are local only
        return null
    }

    private fun saveWatchlistToFirestore(profileId: String, id: String, title: String, poster: String, backdrop: String, type: String) {
        val fs = firestoreInstance ?: return
        val uid = getCurrentUserUid() ?: return
        val map = mapOf(
            "id" to id,
            "title" to title,
            "poster" to poster,
            "backdrop" to backdrop,
            "type" to type,
            "timestamp" to System.currentTimeMillis()
        )
        fs.collection("users").document(uid)
            .collection("profiles").document(profileId)
            .collection("watchlist").document(id)
            .set(map)
    }

    private fun deleteWatchlistFromFirestore(profileId: String, id: String) {
        val fs = firestoreInstance ?: return
        val uid = getCurrentUserUid() ?: return
        fs.collection("users").document(uid)
            .collection("profiles").document(profileId)
            .collection("watchlist").document(id)
            .delete()
    }

    private fun syncHistoryToFirestore(profileId: String, contentId: String, title: String, poster: String, type: String, progress: Float) {
        val fs = firestoreInstance ?: return
        val uid = getCurrentUserUid() ?: return
        val map = mapOf(
            "contentId" to contentId,
            "title" to title,
            "poster" to poster,
            "type" to type,
            "progress" to progress,
            "timestamp" to System.currentTimeMillis()
        )
        fs.collection("users").document(uid)
            .collection("profiles").document(profileId)
            .collection("history").document(contentId)
            .set(map)
    }

    private fun deleteHistoryFromFirestore(profileId: String, contentId: String) {
        val fs = firestoreInstance ?: return
        val uid = getCurrentUserUid() ?: return
        fs.collection("users").document(uid)
            .collection("profiles").document(profileId)
            .collection("history").document(contentId)
            .delete()
    }

    suspend fun syncFirestoreWatchlistAndHistoryToLocal(profileId: String) {
        val fs = firestoreInstance ?: return
        val uid = getCurrentUserUid() ?: return

        fs.collection("users").document(uid)
            .collection("profiles").document(profileId)
            .collection("watchlist")
            .get()
            .addOnSuccessListener { querySnapshot ->
                repositoryScope.launch {
                    for (doc in querySnapshot.documents) {
                        val id = doc.getString("id") ?: doc.id
                        val title = doc.getString("title") ?: ""
                        val poster = doc.getString("poster") ?: ""
                        val backdrop = doc.getString("backdrop") ?: ""
                        val type = doc.getString("type") ?: "movie"

                        val composite = "${profileId}_${id}"
                        appDao.addToWatchlist(LocalWatchlistEntity(composite, profileId, id, title, poster, backdrop, type))
                    }
                }
            }

        fs.collection("users").document(uid)
            .collection("profiles").document(profileId)
            .collection("history")
            .get()
            .addOnSuccessListener { querySnapshot ->
                repositoryScope.launch {
                    for (doc in querySnapshot.documents) {
                        val contentId = doc.getString("contentId") ?: doc.id
                        val title = doc.getString("title") ?: ""
                        val poster = doc.getString("poster") ?: ""
                        val type = doc.getString("type") ?: ""
                        val progress = doc.getDouble("progress")?.toFloat() ?: 0f
                        val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()

                        val composite = "${profileId}_${contentId}"
                        appDao.saveProgress(ContinueWatchingEntity(composite, profileId, contentId, title, poster, type, progress, timestamp))
                    }
                }
            }
    }

    // Cache sync helpers
    suspend fun saveMoviesToLocal(movies: List<Movie>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val entities = movies.map {
            CachedMovieEntity(
                id = it.id,
                title = it.title,
                poster = it.poster,
                backdrop = it.backdrop,
                category = it.category,
                rating = it.rating,
                year = it.year,
                genre = it.genre,
                link = it.link,
                castJson = castToJson(it.cast),
                director = it.director,
                storyline = it.storyline,
                trailerUrl = it.trailerUrl,
                isPremium = it.isPremium,
                isNew = it.isNew,
                qualityBadge = it.qualityBadge,
                subtitleUrl = it.subtitleUrl,
                videoSourcesJson = sourcesToJson(it.videoSources),
                createdAt = it.createdAt
            )
        }
        appDao.clearMovies()
        appDao.insertMovies(entities)
    }

    suspend fun saveSeriesToLocal(series: List<WebSeries>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val entities = series.map { s ->
            CachedSeriesEntity(
                id = s.id,
                title = s.title,
                poster = s.poster,
                backdrop = s.backdrop,
                category = s.category,
                rating = s.rating,
                year = s.year,
                genre = s.genre,
                storyline = s.storyline,
                episodesJson = episodesToJson(s.episodes),
                castJson = castToJson(s.cast),
                director = s.director,
                trailerUrl = s.trailerUrl,
                isPremium = s.isPremium,
                isNew = s.isNew,
                qualityBadge = s.qualityBadge,
                createdAt = s.createdAt
            )
        }
        appDao.clearSeries()
        appDao.insertSeries(entities)
    }

    // ------------------ SIGNED-URL REFRESH ------------------

    suspend fun refreshMovieLink(movieId: String, isPremiumContent: Boolean, quality: String = ""): String? {
        if (isPremiumContent) return playbackSources(movieId, null, null)
            .firstOrNull { VideoSource.normalizeQuality(it.quality).equals(VideoSource.normalizeQuality(quality), ignoreCase = true) }?.streamUrl
        val db = dbInstance ?: return null
        if (movieId.isBlank()) return null
        return try {
            val snapshot = db.getReference("movies").child(movieId).get().await()
            if (snapshot.exists()) return freshStreamUrl(snapshot, quality)
            val querySnapshot = db.getReference("movies").orderByChild("id").equalTo(movieId).get().await()
            for (child in querySnapshot.children) return freshStreamUrl(child, quality)
            null
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "refreshMovieLink failed for $movieId", e)
            null
        }
    }

    suspend fun refreshEpisodeLink(seriesId: String, season: Int, episode: Int, isPremiumContent: Boolean, quality: String = ""): String? {
        if (isPremiumContent) return playbackSources(seriesId, season, episode)
            .firstOrNull { VideoSource.normalizeQuality(it.quality).equals(VideoSource.normalizeQuality(quality), ignoreCase = true) }?.streamUrl
        val db = dbInstance ?: return null
        if (seriesId.isBlank()) return null
        return try {
            var seriesSnapshot = db.getReference("webseries").child(seriesId).get().await()
            if (!seriesSnapshot.exists()) {
                val querySnapshot = db.getReference("webseries").orderByChild("id").equalTo(seriesId).get().await()
                seriesSnapshot = querySnapshot.children.firstOrNull() ?: return null
            }
            for (epChild in seriesSnapshot.child("episodes").children) {
                val s = parseInt(epChild.child("season").value, 1)
                val e = parseInt(epChild.child("episode").value, 1)
                if (s == season && e == episode) return freshStreamUrl(epChild, quality)
            }
            null
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "refreshEpisodeLink failed for $seriesId S${season}E$episode", e)
            null
        }
    }

    private fun freshStreamUrl(node: DataSnapshot, quality: String): String? {
        val sources = parseVideoSources(node).usable()
        if (sources.isNotEmpty()) {
            val wanted = VideoSource.normalizeQuality(quality)
            val match = sources.firstOrNull { it.quality == wanted } ?: sources.first()
            return match.streamUrl.takeIf { it.isNotBlank() }
        }
        return node.child("link").getValue(String::class.java)?.trim()?.takeIf { it.isNotEmpty() }
    }

    // ------------------ FIREBASE REALTIME DATABASE ------------------

    fun getMoviesFromFirebase(): Flow<List<Movie>> = callbackFlow {
        val db = dbInstance
        if (db == null) {
            close(IllegalStateException("Firebase Realtime Database is unavailable"))
            return@callbackFlow
        }
        // Order by createdAt ascending (oldest first), then reverse in memory for newest first.
        // This ensures newly added movies appear at the top of the list.
        val ref = db.getReference("movies").orderByChild("createdAt")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                repositoryScope.launch {
                    val list = mutableListOf<Movie>()
                    for (child in snapshot.children) {
                        try {
                            list.add(parseMovie(child))
                        } catch(e: Exception) {
                            android.util.Log.e("MovieRepository", "Error parsing movie", e)
                        }
                    }
                    // Reverse to get newest first (createdAt descending)
                    trySend(list.reversed())
                }
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun getWebSeriesFromFirebase(): Flow<List<WebSeries>> = callbackFlow {
        val db = dbInstance
        if (db == null) {
            close(IllegalStateException("Firebase Realtime Database is unavailable"))
            return@callbackFlow
        }
        // Order by createdAt ascending (oldest first), then reverse in memory for newest first.
        val ref = db.getReference("webseries").orderByChild("createdAt")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                repositoryScope.launch {
                    val list = mutableListOf<WebSeries>()
                    for (child in snapshot.children) {
                        try {
                            val id = child.child("id").getValue(String::class.java) ?: child.key ?: ""
                            val title = child.child("title").getValue(String::class.java) ?: ""
                            val poster = child.child("poster").getValue(String::class.java) ?: ""
                            val backdrop = child.child("backdrop").getValue(String::class.java) ?: ""
                            val category = parseCategories(child)
                            val rating = child.child("rating").getValue(String::class.java)
                                ?: (child.child("rating").value?.toString() ?: "")
                            val year = child.child("year").getValue(String::class.java)
                                ?: (child.child("year").value?.toString() ?: "")
                            val genre = child.child("genre").getValue(String::class.java) ?: ""
                            val storyline = child.child("storyline").getValue(String::class.java) ?: ""
                            val director = child.child("director").getValue(String::class.java) ?: ""
                            val trailerUrl = child.child("trailerUrl").getValue(String::class.java) ?: ""
                            val isPremium = parseBool(child.child("isPremium").value)
                            val isNew = parseBool(child.child("isNew").value)
                            val qualityBadge = child.child("qualityBadge").getValue(String::class.java)?.let {
                                if (it == "none") "" else it
                            } ?: ""
                            val cast = parseCast(child.child("cast"))
                            val createdAt = child.child("createdAt").getValue(Long::class.java) ?: 0L

                            val episodes = mutableListOf<Episode>()
                            val epSnapshot = child.child("episodes")
                            if (epSnapshot.exists()) {
                                for (epChild in epSnapshot.children) {
                                    try {
                                        episodes.add(parseEpisode(epChild))
                                    } catch (e: Exception) {
                                        android.util.Log.e("MovieRepository", "Error parsing individual episode safely", e)
                                    }
                                }
                            }
                            episodes.sortWith(compareBy({ it.season }, { it.episode }))

                            list.add(
                                WebSeries(
                                    id = id,
                                    title = title,
                                    poster = poster,
                                    backdrop = backdrop,
                                    category = category,
                                    rating = rating,
                                    year = year,
                                    genre = genre,
                                    storyline = storyline,
                                    episodes = episodes,
                                    cast = cast,
                                    director = director,
                                    trailerUrl = trailerUrl,
                                    isPremium = isPremium,
                                    isNew = isNew,
                                    qualityBadge = qualityBadge,
                                    createdAt = createdAt
                                )
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("MovieRepository", "Error parsing individual webseries safely", e)
                        }
                    }
                    // Reverse to get newest first (createdAt descending)
                    trySend(list.reversed())
                }
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun getSystemMaintenance(): Flow<Pair<Boolean, String>> = callbackFlow {
        val db = dbInstance
        if (db == null) {
            trySend(Pair(false, "System maintenance offline - Firebase not initialized"))
            close()
            return@callbackFlow
        }
        val ref = db.getReference("maintenance")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val enabled = snapshot.child("enabled").getValue(Boolean::class.java) ?: false
                val message = snapshot.child("message").getValue(String::class.java) ?: "Scheduled system maintenance under progress."
                trySend(Pair(enabled, message))
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(Pair(false, "Maintenance cancelled"))
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    // ------------------ NOTIFICATIONS (fixed: no targetEmails leak) ------------------

    fun getNotifications(): Flow<List<UserNotification>> = callbackFlow {
        val db = dbInstance
        if (db == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        // The database rules require both the timestamp ordering and the exact
        // bounded query. Reading the parent node without these constraints is
        // rejected for every authenticated user.
        val ref = db.getReference("notifications")
            .orderByChild("timestamp")
            .limitToLast(50)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<UserNotification>()
                for (child in snapshot.children) {
                    try {
                        val title = child.child("title").getValue(String::class.java) ?: ""
                        val message = child.child("message").getValue(String::class.java) ?: ""
                        val icon = child.child("icon").getValue(String::class.java) ?: ""
                        val target = child.child("target").getValue(String::class.java) ?: "all"
                        val timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L
                        val imageUrl = child.child("imageUrl").getValue(String::class.java)
                            ?.takeIf { it.isNotBlank() }
                            ?: child.child("image").getValue(String::class.java)
                            ?: ""
                        val movieId = child.child("movieId").getValue(String::class.java) ?: ""
                        val moviePoster = child.child("moviePoster").getValue(String::class.java) ?: ""

                        // The user panel is intentionally guest-only and has no sign-in
                        // identity. Every notification created by the admin is therefore
                        // treated as a broadcast for all app members. Keep targeting data
                        // private and never expose recipient lists to the client.

                        list.add(
                            UserNotification(
                                id = child.key ?: "",
                                title = title,
                                message = message,
                                icon = icon,
                                imageUrl = imageUrl,
                                movieId = movieId,
                                moviePoster = moviePoster,
                                target = target,
                                targetEmails = emptyList(), // never expose targeting data to clients
                                timestamp = timestamp
                            )
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("MovieRepository", "Error parsing notification", e)
                    }
                }
                list.sortByDescending { it.timestamp }
                trySend(list)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun getUserNotifications(uid: String): Flow<List<UserNotification>> = callbackFlow {
        val db = dbInstance
        if (db == null || uid.isBlank() || uid == "guest_user") {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val ref = db.getReference("user_notifications").child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.children.mapNotNull { child ->
                    val title = child.child("title").getValue(String::class.java) ?: return@mapNotNull null
                    UserNotification(
                        id = "user_${child.key ?: ""}",
                        title = title,
                        message = child.child("message").getValue(String::class.java) ?: "",
                        icon = child.child("icon").getValue(String::class.java) ?: "",
                        imageUrl = child.child("imageUrl").getValue(String::class.java) ?: "",
                        movieId = child.child("movieId").getValue(String::class.java) ?: "",
                        moviePoster = child.child("moviePoster").getValue(String::class.java) ?: "",
                        target = "specific",
                        targetEmails = emptyList(),
                        timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L
                    )
                }.sortedByDescending { it.timestamp })
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    // ------------------ MOVIE REQUEST (fixed: guest support) ------------------

    fun requestMovie(movieName: String, year: String, requesterEmail: String, onComplete: (Boolean) -> Unit) {
        val db = dbInstance
        if (db == null) {
            onComplete(false)
            return
        }
        try {
            val ref = db.getReference("movie_requests").push()
            val req = mutableMapOf<String, Any>(
                "requestedByUid" to (getCurrentUserUid() ?: ""),
                "movieName" to movieName,
                "movieYear" to year,
                "requestedBy" to requesterEmail,
                "requestedByEmail" to requesterEmail,
                "timestamp" to System.currentTimeMillis(),
                "isGuest" to true
            )
            ref.setValue(req).addOnCompleteListener { task ->
                onComplete(task.isSuccessful)
            }
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Error requesting movie via Firebase", e)
            onComplete(false)
        }
    }

    suspend fun playbackSources(contentId: String, season: Int? = null, episode: Int? = null): List<VideoSource> {
        val functions = com.google.firebase.functions.FirebaseFunctions.getInstance("asia-southeast1")
        val payload = hashMapOf<String, Any>(
            "contentId" to contentId,
            "installId" to installationId(appContext)
        )
        if (season != null && episode != null) { payload["season"] = season; payload["episode"] = episode }
        val result = functions.getHttpsCallable("getPlaybackSources").call(payload).await().data as? Map<*, *> ?: return emptyList()
        val list = result["sources"] as? List<*> ?: return emptyList()
        return list.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            VideoSource(quality = map["quality"]?.toString() ?: "", streamUrl = map["streamUrl"]?.toString() ?: "", downloadUrl = map["downloadUrl"]?.toString() ?: "")
        }
    }

    fun deleteMovieFromFirebase(movieId: String, onComplete: (Boolean) -> Unit) {
        val db = dbInstance ?: run { onComplete(false); return }
        db.getReference("movies").child(movieId).removeValue()
            .addOnCompleteListener { onComplete(it.isSuccessful) }
    }

    fun deleteSeriesFromFirebase(seriesId: String, onComplete: (Boolean) -> Unit) {
        val db = dbInstance ?: run { onComplete(false); return }
        db.getReference("webseries").child(seriesId).removeValue()
            .addOnCompleteListener { onComplete(it.isSuccessful) }
    }


    // ------------------ COMMENTS (Firestore) ------------------

    fun getComments(contentId: String): Flow<List<Comment>> = callbackFlow {
        val fs = firestoreInstance
        if (fs == null || contentId.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val ref = fs.collection("content_comments").document(contentId).collection("comments")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(200)
        val registration = ref.addSnapshotListener { snapshot, error ->
            if (error != null) {
                android.util.Log.e("MovieRepository", "Error getting comments", error)
                trySend(emptyList())
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val list = snapshot.documents.mapNotNull { doc ->
                    try {
                        Comment(
                            id = doc.id,
                            contentId = doc.getString("contentId") ?: contentId,
                            userId = doc.getString("userId") ?: "",
                            userName = doc.getString("userName") ?: "",
                            avatarUrl = doc.getString("avatarUrl") ?: "",
                            text = doc.getString("text") ?: "",
                            timestamp = doc.getLong("timestamp") ?: 0L
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                trySend(list)
            } else {
                trySend(emptyList())
            }
        }
        awaitClose { registration.remove() }
    }

    suspend fun addComment(contentId: String, userName: String, avatarUrl: String, text: String): Boolean {
        // Guest-only app: comments require Firebase Auth which is not available.
        // Return false to indicate comments are disabled for guest users.
        // The getComments flow still works for viewing existing comments from Firestore.
        if (getCurrentUserUid() == null) {
            android.util.Log.w("MovieRepository", "Comments disabled for guest users")
            return false
        }
        val fs = firestoreInstance ?: return false
        val uid = getCurrentUserUid() ?: return false
        if (contentId.isBlank() || text.isBlank()) return false
        try {
            val ref = fs.collection("content_comments").document(contentId).collection("comments").document()
            val map = mapOf(
                "id" to ref.id,
                "contentId" to contentId,
                "userId" to uid,
                "userName" to userName,
                "avatarUrl" to avatarUrl,
                "text" to text,
                "timestamp" to System.currentTimeMillis()
            )
            ref.set(map).await()
            return true
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Error adding comment", e)
            return false
        }
    }

    // ------------------ RATINGS (Local Room database) ------------------

    suspend fun saveRating(profileId: String, contentId: String, score: Int): Boolean {
        if (profileId.isBlank() || contentId.isBlank()) return false
        try {
            val compositeId = "${profileId}_${contentId}"
            val entity = com.manacinema.app.database.RatingEntity(
                compositeId = compositeId,
                profileId = profileId,
                contentId = contentId,
                score = score,
                timestamp = System.currentTimeMillis()
            )
            appDao.saveRating(entity)
            return true
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Error saving rating", e)
            return false
        }
    }
    suspend fun setRating(profileId: String, contentId: String, score: Int): Boolean = saveRating(profileId, contentId, score)

    suspend fun getRating(profileId: String, contentId: String): Int {
        if (profileId.isBlank() || contentId.isBlank()) return 0
        try {
            val compositeId = "${profileId}_${contentId}"
            val entity = appDao.getRating(compositeId)
            return entity?.score ?: 0
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Error getting rating", e)
            return 0
        }
    }

    fun getRatingsForContent(contentId: String): Flow<List<com.manacinema.app.database.RatingEntity>> {
        return appDao.getRatingsForContent(contentId)
    }

    /**
     * Aggregate rating computed from the local Room ratings table.
     * This is a client-side read-only aggregation.
     */
    fun getAggregateRatingFromFirestore(contentId: String): Flow<Pair<Float, Int>> = callbackFlow {
        if (contentId.isBlank()) {
            trySend(Pair(0f, 0))
            close()
            return@callbackFlow
        }
        // Use the local Room database for aggregate ratings
        val ratingsFlow = appDao.getRatingsForContent(contentId)
        val job = repositoryScope.launch {
            ratingsFlow.collect { ratings ->
                var sum: Int = 0
                var count: Int = 0
                for (rating in ratings) {
                    val score = rating.score
                    if (score in 1..5) {
                        sum += score
                        count++
                    }
                }
                val avg = if (count > 0) sum.toFloat() / count else 0f
                trySend(Pair(avg, count))
            }
        }
        awaitClose { job.cancel() }
    }

    suspend fun getAggregateRating(contentId: String): Pair<Float, Int> {
        return try {
            var result = Pair(0f, 0)
            getAggregateRatingFromFirestore(contentId).take(1).collect { result = it }
            result
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Error getting aggregate rating", e)
            Pair(0f, 0)
        }
    }

    // ------------------ PREMIUM REQUESTS ------------------

    fun submitPremiumRequest(request: PremiumRequest, onComplete: (Boolean) -> Unit) {
        val db = dbInstance ?: run { onComplete(false); return }
        try {
            val ref = db.getReference("premium_requests").push()
            val map = mapOf(
                "userId" to request.userId,
                "shortId" to request.shortId,
                "userEmail" to request.userEmail,
                "planName" to request.planName,
                "planDays" to request.planDays,
                "planPrice" to request.planPrice,
                "deviceLimit" to request.deviceLimit,
                "utr" to request.utr,
                "status" to "pending",
                "timestamp" to System.currentTimeMillis()
            )
            ref.setValue(map).addOnCompleteListener { onComplete(it.isSuccessful) }
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Error submitting premium request", e)
            onComplete(false)
        }
    }

    fun submitRestoreRequest(uid: String, oldShortId: String, onComplete: (Boolean) -> Unit) {
        val db = dbInstance
        val normalized = oldShortId.trim().uppercase()
        if (db == null || uid.isBlank() || uid == "guest_user" || normalized.length !in 4..20) {
            onComplete(false)
            return
        }
        val request = mapOf(
            "userId" to uid,
            "oldShortId" to normalized,
            "status" to "pending",
            "timestamp" to System.currentTimeMillis()
        )
        db.getReference("restore_requests").push().setValue(request)
            .addOnCompleteListener { onComplete(it.isSuccessful) }
    }

    fun observeMyPremiumRequest(uid: String): Flow<PremiumRequest?> = callbackFlow {
        val db = dbInstance
        if (db == null || uid.isEmpty() || uid == "guest_user") {
            trySend(null); close(); return@callbackFlow
        }
        val ref = db.getReference("premium_requests").orderByChild("userId").equalTo(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val latest = snapshot.children.mapNotNull { c ->
                    try {
                        PremiumRequest(
                            userId = c.child("userId").getValue(String::class.java) ?: "",
                             userEmail = c.child("userEmail").getValue(String::class.java) ?: "",
                             shortId = c.child("shortId").getValue(String::class.java) ?: "",
                            planName = c.child("planName").getValue(String::class.java) ?: "",
                            planDays = parseInt(c.child("planDays").value),
                            planPrice = c.child("planPrice").getValue(String::class.java)
                                ?: (c.child("planPrice").value?.toString() ?: ""),
                            deviceLimit = parseInt(c.child("deviceLimit").value, 1),
                            utr = c.child("utr").getValue(String::class.java) ?: "",
                            status = c.child("status").getValue(String::class.java) ?: "pending",
                            rejectReason = c.child("rejectReason").getValue(String::class.java) ?: "",
                            timestamp = c.child("timestamp").getValue(Long::class.java) ?: 0L
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("MovieRepository", "Error parsing my premium request", e)
                        null
                    }
                }.maxByOrNull { it.timestamp }
                trySend(latest)
            }
            override fun onCancelled(error: DatabaseError) { trySend(null) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    // ------------------ DEVICE LIMIT (Phase 5) ------------------

    fun installationId(context: Context): String {
        val prefs = context.getSharedPreferences("mana_device", Context.MODE_PRIVATE)
        prefs.getString("installation_id", null)?.let { return it }
        val fresh = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("installation_id", fresh).apply()
        return fresh
    }

    /**
     * Atomic device registration via RTDB transaction — crash- and race-safe.
     *
     * Without a transaction two simultaneous first-launches could both observe
     * the same (empty) device list and both register, bypassing the limit.
     * With runTransaction the count check + insert is a single atomic commit.
     */
    suspend fun registerDevice(uid: String, installId: String, subscription: Subscription): DeviceStatus {
        if (uid.isEmpty() || uid == "guest_user" || installId.isEmpty()) return DeviceStatus.UNKNOWN
        if (!subscription.isActive) return DeviceStatus.UNKNOWN
        return try {
            val result = com.google.firebase.functions.FirebaseFunctions
                .getInstance("asia-southeast1")
                .getHttpsCallable("registerDevice")
                .call(mapOf("installId" to installId))
                .await().data as? Map<*, *>
            when (result?.get("status")?.toString()) {
                "ALLOWED" -> DeviceStatus.ALLOWED
                "LIMIT_REACHED" -> DeviceStatus.LIMIT_REACHED
                else -> DeviceStatus.UNKNOWN
            }
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Device registration failed", e)
            DeviceStatus.UNKNOWN
        }
    }

    suspend fun releaseDevice(uid: String, installId: String) {
        val db = dbInstance ?: return
        if (uid.isEmpty() || installId.isEmpty()) return
        try {
            db.getReference("users").child(uid).child("devices").child(installId).removeValue().await()
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Device release failed", e)
        }
    }

    // ------------------ ENSURE USER RECORD ------------------

    suspend fun ensureUserRecord(uid: String, displayName: String, email: String) {
        val db = dbInstance ?: return
        if (uid.isEmpty() || uid == "guest_user") return
        try {
            val userRef = db.getReference("users").child(uid)
            val snapshot = userRef.get().await()
            if (!snapshot.exists()) {
                userRef.child("displayName").setValue(displayName).await()
                userRef.child("email").setValue(email).await()
                userRef.child("lastLogin").setValue(System.currentTimeMillis()).await()
            } else {
                userRef.child("lastLogin").setValue(System.currentTimeMillis()).await()
            }
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "ensureUserRecord failed", e)
        }
    }

    // ------------------ AVATARS / APP VERSION / ADS ------------------

    fun getActiveAds(): Flow<List<AdItem>> = callbackFlow {
        val db = dbInstance
        if (db == null) { trySend(emptyList()); close(); return@callbackFlow }
        val ref = db.getReference("ads")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<AdItem>()
                for (child in snapshot.children) {
                    try {
                        list.add(
                            AdItem(
                                id = child.key ?: "",
                                title = child.child("title").getValue(String::class.java) ?: "",
                                type = child.child("type").getValue(String::class.java) ?: "image",
                                mediaUrl = child.child("mediaUrl").getValue(String::class.java) ?: "",
                                targetUrl = child.child("targetUrl").getValue(String::class.java) ?: "",
                                placement = child.child("placement").getValue(String::class.java) ?: "all",
                                duration = (child.child("duration").getValue(Int::class.java) ?: 0),
                                isActive = parseBool(child.child("isActive").value)
                            )
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("MovieRepository", "Error parsing ad", e)
                    }
                }
                trySend(list)
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun getAvatars(): Flow<Pair<List<String>, List<String>>> = callbackFlow {
        val db = dbInstance
        if (db == null) { trySend(Pair(emptyList(), emptyList())); close(); return@callbackFlow }
        val ref = db.getReference("app_settings/avatars")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val male = mutableListOf<String>()
                val female = mutableListOf<String>()
                for (child in snapshot.children) {
                    val url = child.value?.toString() ?: continue
                    if (child.key == "male") male.add(url)
                    else if (child.key == "female") female.add(url)
                }
                trySend(Pair(male, female))
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun getAppVersionInfo(): Flow<AppVersionInfo> = callbackFlow {
        val db = dbInstance
        if (db == null) { trySend(AppVersionInfo()); close(); return@callbackFlow }
        val ref = db.getReference("app_settings/version")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(
                    AppVersionInfo(
                        versionCode = snapshot.child("versionCode").getValue(Int::class.java) ?: 0,
                        versionName = snapshot.child("versionName").getValue(String::class.java) ?: "",
                        forceUpdate = parseBool(snapshot.child("forceUpdate").value),
                        apkUrl = snapshot.child("apkUrl").getValue(String::class.java) ?: "",
                        changelog = snapshot.child("changelog").getValue(String::class.java) ?: ""
                    )
                )
            }
            override fun onCancelled(error: DatabaseError) { trySend(AppVersionInfo()) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    // ------------------ PARSE HELPERS ------------------

    private fun parseBool(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        is Number -> value.toInt() != 0
        else -> false
    }

    private fun parseInt(value: Any?, default: Int = 0): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull() ?: default
        else -> default
    }

    private fun parseCategories(child: DataSnapshot): String {
        val arr = child.child("categories")
        if (arr.exists()) {
            val fromArray = arr.children.mapNotNull { c ->
                (c.getValue(String::class.java) ?: c.value?.toString())?.trim()
            }.filter { it.isNotBlank() }
            if (fromArray.isNotEmpty()) return fromArray.distinct().joinToString(", ")
            val scalar = arr.getValue(String::class.java)?.trim()
            if (!scalar.isNullOrBlank()) return splitCategories(scalar).joinToString(", ")
        }
        val legacy = child.child("category").getValue(String::class.java) ?: ""
        return splitCategories(legacy).joinToString(", ")
    }

    private fun parseVideoSources(parent: DataSnapshot): List<VideoSource> {
        val node = parent.child("videoSources")
        if (!node.exists()) return emptyList()
        return node.children.mapNotNull { c ->
            try {
                val quality = c.child("quality").getValue(String::class.java)
                    ?.takeIf { it.isNotBlank() }
                    ?: c.key.orEmpty()
                val stream = c.child("streamUrl").getValue(String::class.java)?.trim() ?: ""
                val download = c.child("downloadUrl").getValue(String::class.java)?.trim() ?: ""
                if (stream.isEmpty() && download.isEmpty()) return@mapNotNull null
                VideoSource(
                    quality = VideoSource.normalizeQuality(quality),
                    streamUrl = stream,
                    downloadUrl = download,
                    fileSize = c.child("fileSize").getValue(String::class.java) ?: "",
                    format = c.child("format").getValue(String::class.java) ?: "",
                    server = c.child("server").getValue(String::class.java) ?: "",
                    enabled = if (c.child("enabled").exists()) parseBool(c.child("enabled").value) else true
                )
            } catch (e: Exception) {
                android.util.Log.e("MovieRepository", "Error parsing video source", e)
                null
            }
        }.sortedByDescending { it.heightRank }
    }

    private fun parseAvailableQualities(parent: DataSnapshot): List<VideoSource> {
        return parent.child("availableQualities").children.mapNotNull { child ->
            val raw = child.getValue(String::class.java) ?: child.value?.toString()
            raw?.takeIf { it.isNotBlank() }?.let { quality ->
                VideoSource(quality = VideoSource.normalizeQuality(quality))
            }
        }.distinctBy { it.quality.lowercase() }.sortedByDescending { it.heightRank }
    }

    private fun parseEpisode(epChild: DataSnapshot): Episode {
        val isPremium = parseBool(epChild.child("isPremium").value)
        return Episode(
            season = parseInt(epChild.child("season").value, 1),
            episode = parseInt(epChild.child("episode").value, 1),
            title = epChild.child("title").getValue(String::class.java) ?: "",
            link = if (isPremium) "" else (epChild.child("link").getValue(String::class.java) ?: epChild.child("videoUrl").getValue(String::class.java) ?: ""),
            subtitleUrl = epChild.child("subtitleUrl").getValue(String::class.java) ?: "",
            thumbnail = epChild.child("thumbnail").getValue(String::class.java) ?: "",
            isPremium = isPremium,
            videoSources = parseVideoSources(epChild).ifEmpty { parseAvailableQualities(epChild) },
            // Optional skip windows, seconds. Absent or malformed = -1 (UI hides them).
            introStartSec = parseInt(epChild.child("introStart").value, -1),
            introEndSec = parseInt(epChild.child("introEnd").value, -1),
            recapStartSec = parseInt(epChild.child("recapStart").value, -1),
            recapEndSec = parseInt(epChild.child("recapEnd").value, -1),
            durationSec = parseInt(epChild.child("duration").value, 0).let { if (it > 0) it else parseInt(epChild.child("durationSec").value, 0) }
        )
    }

    private fun parseCast(castSnapshot: DataSnapshot): List<CastMember> {
        val cast = mutableListOf<CastMember>()
        if (!castSnapshot.exists()) return cast
        for (c in castSnapshot.children) {
            try {
                val name = c.child("name").getValue(String::class.java) ?: ""
                val photo = c.child("photo").getValue(String::class.java) ?: ""
                if (name.isNotEmpty() || photo.isNotEmpty()) {
                    cast.add(CastMember(name = name, photo = photo))
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieRepository", "Error parsing cast member", e)
            }
        }
        return cast
    }

    private fun parseMovie(child: DataSnapshot): Movie {
        val id = child.child("id").getValue(String::class.java)?.takeIf { it.isNotEmpty() } ?: child.key ?: ""
        val qualityRaw = child.child("qualityBadge").getValue(String::class.java) ?: ""
        val createdAt = child.child("createdAt").getValue(Long::class.java) ?: 0L
        val isPremium = parseBool(child.child("isPremium").value)
        return Movie(
            id = id,
            title = child.child("title").getValue(String::class.java) ?: "",
            poster = child.child("poster").getValue(String::class.java) ?: "",
            backdrop = child.child("backdrop").getValue(String::class.java) ?: "",
            category = parseCategories(child),
            rating = child.child("rating").getValue(String::class.java)
                ?: (child.child("rating").value?.toString() ?: ""),
            year = child.child("year").getValue(String::class.java)
                ?: (child.child("year").value?.toString() ?: ""),
            genre = child.child("genre").getValue(String::class.java) ?: "",
            // Premium streams never leave the server; only the link for free titles is cached locally.
            link = if (isPremium) "" else (child.child("link").getValue(String::class.java) ?: child.child("videoUrl").getValue(String::class.java) ?: ""),
            cast = parseCast(child.child("cast")),
            director = child.child("director").getValue(String::class.java) ?: "",
            storyline = child.child("storyline").getValue(String::class.java) ?: "",
            trailerUrl = child.child("trailerUrl").getValue(String::class.java) ?: "",
            isPremium = isPremium,
            isNew = parseBool(child.child("isNew").value),
            qualityBadge = if (qualityRaw == "none") "" else qualityRaw,
            subtitleUrl = child.child("subtitleUrl").getValue(String::class.java) ?: "",
            videoSources = parseVideoSources(child).ifEmpty { parseAvailableQualities(child) },
            createdAt = createdAt
        )
    }

    companion object {
        fun castToJson(cast: List<CastMember>): String {
            val arr = JSONArray()
            cast.forEach { c ->
                arr.put(JSONObject().apply {
                    put("name", c.name)
                    put("photo", c.photo)
                })
            }
            return arr.toString()
        }

        fun castFromJson(json: String?): List<CastMember> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    CastMember(o.optString("name", ""), o.optString("photo", ""))
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieRepository", "Error parsing cast JSON", e)
                emptyList()
            }
        }

        fun sourcesToJson(sources: List<VideoSource>): String {
            val arr = JSONArray()
            sources.forEach { s ->
                arr.put(JSONObject().apply {
                    put("quality", s.quality)
                    put("streamUrl", s.streamUrl)
                    put("downloadUrl", s.downloadUrl)
                    put("fileSize", s.fileSize)
                    put("format", s.format)
                    put("server", s.server)
                    put("enabled", s.enabled)
                })
            }
            return arr.toString()
        }

        fun sourcesFromJson(json: String?): List<VideoSource> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    VideoSource(
                        quality = o.optString("quality", ""),
                        streamUrl = o.optString("streamUrl", ""),
                        downloadUrl = o.optString("downloadUrl", ""),
                        fileSize = o.optString("fileSize", ""),
                        format = o.optString("format", ""),
                        server = o.optString("server", ""),
                        enabled = o.optBoolean("enabled", true)
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieRepository", "Error parsing video sources JSON", e)
                emptyList()
            }
        }

        fun episodesToJson(episodes: List<Episode>): String {
            val arr = JSONArray()
            episodes.forEach { ep ->
                arr.put(JSONObject().apply {
                    put("season", ep.season)
                    put("episode", ep.episode)
                    put("title", ep.title)
                    put("link", ep.link)
                    put("subtitleUrl", ep.subtitleUrl)
                    put("thumbnail", ep.thumbnail)
                    put("isPremium", ep.isPremium)
                    put("introStart", ep.introStartSec)
                    put("introEnd", ep.introEndSec)
                    put("recapStart", ep.recapStartSec)
                    put("recapEnd", ep.recapEndSec)
                    put("duration", ep.durationSec)
                    put("videoSources", JSONArray(sourcesToJson(ep.videoSources)))
                })
            }
            return arr.toString()
        }

        fun episodesFromJson(json: String?): List<Episode> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Episode(
                        season = o.optInt("season", 1),
                        episode = o.optInt("episode", 1),
                        title = o.optString("title", ""),
                        link = o.optString("link", ""),
                        subtitleUrl = o.optString("subtitleUrl", ""),
                        thumbnail = o.optString("thumbnail", ""),
                        isPremium = o.optBoolean("isPremium", false),
                        videoSources = sourcesFromJson(o.optJSONArray("videoSources")?.toString()),
                        introStartSec = o.optInt("introStart", -1),
                        introEndSec = o.optInt("introEnd", -1),
                        recapStartSec = o.optInt("recapStart", -1),
                        recapEndSec = o.optInt("recapEnd", -1),
                        durationSec = o.optInt("duration", 0)
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieRepository", "Error parsing episodes JSON", e)
                emptyList()
            }
        }

        fun splitCategories(raw: String): List<String> =
            raw.split(",", "|", "/")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
    }

    // ------------------ MISSING METHODS (re-added) ------------------

    fun observeUserBanned(uid: String): Flow<Boolean> = callbackFlow {
        val db = dbInstance
        if (db == null || uid.isEmpty() || uid == "guest_user") {
            trySend(false)
            close()
            return@callbackFlow
        }
        val ref = db.getReference("users").child(uid).child("isBanned")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(parseBool(snapshot.value))
            }
            override fun onCancelled(error: DatabaseError) {
                trySend(false)
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun observeSubscription(uid: String): Flow<Subscription> = callbackFlow {
        val db = dbInstance
        if (db == null || uid.isEmpty() || uid == "guest_user") {
            trySend(Subscription())
            close()
            return@callbackFlow
        }
        val ref = db.getReference("users").child(uid).child("subscription")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val expiry = when (val v = snapshot.child("expiryDate").value) {
                    is Number -> v.toLong()
                    is String -> v.toLongOrNull() ?: 0L
                    else -> 0L
                }
                trySend(
                    Subscription(
                        isPremium = parseBool(snapshot.child("isPremium").value),
                        planName = snapshot.child("planName").getValue(String::class.java) ?: "",
                        expiryDate = expiry,
                        deviceLimit = parseInt(snapshot.child("deviceLimit").value, 1)
                    )
                )
            }
            override fun onCancelled(error: DatabaseError) {
                trySend(Subscription())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun getPlans(): Flow<List<Plan>> = callbackFlow {
        val db = dbInstance
        if (db == null) {
            close(IllegalStateException("Firebase Database is unavailable"))
            return@callbackFlow
        }
        val ref = db.getReference("plans")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.mapNotNull { child ->
                    try {
                        Plan(
                            id = child.key ?: "",
                            name = child.child("name").getValue(String::class.java) ?: "",
                            price = child.child("price").getValue(String::class.java)
                                ?: (child.child("price").value?.toString() ?: ""),
                            days = parseInt(child.child("days").value),
                            devices = parseInt(child.child("devices").value, 1),
                            description = child.child("description").getValue(String::class.java) ?: ""
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("MovieRepository", "Error parsing plan", e)
                        null
                    }
                }
                trySend(list)
            }
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun getPaymentInfo(): Flow<PaymentInfo> = callbackFlow {
        val db = dbInstance
        if (db == null) { trySend(PaymentInfo()); close(); return@callbackFlow }
        val ref = db.getReference("app_settings/payment")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(
                    PaymentInfo(
                        upiId = snapshot.child("upiId").getValue(String::class.java) ?: "",
                        qrCodeUrl = snapshot.child("qrCodeUrl").getValue(String::class.java) ?: ""
                    )
                )
            }
            override fun onCancelled(error: DatabaseError) { trySend(PaymentInfo()) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    suspend fun hasInterruptedDownloads(): Boolean = appDao.getActiveDownloads().isNotEmpty()

    fun cleanup() {
        repositoryScope.cancel()
    }

    private fun kotlinx.coroutines.CoroutineScope.cancel() {
        (this.coroutineContext[kotlinx.coroutines.Job])?.cancel()
    }

}
