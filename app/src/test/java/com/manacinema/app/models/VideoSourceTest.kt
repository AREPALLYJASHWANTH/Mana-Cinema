package com.manacinema.app.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Phase 35 quality/source logic. These cover the rules the
 * player and download flows depend on: canonical quality labels, numeric (not
 * alphabetical) ordering, enabled/usable filtering, stream vs download separation,
 * and backward compatibility with the legacy `link` field.
 */
class VideoSourceTest {

    @Test
    fun normalizeQuality_canonicalisesCommonAliases() {
        assertEquals("2160p", VideoSource.normalizeQuality("4K"))
        assertEquals("2160p", VideoSource.normalizeQuality("2160"))
        assertEquals("2160p", VideoSource.normalizeQuality("uhd"))
        assertEquals("1080p", VideoSource.normalizeQuality("FHD"))
        assertEquals("720p", VideoSource.normalizeQuality("hd"))
        assertEquals("480p", VideoSource.normalizeQuality("480"))
        // Unknown labels are preserved verbatim (trimmed), never dropped.
        assertEquals("Default", VideoSource.normalizeQuality("  Default "))
    }

    @Test
    fun displayLabel_shows4KForTopRung() {
        assertEquals("4K", VideoSource.displayLabel("2160p"))
        assertEquals("4K", VideoSource.displayLabel("uhd"))
        assertEquals("1080p", VideoSource.displayLabel("1080p"))
    }

    @Test
    fun usable_sortsDescendingByHeightNotAlphabetically() {
        val sources = listOf(
            VideoSource(quality = "480p", streamUrl = "s480"),
            VideoSource(quality = "1080p", streamUrl = "s1080"),
            VideoSource(quality = "720p", streamUrl = "s720"),
            VideoSource(quality = "2160p", streamUrl = "s2160")
        )
        val ordered = sources.usable().map { it.quality }
        assertEquals(listOf("2160p", "1080p", "720p", "480p"), ordered)
    }

    @Test
    fun usable_dropsDisabledSources() {
        val sources = listOf(
            VideoSource(quality = "720p", streamUrl = "s720", enabled = true),
            VideoSource(quality = "1080p", streamUrl = "s1080", enabled = false)
        )
        val streamable = sources.usable().map { it.quality }
        assertEquals(listOf("720p"), streamable)
    }

    @Test
    fun usable_requireDownload_keepsOnlySourcesWithDownloadUrl() {
        val sources = listOf(
            // Stream only — must not appear as a download option.
            VideoSource(quality = "720p", streamUrl = "s720"),
            // Both — appears in both lists.
            VideoSource(quality = "1080p", streamUrl = "s1080", downloadUrl = "d1080")
        )
        assertEquals(listOf("1080p", "720p"), sources.usable().map { it.quality })
        assertEquals(listOf("1080p"), sources.usable(requireDownload = true).map { it.quality })
    }

    @Test
    fun movieStreamSources_fallBackToLegacyLinkWhenNoVideoSources() {
        val legacy = Movie(id = "m1", link = "http://x/movie.mp4", qualityBadge = "720p")
        val streams = legacy.streamSources
        assertEquals(1, streams.size)
        assertEquals("http://x/movie.mp4", streams.first().streamUrl)
        // Legacy link is downloadable too (existing behaviour).
        assertEquals("http://x/movie.mp4", legacy.downloadSources.first().downloadUrl)
    }

    @Test
    fun movieWithNoLinkAndNoSources_hasNoPlayableOrDownloadable() {
        val empty = Movie(id = "m2")
        assertTrue(empty.streamSources.isEmpty())
        assertTrue(empty.downloadSources.isEmpty())
    }

    @Test
    fun movieVideoSources_takePrecedenceOverLegacyLink() {
        val movie = Movie(
            id = "m3",
            link = "http://x/legacy.mp4",
            videoSources = listOf(
                VideoSource(quality = "1080p", streamUrl = "http://x/1080.mp4", downloadUrl = "http://x/1080dl.mp4"),
                VideoSource(quality = "720p", streamUrl = "http://x/720.mp4")
            )
        )
        // Best-first, and the legacy link is not used when real sources exist.
        assertEquals(listOf("1080p", "720p"), movie.streamSources.map { it.quality })
        // Only 1080p has a download URL.
        assertEquals(listOf("1080p"), movie.downloadSources.map { it.quality })
    }

    @Test
    fun episodeSources_supportQualityAndLegacyFallback() {
        val legacyEp = Episode(season = 1, episode = 1, link = "http://x/e.mp4")
        assertEquals("http://x/e.mp4", legacyEp.streamSources.first().streamUrl)

        val modernEp = Episode(
            season = 1, episode = 2,
            videoSources = listOf(
                VideoSource(quality = "480p", streamUrl = "s480", downloadUrl = "d480"),
                VideoSource(quality = "1080p", streamUrl = "s1080")
            )
        )
        assertEquals(listOf("1080p", "480p"), modernEp.streamSources.map { it.quality })
        assertEquals(listOf("480p"), modernEp.downloadSources.map { it.quality })
    }

    @Test
    fun episodeLocked_whenSeriesOrEpisodePremium() {
        val series = WebSeries(id = "s1", isPremium = false)
        val freeEp = Episode(season = 1, episode = 1, isPremium = false)
        val paidEp = Episode(season = 1, episode = 2, isPremium = true)
        assertFalse(series.isEpisodeLocked(freeEp))
        assertTrue(series.isEpisodeLocked(paidEp))

        val premiumSeries = WebSeries(id = "s2", isPremium = true)
        // Series-level premium locks every episode.
        assertTrue(premiumSeries.isEpisodeLocked(freeEp))
    }
}
