package com.guardpulse.parentcontrol.tv.activity

/**
 * Decides whether the accessibility node-tree walk is worth running for a
 * window event, purely for media-title capture. Three evidence signals:
 * a known video player package, an active media session for the package, or
 * on-screen timecodes (m:ss / h:mm:ss) in the event text.
 */
object MediaTitlePolicy {
    val knownPlayers: Set<String> = setOf(
        "com.google.android.youtube.tv",
        "in.startv.hotstar",
        "com.stremio.one",
        "org.smarttube.stable",
        "com.nuvio.app",
        "com.google.android.youtube"
    )

    private val timecodePattern = Regex("""(?<!\d)(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?!\d)""")

    fun shouldWalkNodes(packageName: String, eventText: List<CharSequence>, sessionPackages: Set<String>): Boolean {
        if (packageName in knownPlayers) return true
        if (packageName in sessionPackages) return true
        return eventText.any { timecodePattern.containsMatchIn(it.toString()) }
    }

    fun looksLikeVideoEvidence(text: String): Boolean = timecodePattern.containsMatchIn(text)
}
