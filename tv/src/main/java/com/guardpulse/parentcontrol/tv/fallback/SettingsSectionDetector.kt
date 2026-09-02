package com.guardpulse.parentcontrol.tv.fallback

import android.view.accessibility.AccessibilityNodeInfo
import com.guardpulse.parentcontrol.shared.PolicyConstants

data class ProtectedSettingsSection(
    val key: String,
    val label: String,
    val policyPackage: String
)

object SettingsSectionDetector {
    private val appSubpagePhrases = listOf(
        "manage apps",
        "see all apps",
        "app info",
        "all apps",
        "recently opened apps"
    )

    private val allowedDevicePreferenceRows = listOf(
        "home screen",
        "google assistant",
        "chromecast built-in",
        "screen saver",
        "location",
        "usage & diagnostics",
        "tv lock"
    )

    private val protectedDevicePreferenceRows = mapOf(
        "developer options" to PolicyConstants.SETTINGS_DEVELOPER_OPTIONS_PACKAGE,
        "security & restrictions" to PolicyConstants.SETTINGS_SECURITY_RESTRICTIONS_PACKAGE,
        "accessibility" to PolicyConstants.SETTINGS_ACCESSIBILITY_PACKAGE,
        "reset" to PolicyConstants.SETTINGS_RESET_PACKAGE
    )

    // Precompiled once: containsStandalonePhrase sits on the per-event hot path and
    // recompiling the pattern per call was pure waste. Unknown phrases fall back to
    // on-demand compilation.
    private val standalonePatterns: Map<String, Regex> =
        (protectedDevicePreferenceRows.keys + "apps").associateWith { phrase ->
            standaloneRegex(phrase)
        }

    fun detect(
        packageName: String,
        eventClassName: CharSequence?,
        eventText: List<CharSequence>,
        root: AccessibilityNodeInfo?
    ): ProtectedSettingsSection? {
        if (packageName !in PolicyConstants.primarySettingsPackages) return null

        val focusedText = root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?.let { node -> nodeText(node) }
            .orEmpty()
        val windowText = buildString {
            root?.let { collectText(it, this, 0, NodeBudget(MAX_NODES)) }
        }
        val rawInput = buildString {
            append(eventClassName?.toString().orEmpty())
            append('\n')
            eventText.forEach {
                append(it)
                append('\n')
            }
            append(focusedText)
            append('\n')
            append(windowText)
        }

        val input = rawInput.lowercase()
        if (input.isBlank()) return null

        return detectFromText(
            packageName = packageName,
            focusedText = focusedText,
            eventText = eventText.joinToString("\n"),
            windowText = windowText
        )
    }

    internal fun detectFromText(
        packageName: String,
        focusedText: String,
        eventText: String,
        windowText: String
    ): ProtectedSettingsSection? {
        if (packageName !in PolicyConstants.primarySettingsPackages) return null

        val focusedInput = focusedText.lowercase()
        val eventInput = eventText.lowercase()
        val activeInput = "$focusedInput\n$eventInput"
        val windowInput = windowText.lowercase()
        val allInput = "$activeInput\n$windowInput"

        detectFocusedTopLevelApps(activeInput)?.let { return it }
        detectAppSubpage(allInput, windowInput)?.let { return it }
        detectDevicePreferenceSection(activeInput, windowInput)?.let { return it }
        return null
    }

    private fun detectFocusedTopLevelApps(input: String): ProtectedSettingsSection? {
        return if (containsStandalonePhrase(input, "apps")) {
            sectionFor(PolicyConstants.SETTINGS_APPS_PACKAGE)
        } else {
            null
        }
    }

    private fun detectAppSubpage(input: String, windowInput: String): ProtectedSettingsSection? {
        if (isTopLevelSettingsHome(windowInput)) return null
        return if (appSubpagePhrases.any { it in input }) {
            sectionFor(PolicyConstants.SETTINGS_APPS_PACKAGE)
        } else {
            null
        }
    }

    private fun detectDevicePreferenceSection(
        activeInput: String,
        windowInput: String
    ): ProtectedSettingsSection? {
        protectedDevicePreferenceRows.forEach { (rowText, packageName) ->
            if (containsStandalonePhrase(activeInput, rowText)) {
                return sectionFor(packageName)
            }
        }
        if (isDevicePreferencesMenu(windowInput)) return null
        protectedDevicePreferenceRows.forEach { (rowText, packageName) ->
            if (rowText in windowInput) {
                return sectionFor(packageName)
            }
        }
        return null
    }

    private fun sectionFor(packageName: String): ProtectedSettingsSection? {
        val section = PolicyConstants.settingsSectionPolicy(packageName) ?: return null
        return ProtectedSettingsSection(
            key = section.key,
            label = section.shortLabel,
            policyPackage = section.packageName
        )
    }

    private fun containsStandalonePhrase(input: String, phrase: String): Boolean {
        return (standalonePatterns[phrase] ?: standaloneRegex(phrase)).containsMatchIn(input)
    }

    private fun standaloneRegex(phrase: String): Regex =
        Regex("""(^|[^a-z0-9&])${Regex.escape(phrase)}([^a-z0-9&]|$)""")

    private fun isTopLevelSettingsHome(input: String): Boolean {
        val hasGeneralSettingsHeader = "general settings" in input
        val hasPrimaryNavigationRows = "network & internet" in input &&
            "inputs" in input &&
            "accounts & sign in" in input &&
            "device preferences" in input
        return hasGeneralSettingsHeader || hasPrimaryNavigationRows
    }

    private fun isDevicePreferencesMenu(input: String): Boolean {
        if ("device preferences" !in input) return false
        val visibleRows = allowedDevicePreferenceRows.count { it in input } +
            protectedDevicePreferenceRows.keys.count { it in input }
        return visibleRows >= 3
    }

    private fun collectText(
        node: AccessibilityNodeInfo,
        output: StringBuilder,
        depth: Int,
        budget: NodeBudget
    ) {
        if (depth > MAX_DEPTH || !budget.consume()) return
        nodeText(node).takeIf { it.isNotBlank() }?.let {
            output.append(it)
            output.append('\n')
        }
        for (index in 0 until node.childCount.coerceAtMost(MAX_CHILDREN)) {
            node.getChild(index)?.let { child ->
                collectText(child, output, depth + 1, budget)
            }
            if (budget.exhausted) break
        }
    }

    private class NodeBudget(private val maximum: Int) {
        private var visited = 0
        val exhausted: Boolean get() = visited >= maximum

        fun consume(): Boolean {
            if (exhausted) return false
            visited += 1
            return true
        }
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        return buildString {
            node.text?.let {
                append(it)
                append('\n')
            }
            node.contentDescription?.let {
                append(it)
                append('\n')
            }
            node.viewIdResourceName?.let {
                append(it)
                append('\n')
            }
            node.className?.let {
                append(it)
                append('\n')
            }
        }
    }

    private const val MAX_DEPTH = 8
    private const val MAX_CHILDREN = 80
    private const val MAX_NODES = 500
}
