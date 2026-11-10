package com.portalhacks.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRelayPollerTest {

    @Test
    fun testAlbumUrlRegexExtractsMultipleUrls() {
        val albumUrlRegex = Regex("""https?://(?:photos\.app\.goo\.gl/[^\s,"';|]+|photos\.google\.com/[^\s,"';|]+|share\.icloud\.com/photos/[^\s,"';|]+)""")
        val input = "#setalbum:https://photos.app.goo.gl/AlbumA | https://photos.app.goo.gl/AlbumB\nHere are the trip photos!"

        val urls = albumUrlRegex.findAll(input).map { it.value.trim() }.distinct().toList()
        assertEquals(2, urls.size)
        assertEquals("https://photos.app.goo.gl/AlbumA", urls[0])
        assertEquals("https://photos.app.goo.gl/AlbumB", urls[1])

        var remaining = input
        for (u in urls) remaining = remaining.replace(u, "")
        remaining = remaining.replace("#setalbum:", "").replace("#addalbum:", "").replace("|", "").trim()
        assertEquals("Here are the trip photos!", remaining)
    }

    @Test
    fun testICloudAndGoogleUrlsRecognized() {
        val albumUrlRegex = Regex("""https?://(?:photos\.app\.goo\.gl/[^\s,"';|]+|photos\.google\.com/[^\s,"';|]+|share\.icloud\.com/photos/[^\s,"';|]+)""")
        val input = "#addalbum:https://share.icloud.com/photos/0123XYZ, https://photos.google.com/share/ABC123"
        val urls = albumUrlRegex.findAll(input).map { it.value.trim() }.distinct().toList()

        assertEquals(2, urls.size)
        assertEquals("https://share.icloud.com/photos/0123XYZ", urls[0])
        assertEquals("https://photos.google.com/share/ABC123", urls[1])
    }

    @Test
    fun testChannelSwitchRegex() {
        val channelMatch = Regex("""#(?:channel|setchannel|family):([A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE)
            .find("#family:smith_household")
        assertTrue(channelMatch != null)
        assertEquals("smith_household", channelMatch!!.groupValues[1])
    }

    @Test
    fun testRenameRegex() {
        val renameWithTarget = Regex("""#(?:rename|setname):([A-Za-z0-9_.-]+):([^\n\r]+)""", RegexOption.IGNORE_CASE)
            .find("#setname:1A2B:Kitchen Portal")
        assertTrue(renameWithTarget != null)
        assertEquals("1A2B", renameWithTarget!!.groupValues[1])
        assertEquals("Kitchen Portal", renameWithTarget.groupValues[2])

        val renameBroadcastAll = Regex("""#(?:rename|setname):([^\n\r:]+)""", RegexOption.IGNORE_CASE)
            .find("#rename:Living Room")
        assertTrue(renameBroadcastAll != null)
        assertEquals("Living Room", renameBroadcastAll!!.groupValues[1])
    }

    @Test
    fun testShowcaseTagExtraction() {
        val input = "Greetings from Rome! #showcase:italy"
        val hashMatch = Regex("""#(?:showcase:(?:date:)?|date:|showcase:|location:)?([A-Za-z0-9_.-]+)""").find(input)
        assertTrue(hashMatch != null)
        assertEquals("#showcase:italy", hashMatch!!.groupValues[0])
        assertEquals("italy", hashMatch.groupValues[1])

        val displayMsg = input.replace(hashMatch.groupValues[0], "").trim()
        assertEquals("Greetings from Rome!", displayMsg)
    }
}
