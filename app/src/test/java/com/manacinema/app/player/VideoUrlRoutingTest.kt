package com.manacinema.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoUrlRoutingTest {
    @Test
    fun extensionlessSignedMediaUrlUsesNativePlayer() {
        assertFalse(isEmbedPageUrl("https://cdn.example/video?id=123&signature=redacted"))
    }

    @Test
    fun knownEmbedProvidersUseWebView() {
        assertTrue(isEmbedPageUrl("https://www.youtube.com/embed/abc"))
        assertTrue(isEmbedPageUrl("https://player.vimeo.com/video/123"))
    }

    @Test
    fun htmlPageIsRecognizedAsEmbed() {
        assertTrue(isEmbedPageUrl("https://example.com/player.html?source=video"))
    }

    @Test
    fun httpsAlternativePreservesPathAndSignedQuery() {
        assertEquals(
            "https://cdn.example/path/movie.m3u8?token=a%2Fb&expires=123#part",
            httpsAlternativeFor("http://cdn.example/path/movie.m3u8?token=a%2Fb&expires=123#part")
        )
    }

    @Test
    fun httpsAlternativeIsOnlyBuiltForAbsoluteHttpUrls() {
        assertNull(httpsAlternativeFor("https://cdn.example/movie.mp4?token=secret"))
        assertNull(httpsAlternativeFor("/storage/emulated/0/movie.mp4"))
        assertNull(httpsAlternativeFor("http:opaque"))
        assertNull(httpsAlternativeFor("http://"))
    }
}
