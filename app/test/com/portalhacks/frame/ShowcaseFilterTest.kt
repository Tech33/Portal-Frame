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

        // Search for a location that doesn't exist in the album in location mode
        val result = SlideshowController.filterForShowcase(
            allSlides,
            mode = "location",
            locationQuery = "Tokyo"
        )

        // Must return empty list so photos from unrelated locations never leak
        assertEquals(0, result.size)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testDateRangeParser() {
        val r1 = DateRangeParser.parse("2024-09")
        org.junit.Assert.assertNotNull(r1)
        assertEquals("September 2024", r1?.label)

        val r2 = DateRangeParser.parse("#showcase:2024-09")
        org.junit.Assert.assertNotNull(r2)
        assertEquals("September 2024", r2?.label)

        val r3 = DateRangeParser.parse("2024-09-01..2024-09-15")
        org.junit.Assert.assertNotNull(r3)
        assertEquals("2024-09-01 to 2024-09-15", r3?.label)

        val r4 = DateRangeParser.parse("September 2024")
        org.junit.Assert.assertNotNull(r4)
        assertEquals("September 2024", r4?.label)

        val r5 = DateRangeParser.parse("2024")
        org.junit.Assert.assertNotNull(r5)
        assertEquals("2024", r5?.label)

        val r6 = DateRangeParser.parse("Happy Birthday John!")
        org.junit.Assert.assertNull(r6)
    }

    @Test
    fun testDateRangeShowcaseFiltering() {
        // Create dates:
        // 2021-08-28 ~ 1630108800000L
        // 2024-09-07 ~ 1725667200000L
        // 2026-09-07 ~ 1788739200000L
        val s2021 = Slide(id = "old", caption = "Old photo", location = null, timeMs = 1630108800000L)
        val s2024Sep = Slide(id = "target", caption = "Sep 2024 trip", location = null, timeMs = 1725667200000L)
        val s2026Sep = Slide(id = "future", caption = "Future photo", location = null, timeMs = 1788739200000L)

        val slides = listOf(s2021, s2024Sep, s2026Sep)

        val filtered = SlideshowController.filterForShowcase(
            slides,
            mode = "location",
            locationQuery = "Portugal trip #showcase:2024-09"
        )

        assertEquals(1, filtered.size)
        assertEquals("target", filtered[0].id)
    }

    @Test
    fun testSortByCaptureDescending() {
        val s1 = Slide(id = "s1", caption = null, location = null, timeMs = 1000L)
        val s2 = Slide(id = "s2", caption = null, location = null, timeMs = 5000L)
        val s3 = Slide(id = "s3", caption = null, location = null, timeMs = 3000L)

        val sorted = SlideshowController.sortByCaptureDescending(listOf(s1, s2, s3))
        assertEquals("s2", sorted[0].id)
        assertEquals("s3", sorted[1].id)
        assertEquals("s1", sorted[2].id)
    }

    @Test
    fun testCryptoUtilsKeyDerivation() {
        assertEquals("PortalGlobal2026", CryptoUtils.deriveAesKey("portal_broadcast"))
        assertEquals("PortalGlobal2026", CryptoUtils.deriveAesKey(null))
        assertEquals("PortalGlobal2026", CryptoUtils.deriveAesKey(""))

        val kakkarKey = CryptoUtils.deriveAesKey("kakkar")
        assertEquals(16, kakkarKey.length)
        assertEquals(kakkarKey, CryptoUtils.deriveAesKey("kakkar")) // Deterministic
        assertFalse(kakkarKey == "PortalGlobal2026")
    }

    @Test
    fun testDefaultShowcaseMode() {
        assertEquals("recent_trip", ConfigReceiver.DEFAULT_SHOWCASE_MODE)
        assertTrue(ConfigReceiver.DEFAULT_RECENT_FIRST)
    }

    @Test
    fun testExactSingleDateParsing() {
        val r1 = DateRangeParser.parse("2024-06-14")
        org.junit.Assert.assertNotNull(r1)
        assertTrue(r1!!.isSingleDate)
        assertEquals("June 14, 2024", r1.label)

        val r2 = DateRangeParser.parse("June 14, 2018")
        org.junit.Assert.assertNotNull(r2)
        assertTrue(r2!!.isSingleDate)
        assertEquals("June 14, 2018", r2.label)

        val r3 = DateRangeParser.parse("14 June 2024")
        org.junit.Assert.assertNotNull(r3)
        assertTrue(r3!!.isSingleDate)
        assertEquals("June 14, 2024", r3.label)

        val rAnniversary = DateRangeParser.parse("07-04")
        org.junit.Assert.assertNotNull(rAnniversary)
        assertTrue(rAnniversary!!.isRecurring)
        assertEquals(7, rAnniversary.recurringMonth)
        assertEquals(4, rAnniversary.recurringDay)
        assertEquals("July 4 (Every Year)", rAnniversary.label)
    }

    @Test
    fun testExactSingleDateShowcaseFiltering() {
        // UTC milliseconds for 2024-06-14 12:00:00 UTC = 1718366400000L
        // UTC milliseconds for 2024-06-15 12:00:00 UTC = 1718452800000L
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            set(2024, java.util.Calendar.JUNE, 14, 15, 30, 0)
        }
        val sTarget = Slide(id = "june14", caption = "Birthday party", location = null, timeMs = cal.timeInMillis)

        cal.set(2024, java.util.Calendar.JUNE, 15, 10, 0, 0)
        val sNextDay = Slide(id = "june15", caption = "Next day brunch", location = null, timeMs = cal.timeInMillis)

        val slides = listOf(sTarget, sNextDay)
        val filtered = SlideshowController.filterForShowcase(
            slides,
            mode = "date_range",
            locationQuery = "2024-06-14"
        )

        assertEquals(1, filtered.size)
        assertEquals("june14", filtered[0].id)
    }

    @Test
    fun testRecurringAnniversaryFiltering() {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(2020, java.util.Calendar.JULY, 4, 12, 0, 0)
        val s2020 = Slide(id = "y2020", caption = "4th July 2020", location = null, timeMs = cal.timeInMillis)

        cal.set(2023, java.util.Calendar.JULY, 4, 18, 0, 0)
        val s2023 = Slide(id = "y2023", caption = "4th July 2023", location = null, timeMs = cal.timeInMillis)

        cal.set(2023, java.util.Calendar.JULY, 5, 12, 0, 0)
        val sDiffDay = Slide(id = "diff", caption = "5th July", location = null, timeMs = cal.timeInMillis)

        val slides = listOf(s2020, s2023, sDiffDay)
        val filtered = SlideshowController.filterForShowcase(
            slides,
            mode = "date_range",
            locationQuery = "07-04"
        )

        assertEquals(2, filtered.size)
        assertTrue(filtered.contains(s2020))
        assertTrue(filtered.contains(s2023))
        assertFalse(filtered.contains(sDiffDay))
    }

    @Test
    fun testChimeStyleAndBlurDefaults() {
        assertEquals("zen_bowl", ConfigReceiver.DEFAULT_CHIME_STYLE)
        assertEquals(24, ConfigReceiver.DEFAULT_NOW_PLAYING_BLUR)
    }
}
