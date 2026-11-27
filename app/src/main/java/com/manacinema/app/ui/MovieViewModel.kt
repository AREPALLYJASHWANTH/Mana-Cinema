package com.manacinema.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.manacinema.app.localization.Language
import com.manacinema.app.models.*
import com.manacinema.app.repository.MovieRepository
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import com.manacinema.app.firebase.AppFirebaseMessagingService
import com.manacinema.app.firebase.FirebaseInitializer
import com.manacinema.app.notifications.OneSignalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MovieViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface PlansUiState {
        data object Loading : PlansUiState
        data class Success(val plans: List<Plan>) : PlansUiState
        data class Error(val message: String) : PlansUiState
    }

    private val repository = MovieRepository(application)
    private val sharedPrefs = application.getSharedPreferences("infinity_movie_prefs", Context.MODE_PRIVATE)
    private val lastProgressPersisted = mutableMapOf<String, Pair<Long, Float>>()

    // --- Floating Video Player State ---
    data class FloatingVideoState(
        val videoUrl: String,
        val title: String,
        val contentId: String,
        val poster: String,
        val type: String,
        val startPositionMs: Long
    )

    private val _floatingVideo = MutableStateFlow<FloatingVideoState?>(null)
    val floatingVideo: StateFlow<FloatingVideoState?> = _floatingVideo.asStateFlow()

    fun setFloatingVideo(state: FloatingVideoState?) {
        _floatingVideo.value = state
    }

    // ------------------ EXPOSED STATEFLOWS ------------------
    private val _movies = MutableStateFlow<List<Movie>>(emptyList())
    val movies: StateFlow<List<Movie>> = _movies.asStateFlow()

    private val _webSeries = MutableStateFlow<List<WebSeries>>(emptyList())
    val webSeries: StateFlow<List<WebSeries>> = _webSeries.asStateFlow()

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    private val _notifications = MutableStateFlow<List<UserNotification>>(emptyList())
    val notifications: StateFlow<List<UserNotification>> = _notifications.asStateFlow()
    private val broadcastNotifications = MutableStateFlow<List<UserNotification>>(emptyList())
    private val targetedNotifications = MutableStateFlow<List<UserNotification>>(emptyList())
    private val _manaId = MutableStateFlow("")
    val manaId: StateFlow<String> = _manaId.asStateFlow()

    fun submitRestoreRequest(oldShortId: String, onComplete: (Boolean) -> Unit) {
        val uid = _currentUserProfile.value?.uid.orEmpty()
        if (_isOffline.value || uid.isBlank() || uid == "guest_user") {
            onComplete(false)
            return
        }
        repository.submitRestoreRequest(uid, oldShortId, onComplete)
    }

    private fun publishNotifications() {
        _notifications.value = (broadcastNotifications.value + targetedNotifications.value)
            .distinctBy { it.id }
            .sortedByDescending { it.timestamp }
    }

    private val _watchlist = MutableStateFlow<List<WatchlistItem>>(emptyList())
    val watchlist: StateFlow<List<WatchlistItem>> = _watchlist.asStateFlow()

    private val _continueWatching = MutableStateFlow<List<ContinueWatching>>(emptyList())
    val continueWatching: StateFlow<List<ContinueWatching>> = _continueWatching.asStateFlow()

    private val _currentUserProfile = MutableStateFlow<UserProfile?>(null)
    val currentUserProfile: StateFlow<UserProfile?> = _currentUserProfile.asStateFlow()

    // --- Profile System State ---
    private val _activeProfile = MutableStateFlow<MovieProfile?>(null)
    val activeProfile: StateFlow<MovieProfile?> = _activeProfile.asStateFlow()

    private val _profiles = MutableStateFlow<List<MovieProfile>>(emptyList())
    val profiles: StateFlow<List<MovieProfile>> = _profiles.asStateFlow()

    val recommendedMovies: StateFlow<List<Movie>> = combine(movies, _activeProfile) { movieList, profile ->
        if (profile == null) return@combine movieList
        var filtered = movieList
        if (profile.isKids) {
            filtered = filtered.filter { movie ->
                // Kids-safe check spans both genre and the admin's categories, so a
                // title tagged only via categories is still matched.
                val haystack = (movie.genre + " " + movie.category).lowercase()
                haystack.contains("animation") || haystack.contains("family") ||
                    haystack.contains("comedy") || haystack.contains("adventure") ||
                    haystack.contains("kids")
            }
        } else if (profile.preferredGenre != "All") {
            val wanted = profile.preferredGenre.lowercase()
            filtered = filtered.filter { movie ->
                movie.genre.lowercase().contains(wanted) ||
                    movie.categoryList.any { it.lowercase() == wanted }
            }
        }
        filtered.take(12)
    }.flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ------------------ HOME SLIDER ROWS ------------------

    // Firebase has no ranking field, so "Top 10" and "Trending" preserve the order the
    // admin panel wrote rather than inventing a popularity metric.
    val heroItems: StateFlow<List<Movie>> = movies
        .map { list -> list.filter { it.backdrop.isNotBlank() || it.poster.isNotBlank() }.take(7) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val continueWatchingMovies: StateFlow<List<Pair<Movie, Float>>> =
        combine(continueWatching, movies) { records, movieList ->
            val byId = movieList.associateBy { it.id }
            records.mapNotNull { record ->
                byId[record.contentId]?.let { it to record.progress }
            }
        }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Keyed by row title, insertion-ordered. Only non-empty rows are emitted, so the
    // UI can render the map directly without null checks.
    val homeRows: StateFlow<Map<String, List<Movie>>> =
        combine(movies, categories, recommendedMovies) { movieList, cats, recommended ->
            if (movieList.isEmpty()) return@combine emptyMap<String, List<Movie>>()
            val rows = LinkedHashMap<String, List<Movie>>()

            rows[ROW_TOP_10] = movieList.take(10)
            rows[ROW_TRENDING] = movieList.drop(10).take(20)
            rows[ROW_NEW_RELEASES] = movieList.filter { it.isNew }.take(20)
            if (recommended.isNotEmpty()) rows[ROW_RECOMMENDED] = recommended

            // Genres come from the slash-separated `genre` field; categories are the
            // coarser admin-defined buckets. Both are data-driven, nothing hardcoded.
            // A movie tagged "Action, Drama, Thriller" appears in ALL THREE rows —
            // matching per-category, never string-equality on the whole field.
            cats.forEach { category ->
                val inCategory = movieList.filter { movie ->
                    movie.categoryList.any { it.equals(category, ignoreCase = true) } ||
                        (movie.categoryList.isEmpty() && category == UNCATEGORIZED)
                }
                if (inCategory.isNotEmpty()) rows[category] = inCategory
            }
            movieList
                .flatMap { movie -> movie.genre.split("/", ",").map { it.trim() } }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { genre ->
                    if (rows.containsKey(genre)) return@forEach
                    val inGenre = movieList.filter { movie ->
                        movie.genre.split("/", ",").any { it.trim().equals(genre, ignoreCase = true) }
                    }
                    if (inGenre.size >= 3) rows[genre] = inGenre
                }

            rows.filterValues { it.isNotEmpty() }
        }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ------------------ SERIES SLIDER ROWS ------------------
    // Same shape and logic as homeRows, built from webSeries instead of movies, so the
    // Series screen can render horizontal sliders (Top 10 / Trending / New / per-genre)
    // instead of a single flat vertical list.

    val seriesHeroItems: StateFlow<List<WebSeries>> = webSeries
        .map { list -> list.filter { it.backdrop.isNotBlank() || it.poster.isNotBlank() }.take(7) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val seriesRows: StateFlow<Map<String, List<WebSeries>>> =
        combine(webSeries, categories) { seriesList, cats ->
            if (seriesList.isEmpty()) return@combine emptyMap<String, List<WebSeries>>()
            val rows = LinkedHashMap<String, List<WebSeries>>()

            rows[ROW_TOP_10] = seriesList.take(10)
            rows[ROW_TRENDING] = seriesList.drop(10).take(20)
            rows[ROW_NEW_RELEASES] = seriesList.filter { it.isNew }.take(20)

            cats.forEach { category ->
                val inCategory = seriesList.filter { series ->
                    series.categoryList.any { it.equals(category, ignoreCase = true) } ||
                        (series.categoryList.isEmpty() && category == UNCATEGORIZED)
                }
                if (inCategory.isNotEmpty()) rows[category] = inCategory
            }
            seriesList
                .flatMap { series -> series.genre.split("/", ",").map { it.trim() } }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { genre ->
                    if (rows.containsKey(genre)) return@forEach
                    val inGenre = seriesList.filter { series ->
                        series.genre.split("/", ",").any { it.trim().equals(genre, ignoreCase = true) }
                    }
                    if (inGenre.size >= 3) rows[genre] = inGenre
                }

            rows.filterValues { it.isNotEmpty() }
        }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    companion object {
        const val ROW_TOP_10 = "Top 10 Today"
        const val ROW_TRENDING = "Trending Now"
        const val ROW_NEW_RELEASES = "New Releases"
        const val ROW_RECOMMENDED = "Recommended for You"

        /** Bucket for titles the admin left without any category. */
        const val UNCATEGORIZED = "Movies"

        /**
         * Every distinct category across the catalog, from the multi-category
         * field. "Action, Drama" contributes both "Action" and "Drama".
         */
        fun distinctCategories(movies: List<Movie>): List<String> {
            val all = movies.flatMap { it.categoryList }
            val cats = all.distinctBy { it.lowercase() }.sorted()
            // Only offer the fallback bucket when something actually needs it.
            return if (movies.any { it.categoryList.isEmpty() }) cats + UNCATEGORIZED else cats
        }
    }

    private val _isGuestUser = MutableStateFlow(true)
    val isGuestUser: StateFlow<Boolean> = _isGuestUser.asStateFlow()

    private val _isMaintenance = MutableStateFlow(false)
    val isMaintenance: StateFlow<Boolean> = _isMaintenance.asStateFlow()

    private val _maintenanceMessage = MutableStateFlow("System is currently undergoing scheduled maintenance.")
    val maintenanceMessage: StateFlow<String> = _maintenanceMessage.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    // Catalog load state surfaced to the UI so failures are not silently
    // swallowed. Sealed so the Home/Series shells can render loading,
    // empty, or error-with-retry distinctly.
    sealed interface ContentState {
        data object Loading : ContentState
        data object Ready : ContentState
        data class Error(val message: String) : ContentState
    }
    private val _contentState = MutableStateFlow<ContentState>(ContentState.Loading)
    val contentState: StateFlow<ContentState> = _contentState.asStateFlow()

    fun clearContentError() {
        if (_contentState.value is ContentState.Error) _contentState.value = ContentState.Ready
    }

    private var globalAdminDataJob: kotlinx.coroutines.Job? = null

    private fun hasValidatedInternet(): Boolean {
        val manager = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun setOfflineState(offline: Boolean) {
        if (_isOffline.value == offline) return
        _isOffline.value = offline
        if (offline) {
            contentSyncJob?.cancel()
            contentSyncJob = null
            globalAdminDataJob?.cancel()
            globalAdminDataJob = null
            _isLoading.value = false
            _isRefreshing.value = false
        } else {
            loadGlobalAdminData()
            if (FirebaseAuth.getInstance().currentUser == null) {
                viewModelScope.launch { signInAnonymouslyAndSync() }
            } else {
                startContentSync()
            }
        }
    }

    val downloads: StateFlow<List<com.manacinema.app.database.DownloadEntity>> = repository.getAllDownloads()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refreshData() {
        if (_isOffline.value) return
        // Ensure we have an auth user before syncing; otherwise the RTDB
        // permission rules (auth != null) reject the read and the UI shows an
        // empty screen with no explanation.
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            _contentState.value = ContentState.Error(
                "Unable to connect to Mana Cinema services. Check your connection and try again."
            )
            viewModelScope.launch { signInAnonymouslyAndSync() }
            return
        }
        viewModelScope.launch {
            _isRefreshing.value = true
            _contentState.value = ContentState.Loading

            // Guarantee refresh state is cleared even if network fails
            launch {
                kotlinx.coroutines.delay(4000)
                _isRefreshing.value = false
                if (_contentState.value is ContentState.Loading) {
                    _contentState.value = ContentState.Ready
                }
            }

            var moviesOk = false
            var seriesOk = false
            launch {
                repository.getMoviesFromFirebase()
                    .catch { e ->
                        android.util.Log.e("MovieViewModel", "Refresh movies failed", e)
                        if (!moviesOk && _movies.value.isEmpty()) {
                            _contentState.value = ContentState.Error(userFacingMessage(e))
                        }
                    }
                    .take(1)
                    .collect { list ->
                        _movies.value = list
                        updateCategories(list)
                        repository.saveMoviesToLocal(list)
                        moviesOk = true
                        _isRefreshing.value = false
                        if (seriesOk || _webSeries.value.isNotEmpty()) {
                            _contentState.value = ContentState.Ready
                        }
                        if (list.isNotEmpty() || _webSeries.value.isNotEmpty()) {
                            _contentState.value = ContentState.Ready
                        }
                    }
            }

            launch {
                repository.getWebSeriesFromFirebase()
                    .catch { e ->
                        android.util.Log.e("MovieViewModel", "Refresh series failed", e)
                        if (!moviesOk && _movies.value.isEmpty()) {
                            _contentState.value = ContentState.Error(userFacingMessage(e))
                        }
                    }
                    .take(1)
                    .collect { list ->
                        _webSeries.value = list
                        repository.saveSeriesToLocal(list)
                        seriesOk = true
                        if (moviesOk || list.isNotEmpty()) {
                            _contentState.value = ContentState.Ready
                        }
                    }
            }
        }
    }

    // Moved up from later in the file: these MUST be initialized before init{} runs,
    // since observeUserSession() (called from init{}) sets these synchronously.
    private val _isBanned = MutableStateFlow(false)
    val isBanned: StateFlow<Boolean> = _isBanned.asStateFlow()

    private val _subscription = MutableStateFlow(Subscription())
    val subscription: StateFlow<Subscription> = _subscription.asStateFlow()

    private var contentSyncJob: kotlinx.coroutines.Job? = null

    private var banJob: kotlinx.coroutines.Job? = null
    private var subJob: kotlinx.coroutines.Job? = null
    private var userNotificationsJob: kotlinx.coroutines.Job? = null

    init {
        _isOffline.value = !hasValidatedInternet()
        // Safeguard initialization of Firebase
        com.manacinema.app.firebase.FirebaseInitializer.initialize(application)

        // Anonymous Firebase identity has no sign-in UI and is persisted by Firebase.
        observeUserSession()
        if (!_isOffline.value) viewModelScope.launch { signInAnonymouslyAndSync() }
        if (_isOffline.value) {
            val localProfiles = getSavedProfilesFromPrefs("guest_user").ifEmpty {
                listOf(
                    MovieProfile(
                        id = "prof_default",
                        name = "Guest User",
                        avatarUrl = "",
                        preferredGenre = "All"
                    )
                )
            }
            _profiles.value = localProfiles
            _activeProfile.value = localProfiles.first()
        }

        // Load global data that is not owned by a lifecycle-bound screen.
        if (!_isOffline.value) loadGlobalAdminData()

        // Fetch Live real-time sync from Firebase & fallback to Local DB
        viewModelScope.launch {
            _isLoading.value = true

            // Guarantees loading is cleared after 4 seconds even if Firebase hangs
            launch {
                kotlinx.coroutines.delay(4000)
                _isLoading.value = false
            }

            // Gather maintenance state securely
            if (!_isOffline.value) launch {
                repository.getSystemMaintenance()
                    .catch { e ->
                        android.util.Log.e("MovieViewModel", "Error syncing maintenance", e)
                        emit(Pair(false, "System maintenance failed to sync"))
                    }
                    .collect { (enabled, message) ->
                        _isMaintenance.value = enabled
                        _maintenanceMessage.value = message
                    }
            }

            // Sync or read cached items
            launch {
                repository.localMoviesFlow.collect { entities ->
                    val (mappedMovies, mappedCategories) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val mapped = entities.map {
                            Movie(
                                id = it.id,
                                title = it.title,
                                poster = it.poster,
                                backdrop = it.backdrop,
                                category = it.category,
                                rating = it.rating,
                                year = it.year,
                                genre = it.genre,
                                link = it.link,
                                cast = MovieRepository.castFromJson(it.castJson),
                                director = it.director,
                                storyline = it.storyline,
                                trailerUrl = it.trailerUrl,
                                isPremium = it.isPremium,
                                isNew = it.isNew,
                                qualityBadge = it.qualityBadge,
                                subtitleUrl = it.subtitleUrl,
                                videoSources = MovieRepository.sourcesFromJson(it.videoSourcesJson),
                                createdAt = it.createdAt
                            )
                        }
                        Pair(mapped, distinctCategories(mapped))
                    }
                    _movies.value = mappedMovies
                    _categories.value = mappedCategories
                }
            }

            launch {
                repository.localSeriesFlow.collect { entities ->
                    val mappedSeries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        entities.map { s ->
                            WebSeries(
                                id = s.id,
                                title = s.title,
                                poster = s.poster,
                                backdrop = s.backdrop,
                                category = s.category,
                                rating = s.rating,
                                year = s.year,
                                genre = s.genre,
                                storyline = s.storyline,
                                episodes = MovieRepository.episodesFromJson(s.episodesJson),
                                cast = MovieRepository.castFromJson(s.castJson),
                                director = s.director,
                                trailerUrl = s.trailerUrl,
                                isPremium = s.isPremium,
                                isNew = s.isNew,
                                qualityBadge = s.qualityBadge,
                                createdAt = s.createdAt
                            )
                        }
                            .sortedByDescending { it.createdAt }
                    }
                    _webSeries.value = mappedSeries
                }
            }

            // Bind watchlist & continue watching dynamically depending on the selected active profile
            launch {
                _activeProfile.collectLatest { profile ->
                    if (profile != null) {
                        if (!_isOffline.value) {
                            repository.syncFirestoreWatchlistAndHistoryToLocal(profile.id)
                        }

                        // Collect watchlist for active profile
                        launch {
                            repository.getLocalWatchlistFlow(profile.id).collect { entities ->
                                _watchlist.value = entities.map {
                                    WatchlistItem(it.id, it.title, it.poster, it.backdrop, it.type)
                                }
                            }
                        }

                        // Collect continue watching for active profile
                        launch {
                            repository.getLocalContinueWatchingFlow(profile.id).collect { entities ->
                                _continueWatching.value = entities.map {
                                    ContinueWatching(it.contentId, it.title, it.poster, it.type, it.progress)
                                }
                            }
                        }
                    } else {
                        _watchlist.value = emptyList()
                        _continueWatching.value = emptyList()
                    }
                }
            }

            // Sync user profiles reactively based on currently authenticated Firebase/Guest account
            if (!_isOffline.value) launch {
                _currentUserProfile.collectLatest { user ->
                    if (user != null) {
                        if (_isOffline.value) {
                            val localProfiles = getSavedProfilesFromPrefs(user.uid)
                            _profiles.value = localProfiles
                            if (_activeProfile.value == null) _activeProfile.value = localProfiles.firstOrNull()
                            return@collectLatest
                        }
                        val uid = user.uid
                        launch {
                            repository.getProfilesFromFirestore(uid).collect { firestoreList ->
                                val localProfiles = getSavedProfilesFromPrefs(uid)
                                val merged = (firestoreList + localProfiles).distinctBy { it.id }
                                if (merged.isEmpty()) {
                                    val defaultProfile = MovieProfile(
                                        id = "prof_default_${System.currentTimeMillis()}",
                                        name = user.displayName.ifEmpty { "Main Viewer" },
                                        avatarUrl = "https://i.ibb.co/yBNK21P/avatar1.jpg",
                                        isKids = false,
                                        preferredGenre = "All"
                                    )
                                    saveNewProfile(defaultProfile)
                                } else {
                                    _profiles.value = merged
                                    if (_activeProfile.value == null) {
                                        val lastActiveId = sharedPrefs.getString("last_active_profile_$uid", "")
                                        val lastProf = merged.find { it.id == lastActiveId } ?: merged.firstOrNull()
                                        _activeProfile.value = lastProf
                                    }
                                }
                            }
                        }
                    } else {
                        _profiles.value = emptyList()
                        _activeProfile.value = null
                    }
                }
            }

            // Content sync starts after anonymous Auth succeeds. Starting the
            // listeners before that point causes a permission-denied terminal flow.
        }
    }

    // ------------------ CONTENT SYNC (RESTARTABLE) ------------------

    /**
     * (Re)attaches the realtime listeners for movies, series and notifications.
     * Any previous listeners are cancelled first, and each stream auto-retries so a
     * permission-denied read while the user was still signed out cannot leave the
     * home screen permanently empty.
     */
    fun startContentSync() {
        if (_isOffline.value) return
        // Movies and webseries reads are gated by auth != null in RTDB rules. If
        // anonymous sign-in hasn't completed (or was revoked), sign in first so
        // startContentSync doesn't silently no-op and leave the Home shell on a
        // spinner forever.
        if (FirebaseAuth.getInstance().currentUser == null) {
            // Anonymous identity is recovered from the saved account on the next
            // attempt; signing in here is safe and idempotent.
            viewModelScope.launch { signInAnonymouslyAndSync() }
            return
        }
        contentSyncJob?.cancel()
        _contentState.value = ContentState.Loading
        contentSyncJob = viewModelScope.launch {
            _isLoading.value = _movies.value.isEmpty()
            var moviesInitialSnapshotReceived = false
            var seriesInitialSnapshotReceived = false

            fun markInitialSnapshotReceived(isMovies: Boolean) {
                if (isMovies) moviesInitialSnapshotReceived = true
                else seriesInitialSnapshotReceived = true
                if (moviesInitialSnapshotReceived && seriesInitialSnapshotReceived) {
                    _contentState.value = ContentState.Ready
                }
            }

            launch {
                kotlinx.coroutines.delay(4000)
                _isLoading.value = false
            }

            launch {
                repository.getMoviesFromFirebase()
                    .retryWhen { e, attempt ->
                        android.util.Log.e("MovieViewModel", "Movies stream failed (attempt $attempt)", e)
                        if (attempt < 5) {
                            kotlinx.coroutines.delay(1500 * (attempt + 1))
                            true
                        } else false
                    }
                    .catch { e ->
                        android.util.Log.e("MovieViewModel", "Movies Firebase fetch failed", e)
                        _isLoading.value = false
                        // Only publish an error when we have no cached data to show.
                        if (_movies.value.isEmpty() && _webSeries.value.isEmpty()) {
                            _contentState.value = ContentState.Error(userFacingMessage(e))
                        }
                    }
                    .collect { list ->
                        _movies.value = list
                        updateCategories(list)
                        repository.saveMoviesToLocal(list)
                        _isLoading.value = false
                        markInitialSnapshotReceived(isMovies = true)
                    }
            }

            launch {
                repository.getWebSeriesFromFirebase()
                    .retryWhen { e, attempt ->
                        android.util.Log.e("MovieViewModel", "Series stream failed (attempt $attempt)", e)
                        if (attempt < 5) {
                            kotlinx.coroutines.delay(1500 * (attempt + 1))
                            true
                        } else false
                    }
                    .catch { e ->
                        android.util.Log.e("MovieViewModel", "WebSeries Firebase fetch failed", e)
                        if (_movies.value.isEmpty() && _webSeries.value.isEmpty()) {
                            _contentState.value = ContentState.Error(userFacingMessage(e))
                        }
                    }
                    .collect { list ->
                        _webSeries.value = list
                        repository.saveSeriesToLocal(list)
                        markInitialSnapshotReceived(isMovies = false)
                    }
            }

            launch {
                repository.getNotifications()
                    .retryWhen { e, attempt ->
                        android.util.Log.e("MovieViewModel", "Notifications stream failed (attempt $attempt)", e)
                        if (attempt < 5) {
                            kotlinx.coroutines.delay(1500 * (attempt + 1))
                            true
                        } else false
                    }
                    .catch { e ->
                        android.util.Log.e("MovieViewModel", "Notifications Firebase fetch failed", e)
                    }
                    .collect { list ->
                        broadcastNotifications.value = list.filter { it.target != "specific" }
                        publishNotifications()
                    }
            }

            launch {
                repository.getSystemMaintenance()
                    .catch { e ->
                        android.util.Log.e("MovieViewModel", "Maintenance re-sync failed", e)
                    }
                    .collect { (enabled, message) ->
                        _isMaintenance.value = enabled
                        _maintenanceMessage.value = message
                    }
            }
        }
    }


    private fun observeUserSession() {
        try {
            val isGuestPersisted = sharedPrefs.getBoolean("is_guest_mode", true)

            // Always use local guest profile - no Firebase Auth required
            _isGuestUser.value = true
            val displayName = sharedPrefs.getString("user_name_guest_user", "Guest User") ?: "Guest"
            val photoUrl = sharedPrefs.getString("user_photo_guest_user", "https://i.ibb.co/yBNK21P/avatar1.jpg") ?: "https://i.ibb.co/yBNK21P/avatar1.jpg"
            val gender = sharedPrefs.getString("user_gender_guest_user", "male") ?: "male"

            _currentUserProfile.value = UserProfile(
                uid = "guest_user",
                displayName = displayName,
                email = "guest@manacinema.com",
                photoURL = photoUrl,
                gender = gender,
                createdAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            android.util.Log.e("MovieViewModel", "Error in observeUserSession - falling back to Guest default", e)
            _isGuestUser.value = true
            _currentUserProfile.value = UserProfile(
                uid = "guest_user",
                displayName = "Guest User",
                email = "guest@manacinema.com",
                photoURL = "https://i.ibb.co/yBNK21P/avatar1.jpg",
                gender = "male",
                createdAt = System.currentTimeMillis()
            )
        }

        // For guest user, no ban/subscription observation needed
        _isBanned.value = false
        _subscription.value = Subscription()
    }

    private suspend fun signInAnonymouslyAndSync() {
        try {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser ?: auth.signInAnonymously().await().user ?: return
            val localId = sharedPrefs.getString("mana_short_id_${user.uid}", null)
            val shortId = repository.ensureManaId(user.uid, localId)
            if (shortId != null) sharedPrefs.edit().putString("mana_short_id_${user.uid}", shortId).apply()
            val oldName = sharedPrefs.getString("user_name_guest_user", "Guest User") ?: "Guest User"
            sharedPrefs.edit()
                .putInt("profile_count_${user.uid}", sharedPrefs.getInt("profile_count_guest_user", 0))
                .putString("last_active_profile_${user.uid}", sharedPrefs.getString("last_active_profile_guest_user", ""))
                .putString("user_name_${user.uid}", oldName)
                .putString("user_gender_${user.uid}", sharedPrefs.getString("user_gender_guest_user", "male"))
                .putString("user_photo_${user.uid}", sharedPrefs.getString("user_photo_guest_user", "https://i.ibb.co/yBNK21P/avatar1.jpg"))
                .apply()
            val profile = UserProfile(user.uid, oldName, "", sharedPrefs.getString("user_photo_${user.uid}", "") ?: "", sharedPrefs.getString("user_gender_${user.uid}", "male") ?: "male", System.currentTimeMillis())
            _isGuestUser.value = false
            _currentUserProfile.value = profile
            OneSignalManager.login(user.uid)
            AppFirebaseMessagingService.flushPendingToken(getApplication(), user.uid)
            repository.migrateGuestRoomDataToUser(user.uid)
            observeUserSpecificData(user.uid)

            // The catalog listeners require an authenticated Firebase user. They
            // may have started during ViewModel initialization before anonymous
            // sign-in completed and then stopped after permission-denied errors.
            // Reattach them now that the auth token is available.
            startContentSync()
        } catch (e: Exception) {
            android.util.Log.w("MovieViewModel", "Anonymous identity sync failed", e)
            // Bound the retry so a permanently-failing sign-in (e.g. revoked
            // device accounts, no network) does not loop forever in the
            // background. Surface a real error to the UI on the final failure.
            val attempt = (sharedPrefs.getInt("firebase_auth_retry_count", 0) + 1)
            sharedPrefs.edit().putInt("firebase_auth_retry_count", attempt).apply()
            if (attempt < 4 && !_isOffline.value) {
                kotlinx.coroutines.delay(5000L * attempt) // 5s, 10s, 15s backoff
                if (!_isOffline.value) signInAnonymouslyAndSync()
            } else {
                sharedPrefs.edit().putInt("firebase_auth_retry_count", 0).apply()
                _contentState.value = ContentState.Error(
                    firebaseConnectionFailureMessage(e)
                )
            }
        }
    }

    /**
     * Keep the connection screen actionable. Anonymous sign-in is required by
     * the catalog rules; a disabled provider or rejected API key cannot be fixed
     * by repeatedly retrying the same request.
     */
    private fun firebaseConnectionFailureMessage(error: Throwable): String {
        val authError = error as? com.google.firebase.auth.FirebaseAuthException
        return when (authError?.errorCode) {
            "ERROR_OPERATION_NOT_ALLOWED" ->
                "Firebase anonymous sign-in is disabled. Enable Authentication > Sign-in method > Anonymous for the Mana Cinema Firebase project, then retry."
            "ERROR_INVALID_API_KEY", "ERROR_APP_NOT_AUTHORIZED" ->
                "Firebase rejected this app's API key. Check FIREBASE_API_KEY and the key's Firebase Authentication API restrictions."
            "ERROR_NETWORK_REQUEST_FAILED" ->
                "Firebase sign-in could not reach the server. Check your internet connection, VPN/firewall settings, and retry."
            "ERROR_TOO_MANY_REQUESTS" ->
                "Firebase temporarily blocked sign-in attempts. Wait a little while, then retry."
            else -> when (error) {
                is com.google.firebase.FirebaseNetworkException ->
                    "Firebase sign-in could not reach the server. Check your internet connection, VPN/firewall settings, and retry."
                else ->
                    "Firebase sign-in failed (${authError?.errorCode ?: error.javaClass.simpleName}). Check the Firebase project/API key and ensure Anonymous sign-in is enabled."
            }
        }
    }

    private fun updateCategories(moviesList: List<Movie>) {
        viewModelScope.launch(Dispatchers.Default) {
            val cats = distinctCategories(moviesList)
            _categories.value = cats
        }
    }

    /**
     * Translate a Firebase/RTDB failure into a concise, user-facing message.
     * Logs only the exception class + message server-side; never logs tokens or
     * signed URLs (the URL lives in the repository's safe-host log already).
     */
    private fun userFacingMessage(error: Throwable): String {
        val code = error.message?.takeIf { it.isNotBlank() }
        return when {
            error is com.google.firebase.database.DatabaseException ->
                "Unable to load the catalog from the server. Check your connection and try again. " +
                    "(Database: ${code?.take(120)})"
            error is com.google.firebase.auth.FirebaseAuthException ->
                "Sign-in failed. Check your connection and try again. " +
                    "(Auth: ${code?.take(120)})"
            else ->
                "Unable to load content. Check your connection and try again. " +
                    "(Error: ${code?.take(120) ?: error::class.java.simpleName})"
        }
    }

    /** Movies in one category, matching any of its multi-category values. */
    fun moviesInCategory(category: String): List<Movie> = _movies.value.filter { movie ->
        movie.categoryList.any { it.equals(category, ignoreCase = true) } ||
            (movie.categoryList.isEmpty() && category == UNCATEGORIZED)
    }

    /** Series in one category, matching any of its multi-category values. */
    fun seriesInCategory(category: String): List<WebSeries> = _webSeries.value.filter { series ->
        series.categoryList.any { it.equals(category, ignoreCase = true) }
    }

    fun handleLogout() {
        // Clear any profile-specific data and reset to guest
        sharedPrefs.edit().putBoolean("is_guest_mode", true).apply()
        OneSignalManager.logout()
        observeUserSession()
        startContentSync()
    }

    // ------------------ PROFILE DATA MANAGERS ------------------

    fun getSavedProfilesFromPrefs(uid: String): List<MovieProfile> {
        val count = sharedPrefs.getInt("profile_count_$uid", 0)
        val list = mutableListOf<MovieProfile>()
        for (i in 0 until count) {
            val id = sharedPrefs.getString("profile_id_${uid}_$i", "") ?: continue
            if (id.isEmpty()) continue
            val name = sharedPrefs.getString("profile_name_${uid}_$id", "") ?: ""
            val avatar = sharedPrefs.getString("profile_avatar_${uid}_$id", "") ?: ""
            val isKids = sharedPrefs.getBoolean("profile_iskids_${uid}_$id", false)
            val genre = sharedPrefs.getString("profile_genre_${uid}_$id", "All") ?: "All"
            if (name.isNotEmpty()) {
                list.add(MovieProfile(id, name, avatar, isKids, genre))
            }
        }
        return list
    }

    fun saveNewProfile(profile: MovieProfile) {
        val user = _currentUserProfile.value
        val uid = user?.uid ?: "guest_user"

        val currentList = getSavedProfilesFromPrefs(uid).toMutableList()
        currentList.removeAll { it.id == profile.id }
        currentList.add(profile)

        val editor = sharedPrefs.edit()
        editor.putInt("profile_count_$uid", currentList.size)
        currentList.forEachIndexed { index, p ->
            editor.putString("profile_id_${uid}_$index", p.id)
            editor.putString("profile_name_${uid}_${p.id}", p.name)
            editor.putString("profile_avatar_${uid}_${p.id}", p.avatarUrl)
            editor.putBoolean("profile_iskids_${uid}_${p.id}", p.isKids)
            editor.putString("profile_genre_${uid}_${p.id}", p.preferredGenre)
        }
        editor.apply()

        // Save to Firestore only if we have a non-guest UID (kept for admin panel compatibility)
        if (uid != "guest_user") {
            repository.saveProfileToFirestore(uid, profile)
        }

        _profiles.value = currentList
        if (_activeProfile.value == null || _activeProfile.value?.id == profile.id) {
            selectProfile(profile)
        }
    }

    fun selectProfile(profile: MovieProfile) {
        val uid = _currentUserProfile.value?.uid ?: "guest_user"
        sharedPrefs.edit().putString("last_active_profile_$uid", profile.id).apply()
        _activeProfile.value = profile
    }

    // ------------------ P8: PARENTAL PIN ------------------
    // Kids profiles can be locked with a 4-digit PIN stored in EncryptedSharedPreferences.

    private val securePrefs: android.content.SharedPreferences by lazy {
        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(getApplication())
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                getApplication(),
                "mana_secure_prefs",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            android.util.Log.e("MovieViewModel", "Secure prefs init failed, falling back", e)
            getApplication<Application>().getSharedPreferences("mana_secure_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun pinKey(profileId: String): String {
        val uid = _currentUserProfile.value?.uid ?: "guest_user"
        return "parental_pin_${uid}_$profileId"
    }

    fun hasParentalPin(profileId: String): Boolean =
        !securePrefs.getString(pinKey(profileId), null).isNullOrEmpty()

    fun setParentalPin(profileId: String, pin: String) {
        securePrefs.edit().putString(pinKey(profileId), pin).apply()
    }

    fun clearParentalPin(profileId: String) {
        securePrefs.edit().remove(pinKey(profileId)).apply()
    }

    fun verifyParentalPin(profileId: String, pin: String): Boolean =
        securePrefs.getString(pinKey(profileId), null) == pin

    fun clearActiveProfile() {
        val uid = _currentUserProfile.value?.uid ?: "guest_user"
        sharedPrefs.edit().remove("last_active_profile_$uid").apply()
        _activeProfile.value = null
    }

    fun deleteProfile(profileId: String) {
        val uid = _currentUserProfile.value?.uid ?: "guest_user"
        val currentList = getSavedProfilesFromPrefs(uid).toMutableList()
        currentList.removeAll { it.id == profileId }

        val editor = sharedPrefs.edit()
        editor.putInt("profile_count_$uid", currentList.size)
        currentList.forEachIndexed { index, p ->
            editor.putString("profile_id_${uid}_$index", p.id)
            editor.putString("profile_name_${uid}_${p.id}", p.name)
            editor.putString("profile_avatar_${uid}_${p.id}", p.avatarUrl)
            editor.putBoolean("profile_iskids_${uid}_${p.id}", p.isKids)
            editor.putString("profile_genre_${uid}_${p.id}", p.preferredGenre)
        }
        editor.apply()

        // Delete from Firestore only if we have a non-guest UID
        if (uid != "guest_user") {
            repository.deleteProfileFromFirestore(uid, profileId)
        }

        _profiles.value = currentList
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = currentList.firstOrNull()
        }
    }

    // ------------------ DOWNLOAD RESUME ------------------

    fun resumeInterruptedDownloadsIfAny(context: Context) {
        viewModelScope.launch {
            if (repository.hasInterruptedDownloads()) {
                com.manacinema.app.downloads.DownloadService.resumeInterrupted(context)
            }
        }
    }

    // ------------------ SIGNED-URL REFRESH ------------------
    // Re-reads the current stream link from Firebase for a movie/episode whose
    // signed URL has expired (403/404/410). Returns null if no fresh link exists.

    suspend fun refreshMovieLink(movieId: String, isPremiumContent: Boolean, quality: String = ""): String? =
        if (_isOffline.value) null else repository.refreshMovieLink(movieId, isPremiumContent, quality)

    suspend fun refreshEpisodeLink(seriesId: String, season: Int, episode: Int, isPremiumContent: Boolean, quality: String = ""): String? =
        if (_isOffline.value) null else repository.refreshEpisodeLink(seriesId, season, episode, isPremiumContent, quality)

    suspend fun resolvePremiumSources(
        contentId: String,
        season: Int? = null,
        episode: Int? = null
    ): Result<List<VideoSource>> = runCatching {
        if (_isOffline.value) error("Connect to the internet to play premium content.")
        val sources = repository.playbackSources(contentId, season, episode)
        if (sources.isEmpty()) error("No playable sources are available for this title.")
        sources
    }.recoverCatching { error ->
        val code = (error as? com.google.firebase.functions.FirebaseFunctionsException)?.code
        val message = when (code) {
            com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED -> "Premium access or this device is not authorized."
            com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND -> "No playback source was found."
            com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE -> "Service unavailable. Check your connection and retry."
            else -> error.message ?: "Could not load playback sources."
        }
        throw IllegalStateException(message, error)
    }

    // ------------------ DOWNLOAD GATING (Phase 19 / 35) ------------------

    /**
     * Starts a movie download for one selected quality after checking entitlement.
     * Premium is verified here, not just by hiding the button — a premium title
     * cannot be pulled down by a free user through any quality or URL.
     *
     * Returns null on success, or a message explaining why it was refused.
     */
    fun downloadMovie(context: Context, movie: Movie, source: VideoSource, premiumResolved: Boolean = false): String? {
        if (_isOffline.value) return "You're offline. Connect to download movies."
        if (movie.isPremium && !_subscription.value.isActive) return "Premium subscription required."
        if (movie.isPremium && !premiumResolved) return "Premium source must be resolved before download."
        if (source.downloadUrl.isBlank()) return "Download unavailable for this quality."
        val quality = VideoSource.normalizeQuality(source.quality)
        com.manacinema.app.downloads.DownloadService.startAction(
            context = context,
            id = downloadRowId(movie.id, quality),
            title = movie.title,
            poster = movie.poster,
            url = source.downloadUrl,
            subtitleUrl = movie.subtitleUrl,
            quality = quality,
            contentId = movie.id,
            isPremiumContent = movie.isPremium
        )
        return null
    }

    suspend fun downloadPremiumMovie(context: Context, movie: Movie, quality: String): String? {
        if (!movie.isPremium) return "This title does not require premium source resolution."
        if (!_subscription.value.isActive) return "Premium subscription required."
        val resolved = resolvePremiumSources(movie.id).getOrElse { return it.message ?: "Could not load download sources." }
        val source = resolved.firstOrNull { it.quality.equals(quality, ignoreCase = true) }
            ?: return "That quality is no longer available."
        return downloadMovie(context, movie, source.copy(
            streamUrl = "",
            downloadUrl = source.downloadUrl
        ), premiumResolved = true)
    }

    /** Same gate for episodes, honouring the per-episode premium flag. */
    fun downloadEpisode(
        context: Context,
        series: WebSeries,
        episode: Episode,
        source: VideoSource,
        premiumResolved: Boolean = false
    ): String? {
        if (_isOffline.value) return "You're offline. Connect to download episodes."
        if (series.isEpisodeLocked(episode) && !_subscription.value.isActive) {
            return "Premium subscription required."
        }
        if (series.isEpisodeLocked(episode) && !premiumResolved) return "Premium source must be resolved before download."
        if (source.downloadUrl.isBlank()) return "Download unavailable for this quality."
        val quality = VideoSource.normalizeQuality(source.quality)
        val contentId = "${series.id}_s${episode.season}e${episode.episode}"
        com.manacinema.app.downloads.DownloadService.startEpisodeAction(
            context = context,
            id = downloadRowId(contentId, quality),
            title = "${series.title} S${episode.season}E${episode.episode}: ${episode.title}",
            poster = series.poster,
            url = source.downloadUrl,
            seriesId = series.id,
            season = episode.season,
            episode = episode.episode,
            episodeTitle = episode.title,
            subtitleUrl = episode.subtitleUrl,
            quality = quality,
            contentId = contentId,
            isPremiumContent = series.isEpisodeLocked(episode)
        )
        return null
    }

    suspend fun downloadPremiumEpisode(
        context: Context,
        series: WebSeries,
        episode: Episode,
        quality: String
    ): String? {
        if (!series.isEpisodeLocked(episode)) return "This episode does not require premium source resolution."
        if (!_subscription.value.isActive) return "Premium subscription required."
        val resolved = resolvePremiumSources(series.id, episode.season, episode.episode)
            .getOrElse { return it.message ?: "Could not load download sources." }
        val source = resolved.firstOrNull { it.quality.equals(quality, ignoreCase = true) }
            ?: return "That quality is no longer available."
        return downloadEpisode(context, series, episode, source, premiumResolved = true)
    }

    /**
     * Row id for a download. Quality is part of the key so 720p and 1080p of the
     * same title coexist, while re-requesting the same movie+quality is a no-op
     * (duplicate prevention lives in DownloadService, keyed on this id).
     */
    private fun downloadRowId(contentId: String, quality: String): String =
        if (quality.isBlank()) contentId else "${contentId}_$quality"

    // ------------------ BUSINESS ACTIONS ------------------

    fun saveWatchProgress(contentId: String, title: String, poster: String, type: String, progress: Float) {
        if (contentId.isBlank()) return
        val now = System.currentTimeMillis()
        val previous = lastProgressPersisted[contentId]
        val completed = progress >= 0.95f
        if (!completed && previous != null && now - previous.first < 1500L &&
            kotlin.math.abs(progress - previous.second) < 0.02f
        ) return
        lastProgressPersisted[contentId] = now to progress
        viewModelScope.launch {
            val profId = _activeProfile.value?.id ?: "prof_default"
            if (progress >= 0.95f) {
                repository.deleteProgress(profId, contentId)
                lastProgressPersisted.remove(contentId)
            } else {
                repository.saveProgress(profId, contentId, title, poster, type, progress)
            }
        }
    }

    fun removeFromContinueWatching(contentId: String) {
        if (contentId.isBlank()) return
        lastProgressPersisted.remove(contentId)
        viewModelScope.launch {
            val profileId = _activeProfile.value?.id ?: "prof_default"
            repository.deleteProgress(profileId, contentId)
        }
    }

    fun toggleWatchlist(id: String, title: String, poster: String, backdrop: String, type: String) {
        viewModelScope.launch {
            val profId = _activeProfile.value?.id ?: "prof_default"
            repository.toggleWatchlist(profId, id, title, poster, backdrop, type)
        }
    }

    fun submitMovieRequest(name: String, year: String, onComplete: (Boolean) -> Unit) {
        if (_isOffline.value) {
            onComplete(false)
            return
        }
        val userMail = _currentUserProfile.value?.email ?: "guest_user"
        repository.requestMovie(name, year, userMail, onComplete)
    }

    fun updateProfile(name: String, gender: String, onComplete: (Boolean) -> Unit) {
        val uid = _currentUserProfile.value?.uid ?: "guest_user"

        sharedPrefs.edit()
            .putString("user_name_$uid", name)
            .putString("user_gender_$uid", gender)
            .apply()

        _currentUserProfile.value = _currentUserProfile.value?.copy(
            displayName = name,
            gender = gender
        )
        onComplete(true)
    }

    fun updateAvatar(avatarUrl: String, onComplete: (Boolean) -> Unit) {
        val uid = _currentUserProfile.value?.uid ?: "guest_user"

        sharedPrefs.edit()
            .putString("user_photo_$uid", avatarUrl)
            .apply()

        _currentUserProfile.value = _currentUserProfile.value?.copy(
            photoURL = avatarUrl
        )
        onComplete(true)
    }

    fun saveThemeSettings(themeName: String, isDark: Boolean) {
        sharedPrefs.edit()
            .putString("active_theme", themeName)
            .putBoolean("is_theme_dark", isDark)
            .apply()
    }

    // ------------------ LOCALIZATION LANGUAGE ------------------
    private val _currentLanguage = MutableStateFlow(
        Language.fromCode(sharedPrefs.getString("app_language", "en") ?: "en")
    )
    val currentLanguage: StateFlow<Language> = _currentLanguage.asStateFlow()

    fun setLanguage(language: Language) {
        sharedPrefs.edit().putString("app_language", language.code).apply()
        _currentLanguage.value = language
        com.manacinema.app.localization.LocalizationManager.setLanguage(language)
    }

    // ------------------ P3: BAN / SUBSCRIPTION / PLANS / ADS / AVATARS / VERSION ------------------

    val isPremiumUser: StateFlow<Boolean> = _subscription
        .map { it.isActive }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    val plans: StateFlow<List<Plan>> = _plans.asStateFlow()

    private val _plansUiState = MutableStateFlow<PlansUiState>(PlansUiState.Loading)
    val plansUiState: StateFlow<PlansUiState> = _plansUiState.asStateFlow()
    private var plansJob: kotlinx.coroutines.Job? = null

    private val _paymentInfo = MutableStateFlow(PaymentInfo())
    val paymentInfo: StateFlow<PaymentInfo> = _paymentInfo.asStateFlow()

    private val _activeAds = MutableStateFlow<List<AdItem>>(emptyList())
    val activeAds: StateFlow<List<AdItem>> = _activeAds.asStateFlow()

    private val _maleAvatars = MutableStateFlow<List<String>>(emptyList())
    val maleAvatars: StateFlow<List<String>> = _maleAvatars.asStateFlow()

    private val _femaleAvatars = MutableStateFlow<List<String>>(emptyList())
    val femaleAvatars: StateFlow<List<String>> = _femaleAvatars.asStateFlow()

    private val defaultAvatars = listOf(
        "https://i.ibb.co/yBNK21P/avatar1.jpg",
        "https://i.ibb.co/VtxZ2Zq/avatar2.jpg",
        "https://i.ibb.co/zX7Vp6c/avatar3.jpg",
        "https://i.ibb.co/0y7R8j4/avatar4.jpg",
        "https://i.ibb.co/W21d2gX/avatar5.jpg"
    )

    // Combined admin-managed avatar list, falling back to defaults when admin hasn't set any.
    val avatarUrls: StateFlow<List<String>> = combine(_maleAvatars, _femaleAvatars) { male, female ->
        val combined = (male + female).distinct()
        combined.ifEmpty { defaultAvatars }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), defaultAvatars)

    private val _appVersionInfo = MutableStateFlow(AppVersionInfo())
    val appVersionInfo: StateFlow<AppVersionInfo> = _appVersionInfo.asStateFlow()

    private fun observeUserSpecificData(uid: String) {
        banJob?.cancel()
        subJob?.cancel()
        userNotificationsJob?.cancel()
        premiumReqJob?.cancel()
        deviceCheckJob?.cancel()
        if (uid.isEmpty() || uid == "guest_user") {
            _isBanned.value = false
            _subscription.value = Subscription()
            _myPremiumRequest.value = null
            _deviceStatus.value = DeviceStatus.UNKNOWN
            _manaId.value = ""
            targetedNotifications.value = emptyList()
            publishNotifications()
            return
        }
        // Make this user visible/manageable in the admin panel's user list.
        val profile = _currentUserProfile.value
        viewModelScope.launch {
            repository.ensureUserRecord(
                uid,
                profile?.displayName ?: "Viewer",
                profile?.email ?: ""
            )
        }
        _manaId.value = sharedPrefs.getString("mana_short_id_$uid", "") ?: ""
        viewModelScope.launch {
            val ensured = repository.ensureManaId(uid, _manaId.value.ifBlank { null })
            if (!ensured.isNullOrBlank()) {
                _manaId.value = ensured
                sharedPrefs.edit().putString("mana_short_id_$uid", ensured).apply()
            }
        }
        userNotificationsJob = viewModelScope.launch {
            repository.getUserNotifications(uid)
                .catch { android.util.Log.e("MovieViewModel", "Targeted notifications failed", it) }
                .collect { targeted ->
                    targetedNotifications.value = targeted
                    publishNotifications()
                }
        }
        banJob = viewModelScope.launch {
            repository.observeUserBanned(uid)
                .catch { android.util.Log.e("MovieViewModel", "Ban observe failed", it) }
                .collect { _isBanned.value = it }
        }
        subJob = viewModelScope.launch {
            repository.observeSubscription(uid)
                .catch { android.util.Log.e("MovieViewModel", "Subscription observe failed", it) }
                .collect { _subscription.value = it }
        }
        premiumReqJob = viewModelScope.launch {
            repository.observeMyPremiumRequest(uid)
                .catch { android.util.Log.e("MovieViewModel", "Premium request observe failed", it) }
                .collect { _myPremiumRequest.value = it }
        }
        startDeviceEnforcement(uid)
    }

    private fun loadGlobalAdminData() {
        if (_isOffline.value) return
        globalAdminDataJob?.cancel()
        globalAdminDataJob = viewModelScope.launch {
            coroutineScope {
                launch {
            repository.getPaymentInfo().catch { emit(PaymentInfo()) }.collect { _paymentInfo.value = it }
                }
                launch {
            repository.getActiveAds().catch { emit(emptyList()) }.collect { _activeAds.value = it }
                }
                launch {
            repository.getAvatars().catch { emit(Pair(emptyList(), emptyList())) }.collect { (m, f) ->
                _maleAvatars.value = m
                _femaleAvatars.value = f
            }
                }
                launch {
            repository.getAppVersionInfo().catch { emit(AppVersionInfo()) }.collect { _appVersionInfo.value = it }
                }
                launch {
            repository.getPlans().catch { emit(emptyList()) }.collect { _plans.value = it }
                }
            }
        }
    }

    fun startPlansLoading() {
        if (_isOffline.value) {
            _plansUiState.value = PlansUiState.Error("Premium plans are unavailable offline.")
            return
        }
        plansJob?.cancel()
        _plansUiState.value = PlansUiState.Loading
        plansJob = viewModelScope.launch {
            repository.getPlans()
                .catch { error ->
                    android.util.Log.e("MovieViewModel", "Plans load failed", error)
                    _plansUiState.value = PlansUiState.Error(
                        error.localizedMessage ?: "Unable to load VIP plans."
                    )
                }
                .collect { loadedPlans ->
                    _plans.value = loadedPlans
                    _plansUiState.value = PlansUiState.Success(loadedPlans)
                }
        }
    }

    fun stopPlansLoading() {
        plansJob?.cancel()
        plansJob = null
    }

    /** Returns true if the given content can be played for the current user. */
    fun canPlay(isPremiumContent: Boolean): Boolean = !isPremiumContent || _subscription.value.isActive

    /**
     * Episode gate (Phase 4). An episode is locked when the series is premium OR
     * the episode itself is flagged premium — series-level status alone is not enough.
     */
    fun canPlayEpisode(series: WebSeries, episode: Episode): Boolean =
        canPlay(series.isEpisodeLocked(episode))

    /**
     * Offline entitlement gate (Phase 19). A file already on disk is NOT proof of
     * entitlement: a user could download a premium title while subscribed and keep
     * playing it after expiry, or lose premium mid-subscription. `isPremiumContent`
     * is recorded at download time exactly so this can be re-checked.
     *
     * Rows created before that column existed default to false and therefore stay
     * playable — existing downloads are never retro-locked.
     */
    fun canPlayDownload(download: com.manacinema.app.database.DownloadEntity): Boolean =
        canPlay(download.isPremiumContent)

    // ------------------ DEVICE LIMIT (Phase 5) ------------------

    private val _deviceStatus = MutableStateFlow(DeviceStatus.UNKNOWN)
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    private var deviceCheckJob: kotlinx.coroutines.Job? = null

    /** True only when Firebase positively reported the limit is exceeded. */
    val isDeviceLimitExceeded: StateFlow<Boolean> = _deviceStatus
        .map { it == DeviceStatus.LIMIT_REACHED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Registers this installation whenever an active subscription is observed.
     * Re-runs when the subscription changes (renewal, plan change, expiry) so a
     * raised deviceLimit takes effect without a reinstall.
     */
    private fun startDeviceEnforcement(uid: String) {
        deviceCheckJob?.cancel()
        deviceCheckJob = viewModelScope.launch {
            _subscription
                .map { it.isActive to it.deviceLimit }
                .distinctUntilChanged()
                .collect { (active, _) ->
                    if (!active) {
                        _deviceStatus.value = DeviceStatus.UNKNOWN
                        return@collect
                    }
                    val installId = repository.installationId(getApplication())
                    _deviceStatus.value = repository.registerDevice(uid, installId, _subscription.value)
                }
        }
    }

    // ------------------ PREMIUM REQUEST STATE (Phase 7) ------------------

    private val _myPremiumRequest = MutableStateFlow<PremiumRequest?>(null)
    val myPremiumRequest: StateFlow<PremiumRequest?> = _myPremiumRequest.asStateFlow()
    private var premiumReqJob: kotlinx.coroutines.Job? = null

    /** True while a request awaits admin action — blocks duplicate submissions. */
    val hasPendingPremiumRequest: StateFlow<Boolean> = _myPremiumRequest
        .map { it?.status == "pending" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun submitPremiumRequest(plan: Plan, utr: String, onComplete: (Boolean) -> Unit) {
        if (_isOffline.value) {
            onComplete(false)
            return
        }
        // A pending request must not be duplicated; premium is granted by the admin
        // approving a request, never by a request merely existing.
        if (_myPremiumRequest.value?.status == "pending") {
            onComplete(false)
            return
        }
        val user = _currentUserProfile.value
        val uid = user?.uid ?: "guest_user"
        if (uid.isEmpty() || uid == "guest_user") {
            onComplete(false)
            return
        }
        val request = PremiumRequest(
            userId = uid,
            shortId = _manaId.value,
            userEmail = user?.email ?: "",
            planName = plan.name,
            planDays = plan.days,
            planPrice = plan.price,
            deviceLimit = plan.devices,
            utr = utr
        )
        repository.submitPremiumRequest(request, onComplete)
    }

    // ------------------ P7: RATINGS ------------------

    fun getUserRating(contentId: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val profId = _activeProfile.value?.id ?: "prof_default"
            onResult(repository.getRating(profId, contentId))
        }
    }

    fun setUserRating(contentId: String, score: Int) {
        viewModelScope.launch {
            val profId = _activeProfile.value?.id ?: "prof_default"
            repository.setRating(profId, contentId, score)
        }
    }

    fun getAggregateRating(contentId: String, onResult: (Float, Int) -> Unit) {
        viewModelScope.launch {
            val (avg, count) = repository.getAggregateRating(contentId)
            onResult(avg, count)
        }
    }

    // ------------------ COMMENTS ------------------

    /** Live comments for a title/episode, newest first. */
    fun commentsFlow(contentId: String): Flow<List<Comment>> = repository.getComments(contentId)

    /**
     * Posts a comment as the signed-in user (or active profile, or a guest fallback).
     * Trims to [MAX_COMMENT_WORDS] words rather than rejecting, so a slightly long
     * reaction still gets posted instead of vanishing on submit.
     */
    fun postComment(contentId: String, text: String, onResult: (Boolean) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || contentId.isBlank()) {
            onResult(false)
            return
        }
        val words = trimmed.split(Regex("\\s+"))
        val limited = if (words.size > MAX_COMMENT_WORDS) words.take(MAX_COMMENT_WORDS).joinToString(" ") else trimmed
        val profile = _activeProfile.value
        val user = _currentUserProfile.value
        val userName = profile?.name?.takeIf { it.isNotBlank() }
            ?: user?.displayName?.takeIf { it.isNotBlank() }
            ?: "Viewer"
        val avatarUrl = profile?.avatarUrl?.takeIf { it.isNotBlank() } ?: user?.photoURL.orEmpty()
        viewModelScope.launch {
            val success = repository.addComment(contentId, userName, avatarUrl, limited)
            onResult(success)
        }
    }

    // ------------------ P7: SEARCH (recent + trending) ------------------

    private val _recentSearches = MutableStateFlow<List<String>>(
        sharedPrefs.getString("recent_searches", "")
            ?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
    )
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    // Trending = titles surfaced most by the admin catalog order (first N movies + series).
    val trendingTitles: StateFlow<List<String>> = combine(movies, webSeries) { m, s ->
        (m.map { it.title } + s.map { it.title }).filter { it.isNotBlank() }.distinct().take(8)
    }.flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        val updated = (listOf(q) + _recentSearches.value.filter { !it.equals(q, ignoreCase = true) }).take(10)
        _recentSearches.value = updated
        sharedPrefs.edit().putString("recent_searches", updated.joinToString("|")).apply()
    }

    fun clearRecentSearches() {
        _recentSearches.value = emptyList()
        sharedPrefs.edit().remove("recent_searches").apply()
    }

    override fun onCleared() {
        super.onCleared()
        repository.cleanup()
    }
}
