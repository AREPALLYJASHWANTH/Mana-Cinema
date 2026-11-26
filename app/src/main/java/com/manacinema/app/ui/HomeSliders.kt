package com.manacinema.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.manacinema.app.models.Movie
import com.manacinema.app.models.PosterItem
import com.manacinema.app.ui.theme.ManaBody
import com.manacinema.app.ui.theme.ManaDisplay
import com.manacinema.app.ui.theme.ManaMono
import kotlinx.coroutines.delay

// Overlay variants a PosterCard can show. Progress is 0f..1f, matching
// ContinueWatchingEntity.progress.
sealed class CardBadge {
    object New : CardBadge()
    data class Quality(val label: String) : CardBadge()
    data class TopTen(val rank: Int) : CardBadge()
    data class Progress(val fraction: Float) : CardBadge()
}

private val CardWidthPortrait = 118.dp
private val CardHeightPortrait = 170.dp
private val CardWidthWide = 172.dp
private val CardHeightWide = 97.dp

@Composable
private fun ShimmerBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "shimmerAlpha"
    )
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = alpha),
                    Color.White.copy(alpha = alpha * 0.4f)
                )
            )
        )
    )
}
@Composable
fun <T : PosterItem> PosterCard(
    movie: T,
    badge: CardBadge? = null,
    wide: Boolean = false,
    onClick: (T) -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 1.05f else 1f,
        animationSpec = tween(140),
        label = "pressScale"
    )
    val accent = MaterialTheme.colorScheme.primary
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .width(if (wide) CardWidthWide else CardWidthPortrait)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick(movie) }
            .testTag("poster_card_${movie.id}")
    ) {
        Card(
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = if (pressed) 0.35f else 0.10f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = if (pressed) 10.dp else 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(if (wide) CardHeightWide else CardHeightPortrait)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val model = if (wide) movie.backdrop.ifBlank { movie.poster } else movie.poster

                val imageRequest = remember(model, wide) {
                    ImageRequest.Builder(context)
                        .data(model)
                        .size(if (wide) 520 else 360)
                        .crossfade(false)
                        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .build()
                }
                AsyncImage(
                    model = imageRequest,
                    contentDescription = movie.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1C1C1E)) // placeholder background
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                            )
                        )
                )

                if (movie.isPremium) {
                    Icon(
                        imageVector = Icons.Default.WorkspacePremium,
                        contentDescription = "Premium",
                        tint = Color(0xFFFFD700),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(14.dp)
                    )
                }

                val contentTypeLabel = when (movie) {
                    is com.manacinema.app.models.WebSeries -> "WEB SERIES"
                    is Movie -> "MOVIE"
                    else -> null
                }
                if (contentTypeLabel != null) {
                    BadgePill(contentTypeLabel, Color.White, Color.Black.copy(alpha = 0.72f), Alignment.TopStart)
                }

                when (badge) {
                    is CardBadge.New -> BadgePill("NEW", Color.White, accent, Alignment.TopStart)
                    is CardBadge.Quality ->
                        BadgePill(badge.label.uppercase(), Color.Black, Color(0xFFFFC107), Alignment.TopStart)
                    is CardBadge.TopTen -> Text(
                        text = "${badge.rank}",
                        color = Color.White,
                        fontSize = 44.sp,
                        fontFamily = ManaMono,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 6.dp)
                    )
                    is CardBadge.Progress -> Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(badge.fraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(accent)
                        )
                    }
                    null -> Unit
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = movie.title,
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = ManaDisplay,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (movie.rating.isNotBlank() || movie.year.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (movie.rating.isNotBlank()) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(9.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = movie.rating,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        fontFamily = ManaMono
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = movie.year,
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = ManaMono,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun BoxScope.BadgePill(
    text: String,
    textColor: Color,
    bg: Color,
    alignment: Alignment
) {
    Text(
        text = text,
        color = textColor,
        fontSize = 8.sp,
        fontFamily = ManaMono,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .align(alignment)
            .padding(6.dp)
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}
@Composable
fun <T : PosterItem> MovieSliderRow(
    title: String,
    movies: List<T>,
    onMovieClick: (T) -> Unit,
    badgeFor: (Int, T) -> CardBadge? = { _, _ -> null },
    wide: Boolean = false,
    onSeeAll: (() -> Unit)? = null
) {
    if (movies.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
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
                    text = title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontFamily = ManaDisplay,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onSeeAll != null) {
                // Small, subtle "View All" affordance sitting on the header
                // baseline - intentionally lighter and smaller than the title.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSeeAll() }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "View All",
                        color = Color.White.copy(alpha = 0.60f),
                        fontSize = 12.sp,
                        fontFamily = ManaDisplay,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(movies, key = { _, movie -> movie.id }) { index, movie ->
                PosterCard(
                    movie = movie,
                    badge = badgeFor(index, movie),
                    wide = wide,
                    onClick = onMovieClick
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroBannerCarousel(
    items: List<Movie>,
    onPlayClick: (Movie) -> Unit,
    onInfoClick: (Movie) -> Unit
) {
    if (items.isEmpty()) return

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val heroHeight = when {
        configuration.screenHeightDp < 700 -> 360.dp
        configuration.screenHeightDp < 820 -> 390.dp
        else -> 420.dp
    }
    val pagerState = rememberPagerState(pageCount = { items.size })

    // Auto-advance: keyed on settledPage (not currentPage) so the timer only runs
    // once a page has fully settled. Any user drag cancels + restarts it, and the
    // scroll waits for isScrollInProgress == false so the animation is never
    // launched mid-drag (that is what left the banner stuck between two pages).
    LaunchedEffect(pagerState.settledPage, items.size) {
        if (items.size <= 1) return@LaunchedEffect
        delay(5000)
        if (pagerState.isScrollInProgress) return@LaunchedEffect
        val next = (pagerState.settledPage + 1) % items.size
        runCatching {
            pagerState.animateScrollToPage(
                page = next,
                animationSpec = tween(330, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            )
        }
    }

    // Safety net: if a gesture or an interrupted animation leaves the pager parked
    // between two banners, snap it to the nearest page as soon as scrolling stops.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    val offset = pagerState.currentPageOffsetFraction
                    if (kotlin.math.abs(offset) > 0.01f) {
                        val target = if (offset > 0.5f) {
                            (pagerState.currentPage + 1).coerceAtMost(items.lastIndex)
                        } else if (offset < -0.5f) {
                            (pagerState.currentPage - 1).coerceAtLeast(0)
                        } else pagerState.currentPage
                        runCatching {
                            pagerState.animateScrollToPage(
                                page = target,
                                animationSpec = tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                            )
                        }
                    }
                }
            }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fill,
            // One page per fling, a low positional threshold and a snappy spring:
            // even a short drag completes to the next/previous banner instead of
            // getting stuck half-way between two pages.
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                pagerSnapDistance = PagerSnapDistance.atMost(1),
                lowVelocityAnimationSpec = tween(
                    durationMillis = 320,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                ),
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                snapPositionalThreshold = 0.12f
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(heroHeight)
                .clip(RoundedCornerShape(28.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
        ) { page ->
            val movie = items[page]
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = remember(movie.id) {
                         ImageRequest.Builder(context)
                            .data(movie.backdrop.ifBlank { movie.poster })
                            .size(1080, 420) // Limit decoded bitmap size
                            .crossfade(false)
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                            .build()
                    },
                    contentDescription = movie.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1C1C1E)) // placeholder background
                )
                // Scrim fades to the theme background so the row below blends in.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.45f),
                                0.35f to Color.Transparent,
                                0.75f to Color.Black.copy(alpha = 0.75f),
                                1f to MaterialTheme.colorScheme.background
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    androidx.compose.material3.Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.52f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                    ) {
                        Text(
                        text = when {
                            movie.isNew -> "New Release"
                            movie.qualityBadge.isNotBlank() -> movie.qualityBadge
                            else -> "Featured"
                        },
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = movie.title,
                        color = Color.White,
                        fontSize = 25.sp,
                        fontFamily = ManaDisplay,
                        fontWeight = FontWeight.Black,
                        lineHeight = 29.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${movie.genre.split("/", ",").firstOrNull()?.trim().orEmpty().ifBlank { "Cinema" }} · Feature Film",
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val chips = remember(movie.genre) {
                        movie.genre.split("/", ",").map { it.trim() }.filter { it.isNotBlank() }.take(3)
                    }
                    if (chips.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            chips.forEach { chip ->
                                Text(
                                    text = chip,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontFamily = ManaMono,
                                    modifier = Modifier
                                        .background(
                                            Color.White.copy(alpha = 0.14f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .clickable { onPlayClick(movie) }
                                .height(48.dp)
                                .width(150.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Play",
                                color = Color.Black,
                                fontSize = 13.sp,
                                fontFamily = ManaDisplay,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        androidx.compose.material3.IconButton(
                            onClick = { onInfoClick(movie) },
                            modifier = Modifier
                                .size(52.dp)
                                .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                                .background(Color.Black.copy(alpha = 0.42f), CircleShape)
                        ) { Icon(Icons.Default.BookmarkBorder, contentDescription = "My List", tint = Color.White) }
                        androidx.compose.material3.IconButton(
                            onClick = { onInfoClick(movie) },
                            modifier = Modifier
                                .size(52.dp)
                                .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                                .background(Color.Black.copy(alpha = 0.42f), CircleShape)
                        ) { Icon(Icons.Default.Info, contentDescription = "Movie details", tint = Color.White) }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.indices.forEach { index ->
                val selected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .width(if (selected) 16.dp else 6.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.35f)
                        )
                )
            }
        }
    }
}
