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

    @Test
    fun testOverwriteVsAppendCommandDetection() {
        val setCmd = "#setalbum:https://photos.app.goo.gl/NewAlbum"
        val isSetMatch = Regex("""#(?:setalbum|replacealbum|setalbums):([^\r\n]+)""", RegexOption.IGNORE_CASE).find(setCmd)
        assertTrue(isSetMatch != null)

        val addCmd = "#addalbum:https://photos.app.goo.gl/NewAlbum"
        val isAddMatch = Regex("""#(?:addalbum|addalbums|album):([^\r\n]+)""", RegexOption.IGNORE_CASE).find(addCmd)
        assertTrue(isAddMatch != null)
    }

    @Test
    fun testExclusiveAlbumLogicDisablesRatherThanDeletes() {
        val existingAlbums = mutableListOf("https://photos.app.goo.gl/OldAlbum1", "https://photos.app.goo.gl/OldAlbum2")
        val disabledAlbums = mutableSetOf<String>()
        val incomingUrls = listOf("https://photos.app.goo.gl/NewAlbum")

        // Overwrite logic: disable existing, add incoming, enable incoming
        for (u in existingAlbums) {
            if (u !in incomingUrls) {
                disabledAlbums.add(u)
            }
        }
        for (u in incomingUrls) {
            if (!existingAlbums.contains(u)) {
                existingAlbums.add(u)
            }
            disabledAlbums.remove(u)
        }

        // All 3 albums are still in the list (none deleted!)
        assertEquals(3, existingAlbums.size)
        assertTrue(existingAlbums.contains("https://photos.app.goo.gl/OldAlbum1"))
        assertTrue(existingAlbums.contains("https://photos.app.goo.gl/OldAlbum2"))
        assertTrue(existingAlbums.contains("https://photos.app.goo.gl/NewAlbum"))

        // Old albums are disabled
        assertTrue(disabledAlbums.contains("https://photos.app.goo.gl/OldAlbum1"))
        assertTrue(disabledAlbums.contains("https://photos.app.goo.gl/OldAlbum2"))

        // Only the new album is active for slideshow playback
        val activePlaying = existingAlbums.filter { it !in disabledAlbums }
        assertEquals(1, activePlaying.size)
        assertEquals("https://photos.app.goo.gl/NewAlbum", activePlaying[0])
    }

    @Test
    fun testEnableAllAlbumsCommandDetection() {
        val cmds = listOf("#enableallalbums", "#enableall", "#enablealbums", "  #enableallalbums  ")
        val isEnableMatch = { input: String ->
            val trimmed = input.trim()
            trimmed.equals("#enableallalbums", ignoreCase = true) ||
            trimmed.equals("#enableall", ignoreCase = true) ||
            trimmed.equals("#enablealbums", ignoreCase = true) ||
            trimmed.contains("#enableallalbums") ||
            trimmed.contains("#enableall")
        }
        for (cmd in cmds) {
            assertTrue("Expected $cmd to match enable all albums", isEnableMatch(cmd))
        }
        assertFalse(isEnableMatch("Happy Diwali"))
        assertFalse(isEnableMatch("#showcase:last_7_days"))
    }

    @Test
    fun testNoteWithShowcaseTagBothFiltersCoexist() {
        val input = "Happy Diwali #showcase:last_7_days"
        val trimmed = input.trim()

        val hashMatch = Regex("""#(?:showcase:(?:date:)?|date:|showcase:|location:)([A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE).find(trimmed)
            ?: Regex("""#(all|recent_trip|last_7_days|last_30_days|7days|30days)\b""", RegexOption.IGNORE_CASE).find(trimmed)

        assertTrue(hashMatch != null)
        val fullTag = hashMatch!!.groupValues[0]
        val tag = if (hashMatch.groupValues[1].isNotEmpty()) hashMatch.groupValues[1] else fullTag.removePrefix("#")
        val displayMsg = trimmed.replace(fullTag, "").trim()

        assertEquals("last_7_days", tag)
        assertEquals("Happy Diwali", displayMsg)
    }

    @Test
    fun testArbitraryHashtagDoesNotOverrideShowcaseFilter() {
        val input = "Happy Diwali #diwali #family"
        val trimmed = input.trim()

        val hashMatch = Regex("""#(?:showcase:(?:date:)?|date:|showcase:|location:)([A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE).find(trimmed)
            ?: Regex("""#(all|recent_trip|last_7_days|last_30_days|7days|30days)\b""", RegexOption.IGNORE_CASE).find(trimmed)

        // #diwali and #family should NOT match the showcase regex
        assertTrue(hashMatch == null)
    }
}
