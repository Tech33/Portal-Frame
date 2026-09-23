package com.portalhacks.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCapsuleConfigTest {

    @Test
    fun testDefaultsConfiguredCorrectly() {
        // Default style must be the new slim floating capsule docked at bottom
        assertEquals("capsule", ConfigReceiver.DEFAULT_NOW_PLAYING_STYLE)

        // Media widget default scale is 1.0f with min 0.6f and max 1.6f
        assertEquals(1.0f, ConfigReceiver.DEFAULT_MEDIA_WIDGET_SCALE, 0.001f)
        assertEquals(0.6f, ConfigReceiver.MIN_MEDIA_WIDGET_SCALE, 0.001f)
        assertEquals(1.6f, ConfigReceiver.MAX_MEDIA_WIDGET_SCALE, 0.001f)
    }

    @Test
    fun testScaleClamping() {
        val min = ConfigReceiver.MIN_MEDIA_WIDGET_SCALE
        val max = ConfigReceiver.MAX_MEDIA_WIDGET_SCALE

        // Values below min clamp to min
        assertEquals(min, 0.2f.coerceIn(min, max), 0.001f)
        assertEquals(min, 0.59f.coerceIn(min, max), 0.001f)

        // Normal values remain within bounds
        assertEquals(1.0f, 1.0f.coerceIn(min, max), 0.001f)
        assertEquals(1.25f, 1.25f.coerceIn(min, max), 0.001f)

        // Values above max clamp to max
        assertEquals(max, 1.65f.coerceIn(min, max), 0.001f)
        assertEquals(max, 3.0f.coerceIn(min, max), 0.001f)
    }
}
