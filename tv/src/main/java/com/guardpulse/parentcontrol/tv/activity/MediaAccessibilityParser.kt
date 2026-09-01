package com.guardpulse.parentcontrol.tv.activity

data class AccessibilityTextNode(
    val text: String,
    val viewId: String? = null
)

object MediaAccessibilityParser {
    private const val WINDOW_TITLE_VIEW_ID = "__window_title__"
    private val supportedPackages = setOf(
        "com.google.android.youtube.tv",
        "in.startv.hotstar"
    )
    private val timePattern = Regex("""(?<!\d)(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?!\d)""")
    private val ignoredExact = setOf(
        "play", "pause", "rewind", "fast forward", "next", "previous", "back",
        "settings", "subtitles", "closed captions", "more", "live", "skip", "replay"
    )
    private val titleIdHints = listOf("title", "video_name", "media_name", "player_title")
    private val subtitleIdHints = listOf("subtitle", "episode", "description", "secondary")

    fun parse(packageName: String, nodes: List<AccessibilityTextNode>): MediaObservation? {
        if (nodes.isEmpty()) return null
        val clean = nodes.mapNotNull { node ->
            sanitize(node.text)?.let { node.copy(text = it) }
        }.distinctBy { "${it.viewId}|${it.text}" }
        if (clean.isEmpty()) return null

        val joined = clean.joinToString(" ") { it.text }.lowercase()
        val playbackState = when {
            "buffering" in joined || "loading video" in joined -> MediaObservation.PLAYBACK_BUFFERING
            clean.any { it.text.equals("pause", true) || it.text.contains("pause video", true) } ->
                MediaObservation.PLAYBACK_PLAYING
            clean.any { it.text.equals("play", true) || it.text.contains("play video", true) } ->
                MediaObservation.PLAYBACK_PAUSED
            else -> MediaObservation.PLAYBACK_UNKNOWN
        }

        val times = clean.flatMap { node ->
            timePattern.findAll(node.text).mapNotNull { match -> parseTime(match.value) }.toList()
        }
        val position = times.getOrNull(times.size - 2)
        val duration = times.lastOrNull()?.takeIf { times.size >= 2 && it >= (position ?: 0L) }

        val windowTitleNode = clean.firstOrNull { node ->
            node.viewId == WINDOW_TITLE_VIEW_ID && isTitleCandidate(node.text)
        }
        val titleNode = clean.firstOrNull { node ->
            node.viewId?.lowercase()?.let { id -> titleIdHints.any(id::contains) } == true &&
                isTitleCandidate(node.text)
        } ?: windowTitleNode
        val packageSpecificTitle = if (packageName in supportedPackages) {
            clean.firstOrNull { node ->
                isTitleCandidate(node.text) &&
                    node.text.length in 3..120 &&
                    !timePattern.containsMatchIn(node.text)
            }
        } else {
            null
        }
        val title = titleNode?.text ?: packageSpecificTitle?.text
        val subtitle = clean.firstOrNull { node ->
            node.text != title &&
                node.viewId?.lowercase()?.let { id -> subtitleIdHints.any(id::contains) } == true &&
                isTitleCandidate(node.text)
        }?.text

        val confidence = when {
            titleNode != null && (duration != null || playbackState != MediaObservation.PLAYBACK_UNKNOWN) ->
                MediaObservation.CONFIDENCE_HIGH
            title != null || duration != null ->
                MediaObservation.CONFIDENCE_MEDIUM
            else -> MediaObservation.CONFIDENCE_LOW
        }
        val captureSource = when {
            titleNode == null && playbackState == MediaObservation.PLAYBACK_UNKNOWN && duration == null ->
                MediaObservation.SOURCE_ACCESSIBILITY
            windowTitleNode != null && titleNode == windowTitleNode && (duration != null || playbackState != MediaObservation.PLAYBACK_UNKNOWN) ->
                MediaObservation.SOURCE_COMBINED
            else -> MediaObservation.SOURCE_ACCESSIBILITY
        }
        val result = MediaObservation(
            title = title,
            subtitle = subtitle,
            playbackState = playbackState,
            positionMs = position,
            durationMs = duration,
            confidence = confidence,
            captureSource = captureSource
        )
        return result.takeIf {
            it.hasUsefulDetails() || playbackState != MediaObservation.PLAYBACK_UNKNOWN
        }
    }

    fun parseTime(value: String): Long? {
        val parts = value.trim().split(":").mapNotNull(String::toLongOrNull)
        if (parts.size !in 2..3 || parts.any { it < 0 } || parts.last() > 59) return null
        val seconds = if (parts.size == 3) {
            if (parts[1] > 59) return null
            parts[0] * 3600 + parts[1] * 60 + parts[2]
        } else {
            parts[0] * 60 + parts[1]
        }
        return seconds * 1_000L
    }

    private fun sanitize(value: String): String? {
        val clean = value.replace(Regex("""\s+"""), " ").trim().take(160)
        return clean.takeIf { it.isNotBlank() }
    }

    private fun isTitleCandidate(value: String): Boolean {
        val lower = value.lowercase()
        if (lower in ignoredExact) return false
        if (lower.startsWith("play ") || lower.startsWith("pause ")) return false
        if (lower.contains("minutes remaining") || lower.contains("hours remaining")) return false
        return value.any(Char::isLetter)
    }
}
