package com.portalhacks.frame

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalAccessibilityServiceTest {

    @Test
    fun testIsFramePackage_identifiesFrameAndVariants() {
        assertTrue(PortalAccessibilityService.isFramePackage("com.portalhacks.frame"))
        assertTrue(PortalAccessibilityService.isFramePackage("com.portalhacks.frame.debug"))
        assertTrue(PortalAccessibilityService.isFramePackage("COM.PORTALHACKS.FRAME"))
        assertTrue(PortalAccessibilityService.isFramePackage("some.portalhacks.other"))

        assertFalse(PortalAccessibilityService.isFramePackage("com.android.packageinstaller"))
        assertFalse(PortalAccessibilityService.isFramePackage("com.google.android.packageinstaller"))
        assertFalse(PortalAccessibilityService.isFramePackage("com.android.settings"))
        assertFalse(PortalAccessibilityService.isFramePackage(null))
        assertFalse(PortalAccessibilityService.isFramePackage(""))
    }

    @Test
    fun testIsTargetInstallerPackage_acceptsInstallersAndBlocksFrame() {
        // Must reject Frame packages even if somehow named installer
        assertFalse(PortalAccessibilityService.isTargetInstallerPackage("com.portalhacks.frame"))
        assertFalse(PortalAccessibilityService.isTargetInstallerPackage("com.portalhacks.frame.installer"))

        // Standard Android package installers
        assertTrue(PortalAccessibilityService.isTargetInstallerPackage("com.android.packageinstaller"))
        assertTrue(PortalAccessibilityService.isTargetInstallerPackage("com.google.android.packageinstaller"))
        assertTrue(PortalAccessibilityService.isTargetInstallerPackage("com.google.android.packageinstaller.v2"))

        // Settings (for unknown app sources toggle)
        assertTrue(PortalAccessibilityService.isTargetInstallerPackage("com.android.settings"))
        assertTrue(PortalAccessibilityService.isTargetInstallerPackage("com.google.android.settings"))

        // Unrelated apps must be rejected
        assertFalse(PortalAccessibilityService.isTargetInstallerPackage("com.spotify.music"))
        assertFalse(PortalAccessibilityService.isTargetInstallerPackage("com.google.android.youtube"))
        assertFalse(PortalAccessibilityService.isTargetInstallerPackage(null))
        assertFalse(PortalAccessibilityService.isTargetInstallerPackage(""))
    }

    @Test
    fun testIsTargetSettingsPackage_acceptsSettingsAndBlocksFrame() {
        assertFalse(PortalAccessibilityService.isTargetSettingsPackage("com.portalhacks.frame"))
        assertFalse(PortalAccessibilityService.isTargetSettingsPackage("com.portalhacks.frame.settings"))

        assertTrue(PortalAccessibilityService.isTargetSettingsPackage("com.android.settings"))
        assertTrue(PortalAccessibilityService.isTargetSettingsPackage("com.google.android.settings"))

        assertFalse(PortalAccessibilityService.isTargetSettingsPackage("com.android.packageinstaller"))
        assertFalse(PortalAccessibilityService.isTargetSettingsPackage(null))
        assertFalse(PortalAccessibilityService.isTargetSettingsPackage(""))
    }
}
