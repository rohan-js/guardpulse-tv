package com.guardpulse.parentcontrol.tv.activity

data class MediaObservation(
    val title: String? = null,
    val subtitle: String? = null,
    val playbackState: String = PLAYBACK_UNKNOWN,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val confidence: String = CONFIDENCE_LOW,
    val captureSource: String = SOURCE_ACCESSIBILITY
) {
    fun hasUsefulDetails(): Boolean {
        return !title.isNullOrBlank() || positionMs != null || durationMs != null
    }

    companion object {
        const val PLAYBACK_PLAYING = "playing"
        const val PLAYBACK_PAUSED = "paused"
        const val PLAYBACK_BUFFERING = "buffering"
        const val PLAYBACK_STOPPED = "stopped"
        const val PLAYBACK_UNKNOWN = "unknown"

        const val CONFIDENCE_HIGH = "high"
        const val CONFIDENCE_MEDIUM = "medium"
        const val CONFIDENCE_LOW = "low"

        const val SOURCE_ACCESSIBILITY = "accessibility"
        const val SOURCE_AUDIO = "audio"
        const val SOURCE_MEDIA_BROWSER = "mediaBrowser"
        const val SOURCE_COMBINED = "combined"
    }
}

data class ActivitySnapshot(
    val runtimePackage: String,
    val packageName: String,
    val appLabel: String,
    val appStartedAt: Long,
    val overlayState: String,
    val mediaTitle: String? = null,
    val mediaSubtitle: String? = null,
    val playbackState: String = MediaObservation.PLAYBACK_UNKNOWN,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val positionCapturedAt: Long? = null,
    val playbackSpeed: Float = 0f,
    val mediaStartedAt: Long? = null,
    val mediaConfidence: String? = null,
    val captureSource: String = MediaObservation.SOURCE_ACCESSIBILITY,
    val overlayStartedAt: Long? = null,
    val overlayMs: Long = 0L,
    val updatedAt: Long
) {
    fun toFirebaseMap(): Map<String, Any?> = mapOf(
        "runtimePackage" to runtimePackage,
        "packageName" to packageName,
        "appLabel" to appLabel,
        "appStartedAt" to appStartedAt,
        "overlayState" to overlayState,
        "mediaAvailable" to (!mediaTitle.isNullOrBlank() || positionMs != null || durationMs != null),
        "mediaTitle" to mediaTitle,
        "mediaSubtitle" to mediaSubtitle,
        "playbackState" to playbackState,
        "positionMs" to positionMs,
        "durationMs" to durationMs,
        "positionCapturedAt" to positionCapturedAt,
        "playbackSpeed" to playbackSpeed,
        "mediaStartedAt" to mediaStartedAt,
        "mediaConfidence" to mediaConfidence,
        "captureSource" to captureSource,
        "overlayStartedAt" to overlayStartedAt,
        "overlayMs" to overlayMs,
        "updatedAt" to updatedAt
    )

    companion object {
        const val OVERLAY_NONE = "none"
        const val OVERLAY_LOCKED = "locked"
    }
}

data class ActivityHistoryRecord(
    val id: String,
    val type: String,
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val subtitle: String?,
    val startedAt: Long,
    val endedAt: Long,
    val lastPositionMs: Long?,
    val durationMs: Long?,
    val playbackState: String?,
    val confidence: String?,
    val captureSource: String = MediaObservation.SOURCE_ACCESSIBILITY,
    val overlayMs: Long = 0L
) {
    fun toFirebaseMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to type,
        "packageName" to packageName,
        "appLabel" to appLabel,
        "title" to title,
        "subtitle" to subtitle,
        "startedAt" to startedAt,
        "endedAt" to endedAt,
        "lastPositionMs" to lastPositionMs,
        "durationMs" to durationMs,
        "playbackState" to playbackState,
        "confidence" to confidence,
        "captureSource" to captureSource,
        "overlayMs" to overlayMs,
        "updatedAt" to endedAt
    )

    companion object {
        const val TYPE_APP = "app"
        const val TYPE_MEDIA = "media"
    }
}
