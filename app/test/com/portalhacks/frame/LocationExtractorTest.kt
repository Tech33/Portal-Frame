package com.portalhacks.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationExtractorTest {

    @Test
    fun testExtractPortugalFromTripPhrase() {
        val loc = LocationExtractor.extractLocation("Our trip to Portugal! 🇵🇹")
        assertNotNull(loc)
        assertEquals("Portugal", loc!!.primary)
        assertTrue(loc.terms.contains("portugal"))
        assertTrue(loc.terms.contains("lisbon"))
        assertTrue(loc.terms.contains("porto"))
        assertTrue(loc.terms.contains("faro"))
    }

    @Test
    fun testExtractDublinFromPhrase() {
        val loc = LocationExtractor.extractLocation("Hello from Dublin! ☘️")
        assertNotNull(loc)
        assertEquals("Dublin", loc!!.primary)
        assertEquals("Ireland", loc.country)
        assertTrue(loc.terms.contains("dublin"))
        assertTrue(loc.terms.contains("ireland"))
    }

    @Test
    fun testExtractFromHashtags() {
        val loc1 = LocationExtractor.extractLocation("Special memory #showcase:portugal")
        assertNotNull(loc1)
        assertEquals("Portugal", loc1!!.primary)

        val loc2 = LocationExtractor.extractLocation("#showcase:dublin")
        assertNotNull(loc2)
        assertEquals("Dublin", loc2!!.primary)
    }

    @Test
    fun testNonLocationMessagesReturnNull() {
        assertNull(LocationExtractor.extractLocation("Good morning everyone!"))
        assertNull(LocationExtractor.extractLocation("Happy Birthday!"))
        assertNull(LocationExtractor.extractLocation(""))
        assertNull(LocationExtractor.extractLocation(null))
    }

    @Test
    fun testMatchesLocationAndCaption() {
        val loc = LocationExtractor.extractLocation("Our trip to Portugal!")!!

        val s1 = Slide(id = "local_1.jpg", caption = null, location = "Lisbon, Portugal")
        assertTrue(LocationExtractor.matches(s1, loc))

        val s2 = Slide(id = "local_2.jpg", caption = "Sunset over Porto bridge", location = null)
        assertTrue(LocationExtractor.matches(s2, loc))

        val dublinSlide = Slide(id = "local_3.jpg", caption = "Trinity College", location = "Dublin, Ireland")
        assertFalse(LocationExtractor.matches(dublinSlide, loc))
    }

    @Test
    fun testRemoteCdnUrlDoesNotMatchOnSubstring() {
        val portugalLoc = LocationExtractor.extractLocation("Our trip to Portugal!")!!

        // Remote Google CDN URL with "faro" or "porto" embedded in random base64 hash
        val cdnSlide = Slide(
            id = "https://lh3.googleusercontent.com/pw/AP1GczOaFArOm123xYz9",
            caption = null,
            location = "Dublin, Ireland"
        )
        // Must NOT match because id is a remote CDN URL and location is Dublin
        assertFalse(LocationExtractor.matches(cdnSlide, portugalLoc))
    }

    @Test
    fun testWordBoundaryPreventsSubstringCollisions() {
        val portugalLoc = LocationExtractor.extractLocation("Our trip to Portugal!")!!

        // "farmer" should not trigger "faro"
        val farmerSlide = Slide(
            id = "local.jpg",
            caption = "Visiting a local farmer in the countryside",
            location = "Iowa, USA"
        )
        assertFalse(LocationExtractor.matches(farmerSlide, portugalLoc))

        val irelandLoc = LocationExtractor.extractLocation("Visiting Dublin!")!!
        // "corkscrew" should not trigger "cork"
        val corkscrewSlide = Slide(
            id = "local.jpg",
            caption = "Opening wine with a corkscrew",
            location = "California, USA"
        )
        assertFalse(LocationExtractor.matches(corkscrewSlide, irelandLoc))
    }
}
