package com.guardpulse.parentcontrol.tv.fallback

import com.guardpulse.parentcontrol.shared.PolicyConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsSectionDetectorTest {
    @Test
    fun topLevelSettingsRowsStayAllowedExceptApps() {
        val topLevel = """
            Settings
            General Settings
            Network & Internet
            Inputs
            Accounts & Sign In
            Apps
            Device Preferences
        """.trimIndent()

        assertNull(detect(focusedText = "Network & Internet", windowText = topLevel))
        assertNull(detect(focusedText = "Inputs", windowText = topLevel))
        assertNull(detect(focusedText = "Accounts & Sign In", windowText = topLevel))
        assertNull(detect(focusedText = "Device Preferences", windowText = topLevel))

        assertEquals(
            PolicyConstants.SETTINGS_APPS_PACKAGE,
            detect(focusedText = "Apps", windowText = topLevel)?.policyPackage
        )
    }

    @Test
    fun devicePreferencesMenuIsALockedSection() {
        val devicePreferences = """
            Device Preferences
            Home screen
            Google Assistant
            Chromecast built-in
            Screen saver
            Developer options
            Language
            Date & Time
            Keyboard
            Location
            Usage & Diagnostics
            Security & restrictions
            Accessibility
            TV lock
            Reset
        """.trimIndent()

        // The whole menu is a default-locked section now: Language / Date & Time
        // / Keyboard used to be a deliberate blind spot and were the escape path
        // for locale and clock tampering.
        assertEquals(
            PolicyConstants.SETTINGS_DEVICE_PREFERENCES_PACKAGE,
            detect(focusedText = "", windowText = devicePreferences)?.policyPackage
        )
        assertEquals(
            PolicyConstants.SETTINGS_DEVICE_PREFERENCES_PACKAGE,
            detect(focusedText = "Home screen", windowText = devicePreferences)?.policyPackage
        )
        assertEquals(
            PolicyConstants.SETTINGS_DEVICE_PREFERENCES_PACKAGE,
            detect(focusedText = "Language", windowText = devicePreferences)?.policyPackage
        )
        assertEquals(
            PolicyConstants.SETTINGS_DEVICE_PREFERENCES_PACKAGE,
            detect(focusedText = "Date & Time", windowText = devicePreferences)?.policyPackage
        )

        // Focused protected rows still attribute their own precise section.
        assertEquals(
            PolicyConstants.SETTINGS_DEVELOPER_OPTIONS_PACKAGE,
            detect(focusedText = "Developer options", windowText = devicePreferences)?.policyPackage
        )
        assertEquals(
            PolicyConstants.SETTINGS_SECURITY_RESTRICTIONS_PACKAGE,
            detect(focusedText = "Security & restrictions", windowText = devicePreferences)?.policyPackage
        )
        assertEquals(
            PolicyConstants.SETTINGS_ACCESSIBILITY_PACKAGE,
            detect(focusedText = "Accessibility", windowText = devicePreferences)?.policyPackage
        )
        assertEquals(
            PolicyConstants.SETTINGS_RESET_PACKAGE,
            detect(focusedText = "Reset", windowText = devicePreferences)?.policyPackage
        )
    }

    @Test
    fun subpagesLockOnlyRequestedAreas() {
        assertEquals(
            PolicyConstants.SETTINGS_APPS_PACKAGE,
            detect(windowText = "App info\nYouTube\nForce stop\nUninstall")?.policyPackage
        )
        assertEquals(
            PolicyConstants.SETTINGS_DEVELOPER_OPTIONS_PACKAGE,
            detect(windowText = "Developer options\nUSB debugging")?.policyPackage
        )
        assertEquals(
            PolicyConstants.SETTINGS_SECURITY_RESTRICTIONS_PACKAGE,
            detect(windowText = "Security & restrictions\nUnknown sources")?.policyPackage
        )
        assertEquals(
            PolicyConstants.SETTINGS_ACCESSIBILITY_PACKAGE,
            detect(windowText = "Accessibility\nDevice Support Service")?.policyPackage
        )
        assertEquals(
            PolicyConstants.SETTINGS_RESET_PACKAGE,
            detect(windowText = "Reset\nFactory data reset")?.policyPackage
        )

        assertNull(detect(windowText = "VPN\nGuardPulse Network Service"))
        assertNull(detect(windowText = "Usage access\nDevice Service"))
        assertNull(detect(windowText = "Permission manager\nCamera"))
    }

    private fun detect(
        packageName: String = "com.android.tv.settings",
        focusedText: String = "",
        eventText: String = "",
        windowText: String = ""
    ): ProtectedSettingsSection? {
        return SettingsSectionDetector.detectFromText(
            packageName = packageName,
            focusedText = focusedText,
            eventText = eventText,
            windowText = windowText
        )
    }
}
