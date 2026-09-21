package com.portalhacks.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SonosPlaybackFilterTest {

    @Before
    fun setup() {
        MediaMonitor.clear()
    }

    @Test
    fun testTvAndAuxStandbyUrisFiltered() {
        // TV eARC / Optical streams report PLAYING 24/7 on Sonos soundbars even when TV is off/silent
        assertTrue(SonosMonitor.isTvOrAuxUri("x-sonos-htastream:RINCON_000E58D12345:spdif"))
        assertTrue(SonosMonitor.isTvOrAuxUri("x-sonos-htastream:RINCON_000E58D12345"))
        assertTrue(SonosMonitor.isTvOrAuxUri("x-sonos-vli:RINCON_000E58D12345"))
        assertTrue(SonosMonitor.isTvOrAuxUri("x-rincon-stream:RINCON_000E58D12345"))
        assertTrue(SonosMonitor.isTvOrAuxUri("x-rincon-audiodoc:RINCON_000E58D12345"))
        assertTrue(SonosMonitor.isTvOrAuxUri("x-sonos-http:linein:RINCON_000E58D12345"))
        assertTrue(SonosMonitor.isTvOrAuxUri("netbios:192.168.1.1"))

        // Legitimate music tracks must NOT be flagged as TV/Aux
        assertFalse(SonosMonitor.isTvOrAuxUri("x-sonos-spotify:spotify%3atrack%3a2b8OXOxoebV4U15iA9y5L4"))
        assertFalse(SonosMonitor.isTvOrAuxUri("x-sonosapi-stream:s12345?sid=254"))
        assertFalse(SonosMonitor.isTvOrAuxUri("http://192.168.1.100:1400/music/track1.flac"))
        assertFalse(SonosMonitor.isTvOrAuxUri("aac://icecast.media.stream/live"))
    }

    @Test
    fun testDummyTitlesFiltered() {
        // Dummy / placeholder / TV titles that should never produce a Now Playing card
        assertTrue(SonosMonitor.isDummyTitle(""))
        assertTrue(SonosMonitor.isDummyTitle("   "))
        assertTrue(SonosMonitor.isDummyTitle("TV"))
        assertTrue(SonosMonitor.isDummyTitle("tv"))
        assertTrue(SonosMonitor.isDummyTitle("Audio In"))
        assertTrue(SonosMonitor.isDummyTitle("Line-in"))
        assertTrue(SonosMonitor.isDummyTitle("Line In"))
        assertTrue(SonosMonitor.isDummyTitle("NOT_IMPLEMENTED"))
        assertTrue(SonosMonitor.isDummyTitle("Playing on Sonos"))
        assertTrue(SonosMonitor.isDummyTitle("Sonos"))
        assertTrue(SonosMonitor.isDummyTitle("Silence"))

        // Genuine music titles must be accepted
        assertFalse(SonosMonitor.isDummyTitle("Hotel California"))
        assertFalse(SonosMonitor.isDummyTitle("Bohemian Rhapsody"))
        assertFalse(SonosMonitor.isDummyTitle("Chill Beats Radio"))
    }

    @Test
    fun testMediaMonitorPreventsGhostSonosState() {
        // Attempt to publish dummy or blank Sonos title with isPlaying = true
        MediaMonitor.update(
            isPlaying = true,
            title = "Playing on Sonos",
            artist = "",
            source = "Sonos"
        )
        // MediaMonitor must suppress isPlaying and clear title for dummy Sonos
        assertFalse(MediaMonitor.currentState.isPlaying)
        assertEquals("", MediaMonitor.currentState.title)

        // Attempt to publish blank title with isPlaying = true
        MediaMonitor.update(
            isPlaying = true,
            title = "",
            artist = "",
            source = "Sonos"
        )
        assertFalse(MediaMonitor.currentState.isPlaying)
        assertEquals("", MediaMonitor.currentState.title)

        // Attempt to publish TV title
        MediaMonitor.update(
            isPlaying = true,
            title = "TV",
            artist = "",
            source = "Sonos"
        )
        assertFalse(MediaMonitor.currentState.isPlaying)
        assertEquals("", MediaMonitor.currentState.title)

        // Publish genuine music track
        MediaMonitor.update(
            isPlaying = true,
            title = "Hotel California",
            artist = "Eagles",
            source = "Sonos",
            deviceName = "Living Room Sonos"
        )
        assertTrue(MediaMonitor.currentState.isPlaying)
        assertEquals("Hotel California", MediaMonitor.currentState.title)
        assertEquals("Eagles", MediaMonitor.currentState.artist)

        // Clear when playback stops
        MediaMonitor.clear()
        assertFalse(MediaMonitor.currentState.isPlaying)
        assertEquals("", MediaMonitor.currentState.title)
    }
}
