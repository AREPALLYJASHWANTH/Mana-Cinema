package com.manacinema.app.ui

import android.widget.Toast
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.manacinema.app.localization.Language
import com.manacinema.app.localization.LocalAppStrings
import com.manacinema.app.models.*
import com.manacinema.app.player.SeriesPlayerContext
import com.manacinema.app.player.VideoPlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.EaseInOutQuart
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

enum class Screen {
    HOME, WATCHLIST, PROFILE,
    SEARCH, DETAIL, SERIES_DETAIL, NOTIFICATIONS,
    MOVIE_REQUEST, ACCOUNT_SETTINGS, THEME_SETTINGS,
    DOWNLOADS, CONTINUE_WATCHING, PLAY_MOVIE, PLAY_SERIES,
    PLANS, PAYMENT, CATEGORY
}

data class NavigationState(
    val screen: Screen,
    val movie: Movie? = null,
    val webSeries: WebSeries? = null,
    val selectedEpisode: Episode? = null,
    val selectedQuality: String = "",
    val selectedPlan: Plan? = null,
    val trailerUrl: String? = null,
    val categoryTitle: String? = null,
    val categoryMovies: List<Movie> = emptyList()
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MiniPlayerView(
    videoUrl: String,
    startPositionMs: Long,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    
    val exoPlayer = remember(videoUrl) {
        val dshf = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0")
            .setAllowCrossProtocolRedirects(true)
        val dsf = androidx.media3.datasource.DefaultDataSource.Factory(context, dshf)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dsf))
            .build().apply {
                playWhenReady = true
                val uri = if (videoUrl.startsWith("/")) android.net.Uri.fromFile(java.io.File(videoUrl)) else android.net.Uri.parse(videoUrl)
                val mediaItem = MediaItem.Builder().setUri(uri).build()
                setMediaItem(mediaItem)
                prepare()
                if (startPositionMs > 0) {
                    seekTo(startPositionMs)
                }
            }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.clearVideoSurface()
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                    )
                )
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(12.dp))
        }

        IconButton(
            onClick = {
                if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                    isPlaying = false
                } else {
                    exoPlayer.play()
                    isPlaying = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(28.dp)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Play/Pause",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContainer(
    viewModel: MovieViewModel,
    onLogout: () -> Unit
) {
    val strings = LocalAppStrings.current
    val navigationStack = remember { mutableStateListOf(NavigationState(Screen.HOME)) }
    val currentNav = navigationStack.lastOrNull() ?: NavigationState(Screen.HOME)

    // Netflix / Prime Video style back behavior:
    //   - If we have pushed screens on top, pop the last one.
    //   - Otherwise, if we are not on HOME, jump back to HOME instead of exiting.
    //   - Only when the user presses back again ON the HOME root do we leave
    //     the app, and even then we send it to the background (moveTaskToBack)
    //     so the process is not killed — the user never gets "thrown out".
    val ctxForBack = LocalContext.current
    var lastHomeBackAt by remember { androidx.compose.runtime.mutableStateOf(0L) }
    androidx.activity.compose.BackHandler(enabled = true) {
        when {
            navigationStack.size > 1 -> {
                navigationStack.removeAt(navigationStack.size - 1)
            }
            currentNav.screen != Screen.HOME -> {
                navigationStack.clear()
                navigationStack.add(NavigationState(Screen.HOME))
            }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastHomeBackAt < 2000L) {
                    (ctxForBack as? android.app.Activity)?.moveTaskToBack(true)
                } else {
                    lastHomeBackAt = now
                    android.widget.Toast.makeText(
                        ctxForBack,
                        "Press back again to minimise",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val isMaintenance by viewModel.isMaintenance.collectAsState()
    val maintenanceMessage by viewModel.maintenanceMessage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()
    val contentState by viewModel.contentState.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val activeAds by viewModel.activeAds.collectAsState()
    val isPremiumUser by viewModel.isPremiumUser.collectAsState()
    val localDownloads by viewModel.downloads.collectAsState()
    var pendingAdAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var currentAd by remember { mutableStateOf<AdItem?>(null) }

    // Premium users skip ads; otherwise show the first active interstitial before playback.
    val playWithAd: (() -> Unit) -> Unit = { onAdCompleted ->
        val ad = activeAds.firstOrNull { it.placement == "all" || it.placement == "interstitial" }
        if (isPremiumUser || ad == null) {
            onAdCompleted()
        } else {
            currentAd = ad
            pendingAdAction = onAdCompleted
        }
    }

    fun playOfflineMovie(movie: Movie, quality: String = ""): Boolean {
        val download = localDownloads.firstOrNull {
            it.contentId.ifBlank { it.id } == movie.id &&
                it.status == "COMPLETED" && !it.localUri.isNullOrBlank() &&
                (quality.isBlank() || it.quality.isBlank() || it.quality == quality)
        } ?: return false
        navigationStack.add(
            NavigationState(
                Screen.PLAY_MOVIE,
                movie = movie.copy(link = download.localUri.orEmpty(), subtitleUrl = download.subtitleLocalUri)
            )
        )
        return true
    }

    currentAd?.let { ad ->
        InterstitialAdOverlay(
            ad = ad,
            onFinished = {
                val action = pendingAdAction
                currentAd = null
                pendingAdAction = null
                action?.invoke()
            }
        )
    }

    if (isMaintenance) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "System Maintenance",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = maintenanceMessage,
                    color = Color.Gray,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    lineHeight = 22.sp
                )
            }
        }
        return
    }

    // Cached Room content should remain visible while Firebase refreshes in the
    // background. Only block the shell when there is no local content at all.
    val moviesAvailable by viewModel.movies.collectAsState()
    val seriesAvailable by viewModel.webSeries.collectAsState()
    val noContent = moviesAvailable.isEmpty() && seriesAvailable.isEmpty()
    val shellLoading = !isOffline && isLoading && noContent
    val shellError = !isOffline && noContent &&
        contentState is MovieViewModel.ContentState.Error
    val shellEmpty = !isOffline && !isLoading && noContent &&
        contentState is MovieViewModel.ContentState.Ready

    if (shellLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("MANA ", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black)
                    Text("CINEMA", color = MaterialTheme.colorScheme.primary, fontSize = 38.sp, fontWeight = FontWeight.Black)
                }
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        return
    }

    if (shellError) {
        val message = (contentState as? MovieViewModel.ContentState.Error)?.message ?: "Unable to load content."
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text(message, color = Color.LightGray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.clearContentError(); viewModel.refreshData() }) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Retry")
                }
            }
        }
        return
    }

    if (shellEmpty) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("No content available", color = Color.LightGray, fontSize = 16.sp)
        }
        return
    }

    // P3 #8: enforce account ban
    val isBanned by viewModel.isBanned.collectAsState()
    if (isBanned) {
        BannedScreen(onExit = {
            viewModel.handleLogout()
            // Exit the app
            val activity = context as? android.app.Activity
            activity?.finishAffinity()
        })
        return
    }

    // P3 #13: force update gate
    val versionInfo by viewModel.appVersionInfo.collectAsState()
    val mustUpdate = remember(versionInfo) {
        versionInfo.forceUpdate && versionInfo.versionCode > com.manacinema.app.BuildConfig.VERSION_CODE
    }
    if (mustUpdate) {
        ForceUpdateDialog(info = versionInfo)
        return
    }

    val activeProfile by viewModel.activeProfile.collectAsState()
    val floatingVideoState by viewModel.floatingVideo.collectAsState()
    var showOfflineBanner by remember { mutableStateOf(false) }
    var offlineNavigationHandled by remember { mutableStateOf(false) }
    var hasSeenOnlineState by remember { mutableStateOf(!isOffline) }

    fun navigateToDownloads() {
        val existingIndex = navigationStack.indexOfFirst { it.screen == Screen.DOWNLOADS }
        if (existingIndex >= 0) {
            while (navigationStack.size - 1 > existingIndex) {
                navigationStack.removeAt(navigationStack.size - 1)
            }
        } else {
            navigationStack.add(NavigationState(Screen.DOWNLOADS))
        }
    }

    // One lifecycle-scoped offline transition effect. The initial offline state
    // opens Downloads too, but the banner is reserved for ONLINE -> OFFLINE.
    LaunchedEffect(isOffline) {
        if (!isOffline) {
            hasSeenOnlineState = true
            offlineNavigationHandled = false
            showOfflineBanner = false
            return@LaunchedEffect
        }

        if (offlineNavigationHandled) return@LaunchedEffect
        offlineNavigationHandled = true
        val showTransitionMessage = hasSeenOnlineState
        showOfflineBanner = showTransitionMessage
        delay(2000)
        showOfflineBanner = false
        if (isOffline) navigateToDownloads()
    }

    if (activeProfile == null && !isOffline) {
        ProfileSelectionScreen(
            viewModel = viewModel,
            onResetData = { viewModel.handleLogout() }
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Hide bottom bar on player layouts and focus states
            val hideBottomBar = remember(currentNav.screen) {
                listOf(
                    Screen.PLAY_MOVIE, Screen.PLAY_SERIES,
                    Screen.SEARCH, Screen.DETAIL, Screen.SERIES_DETAIL
                ).contains(currentNav.screen)
            }

            if (!hideBottomBar) {
                BottomNavigationBar(
                    currentScreen = currentNav.screen,
                    offline = isOffline,
                    onNavigate = { screen ->
                        if (!isOffline || screen == Screen.DOWNLOADS) {
                            if (navigationStack.any { it.screen == screen }) {
                            while (navigationStack.last().screen != screen) {
                                navigationStack.removeAt(navigationStack.size - 1)
                            }
                            } else {
                                navigationStack.add(NavigationState(screen))
                            }
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = currentNav,
                // Episode changes update the current player source. They are not
                // navigation events and must not dispose the fullscreen player.
                contentKey = { it.screen },
                transitionSpec = {
                    fadeIn(animationSpec = tween(150, easing = EaseInOutQuart)) togetherWith
                    fadeOut(animationSpec = tween(120, easing = EaseInOutQuart))
                },
                label = "NavigationTransition"
            ) { state ->
                when (state.screen) {
                    Screen.HOME -> HomeScreen(viewModel, onNavigateToMovie = { movie ->
                        // Clear floating video if navigating to fullscreen
                        if (floatingVideoState?.contentId == movie.id) {
                            viewModel.setFloatingVideo(null)
                        }
                        navigationStack.add(NavigationState(Screen.DETAIL, movie = movie))
                    }, onNavigateToSeries = { series ->
                        navigationStack.add(NavigationState(Screen.SERIES_DETAIL, webSeries = series))
                    }, onSearchClick = {
                        navigationStack.add(NavigationState(Screen.SEARCH))
                    }, onNotificationsClick = {
                        navigationStack.add(NavigationState(Screen.NOTIFICATIONS))
                    }, onViewAll = { title, movies ->
                        navigationStack.add(
                            NavigationState(
                                Screen.CATEGORY,
                                categoryTitle = title,
                                categoryMovies = movies
                            )
                        )
                    })

                    Screen.PLANS -> PlansScreen(viewModel, onSelectPlan = { plan ->
                        navigationStack.add(NavigationState(Screen.PAYMENT, selectedPlan = plan))
                    }, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })

                    Screen.PAYMENT -> state.selectedPlan?.let { plan ->
                        PaymentScreen(viewModel, plan = plan, onDone = {
                            navigationStack.removeAt(navigationStack.size - 1)
                        }, onBack = {
                            navigationStack.removeAt(navigationStack.size - 1)
                        })
                    }

                    Screen.WATCHLIST -> WatchlistScreen(viewModel, onMovieClick = { movie ->
                        navigationStack.add(NavigationState(Screen.DETAIL, movie = movie))
                    }, onSeriesClick = { series ->
                        navigationStack.add(NavigationState(Screen.SERIES_DETAIL, webSeries = series))
                    })

                    Screen.PROFILE -> ProfileScreen(viewModel, onMenuClick = { menu ->
                        val target = when (menu) {
                            "request" -> Screen.MOVIE_REQUEST
                            "settings" -> Screen.ACCOUNT_SETTINGS
                            "themes" -> Screen.THEME_SETTINGS
                            "downloads" -> Screen.DOWNLOADS
                            "continue" -> Screen.CONTINUE_WATCHING
                            "premium" -> Screen.PLANS
                            else -> null
                        }
                        if (target != null) {
                            navigationStack.add(NavigationState(target))
                        }
                    }, onResetData = { viewModel.handleLogout() })

                    Screen.SEARCH -> SearchScreen(viewModel, onMovieClick = { movie ->
                        navigationStack.add(NavigationState(Screen.DETAIL, movie = movie))
                    }, onSeriesClick = { series ->
                        navigationStack.add(NavigationState(Screen.SERIES_DETAIL, webSeries = series))
                    }, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })

                    Screen.DETAIL -> state.movie?.let { movie ->
                        DetailScreen(viewModel, movie = movie, onPlayClick = { quality ->
                            if (isOffline) {
                                if (!playOfflineMovie(movie, quality)) {
                                    android.widget.Toast.makeText(context, "You're offline. Only downloaded movies can be played.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } else if (movie.isPremium) {
                                coroutineScope.launch {
                                    viewModel.resolvePremiumSources(movie.id)
                                        .onSuccess { sources ->
                                            val chosen = sources.firstOrNull { it.quality.equals(quality, ignoreCase = true) } ?: sources.first()
                                            playWithAd {
                                                navigationStack.add(NavigationState(
                                                    Screen.PLAY_MOVIE,
                                                    movie = movie.copy(videoSources = sources),
                                                    selectedQuality = chosen.quality
                                                ))
                                            }
                                        }
                                        .onFailure { android.widget.Toast.makeText(context, it.message ?: "Could not load playback sources.", android.widget.Toast.LENGTH_LONG).show() }
                                }
                            } else {
                                if (floatingVideoState?.contentId == movie.id) {
                                    viewModel.setFloatingVideo(null)
                                }
                                com.manacinema.app.firebase.Analytics.logPlay(context, movie.id, movie.title)
                                playWithAd {
                                    navigationStack.add(NavigationState(Screen.PLAY_MOVIE, movie = movie, selectedQuality = quality))
                                }
                            }
                        }, onSimilarMovieClick = { simMovie ->
                            navigationStack.add(NavigationState(Screen.DETAIL, movie = simMovie))
                        }, onUpgradeClick = {
                            navigationStack.add(NavigationState(Screen.PLANS))
                        }, onTrailerClick = { trailerUrl ->
                            if (isOffline) {
                                android.widget.Toast.makeText(context, "Trailers are unavailable offline.", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                val trailerMovie = movie.copy(id = "trailer_${movie.id}", link = trailerUrl)
                                navigationStack.add(NavigationState(Screen.PLAY_MOVIE, movie = trailerMovie))
                            }
                        }, onBack = {
                            navigationStack.removeAt(navigationStack.size - 1)
                        })
                    }

                    Screen.SERIES_DETAIL -> state.webSeries?.let { series ->
                        SeriesDetailScreen(viewModel, series = series, onEpisodePlay = { ep ->
                            if (isOffline) {
                                android.widget.Toast.makeText(context, "Online series are unavailable offline.", android.widget.Toast.LENGTH_SHORT).show()
                            } else if (series.isEpisodeLocked(ep)) {
                                coroutineScope.launch {
                                viewModel.resolvePremiumSources(series.id, ep.season, ep.episode)
                                        .onSuccess { sources ->
                                            val chosen = sources.first()
                                            playWithAd {
                                                navigationStack.add(NavigationState(Screen.PLAY_SERIES, webSeries = series.copy(episodes = series.episodes.map { current ->
                                                    if (current.season == ep.season && current.episode == ep.episode) current.copy(videoSources = sources) else current
                                                }), selectedEpisode = ep.copy(videoSources = sources), selectedQuality = chosen.quality))
                                            }
                                        }
                                        .onFailure { android.widget.Toast.makeText(context, it.message ?: "Could not load playback sources.", android.widget.Toast.LENGTH_LONG).show() }
                                }
                            } else {
                                val epId = "${series.id}_s${ep.season}e${ep.episode}"
                                if (floatingVideoState?.contentId == epId) {
                                    viewModel.setFloatingVideo(null)
                                }
                                playWithAd {
                                    navigationStack.add(NavigationState(Screen.PLAY_SERIES, webSeries = series, selectedEpisode = ep))
                                }
                            }
                        }, onUpgradeClick = {
                            navigationStack.add(NavigationState(Screen.PLANS))
                        }, onTrailerClick = { trailerUrl ->
                            if (isOffline) {
                                android.widget.Toast.makeText(context, "Trailers are unavailable offline.", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                val trailerMovie = Movie(id = "trailer_${series.id}", title = series.title, link = trailerUrl)
                                navigationStack.add(NavigationState(Screen.PLAY_MOVIE, movie = trailerMovie))
                            }
                        }, onSeriesClick = { otherSeries ->
                            navigationStack.add(NavigationState(Screen.SERIES_DETAIL, webSeries = otherSeries))
                        }, onBack = {
                            navigationStack.removeAt(navigationStack.size - 1)
                        })
                    }

                    Screen.PLAY_MOVIE -> state.movie?.let { movie ->
                        val continueWatchingList by viewModel.continueWatching.collectAsState()
                        val savedRecord = continueWatchingList.find { it.contentId == movie.id }
                        val initialProg = savedRecord?.progress ?: 0f

                        val selectedSource = movie.streamSources.firstOrNull { VideoSource.normalizeQuality(it.quality) == VideoSource.normalizeQuality(state.selectedQuality) }
                            ?: movie.streamSources.firstOrNull()
                        val playbackUrl = selectedSource?.streamUrl.orEmpty()
                        val isLocalMovie = remember(playbackUrl) {
                            val l = playbackUrl.lowercase().trim()
                            playbackUrl.startsWith("/") || l.startsWith("file://") || l.startsWith("content://")
                        }
                        VideoPlayerView(
                            videoUrl = playbackUrl,
                            title = movie.title,
                            initialProgress = initialProg,
                            subtitleUrl = movie.subtitleUrl,
                            onRefreshUrl = if (isLocalMovie) null else {
                                 { viewModel.refreshMovieLink(movie.id, movie.isPremium, selectedSource?.quality.orEmpty()) }
                             },
                            onProgressUpdate = { progress ->
                                viewModel.saveWatchProgress(movie.id, movie.title, movie.poster, "movie", progress)
                            },
                            onBackClick = {
                                playWithAd {
                                    navigationStack.removeAt(navigationStack.size - 1)
                                }
                            },
                            onMinimizeClick = { currentPos ->
                                viewModel.setFloatingVideo(
                                    MovieViewModel.FloatingVideoState(
                                         videoUrl = playbackUrl,
                                        title = movie.title,
                                        contentId = movie.id,
                                        poster = movie.poster,
                                        type = "movie",
                                        startPositionMs = currentPos
                                    )
                                )
                                playWithAd {
                                    navigationStack.removeAt(navigationStack.size - 1)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Screen.PLAY_SERIES -> state.webSeries?.let { series ->
                        state.selectedEpisode?.let { ep ->
                            val epId = "${series.id}_s${ep.season}e${ep.episode}"
                            val continueWatchingList by viewModel.continueWatching.collectAsState()
                            val savedRecord = continueWatchingList.find { it.contentId == epId }
                            val initialProg = savedRecord?.progress ?: 0f

                            val currentEpIndex = series.episodes.indexOfFirst { it.season == ep.season && it.episode == ep.episode }
                            val nextEpisode = if (currentEpIndex != -1 && currentEpIndex + 1 < series.episodes.size) {
                                series.episodes[currentEpIndex + 1]
                            } else null

                             val selectedSource = ep.streamSources.firstOrNull { VideoSource.normalizeQuality(it.quality) == VideoSource.normalizeQuality(state.selectedQuality) }
                                 ?: ep.streamSources.firstOrNull()
                             val playbackUrl = selectedSource?.streamUrl.orEmpty()
                             val isLocalEpisode = remember(playbackUrl) {
                                 val l = playbackUrl.lowercase().trim()
                                 playbackUrl.startsWith("/") || l.startsWith("file://") || l.startsWith("content://")
                             }
                             val watchedProgressMap = remember(continueWatchingList) {
                                 continueWatchingList.associate { it.contentId to it.progress }
                             }
                             val prevEpisode: Episode? = remember(series.episodes, ep.season, ep.episode) {
                                 val idx = series.episodes.indexOfFirst { it.season == ep.season && it.episode == ep.episode }
                                 if (idx > 0) series.episodes[idx - 1] else null
                             }
                             val seriesCtx = remember(series, ep, continueWatchingList) {
                                 SeriesPlayerContext(
                                     seriesTitle = series.title,
                                     seriesPoster = series.poster,
                                     seriesId = series.id,
                                     currentEpisode = ep,
                                     allEpisodes = series.episodes,
                                     watchedProgress = watchedProgressMap,
                                     nextEpisode = nextEpisode,
                                     onSelectEpisode = { epChosen ->
                                         val top = navigationStack.last()
                                         navigationStack[navigationStack.size - 1] = top.copy(selectedEpisode = epChosen)
                                     },
                                     onPrevEpisodeClick = prevEpisode?.let { prev ->
                                         {
                                             val top = navigationStack.last()
                                             navigationStack[navigationStack.size - 1] = top.copy(selectedEpisode = prev)
                                         }
                                     }
                                 )
                             }

                             VideoPlayerView(
                                 videoUrl = playbackUrl,
                                title = "${series.title} S${ep.season}E${ep.episode}",
                                initialProgress = initialProg,
                                subtitleUrl = ep.subtitleUrl,
                                seriesContext = seriesCtx,
                                onRefreshUrl = if (isLocalEpisode) null else {
                                     { viewModel.refreshEpisodeLink(series.id, ep.season, ep.episode, series.isEpisodeLocked(ep), selectedSource?.quality.orEmpty()) }
                                },
                                onProgressUpdate = { progress ->
                                    viewModel.saveWatchProgress(epId, "${series.title} S${ep.season}E${ep.episode}", series.poster, "webseries", progress)
                                },
                                onBackClick = {
                                    playWithAd {
                                        navigationStack.removeAt(navigationStack.size - 1)
                                    }
                                },
                                onMinimizeClick = { currentPos ->
                                    viewModel.setFloatingVideo(
                                        MovieViewModel.FloatingVideoState(
                                             videoUrl = playbackUrl,
                                            title = "${series.title} S${ep.season}E${ep.episode}",
                                            contentId = epId,
                                            poster = series.poster,
                                            type = "series",
                                            startPositionMs = currentPos
                                        )
                                    )
                                    playWithAd {
                                        navigationStack.removeAt(navigationStack.size - 1)
                                    }
                                },
                                onNextEpisodeClick = nextEpisode?.let { nextEp ->
                                    {
                                        val top = navigationStack.last()
                                        navigationStack[navigationStack.size - 1] = top.copy(selectedEpisode = nextEp)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Screen.CATEGORY -> CategoryScreen(
                        title = state.categoryTitle.orEmpty(),
                        movies = state.categoryMovies,
                        onMovieClick = { movie ->
                            navigationStack.add(NavigationState(Screen.DETAIL, movie = movie))
                        },
                        onBack = { navigationStack.removeAt(navigationStack.size - 1) }
                    )

                    Screen.NOTIFICATIONS -> NotificationsScreen(viewModel, onMovieClick = { movie ->
                        navigationStack.add(NavigationState(Screen.DETAIL, movie = movie))
                    }, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })

                    Screen.MOVIE_REQUEST -> MovieRequestScreen(viewModel, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })

                    Screen.ACCOUNT_SETTINGS -> AccountSettingsScreen(
                        viewModel = viewModel,
                        onBack = {
                            navigationStack.removeAt(navigationStack.size - 1)
                        }
                    )

                    Screen.THEME_SETTINGS -> ThemeSettingsScreen(viewModel, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })

                    Screen.DOWNLOADS -> DownloadsScreen(viewModel, offlineMode = isOffline, onPlayDownloaded = { movie ->
                        playWithAd {
                            navigationStack.add(NavigationState(Screen.PLAY_MOVIE, movie = movie))
                        }
                    }, onPlayDownloadedEpisode = { series, ep ->
                        playWithAd {
                            navigationStack.add(NavigationState(Screen.PLAY_SERIES, webSeries = series, selectedEpisode = ep))
                        }
                    }, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })

                    Screen.CONTINUE_WATCHING -> ContinueWatchingScreen(viewModel, onContinueMovie = { movie ->
                        if (isOffline) {
                            if (!playOfflineMovie(movie)) {
                                android.widget.Toast.makeText(context, "You're offline. This title is not downloaded.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            playWithAd {
                                navigationStack.add(NavigationState(Screen.PLAY_MOVIE, movie = movie))
                            }
                        }
                    }, onContinueSeries = { series, ep ->
                        if (isOffline) {
                            android.widget.Toast.makeText(context, "Online series are unavailable offline.", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            playWithAd {
                                navigationStack.add(NavigationState(Screen.PLAY_SERIES, webSeries = series, selectedEpisode = ep))
                            }
                        }
                    }, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })

                    Screen.PLANS -> PlansScreen(viewModel, onSelectPlan = { plan ->
                        navigationStack.add(NavigationState(Screen.PAYMENT, selectedPlan = plan))
                    }, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })

                    Screen.PAYMENT -> state.selectedPlan?.let { plan ->
                        PaymentScreen(viewModel, plan = plan, onDone = {
                            // Pop back to profile after submitting
                            while (navigationStack.size > 1 && navigationStack.last().screen != Screen.PROFILE) {
                                navigationStack.removeAt(navigationStack.size - 1)
                            }
                        }, onBack = {
                            navigationStack.removeAt(navigationStack.size - 1)
                        })
                    }
                }
            }

            // Draggable Floating Mini-Player overlay
            val fState = floatingVideoState
            if (fState != null) {
                var offsetX by remember { mutableFloatStateOf(0f) }
                var offsetY by remember { mutableFloatStateOf(0f) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 92.dp, end = 16.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Card(
                        modifier = Modifier
                            .width(180.dp)
                            .height(110.dp)
                            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    offsetX += dragAmount.x
                                    offsetY += dragAmount.y
                                }
                            }
                            .clickable {
                                if (fState.type == "movie") {
                                    val movie = Movie(
                                        id = fState.contentId,
                                        title = fState.title,
                                        poster = fState.poster,
                                        link = fState.videoUrl
                                    )
                                    navigationStack.add(NavigationState(Screen.PLAY_MOVIE, movie = movie))
                                } else if (fState.type == "series") {
                                    val parts = fState.contentId.split("_s")
                                    val seriesId = parts.firstOrNull() ?: fState.contentId
                                    val epPart = parts.getOrNull(1) ?: ""
                                    val seasonNum = epPart.substringBefore("e").toIntOrNull() ?: 1
                                    val epNum = epPart.substringAfter("e").toIntOrNull() ?: 1

                                    val ep = Episode(season = seasonNum, episode = epNum, title = fState.title, link = fState.videoUrl)
                                    val series = WebSeries(id = seriesId, title = fState.title.substringBefore(" S"), poster = fState.poster)
                                    navigationStack.add(NavigationState(Screen.PLAY_SERIES, webSeries = series, selectedEpisode = ep))
                                }
                                viewModel.setFloatingVideo(null)
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                        border = BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.05f))
                            )
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            MiniPlayerView(
                                videoUrl = fState.videoUrl,
                                startPositionMs = fState.startPositionMs,
                                onClose = { viewModel.setFloatingVideo(null) }
                            )
                        }
                    }
                }
            }

            // Single transition banner. Home and Downloads do not render their
            // own offline banners, preventing duplicates during recomposition.
            // Never render on player layouts: the banner must not cover the
            // player's controls (back/minimize sit at the top edge).
            val hideBanners = currentNav.screen == Screen.PLAY_MOVIE ||
                currentNav.screen == Screen.PLAY_SERIES

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                AnimatedVisibility(
                    visible = showOfflineBanner && !hideBanners,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE53935))
                            .padding(vertical = 10.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("You're offline • Downloads only", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                var showOnlineBanner by remember { mutableStateOf(false) }
                var lastOfflineState by remember { mutableStateOf(false) }
                LaunchedEffect(isOffline) {
                    if (lastOfflineState && !isOffline) {
                        showOnlineBanner = true
                        delay(3000)
                        showOnlineBanner = false
                    }
                    lastOfflineState = isOffline
                }

                AnimatedVisibility(
                    visible = showOnlineBanner && !hideBanners,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF4CAF50))
                            .padding(vertical = 10.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Wifi, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.backOnline, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ------------------ BOTTOM BAR DESIGN ------------------
@Composable
fun BottomNavigationBar(
    currentScreen: Screen,
    offline: Boolean = false,
    onNavigate: (Screen) -> Unit
) {
    val strings = LocalAppStrings.current
    val items = listOf(
        Triple(Screen.HOME, strings.home, Icons.Default.Home),
        Triple(Screen.DOWNLOADS, strings.downloads, Icons.Default.Download),
        Triple(Screen.WATCHLIST, strings.watchlist, Icons.Default.Favorite),
        Triple(Screen.PROFILE, strings.profile, Icons.Default.Person)
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(vertical = 10.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        shape = RoundedCornerShape(34.dp),
        color = Color.Black.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            items.forEach { (screen, label, icon) ->
                val isSelected = currentScreen == screen
                val itemModifier = Modifier
                    .weight(if (isSelected) 1.75f else 0.82f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onNavigate(screen) }
                    )
                androidx.compose.animation.AnimatedContent(
                    targetState = isSelected,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(120)))
                    },
                    modifier = itemModifier,
                    label = "bottom_nav_${screen.name}"
                ) { selected ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(if (selected) Color.White else Color.Transparent),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (selected) Color.Black else Color.LightGray,
                            modifier = Modifier.size(22.dp)
                        )
                        if (selected) {
                            Spacer(Modifier.width(7.dp))
                            Text(label, color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// ------------------ PRIMARY HOMESCREEN ------------------
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun HomeScreen(
    viewModel: MovieViewModel,
    onNavigateToMovie: (Movie) -> Unit,
    onNavigateToSeries: (WebSeries) -> Unit,
    onSearchClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onViewAll: (String, List<Movie>) -> Unit = { _, _ -> }
) {
    val strings = LocalAppStrings.current
    val notifications by viewModel.notifications.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val heroItems by viewModel.heroItems.collectAsState()
    val homeRows by viewModel.homeRows.collectAsState()
    val seriesHeroItems by viewModel.seriesHeroItems.collectAsState()
    val seriesRows by viewModel.seriesRows.collectAsState()
    val continueWatchingMovies by viewModel.continueWatchingMovies.collectAsState()
    val moviesAvailable = viewModel.movies.collectAsState().value

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { viewModel.refreshData() }
    )

    // Rows are grouped in the ViewModel; this only flattens the map for LazyColumn
    // and is skipped when the map identity is unchanged.
    val rowEntries by remember {
        derivedStateOf { homeRows.entries.map { it.key to it.value } }
    }
    val seriesRowEntries = remember(seriesRows) { seriesRows.entries.map { it.key to it.value } }
    val continueWatchingProgress by remember {
        derivedStateOf { continueWatchingMovies.associate { it.first.id to it.second } }
    }

    Box(modifier = Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 104.dp)
        ) {
        item(key = "compact_top_bar") {
            CompactHomeTopBar(
                searchLabel = strings.search,
                notificationLabel = strings.notifications,
                hasNotifications = notifications.isNotEmpty(),
                onSearchClick = onSearchClick,
                onNotificationsClick = onNotificationsClick
            )
        }

        if (isRefreshing || (isLoading && homeRows.isEmpty() && heroItems.isEmpty() && moviesAvailable.isEmpty())) {
            item(key = "home_loading_placeholder") {
                HomeLoadingPlaceholder()
            }
        }

        if (heroItems.isNotEmpty()) {
            item(key = "hero_banner") {
                HeroBannerCarousel(
                    items = heroItems,
                    onPlayClick = onNavigateToMovie,
                    onInfoClick = onNavigateToMovie
                )
            }
        }

        if (seriesHeroItems.isNotEmpty()) {
            item(key = "series_featured") {
                MovieSliderRow(
                    title = strings.featuredShow,
                    movies = seriesHeroItems.take(1),
                    onMovieClick = onNavigateToSeries,
                    wide = true
                )
            }
        }

        if (continueWatchingMovies.isNotEmpty()) {
            item(key = "row_continue_watching") {
                MovieSliderRow(
                    title = strings.continueWatching,
                    movies = continueWatchingMovies.map { it.first },
                    onMovieClick = onNavigateToMovie,
                    wide = true,
                    badgeFor = { _, movie ->
                        continueWatchingProgress[movie.id]?.let { CardBadge.Progress(it) }
                    },
                    onSeeAll = {
                        onViewAll(strings.continueWatching, continueWatchingMovies.map { it.first })
                    }
                )
            }
        }

        items(rowEntries, key = { it.first }) { (rowTitle, rowMovies) ->
            val isTopTen = rowTitle == MovieViewModel.ROW_TOP_10
            val displayTitle = if (rowTitle == MovieViewModel.ROW_RECOMMENDED) {
                if (activeProfile?.isKids == true) "Kids Friendly Favorites"
                else "Recommended for ${activeProfile?.name ?: "You"}"
            } else rowTitle

            MovieSliderRow(
                title = displayTitle,
                movies = rowMovies,
                onMovieClick = onNavigateToMovie,
                wide = isTopTen,
                badgeFor = { index, movie ->
                    when {
                        isTopTen -> CardBadge.TopTen(index + 1)
                        movie.isNew -> CardBadge.New
                        movie.qualityBadge.isNotBlank() -> CardBadge.Quality(movie.qualityBadge)
                        else -> null
                    }
                },
                onSeeAll = { onViewAll(displayTitle, rowMovies) }

            )
        }

        items(seriesRowEntries, key = { "home_series_${it.first}" }) { (rowTitle, rowSeries) ->
            MovieSliderRow(
                title = rowTitle,
                movies = rowSeries,
                onMovieClick = onNavigateToSeries,
                wide = rowTitle == MovieViewModel.ROW_TOP_10
            )
        }
    }

        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
            backgroundColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun HomeLoadingPlaceholder() {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = "Loading movies...",
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(6, key = { "loading_movie_$it" }) {
                Column(modifier = Modifier.width(118.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryGridRow(
    categoryName: String,
    movies: List<Movie>,
    onClick: (Movie) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = categoryName,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(movies, key = { it.id }) { movie ->
                Column(
                    modifier = Modifier
                        .width(115.dp)
                        .clickable { onClick(movie) }
                ) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                        modifier = Modifier.height(165.dp).fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = movie.poster,
                                contentDescription = movie.title,
                 contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Subtle gradient overlay at the bottom half to enhance card depth
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f))
                                        )
                                    )
                            )
                            
                            // NEW + quality badges top-start
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (movie.isNew) {
                                    Text(
                                        "NEW",
                                        color = Color.White,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                                if (movie.qualityBadge.isNotEmpty()) {
                                    Text(
                                        movie.qualityBadge.uppercase(),
                                        color = Color.Black,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .background(Color(0xFFFFC107), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                                if (movie.isPremium) {
                                    Icon(
                                        imageVector = Icons.Default.WorkspacePremium,
                                        contentDescription = "Premium",
                                        tint = Color(0xFFFFD700),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            // Rating Badge top-right
                            if (!movie.rating.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(9.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = movie.rating,
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = movie.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${movie.year} • ${movie.genre.split("/").firstOrNull()?.trim() ?: movie.genre}",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ------------------ SERIES SCREEN ------------------
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun SeriesScreen(
    viewModel: MovieViewModel,
    onNavigateToSeries: (WebSeries) -> Unit
) {
    val strings = LocalAppStrings.current
    val webSeries by viewModel.webSeries.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val contentState by viewModel.contentState.collectAsState()
    val seriesRows by viewModel.seriesRows.collectAsState()
    val seriesRowEntries = remember(seriesRows) { seriesRows.entries.toList() }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { viewModel.refreshData() }
    )

    Box(modifier = Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 104.dp)
        ) {
            item {
                Text(
                    text = strings.series,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
            }

            val seriesError = contentState is MovieViewModel.ContentState.Error
            val seriesEmpty = webSeries.isEmpty() && contentState is MovieViewModel.ContentState.Ready

            if (seriesError) {
                val message = (contentState as? MovieViewModel.ContentState.Error)?.message
                    ?: "Unable to load series."
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(message, color = Color.LightGray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { viewModel.clearContentError(); viewModel.refreshData() }) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Retry")
                            }
                        }
                    }
                }
            } else if (seriesEmpty) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No content available", color = Color.LightGray, fontSize = 16.sp)
                    }
                }
            }

            if (webSeries.isNotEmpty()) {
                val hero = webSeries.first()
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(290.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable { onNavigateToSeries(hero) },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = hero.backdrop.ifEmpty { hero.poster },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Black.copy(alpha = 0.3f),
                                                Color.Transparent,
                                                Color.Black.copy(alpha = 0.95f)
                                            )
                                        )
                                    )
                            )
                            
                            // Top Left - FEATURED
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(16.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = strings.featuredShow,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            // Bottom Info Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomStart)
                                    .padding(16.dp),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = hero.title,
                                        color = Color.White,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = hero.rating,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = "${hero.year} • ${hero.genre.split("/").firstOrNull()?.trim() ?: hero.genre}",
                                            color = Color.LightGray,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Action button
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .clickable { onNavigateToSeries(hero) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Show Details",
                                        tint = Color.Black,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            items(seriesRowEntries, key = { it.key }) { (rowTitle, rowSeries) ->
                val isTopTen = rowTitle == MovieViewModel.ROW_TOP_10
                MovieSliderRow(
                    title = rowTitle,
                    movies = rowSeries,
                    onMovieClick = onNavigateToSeries,
                    wide = isTopTen,
                    badgeFor = { index, series ->
                        when {
                            isTopTen -> CardBadge.TopTen(index + 1)
                            series.isNew -> CardBadge.New
                            series.qualityBadge.isNotBlank() -> CardBadge.Quality(series.qualityBadge)
                            else -> null
                        }
                    },
                    onSeeAll = null
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
            backgroundColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun CompactHomeTopBar(
    searchLabel: String,
    notificationLabel: String,
    hasNotifications: Boolean,
    onSearchClick: () -> Unit,
    onNotificationsClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 10.dp),
        shape = RoundedCornerShape(32.dp),
        color = Color.Black.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(com.manacinema.app.R.drawable.ic_movie_pro_logo),
                contentDescription = "Mana Cinema",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
            )
            Spacer(Modifier.width(10.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MANA ", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("CINEMA", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            IconButton(
                onClick = onSearchClick,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = searchLabel, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Box {
                IconButton(
                    onClick = onNotificationsClick,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = notificationLabel, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                if (hasNotifications) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .align(Alignment.TopEnd)
                    )
                }
            }
        }
    }
}

@Composable
fun FilterTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color(0xFF1C1C1E))
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2E),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFF8E8E93),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ------------------ WATCHLIST SCREEN ------------------
@Composable
fun WatchlistScreen(
    viewModel: MovieViewModel,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (WebSeries) -> Unit
) {
    val strings = LocalAppStrings.current
    val watchlist by viewModel.watchlist.collectAsState()
    val moviesList by viewModel.movies.collectAsState()
    val seriesList by viewModel.webSeries.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = strings.watchlist,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        if (watchlist.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(strings.noWatchlist, color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(watchlist, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clickable {
                                if (item.type == "movie") {
                                    moviesList.find { it.id == item.id }?.let { onMovieClick(it) }
                                } else {
                                    seriesList.find { it.id == item.id }?.let { onSeriesClick(it) }
                                }
                            },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = item.poster,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(70.dp)
                                    .fillMaxHeight()
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(item.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(item.type.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = { viewModel.toggleWatchlist(item.id, item.title, item.poster, item.backdrop, item.type) },
                                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------ PROFILE SCREEN ------------------
@Composable
fun ProfileScreen(
    viewModel: MovieViewModel,
    onMenuClick: (String) -> Unit,
    onResetData: () -> Unit
) {
    val strings = LocalAppStrings.current
    val userProfile by viewModel.currentUserProfile.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        // Upper Backdrop Header displaying Active Profile!
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), Color.Transparent)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(82.dp)
                        .clip(CircleShape)
                        .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    AsyncImage(
                        model = activeProfile?.avatarUrl ?: "https://i.ibb.co/yBNK21P/avatar1.jpg",
                        contentDescription = "Active Profile Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = activeProfile?.name ?: "No Profile Selected",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (activeProfile?.isKids == true) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "KIDS",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    text = "Preferred Genre: ${activeProfile?.preferredGenre ?: "All"}",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        }

        // Quick Profile Switcher Row
        if (profiles.size > 1) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Switch Viewer Profile",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(profiles, key = { it.id }) { prof ->
                        val isSelected = prof.id == activeProfile?.id
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { viewModel.selectProfile(prof) }
                                .testTag("quick_switch_${prof.id}")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f),
                                        shape = CircleShape
                                    )
                            ) {
                                AsyncImage(
                                    model = prof.avatarUrl,
                                    contentDescription = prof.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = prof.name,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(60.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        val isPremiumUser by viewModel.isPremiumUser.collectAsState()

        // Premium section entry point (P3 #9)
        val premiumItems = listOf(
            iOSMenuItemData(
                if (isPremiumUser) strings.managePremium else strings.goPremium,
                Icons.Default.WorkspacePremium
            ) { onMenuClick("premium") }
        )
        iOSGroupedSection(title = strings.subscription, items = premiumItems)

        Spacer(modifier = Modifier.height(8.dp))

        // Activity Preset Rows themed as iOS Grouped List
        val activityItems = remember {
            mutableListOf(
                iOSMenuItemData(strings.downloads, Icons.Default.ArrowBack) { onMenuClick("downloads") },
                iOSMenuItemData(strings.continueWatching, Icons.Default.PlayArrow) { onMenuClick("continue") },
                iOSMenuItemData(strings.movieRequest, Icons.Default.Edit) { onMenuClick("request") }
            )
        }

        iOSGroupedSection(title = strings.myActivity, items = activityItems)

        Spacer(modifier = Modifier.height(8.dp))

        // Settings, Help & Switch profiles styled as iOS Grouped List
        val settingsItems = remember {
            mutableListOf(
                iOSMenuItemData(strings.switchProfiles, Icons.Default.Person) {
                    viewModel.clearActiveProfile()
                },
                iOSMenuItemData("Themes & Appearance", Icons.Default.Settings) { onMenuClick("themes") },
                iOSMenuItemData(strings.accountSettings, Icons.Default.Person) { onMenuClick("settings") }
            )
        }

        iOSGroupedSection(title = strings.settingsHelp, items = settingsItems)

        Spacer(modifier = Modifier.height(24.dp))

        // iOS Style styled Centered red Row button for Reset Local Data
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            border = BorderStroke(0.5.dp, Color(0xFF2C2C2E)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clickable { onResetData() }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Reset Local Data",
                    color = Color(0xFFFF3B30), // iOS System Red
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

data class iOSMenuItemData(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit
)

@Composable
fun iOSGroupedSection(
    title: String,
    items: List<iOSMenuItemData>
) {
    if (items.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = title.uppercase(),
            color = Color(0xFF8E8E93),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, bottom = 6.dp)
        )
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            border = BorderStroke(0.5.dp, Color(0xFF2C2C2E)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    iOSRowItem(
                        label = item.label,
                        icon = item.icon,
                        onClick = item.onClick
                    )
                    if (index < items.size - 1) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .padding(start = 56.dp)
                                .background(Color(0xFF2C2C2E))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun iOSRowItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val iconBgColor = remember(label) {
            when (label) {
                "Downloads" -> Color(0xFF34C759)
                "Continue Watching" -> Color(0xFF5856D6)
                "Movie Request" -> Color(0xFF007AFF)
                "Themes & Appearance" -> Color(0xFFFF2D55)
                "Account Settings" -> Color(0xFF8E8E93)
                "Ads Diagnostics & Test" -> Color(0xFFFF9F0A)
                "Clear Firebase Movies", "Clear Firebase Series" -> Color(0xFFFF3B30)
                else -> Color(0xFF8E8E93)
            }
        }

        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(17.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = label,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = "Go",
            tint = Color(0xFF8E8E93).copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun iOSLabelledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            color = Color(0xFF8E8E93),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Color(0xFF8E8E93)) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1C1C1E),
                unfocusedContainerColor = Color(0xFF1C1C1E),
                disabledContainerColor = Color(0xFF1C1C1E),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, Color(0xFF2C2C2E), RoundedCornerShape(10.dp))
        )
    }
}

// ------------------ MOVIE REQUEST SCREEN ------------------
@Composable
fun MovieRequestScreen(
    viewModel: MovieViewModel,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    var name by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = strings.back, tint = Color.White)
            }
            Text(strings.requestMovie, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(32.dp))

        iOSLabelledTextField(
            value = name,
            onValueChange = { name = it },
            label = strings.movieName,
            placeholder = "e.g. Inception or Breaking Bad"
        )

        Spacer(modifier = Modifier.height(20.dp))

        iOSLabelledTextField(
            value = year,
            onValueChange = { year = it },
            label = strings.releaseYear,
            placeholder = "e.g. 2010"
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (name.isBlank()) {
                    Toast.makeText(context, strings.enterMovieName, Toast.LENGTH_SHORT).show()
                    return@Button
                }
                viewModel.submitMovieRequest(name, year.trim()) { success ->
                    if (success) {
                        Toast.makeText(context, strings.requestSent, Toast.LENGTH_SHORT).show()
                        onBack()
                    } else {
                        Toast.makeText(context, strings.requestFailed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(strings.submitRequest, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

// ------------------ ACCOUNT SETTINGS SCREEN ------------------
@Composable
fun AccountSettingsScreen(
    viewModel: MovieViewModel,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val profile by viewModel.currentUserProfile.collectAsState()
    var name by remember(profile) { mutableStateOf(profile?.displayName ?: "") }
    var selectedGender by remember(profile) { mutableStateOf(profile?.gender ?: "male") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = strings.back, tint = Color.White)
            }
            Text(strings.accountSettings, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(32.dp))

        iOSLabelledTextField(
            value = name,
            onValueChange = { name = it },
            label = strings.displayName,
            placeholder = "Enter physical name"
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(strings.genderIdentity, color = Color(0xFF8E8E93), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(start = 6.dp)) {
            FilterTabButton(
                label = strings.male,
                selected = selectedGender == "male",
                onClick = { selectedGender = "male" }
            )
            FilterTabButton(
                label = strings.female,
                selected = selectedGender == "female",
                onClick = { selectedGender = "female" }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (name.isBlank()) {
                    Toast.makeText(context, strings.nameCannotBeEmpty, Toast.LENGTH_SHORT).show()
                    return@Button
                }
                viewModel.updateProfile(name, selectedGender) { success ->
                    if (success) {
                        Toast.makeText(context, strings.changesSaved, Toast.LENGTH_SHORT).show()
                        onBack()
                    } else {
                        Toast.makeText(context, strings.updateFailed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(strings.saveChanges, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ------------------ THEME SETTINGS SCREEN ------------------
@Composable
fun ThemeSettingsScreen(
    viewModel: MovieViewModel,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val themes = listOf<Pair<String, String>>(
        "classic-red" to strings.classicRed,
        "ocean-blue" to strings.oceanBlue,
        "forest-green" to strings.forestGreen,
        "royal-purple" to strings.royalPurple,
        "sunset-orange" to strings.sunsetOrange,
        "cyber-pink" to strings.cyberPink
    )

    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val languages = Language.entries

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = strings.back, tint = Color.White)
            }
            Text(strings.themeAppearance, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(strings.selectAccentTheme, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))

        themes.forEach { (id, label) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable {
                        viewModel.saveThemeSettings(id, true)
                        Toast.makeText(context, "$label accent selected!", Toast.LENGTH_SHORT).show()
                    },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (id == "classic-red") {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(Color.Red))
                    } else if (id == "ocean-blue") {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(Color.Cyan))
                    } else if (id == "forest-green") {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(Color.Green))
                    } else if (id == "royal-purple") {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(Color.Magenta))
                    } else {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(strings.selectLanguage, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))

        languages.forEach { lang ->
            val langLabel = when (lang) {
                Language.ENGLISH -> strings.english
                Language.HINDI -> strings.hindi
                Language.BENGALI -> strings.bengali
                Language.TELUGU -> strings.telugu
                Language.TAMIL -> strings.tamil
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable {
                        viewModel.setLanguage(lang)
                        Toast.makeText(context, "$langLabel selected", Toast.LENGTH_SHORT).show()
                    },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (currentLanguage == lang) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${lang.displayName} ($langLabel)",
                        color = if (currentLanguage == lang) MaterialTheme.colorScheme.primary else Color.White,
                        fontSize = 15.sp,
                        fontWeight = if (currentLanguage == lang) FontWeight.Bold else FontWeight.Medium
                    )
                    if (currentLanguage == lang) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// ------------------ MOVIE DETAIL SCREEN ------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: MovieViewModel,
    movie: Movie,
    onPlayClick: (String) -> Unit,
    onSimilarMovieClick: (Movie) -> Unit,
    onUpgradeClick: () -> Unit,
    onTrailerClick: (String) -> Unit,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val watchlist by viewModel.watchlist.collectAsState()
    val movies by viewModel.movies.collectAsState()
    val isFav = watchlist.any { it.id == movie.id }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPremiumUser by viewModel.isPremiumUser.collectAsState()

    var showUpgradePrompt by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadSources by remember(movie.id, movie.isPremium) { mutableStateOf(movie.downloadSources) }
    var selectedQuality by remember { mutableStateOf(movie.streamSources.firstOrNull()?.quality.orEmpty()) }
    val gatedPlay: () -> Unit = {
        if (movie.isPremium && !isPremiumUser) showUpgradePrompt = true else onPlayClick(selectedQuality)
    }

    if (showDownloadDialog) {
        QualityDownloadDialog(
            title = movie.title,
            sources = downloadSources,
            onDismiss = { showDownloadDialog = false },
            onDownload = { source ->
                scope.launch {
                    val error = if (movie.isPremium) {
                        viewModel.downloadPremiumMovie(context, movie, source.quality)
                    } else viewModel.downloadMovie(context, movie, source)
                    Toast.makeText(context, error ?: "${movie.title} ${VideoSource.displayLabel(source.quality)} download started", Toast.LENGTH_SHORT).show()
                    showDownloadDialog = false
                }
            }
        )
    }

    if (showUpgradePrompt) {
        AlertDialog(
            onDismissRequest = { showUpgradePrompt = false },
            title = { Text(strings.premiumContent) },
            text = { Text(strings.premiumRequired) },
            confirmButton = {
                TextButton(onClick = {
                    showUpgradePrompt = false
                    onUpgradeClick()
                }) { Text(strings.viewPlans) }
            },
            dismissButton = {
                TextButton(onClick = { showUpgradePrompt = false }) { Text(strings.cancel) }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                AsyncImage(
                    model = movie.backdrop.ifEmpty { movie.poster },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                            )
                        )
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }

                // Play Button floating overlay
                Button(
                    onClick = gatedPlay,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Movie",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(movie.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "★ ${movie.rating} • ${movie.genre} • ${movie.year}",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (movie.streamSources.isNotEmpty()) {
                        Column {
                            Text("QUALITY", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                movie.streamSources.forEach { source ->
                                    FilterChip(
                                        selected = selectedQuality == source.quality,
                                        onClick = { selectedQuality = source.quality },
                                        label = { Text(VideoSource.displayLabel(source.quality)) }
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = gatedPlay,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (movie.isPremium) strings.watchPremium else strings.watchNow, fontWeight = FontWeight.Bold)
                    }

                    IconButton(
                         onClick = {
                            if (movie.isPremium && !isPremiumUser) showUpgradePrompt = true else if (movie.isPremium) {
                                scope.launch {
                                    viewModel.resolvePremiumSources(movie.id).onSuccess { resolved ->
                                        downloadSources = resolved.filter { it.downloadUrl.isNotBlank() }
                                        if (downloadSources.isEmpty()) Toast.makeText(context, "No download source is available.", Toast.LENGTH_SHORT).show()
                                        else showDownloadDialog = true
                                    }.onFailure { Toast.makeText(context, it.message ?: "Could not load download sources.", Toast.LENGTH_LONG).show() }
                                }
                            } else {
                                downloadSources = movie.downloadSources
                                showDownloadDialog = true
                            }
                         },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            painter = painterResource(com.manacinema.app.R.drawable.ic_download),
                            contentDescription = strings.download,
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleWatchlist(movie.id, movie.title, movie.poster, movie.backdrop, "movie") },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Watchlist",
                            tint = if (isFav) Color.Red else Color.White
                        )
                    }
                }

                // Trailer + Share row
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (movie.trailerUrl.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { onTrailerClick(movie.trailerUrl) },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.watchTrailer)
                        }
                    }
                    OutlinedButton(
                        onClick = { shareTitle(context, movie.title, movie.poster) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.share)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("STORYLINE", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(movie.storyline.ifEmpty { "No description available." }, color = Color.LightGray, fontSize = 14.sp, lineHeight = 22.sp)

                // User rating widget
                Spacer(modifier = Modifier.height(20.dp))
                RatingSection(viewModel = viewModel, contentId = movie.id)

                // Cast list (admin provides cast[]; director is optional and usually empty)
                if (movie.cast.isNotEmpty() || movie.director.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(strings.castCrew, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (movie.director.isNotEmpty()) {
                        Text("Director: ${movie.director}", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (movie.cast.isNotEmpty()) {
                        CastRow(cast = movie.cast)
                    }
                }
            }
        }

        // Similar Showcase Items
        item {
            val similar = movies.filter { it.genre.split(",").any { g -> movie.genre.contains(g.trim()) } && it.id != movie.id }
            if (similar.isNotEmpty()) {
                CategoryGridRow(
                    categoryName = strings.moreLikeThis,
                    movies = similar,
                    onClick = onSimilarMovieClick
                )
            }
        }
    }
}

@Composable
private fun QualityDownloadDialog(
    title: String,
    sources: List<VideoSource>,
    onDismiss: () -> Unit,
    onDownload: (VideoSource) -> Unit
) {
    var selected by remember(sources) { mutableStateOf(sources.firstOrNull()?.quality.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download $title") },
        text = {
            if (sources.isEmpty()) {
                Text("Download unavailable for this title.")
            } else {
                Column {
                    sources.forEach { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = source.quality }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected == source.quality, onClick = { selected = source.quality })
                            Column {
                                Text(VideoSource.displayLabel(source.quality))
                                if (source.fileSize.isNotBlank()) Text(source.fileSize, color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotBlank() && sources.any { it.quality == selected },
                onClick = { sources.firstOrNull { it.quality == selected }?.let(onDownload) }
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ------------------ SERIES DETAIL SCREEN ------------------

private enum class SeriesDetailTab { EPISODES, TRAILER }

@Composable
fun SeriesDetailScreen(
    viewModel: MovieViewModel,
    series: WebSeries,
    onEpisodePlay: (Episode) -> Unit,
    onUpgradeClick: () -> Unit,
    onTrailerClick: (String) -> Unit,
    onSeriesClick: (WebSeries) -> Unit = {},
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val watchlist by viewModel.watchlist.collectAsState()
    val isFav = watchlist.any { it.id == series.id }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPremiumUser by viewModel.isPremiumUser.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val floatingVideo by viewModel.floatingVideo.collectAsState()
    val allSeries by viewModel.webSeries.collectAsState()

    val seasons = remember(series.episodes) { series.episodes.map { it.season }.distinct().sorted().ifEmpty { listOf(1) } }
    var selectedSeason by remember(series.id) { mutableStateOf(seasons.first()) }
    var selectedTab by remember(series.id) { mutableStateOf(SeriesDetailTab.EPISODES) }
    val episodesForSeason = remember(series.episodes, selectedSeason) {
        series.episodes.filter { it.season == selectedSeason }.sortedBy { it.episode }
    }

    var showUpgradePrompt by remember { mutableStateOf(false) }
    var downloadEpisode by remember { mutableStateOf<Episode?>(null) }
    var episodeDownloadSources by remember { mutableStateOf<List<VideoSource>>(emptyList()) }
    val gatedEpisodePlay: (Episode) -> Unit = { ep ->
        if (series.isEpisodeLocked(ep) && !isPremiumUser) showUpgradePrompt = true else onEpisodePlay(ep)
    }

    downloadEpisode?.let { episode ->
        QualityDownloadDialog(
            title = "${series.title} S${episode.season}E${episode.episode}",
            sources = episodeDownloadSources.ifEmpty { episode.downloadSources },
            onDismiss = { downloadEpisode = null },
            onDownload = { source ->
                scope.launch {
                    val error = if (series.isEpisodeLocked(episode)) {
                        viewModel.downloadPremiumEpisode(context, series, episode, source.quality)
                    } else viewModel.downloadEpisode(context, series, episode, source)
                    Toast.makeText(context, error ?: "${VideoSource.displayLabel(source.quality)} download started", Toast.LENGTH_SHORT).show()
                    downloadEpisode = null
                }
            }
        )
    }

    if (showUpgradePrompt) {
        AlertDialog(
            onDismissRequest = { showUpgradePrompt = false },
            title = { Text(strings.premiumContent) },
            text = { Text(strings.seriesPremiumRequired) },
            confirmButton = {
                TextButton(onClick = { showUpgradePrompt = false; onUpgradeClick() }) { Text(strings.viewPlans) }
            },
            dismissButton = {
                TextButton(onClick = { showUpgradePrompt = false }) { Text(strings.cancel) }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // ---------- 1. HERO ----------
        item {
            SeriesHero(
                series = series,
                isFav = isFav,
                onBack = onBack,
                onFavoriteToggle = { viewModel.toggleWatchlist(series.id, series.title, series.poster, series.backdrop, "webseries") },
                onShare = { shareTitle(context, series.title, series.poster) },
                onPlay = {
                    val target = episodesForSeason.firstOrNull() ?: series.episodes.firstOrNull()
                    if (target != null) gatedEpisodePlay(target)
                }
            )
        }

        // ---------- Title / meta / storyline / rating ----------
        item {
            Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                Text(series.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                SeriesMetaRow(series)
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = series.storyline.ifEmpty { "No series details available." },
                    color = Color(0xFFB8B8BE),
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )

                Spacer(modifier = Modifier.height(18.dp))
                RatingSection(viewModel = viewModel, contentId = series.id)

                Spacer(modifier = Modifier.height(20.dp))
                SeriesDetailTabRow(
                    selected = selectedTab,
                    hasTrailer = series.trailerUrl.isNotEmpty(),
                    onSelect = { selectedTab = it }
                )
            }
        }

        // ---------- 3. TOP BILLED CAST (always visible) ----------
        if (series.cast.isNotEmpty() || series.director.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
                    Text(strings.castCrew, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    if (series.director.isNotEmpty()) {
                        Text("Director: ${series.director}", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    if (series.cast.isNotEmpty()) {
                        CastRow(cast = series.cast)
                    }
                }
            }
        }

        // ---------- 2b. TAB CONTENT ----------
        when (selectedTab) {
            SeriesDetailTab.EPISODES -> {
                item {
                    Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.episodes, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("${episodesForSeason.size} episodes", color = Color.Gray, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        if (seasons.size > 1) {
                            SeasonSelector(
                                seasons = seasons,
                                selected = selectedSeason,
                                onSelect = { selectedSeason = it }
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }
                }

                if (episodesForSeason.isEmpty()) {
                    item {
                        Text(
                            "No episodes available for this season yet.",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    itemsIndexed(episodesForSeason, key = { _, ep -> "${series.id}_s${ep.season}e${ep.episode}" }) { index, ep ->
                        val epId = "${series.id}_s${ep.season}e${ep.episode}"
                        val downloadRow = downloads.firstOrNull {
                            it.contentType == "webseries" && it.seriesId == series.id &&
                                it.season == ep.season && it.episode == ep.episode
                        }
                        EpisodeCard(
                            episode = ep,
                            index = index,
                            total = episodesForSeason.size,
                            isLocked = series.isEpisodeLocked(ep) && !isPremiumUser,
                            isPlaying = floatingVideo?.contentId == epId,
                            download = downloadRow,
                            posterFallback = series.poster,
                            onPlayClick = { gatedEpisodePlay(ep) },
                            onDownloadClick = {
                                if (series.isEpisodeLocked(ep) && !isPremiumUser) showUpgradePrompt = true
                                else if (series.isEpisodeLocked(ep)) {
                                    scope.launch {
                                        episodeDownloadSources = emptyList()
                                        viewModel.resolvePremiumSources(series.id, ep.season, ep.episode).onSuccess { resolved ->
                                            episodeDownloadSources = resolved.filter { it.downloadUrl.isNotBlank() }
                                            if (episodeDownloadSources.isEmpty()) Toast.makeText(context, "No download source is available.", Toast.LENGTH_SHORT).show()
                                            else downloadEpisode = ep.copy(videoSources = resolved)
                                        }.onFailure { Toast.makeText(context, it.message ?: "Could not load download sources.", Toast.LENGTH_LONG).show() }
                                    }
                                } else {
                                    episodeDownloadSources = ep.downloadSources
                                    downloadEpisode = ep
                                }
                            }
                        )
                    }
                }
            }

            SeriesDetailTab.TRAILER -> {
                item {
                    TrailerPreview(
                        series = series,
                        modifier = Modifier.padding(horizontal = 18.dp),
                        onWatch = { onTrailerClick(series.trailerUrl) }
                    )
                }
            }
        }

        // ---------- 7. COMMENTS ----------
        item {
            CommentsSection(
                viewModel = viewModel,
                contentId = series.id,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp)
            )
        }

        // ---------- 8. YOU MAY ALSO LIKE ----------
        item {
            val recommendations = remember(allSeries, series.id) {
                allSeries.filter { candidate ->
                    candidate.id != series.id &&
                        candidate.categoryList.any { c -> series.categoryList.contains(c) }
                }.ifEmpty {
                    allSeries.filter { it.id != series.id }
                }.take(12)
            }
            if (recommendations.isNotEmpty()) {
                SeriesRecommendationRow(
                    title = strings.moreLikeThis,
                    series = recommendations,
                    onClick = onSeriesClick
                )
            }
        }
    }
}

// ------------------ HERO ------------------

@Composable
private fun SeriesHero(
    series: WebSeries,
    isFav: Boolean,
    onBack: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onShare: () -> Unit,
    onPlay: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val playScale by animateFloatAsState(if (isPressed) 0.92f else 1f, label = "playScale")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
    ) {
        AsyncImage(
            model = series.backdrop.ifEmpty { series.poster },
            contentDescription = series.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.95f)
                        ),
                        startY = 0f
                    )
                )
        )

        // Top row: back + favorite/share
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            HeroIconButton(icon = Icons.Default.ArrowBack, contentDescription = "Back", onClick = onBack)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroIconButton(
                    icon = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "My List",
                    tint = if (isFav) MaterialTheme.colorScheme.primary else Color.White,
                    onClick = onFavoriteToggle
                )
                HeroIconButton(icon = Icons.Default.Share, contentDescription = "Share", onClick = onShare)
            }
        }

        // Center: prominent play button
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(68.dp)
                .graphicsLayer { scaleX = playScale; scaleY = playScale }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onPlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.size(38.dp)
            )
        }
    }
}

@Composable
private fun HeroIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SeriesMetaRow(series: WebSeries) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (series.rating.isNotBlank()) {
            Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(3.dp))
            Text(series.rating, color = Color.LightGray, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.width(8.dp))
            Text("•", color = Color.Gray, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (series.year.isNotBlank()) {
            Text(series.year, color = Color.LightGray, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("•", color = Color.Gray, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = series.genre.ifBlank { "—" },
            color = Color.LightGray,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (series.isPremium) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.WorkspacePremium, contentDescription = "Premium", tint = Color(0xFFFFD700), modifier = Modifier.size(14.dp))
        }
    }
}

// ------------------ SEGMENTED TAB ------------------

@Composable
private fun SeriesDetailTabRow(
    selected: SeriesDetailTab,
    hasTrailer: Boolean,
    onSelect: (SeriesDetailTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(4.dp)
    ) {
        SeriesDetailTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val bgColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "tabBg"
            )
            val contentColor by animateColorAsState(
                if (isSelected) Color.White else Color.Gray,
                label = "tabContent"
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bgColor)
                    .clickable(enabled = tab != SeriesDetailTab.TRAILER || hasTrailer) { onSelect(tab) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (tab == SeriesDetailTab.EPISODES) Icons.Default.ViewList else Icons.Default.Movie,
                    contentDescription = null,
                    tint = if (tab != SeriesDetailTab.TRAILER || hasTrailer) contentColor else Color.Gray.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (tab == SeriesDetailTab.EPISODES) "Episodes" else "Trailer",
                    color = if (tab != SeriesDetailTab.TRAILER || hasTrailer) contentColor else Color.Gray.copy(alpha = 0.4f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ------------------ SEASON SELECTOR ------------------

@Composable
private fun SeasonSelector(
    seasons: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(seasons, key = { it }) { season ->
            val isSelected = season == selected
            val bgColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "seasonBg"
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(bgColor)
                    .border(
                        width = if (isSelected) 0.dp else 1.dp,
                        color = Color.White.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .clickable { onSelect(season) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Season $season",
                    color = if (isSelected) Color.White else Color.LightGray,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

// ------------------ EPISODE CARD ------------------

@Composable
private fun EpisodeCard(
    episode: Episode,
    index: Int,
    total: Int,
    isLocked: Boolean,
    isPlaying: Boolean,
    download: com.manacinema.app.database.DownloadEntity?,
    posterFallback: String,
    onPlayClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .clickable(enabled = true) { if (isLocked) onDownloadClick() /* opens gate via callers */ else onPlayClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 92.dp, height = 60.dp)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                AsyncImage(
                    model = episode.thumbnail.ifBlank { posterFallback },
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (isLocked) 0.55f else 0.15f)))
                Text(
                    text = "E%02d".format(episode.episode),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
                if (isLocked) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title.ifBlank { "Episode ${episode.episode}" },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                val qualityLabel = episode.streamSources.joinToString(" • ") { VideoSource.displayLabel(it.quality) }
                Text(
                    text = "Episode ${index + 1} of $total".let { base ->
                        if (qualityLabel.isNotBlank()) "$base • $qualityLabel" else base
                    },
                    color = Color.Gray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (download != null && download.status == "DOWNLOADING") {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = download.progress / 100f,
                        modifier = Modifier.fillMaxWidth(0.7f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.DarkGray
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Download control
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable {
                            when {
                                isLocked -> onDownloadClick()
                                download?.status == "DOWNLOADING" || download?.status == "PENDING" ->
                                    Toast.makeText(context, "Already downloading…", Toast.LENGTH_SHORT).show()
                                download?.status == "COMPLETED" ->
                                    Toast.makeText(context, "✓ Already downloaded", Toast.LENGTH_SHORT).show()
                                else -> onDownloadClick()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val status = download?.status
                    when {
                        status == "DOWNLOADING" || status == "PENDING" -> {
                            CircularProgressIndicator(
                                progress = ((download?.progress ?: 0) / 100f).coerceIn(0f, 1f),
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        status == "COMPLETED" -> {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                        status == "FAILED" -> {
                            Icon(Icons.Default.ErrorOutline, contentDescription = "Retry download", tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
                        }
                        else -> {
                            Icon(
                                painter = painterResource(com.manacinema.app.R.drawable.ic_download),
                                contentDescription = "Download Episode",
                                tint = Color.LightGray,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }

                // Play control
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isLocked) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.primary)
                        .clickable { if (isLocked) onDownloadClick() else onPlayClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isLocked -> Icons.Default.Lock
                            isPlaying -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = if (isLocked) "Locked" else "Play Episode",
                        tint = if (isLocked) Color.Gray else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ------------------ TRAILER TAB ------------------

@Composable
private fun TrailerPreview(
    series: WebSeries,
    modifier: Modifier = Modifier,
    onWatch: () -> Unit
) {
    Column(modifier = modifier) {
        if (series.trailerUrl.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text("Trailer not available yet.", color = Color.Gray, fontSize = 13.sp)
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onWatch)
            ) {
                AsyncImage(
                    model = series.backdrop.ifEmpty { series.poster },
                    contentDescription = "Trailer",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play Trailer", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

// ------------------ COMMENTS ------------------

@Composable
private fun CommentsSection(
    viewModel: MovieViewModel,
    contentId: String,
    modifier: Modifier = Modifier
) {
    val commentsFlow = remember(contentId) { viewModel.commentsFlow(contentId) }
    val comments by commentsFlow.collectAsState(initial = emptyList())
    var text by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(modifier = modifier) {
        Text("COMMENTS", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text("Write a comment...", color = Color.Gray, fontSize = 14.sp)
                        }
                        inner()
                    }
                }
            )
            IconButton(
                enabled = text.isNotBlank() && !posting,
                onClick = {
                    posting = true
                    val toSend = text
                    viewModel.postComment(contentId, toSend) { success ->
                        posting = false
                        if (success) {
                            text = ""
                        } else {
                            Toast.makeText(context, "Couldn't post comment. Try again.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank() && !posting) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (comments.isEmpty()) {
            Text("No comments yet. Be the first!", color = Color.Gray, fontSize = 13.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                comments.forEach { comment -> CommentRow(comment) }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: Comment) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (comment.avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = comment.avatarUrl,
                    contentDescription = comment.userName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Text(
                    comment.userName.take(1).uppercase().ifBlank { "?" },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.userName.ifBlank { "Viewer" }, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.width(8.dp))
                Text(relativeTimeLabel(comment.timestamp), color = Color.Gray, fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(comment.text, color = Color(0xFFCFCFD4), fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

private fun relativeTimeLabel(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        minutes < 43200 -> "${minutes / 1440}d ago"
        else -> "${minutes / 43200}mo ago"
    }
}

// ------------------ RECOMMENDATIONS ------------------

@Composable
private fun SeriesRecommendationRow(
    title: String,
    series: List<WebSeries>,
    onClick: (WebSeries) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(series, key = { it.id }) { item ->
                Column(
                    modifier = Modifier
                        .width(115.dp)
                        .clickable { onClick(item) }
                ) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                        modifier = Modifier.height(165.dp).fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = item.poster,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (item.rating.isNotBlank()) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(9.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(item.rating, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${item.year} • ${item.genre.split("/").firstOrNull()?.trim() ?: item.genre}",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ------------------ NOTIFICATIONS SCREEN ------------------
@Composable
fun NotificationsScreen(
    viewModel: MovieViewModel,
    onMovieClick: (Movie) -> Unit,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val notifications by viewModel.notifications.collectAsState()
    val movies by viewModel.movies.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = strings.back, tint = Color.White)
            }
            Text(strings.systemNotifications, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (notifications.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(strings.noNotifications, color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(notifications, key = { it.id.ifBlank { it.hashCode().toString() } }) { item ->
                    Card(
                         modifier = Modifier
                             .fillMaxWidth()
                             .clickable(enabled = item.movieId.isNotBlank()) {
                                 movies.firstOrNull { it.id == item.movieId }?.let(onMovieClick)
                             },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (item.icon.isNotEmpty()) {
                                AsyncImage(
                                    model = item.icon,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.DarkGray)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(item.message, color = Color.LightGray, fontSize = 13.sp)
                                if (item.timestamp > 0L) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.timestamp)),
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------ SEARCH SCREEN ------------------
@Composable
fun SearchScreen(
    viewModel: MovieViewModel,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (WebSeries) -> Unit,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val movies by viewModel.movies.collectAsState()
    val webSeries by viewModel.webSeries.collectAsState()
    val recentSearches by viewModel.recentSearches.collectAsState()
    val trendingTitles by viewModel.trendingTitles.collectAsState()
    var query by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    // 300ms debounce: only filter after the user pauses typing, and record the search term.
    LaunchedEffect(query) {
        delay(300)
        debouncedQuery = query.trim()
        if (debouncedQuery.length >= 2) viewModel.addRecentSearch(debouncedQuery)
    }

    var filteredMovies by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var filteredSeries by remember { mutableStateOf<List<WebSeries>>(emptyList()) }
    LaunchedEffect(movies, webSeries, debouncedQuery) {
        val queryText = debouncedQuery
        val (movieResults, seriesResults) = withContext(Dispatchers.Default) {
            if (queryText.isBlank()) {
                emptyList<Movie>() to emptyList<WebSeries>()
            } else {
                movies.filter {
                    it.title.contains(queryText, ignoreCase = true) ||
                        it.genre.contains(queryText, ignoreCase = true) ||
                        it.cast.any { cast -> cast.name.contains(queryText, ignoreCase = true) }
                } to webSeries.filter {
                    it.title.contains(queryText, ignoreCase = true) ||
                        it.genre.contains(queryText, ignoreCase = true) ||
                        it.cast.any { cast -> cast.name.contains(queryText, ignoreCase = true) }
                }
            }
        }
        filteredMovies = movieResults
        filteredSeries = seriesResults
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1C1C1E))
                    .border(0.5.dp, Color(0xFF2C2C2E), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Icon",
                        tint = Color(0xFF8E8E93),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search_field"),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            if (query.isEmpty()) {
                                    Text(
                                        text = strings.searchPlaceholder,
                                        color = Color(0xFF8E8E93),
                                        fontSize = 15.sp
                                    )
                            }
                            innerTextField()
                        }
                    )
                    
                    if (query.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = Color(0xFF8E8E93),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { query = "" }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                strings.cancel,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onBack() }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (debouncedQuery.isBlank()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (recentSearches.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(strings.recent, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(
                                strings.clear,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { viewModel.clearRecentSearches() }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(recentSearches, key = { "recent_$it" }) { term ->
                                SearchChip(text = term, onClick = { query = term })
                            }
                        }
                    }
                }
                if (trendingTitles.isNotEmpty()) {
                    item {
                        Text(strings.trending, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(trendingTitles, key = { "trending_$it" }) { term ->
                                SearchChip(text = term, onClick = { query = term })
                            }
                        }
                    }
                }
                if (recentSearches.isEmpty() && trendingTitles.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(strings.searchPlaceholder, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        } else if (filteredMovies.isEmpty() && filteredSeries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(strings.noResults, color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (filteredMovies.isNotEmpty()) {
                    item {
                        Text(strings.moviesMatches, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    items(filteredMovies, key = { movie -> "movie_${movie.id}" }) { movie ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .clickable { onMovieClick(movie) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                AsyncImage(
                                    model = movie.poster,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(60.dp).fillMaxHeight()
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(
                                    modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(movie.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text(movie.genre, color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                if (filteredSeries.isNotEmpty()) {
                    item {
                        Text("WEB SERIES MATCHES", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    items(filteredSeries, key = { series -> "series_${series.id}" }) { series ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .clickable { onSeriesClick(series) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                AsyncImage(
                                    model = series.poster,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(60.dp).fillMaxHeight()
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(
                                    modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(series.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text(series.genre, color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            .border(0.5.dp, Color(0xFF2C2C2E), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------ ADMIN INTERSTITIAL AD (P3 #10) ------------------
@Composable
private fun InterstitialAdOverlay(ad: AdItem, onFinished: () -> Unit) {
    val context = LocalContext.current
    // Countdown before the user can skip; falls back to 5s when admin sets none.
    val total = if (ad.duration > 0) ad.duration else 5
    var remaining by remember(ad.id) { mutableStateOf(total) }

    LaunchedEffect(ad.id) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(24.dp)
        ) {
            if (ad.title.isNotEmpty()) {
                Text(ad.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (ad.mediaUrl.isNotEmpty()) {
                AsyncImage(
                    model = ad.mediaUrl,
                    contentDescription = ad.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (ad.targetUrl.isNotEmpty()) {
                                try {
                                    context.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ad.targetUrl))
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.e("InterstitialAd", "Cannot open ad targetUrl", e)
                                }
                            }
                        }
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onFinished, enabled = remaining <= 0) {
                Text(if (remaining > 0) "Skip in ${remaining}s" else "Continue")
            }
        }
    }
}

// ------------------ DOWNLOADS SCREEN ------------------
private fun localDownloadExists(context: android.content.Context, uri: String?): Boolean {
    if (uri.isNullOrBlank()) return false
    return try {
        if (uri.startsWith("content://")) {
            context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(uri), "r")?.use { true } == true
        } else {
            java.io.File(uri.removePrefix("file://")).isFile
        }
    } catch (_: Exception) {
        false
    }
}

@Composable
fun DownloadsScreen(
    viewModel: MovieViewModel,
    onPlayDownloaded: (Movie) -> Unit,
    onPlayDownloadedEpisode: (WebSeries, Episode) -> Unit,
    onBack: () -> Unit,
    offlineMode: Boolean = false
) {
    val strings = LocalAppStrings.current
    val downloads by viewModel.downloads.collectAsState()
    val context = LocalContext.current
    val visibleDownloads = remember(downloads) {
        downloads.filter { download ->
            download.status != "COMPLETED" || localDownloadExists(context, download.localUri)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = strings.back, tint = Color.White)
            }
            Text(
                if (offlineMode) "Offline Mode" else strings.downloads,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (visibleDownloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No Downloads", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Download movies and web series to watch them offline.",
                        color = Color.Gray,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(visibleDownloads, key = { it.id }) { download ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clickable(enabled = download.status == "COMPLETED") {
                                if (download.status == "COMPLETED" && localDownloadExists(context, download.localUri)) {
                            if (download.contentType == "webseries") {
                                // Route episodes as a single-episode series so progress
                                // tracking and player UI treat them as episodes, not movies.
                                val localUri = download.localUri ?: return@clickable
                                val ep = Episode(
                                            season = download.season,
                                            episode = download.episode,
                                            title = download.episodeTitle,
                                    link = localUri,
                                            subtitleUrl = download.subtitleLocalUri
                                        )
                                        val series = WebSeries(
                                            id = download.seriesId.ifBlank { download.id },
                                            title = download.title,
                                            poster = download.poster,
                                            episodes = listOf(ep)
                                        )
                                        onPlayDownloadedEpisode(series, ep)
                            } else {
                                val localUri = download.localUri ?: return@clickable
                                val movie = Movie(
                                            id = download.id,
                                            title = download.title,
                                            poster = download.poster,
                                    link = localUri,
                                            subtitleUrl = download.subtitleLocalUri,
                                            category = "", backdrop = "", rating = "", year = "", genre = "", director = "", storyline = ""
                                        )
                                        onPlayDownloaded(movie)
                                    }
                                }
                            },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                // Never let an offline screen turn a cache miss into a
                                // remote image request. Downloaded playback remains local.
                                model = if (offlineMode) null else download.poster,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(70.dp).fillMaxHeight()
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                             Column(
                                 modifier = Modifier
                                     .weight(1f)
                                    .fillMaxHeight()
                                    .padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(download.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (download.quality.isNotBlank()) {
                                    Text(
                                        VideoSource.displayLabel(download.quality),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                val statusColor = when (download.status) {
                                    "COMPLETED" -> MaterialTheme.colorScheme.primary
                                    "FAILED" -> Color.Red
                                    else -> Color.Gray
                                }
                                
                                if (download.status == "DOWNLOADING" || download.status == "PAUSED") {
                                    LinearProgressIndicator(
                                        progress = download.progress / 100f,
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = Color.DarkGray
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("${download.status} - ${download.progress}%", color = Color.LightGray, fontSize = 11.sp)
                                } else {
                                    Text(download.status, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            
                            Row(
                                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (download.status == "DOWNLOADING") {
                                    IconButton(onClick = { com.manacinema.app.downloads.DownloadService.controlAction(context, download.id, com.manacinema.app.downloads.DownloadService.ACTION_PAUSE) }) {
                                        Icon(painterResource(com.manacinema.app.R.drawable.ic_pause), contentDescription = "Pause", tint = Color.White)
                                    }
                                } else if (download.status == "PAUSED" || download.status == "FAILED") {
                                    IconButton(onClick = { com.manacinema.app.downloads.DownloadService.controlAction(context, download.id, com.manacinema.app.downloads.DownloadService.ACTION_RESUME) }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.White)
                                    }
                                } else if (download.status == "COMPLETED") {
                                    IconButton(onClick = { /* already handled by card click */ }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                
                                IconButton(onClick = { com.manacinema.app.downloads.DownloadService.controlAction(context, download.id, com.manacinema.app.downloads.DownloadService.ACTION_CANCEL) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.7f))
                                 }
                             }
                          }
                    }
                }
            }
        }
    }
}

// ------------------ CONTINUE WATCHING SCREEN ------------------
@Composable
fun ContinueWatchingScreen(
    viewModel: MovieViewModel,
    onContinueMovie: (Movie) -> Unit,
    onContinueSeries: (WebSeries, Episode) -> Unit,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val continueWatchingList by viewModel.continueWatching.collectAsState()
    val moviesList by viewModel.movies.collectAsState()
    val seriesList by viewModel.webSeries.collectAsState()
    val downloads by viewModel.downloads.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = strings.back, tint = Color.White)
            }
            Text(strings.continueWatching, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (continueWatchingList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(strings.noContinueWatching, color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(continueWatchingList, key = { it.contentId }) { record ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clickable {
                                // Prefer a completed local download so Continue Watching
                                // resumes offline and never re-hits the network.
                                val localDl = downloads.find {
                                    it.id == record.contentId &&
                                        it.status == "COMPLETED" && !it.localUri.isNullOrBlank()
                                }
                                if (record.type == "movie") {
                                    if (localDl != null) {
                                        onContinueMovie(
                                            Movie(
                                                id = record.contentId,
                                                title = record.title,
                                                poster = record.poster,
                                                link = localDl.localUri!!,
                                                subtitleUrl = localDl.subtitleLocalUri
                                            )
                                        )
                                    } else {
                                        moviesList.find { it.id == record.contentId }?.let { onContinueMovie(it) }
                                    }
                                } else {
                                    // Parse show id
                                    val seriesId = record.contentId.substringBefore("_s")
                                    val epText = record.contentId.substringAfter("_s")
                                    val sNum = epText.substringBefore("e").toIntOrNull() ?: 1
                                    val eNum = epText.substringAfter("e").toIntOrNull() ?: 1
                                    if (localDl != null) {
                                        val ep = Episode(
                                            season = sNum,
                                            episode = eNum,
                                            title = localDl.episodeTitle,
                                            link = localDl.localUri!!,
                                            subtitleUrl = localDl.subtitleLocalUri
                                        )
                                        onContinueSeries(
                                            WebSeries(
                                                id = seriesId.ifBlank { record.contentId },
                                                title = record.title,
                                                poster = record.poster,
                                                episodes = listOf(ep)
                                            ),
                                            ep
                                        )
                                    } else {
                                        val series = seriesList.find { it.id == seriesId }
                                        if (series != null) {
                                            val ep = series.episodes.find { it.season == sNum && it.episode == eNum } ?: series.episodes.firstOrNull()
                                            if (ep != null) {
                                                onContinueSeries(series, ep)
                                            }
                                        }
                                    }
                                }
                            },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = record.poster,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(70.dp).fillMaxHeight()
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(record.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                 Column {
                                     Text("Unfinished - ${(record.progress * 100).toInt()}% watched", color = Color.Gray, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = record.progress,
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = Color.DarkGray
                                     )
                                 }
                             }
                             IconButton(
                                 onClick = { viewModel.removeFromContinueWatching(record.contentId) },
                                 modifier = Modifier.align(Alignment.CenterVertically)
                             ) {
                                 Icon(
                                     Icons.Default.Close,
                                     contentDescription = "Remove from Continue Watching",
                                     tint = Color.LightGray
                                 )
                             }
                         }
                    }
                }
            }
        }
    }
}

// ------------------ PROFILE SELECTION SCREEN & MANAGEMENT SYSTEM ------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSelectionScreen(
    viewModel: MovieViewModel,
    onResetData: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()

    var isEditMode by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<MovieProfile?>(null) }
    var pinPromptProfile by remember { mutableStateOf<MovieProfile?>(null) }

    // Dialog form states
    var inputName by remember { mutableStateOf("") }
    var selectedAvatar by remember { mutableStateOf("https://i.ibb.co/yBNK21P/avatar1.jpg") }
    var isKidsState by remember { mutableStateOf(false) }
    var selectedGenre by remember { mutableStateOf("All") }
    var pinInput by remember { mutableStateOf("") }

    // P3 #12: avatar URLs are admin-managed via Firebase
    val avatars by viewModel.avatarUrls.collectAsState()

    val genres = listOf("All", "Action", "Drama", "Sci-Fi", "Comedy", "Romance", "Adventure")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F12))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            AsyncImage(
                model = com.manacinema.app.R.drawable.ic_movie_pro_logo,
                contentDescription = "Mana Cinema Logo",
                modifier = Modifier
                    .size(110.dp)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .testTag("app_logo_image")
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text("MANA ", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text("CINEMA", color = MaterialTheme.colorScheme.primary, fontSize = 28.sp, fontWeight = FontWeight.Black)
            }

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = if (isEditMode) "Manage Profiles" else "Who's watching today?",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Grid of Profiles
            val columns = 2
            val chunked = profiles.chunked(columns)
            chunked.forEach { rowProfiles ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    rowProfiles.forEach { profile ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable {
                                    if (isEditMode) {
                                        editingProfile = profile
                                        inputName = profile.name
                                        selectedAvatar = profile.avatarUrl
                                        isKidsState = profile.isKids
                                        selectedGenre = profile.preferredGenre
                                        pinInput = ""
                                        showAddDialog = true
                                    } else if (profile.isKids && viewModel.hasParentalPin(profile.id)) {
                                        pinPromptProfile = profile
                                    } else {
                                        viewModel.selectProfile(profile)
                                    }
                                }
                                .testTag("profile_item_${profile.id}")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(
                                        width = if (isEditMode) 2.dp else 1.dp,
                                        color = if (isEditMode) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            ) {
                                AsyncImage(
                                    model = profile.avatarUrl,
                                    contentDescription = profile.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (isEditMode) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.5f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit profile",
                                            tint = Color.White,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = profile.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp
                                )
                                if (profile.isKids) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            "KIDS",
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Add button if profiles count < 4
                    if (rowProfiles.size < columns && profiles.size < 4 && !isEditMode) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                editingProfile = null
                                inputName = ""
                                selectedAvatar = avatars.random()
                                isKidsState = false
                                selectedGenre = "All"
                                showAddDialog = true
                            }.testTag("add_profile_shortcut")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Create profile",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Create", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                }
            }

            // Inline Add/Create card if profiles size < 4 and row profiles list is empty
            if (profiles.isEmpty() && !isEditMode) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        editingProfile = null
                        inputName = ""
                        selectedAvatar = avatars.random()
                        isKidsState = false
                        selectedGenre = "All"
                        showAddDialog = true
                    }.testTag("add_profile_empty")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create profile",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Add Profile", color = Color.Gray, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Management Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = { isEditMode = !isEditMode },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.testTag("manage_profiles_btn")
                ) {
                    Icon(
                        imageVector = if (isEditMode) Icons.Default.Done else Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isEditMode) "Done" else "Manage")
                }

                if (profiles.size < 4 && !isEditMode) {
                    Button(
                        onClick = {
                            editingProfile = null
                            inputName = ""
                            selectedAvatar = avatars.random()
                            isKidsState = false
                            selectedGenre = "All"
                            showAddDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("add_profile_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Profile")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Reset Local Data Action
            TextButton(
                onClick = onResetData,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.LightGray),
                modifier = Modifier.testTag("reset_data_btn")
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset Local Data", fontSize = 14.sp)
            }
        }
    }

    // Parental PIN prompt before entering a locked kids profile.
    pinPromptProfile?.let { profile ->
        var pinInput by remember(profile.id) { mutableStateOf("") }
        var pinError by remember(profile.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pinPromptProfile = null },
            containerColor = Color(0xFF16161D),
            shape = RoundedCornerShape(24.dp),
            title = { Text("Enter Parental PIN", color = Color.White) },
            text = {
                Column {
                    Text("This profile is locked. Enter the 4-digit PIN to continue.", color = Color.LightGray, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) { pinInput = it; pinError = false } },
                        label = { Text("PIN") },
                        singleLine = true,
                        isError = pinError,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinError) {
                        Text("Incorrect PIN", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (viewModel.verifyParentalPin(profile.id, pinInput)) {
                        viewModel.selectProfile(profile)
                        pinPromptProfile = null
                    } else {
                        pinError = true
                    }
                }) { Text("Unlock") }
            },
            dismissButton = {
                TextButton(onClick = { pinPromptProfile = null }) { Text("Cancel", color = Color.LightGray) }
            }
        )
    }

    // Modern Sheet/Dialog for Add/Edit profile
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = Color(0xFF16161D),
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = if (editingProfile != null) "Edit Profile" else "Add Brand-New Profile",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Profile Name Input
                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = { Text("Profile Name", color = Color.Gray) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = Color.White.copy(alpha = 0.2f),
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("profile_name_field"),
                        singleLine = true
                    )

                    // Avatar Selector Row
                    Column {
                        Text("Choose Avatar Picture", color = Color.Gray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            avatars.forEach { url ->
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .border(
                                            width = if (selectedAvatar == url) 2.dp else 0.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedAvatar = url }
                                ) {
                                    AsyncImage(
                                        model = url,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }

                    // Separation Option - Preferred Genre Switcher
                    Column {
                        Text("Personalized Preferred Genre", color = Color.Gray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            genres.forEach { genre ->
                                val selected = selectedGenre == genre
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { selectedGenre = genre }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = genre,
                                        color = if (selected) Color.White else Color.LightGray,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // Separation Option - Kids Mode Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Kids Mode", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Family-friendly & Safe Animation focus", color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = isKidsState,
                            onCheckedChange = { isKidsState = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                        )
                    }
                    if (isKidsState) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinInput = it },
                            label = { Text("Parental PIN (optional)") },
                            placeholder = { Text("4-6 digits, blank = keep") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val profileToDelete = editingProfile
                    if (profileToDelete != null) {
                        IconButton(
                            onClick = {
                                viewModel.deleteProfile(profileToDelete.id)
                                showAddDialog = false
                                isEditMode = false
                            },
                            modifier = Modifier.testTag("delete_profile_trash_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Profile", tint = Color.Red.copy(alpha = 0.8f))
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showAddDialog = false }) {
                            Text("Cancel", color = Color.Gray)
                        }
                        Button(
                            onClick = {
                                if (inputName.isNotBlank()) {
                                    val newProf = MovieProfile(
                                        id = editingProfile?.id ?: "prof_${System.currentTimeMillis()}",
                                        name = inputName.trim(),
                                        avatarUrl = selectedAvatar,
                                        isKids = isKidsState,
                                        preferredGenre = selectedGenre
                                    )
                                    viewModel.saveNewProfile(newProf)
                                    if (isKidsState && pinInput.length in 4..6) {
                                        viewModel.setParentalPin(newProf.id, pinInput)
                                    } else if (!isKidsState) {
                                        viewModel.clearParentalPin(newProf.id)
                                    }
                                    pinInput = ""
                                    showAddDialog = false
                                    isEditMode = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("save_profile_done_btn")
                        ) {
                            Text("Save", color = Color.White)
                        }
                    }
                }
            }
        )
    }
}


// ------------------ CATEGORY / "VIEW ALL" GRID ------------------
@Composable
fun CategoryScreen(
    title: String,
    movies: List<Movie>,
    onMovieClick: (Movie) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${movies.size}",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 12.dp)
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(118.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 90.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(movies, key = { it.id }) { movie ->
                PosterCard(movie = movie, onClick = onMovieClick)
            }
        }
    }
}
