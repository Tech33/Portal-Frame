package com.portalhacks.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowcaseFilterTest {

    @Test
    fun testLocationShowcaseStrictExclusion() {
        val s1 = Slide(id = "https://photos/portugal_1", caption = "Belem Tower", location = "Lisbon, Portugal", timeMs = 1720000000000L)
        val s2 = Slide(id = "https://photos/portugal_2", caption = "Porto Wine Cellar", location = "Porto, Portugal", timeMs = 1720100000000L)
        val s3 = Slide(id = "https://photos/dublin_1", caption = "Temple Bar", location = "Dublin, Ireland", timeMs = 1725000000000L)
        val s4 = Slide(id = "https://photos/dublin_2", caption = "Ha'penny Bridge", location = "Dublin, Ireland", timeMs = 1725100000000L)
        val s5 = Slide(id = "https://photos/paris_1", caption = "Eiffel Tower", location = "Paris, France", timeMs = 1728000000000L)

        val allSlides = listOf(s1, s2, s3, s4, s5)

        // Broadcast signaled Portugal
        val portugalShowcase = SlideshowController.filterForShowcase(
            allSlides,
            mode = "location",
            locationQuery = "Our trip to Portugal! 🇵🇹"
        )

        // Only Portugal photos should be returned — no leakage of Dublin or Paris!
        assertEquals(2, portugalShowcase.size)
        assertTrue(portugalShowcase.contains(s1))
        assertTrue(portugalShowcase.contains(s2))
        assertFalse(portugalShowcase.contains(s3))
        assertFalse(portugalShowcase.contains(s4))
        assertFalse(portugalShowcase.contains(s5))

        // Broadcast signaled Dublin
        val dublinShowcase = SlideshowController.filterForShowcase(
            allSlides,
            mode = "location",
            locationQuery = "Dublin adventure"
        )

        assertEquals(2, dublinShowcase.size)
        assertTrue(dublinShowcase.contains(s3))
        assertTrue(dublinShowcase.contains(s4))
        assertFalse(dublinShowcase.contains(s1))
        assertFalse(dublinShowcase.contains(s2))
    }

    @Test
    fun testTripClusteringIncludesNearbyDates() {
        val oneDayMs = 86400000L
        val baseTripTime = 1720000000000L // e.g. Day 1 of Portugal trip

        // Photo 1: explicitly tagged Lisbon
        val s1 = Slide(id = "p1", caption = null, location = "Lisbon, Portugal", timeMs = baseTripTime)
        // Photo 2: taken next day on beach, NO location metadata
        val s2 = Slide(id = "p2", caption = null, location = null, timeMs = baseTripTime + oneDayMs)
        // Photo 3: taken 2 days later, explicitly tagged Porto
        val s3 = Slide(id = "p3", caption = "Porto Ribeira", location = "Porto, Portugal", timeMs = baseTripTime + 2 * oneDayMs)
        // Photo 4: completely different month in Dublin
        val s4 = Slide(id = "p4", caption = "Dublin Pub", location = "Dublin, Ireland", timeMs = baseTripTime + 60 * oneDayMs)

        val allSlides = listOf(s1, s2, s3, s4)

        val result = SlideshowController.filterForShowcase(
            allSlides,
            mode = "location",
            locationQuery = "Vacation in Lisbon"
        )

        // Trip clustering should include s1, s2, and s3
        assertEquals(3, result.size)
        assertTrue(result.contains(s1))
        assertTrue(result.contains(s2))
        assertTrue(result.contains(s3))
        // Must exclude Dublin photo
        assertFalse(result.contains(s4))
    }

    @Test
    fun testFallbackWhenNoLocationMatches() {
        val s1 = Slide(id = "p1", caption = "Beach", location = "California", timeMs = 1000L)
        val s2 = Slide(id = "p2", caption = "Mountains", location = "Colorado", timeMs = 2000L)
        val allSlides = listOf(s1, s2)

        // Search for a location that doesn't exist in the album
        val result = SlideshowController.filterForShowcase(
            allSlides,
            mode = "location",
            locationQuery = "Tokyo"
        )

        // Graceful fallback to all photos sorted by date descending
        assertEquals(2, result.size)
        assertEquals(s2, result[0]) // newer first
        assertEquals(s1, result[1])
    }
}
