package com.guardpulse.parentcontrol.tv.activity

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.tv.fallback.LockActivity
import java.util.UUID

class TvActivityTracker(private val context: Context) {
    private val store = ActivityStore(context)
    private var pendingMediaSignature: String? = null
    private var pendingMediaCount = 0

    // Events arrive at high frequency on the accessibility thread; re-parsing
    // the JSON prefs snapshot per event is pure waste when nothing changed.
    // The in-memory mirror is written on every persist and reloaded after a
    // process restart.
    @Volatile
    private var memCurrent: ActivitySnapshot? = null

    private fun loadCurrent(): ActivitySnapshot? {
        memCurrent?.let { return it }
        return store.current()?.also { memCurrent = it }
    }

    private fun persistCurrent(snapshot: ActivitySnapshot) {
        memCurrent = snapshot
        store.saveCurrent(snapshot)
    }

    fun observe(
        runtimePackage: String,
        eventClassName: CharSequence?,
        eventText: List<CharSequence>,
        windowTitles: List<CharSequence>,
        root: AccessibilityNodeInfo?
    ): Boolean {
        val now = System.currentTimeMillis()
        val current = loadCurrent()
        if (runtimePackage == "com.android.systemui") return false
        if (runtimePackage == context.packageName) {
            if (current == null) return false
            if (current.overlayState == ActivitySnapshot.OVERLAY_LOCKED) return false
            persistCurrent(
                current.copy(
                    overlayState = ActivitySnapshot.OVERLAY_LOCKED,
                    overlayStartedAt = now,
                    updatedAt = now
                )
            )
            return true
        }

        val policyPackage = PolicyConstants.sourceLockPolicyPackage(runtimePackage) ?: runtimePackage
        val label = appLabel(policyPackage)
        val appChanged = current == null || current.packageName != policyPackage
        var snapshot = if (appChanged) {
            current?.let { closeCurrentSessions(it, now) }
            pendingMediaSignature = null
            pendingMediaCount = 0
            ActivitySnapshot(
                runtimePackage = runtimePackage,
                packageName = policyPackage,
                appLabel = label,
                appStartedAt = now,
                overlayState = ActivitySnapshot.OVERLAY_NONE,
                updatedAt = now
            )
        } else {
            requireNotNull(current).withOverlayClosed(now).copy(
                runtimePackage = runtimePackage,
                updatedAt = now
            )
        }

        val nodes = collectNodes(root, eventText, windowTitles)
        val media = MediaAccessibilityParser.parse(runtimePackage, nodes)
        if (media != null) {
            val signature = listOf(media.title, media.subtitle, media.durationMs).joinToString("|")
            if (signature == pendingMediaSignature) {
                pendingMediaCount++
            } else {
                pendingMediaSignature = signature
                pendingMediaCount = 1
            }
            val stable = pendingMediaCount >= 2 ||
                media.durationMs != null ||
                media.confidence == MediaObservation.CONFIDENCE_HIGH
            if (stable) {
                if (snapshot.mediaTitle != null &&
                    media.title != null &&
                    snapshot.mediaTitle != media.title
                ) {
                    closeMediaSession(snapshot, now)
                    snapshot = snapshot.clearMedia()
                }
                val capturedPosition = media.positionMs ?: snapshot.estimatedPosition(now)
                snapshot = snapshot.copy(
                    mediaTitle = media.title ?: snapshot.mediaTitle,
                    mediaSubtitle = media.subtitle ?: snapshot.mediaSubtitle,
                    playbackState = media.playbackState.takeUnless {
                        it == MediaObservation.PLAYBACK_UNKNOWN
                    } ?: snapshot.playbackState,
                    positionMs = capturedPosition,
                    durationMs = media.durationMs ?: snapshot.durationMs,
                    positionCapturedAt = capturedPosition?.let { now },
                    playbackSpeed = if (media.playbackState == MediaObservation.PLAYBACK_PLAYING) 1f else 0f,
                    mediaStartedAt = snapshot.mediaStartedAt ?: now,
                    mediaConfidence = strongerConfidence(snapshot.mediaConfidence, media.confidence),
                    captureSource = combineCaptureSources(snapshot.captureSource, media.captureSource),
                    updatedAt = now
                )
            }
        }

        val changed = snapshot != current
        if (changed) persistCurrent(snapshot)
        return changed
    }

    fun current(): ActivitySnapshot? = loadCurrent()

    fun observeAudioPlayback(runtimePackage: String, isPlaying: Boolean): Boolean {
        val current = loadCurrent() ?: return false
        val policyPackage = PolicyConstants.sourceLockPolicyPackage(runtimePackage) ?: runtimePackage
        if (current.packageName != policyPackage) return false
        val nextPlaybackState = if (isPlaying) MediaObservation.PLAYBACK_PLAYING else MediaObservation.PLAYBACK_PAUSED
        val nextSource = combineCaptureSources(current.captureSource, MediaObservation.SOURCE_AUDIO)
        val nextSpeed = if (isPlaying) 1f else 0f
        val now = System.currentTimeMillis()
        if (current.playbackState == nextPlaybackState &&
            current.captureSource == nextSource &&
            current.playbackSpeed == nextSpeed
        ) {
            return false
        }
        persistCurrent(
            current.copy(
                playbackState = nextPlaybackState,
                playbackSpeed = nextSpeed,
                captureSource = nextSource,
                updatedAt = now
            )
        )
        return true
    }

    fun observeMediaBrowser(
        runtimePackage: String,
        title: String?,
        subtitle: String?,
        playbackState: String?,
        positionMs: Long?,
        durationMs: Long?
    ): Boolean {
        val now = System.currentTimeMillis()
        val current = loadCurrent() ?: return false
        val policyPackage = PolicyConstants.sourceLockPolicyPackage(runtimePackage) ?: runtimePackage
        if (current.packageName != policyPackage) return false
        val nextTitle = title?.takeIf(String::isNotBlank) ?: current.mediaTitle
        val nextSubtitle = subtitle?.takeIf(String::isNotBlank) ?: current.mediaSubtitle
        val nextState = playbackState?.takeIf { it != MediaObservation.PLAYBACK_UNKNOWN } ?: current.playbackState
        val nextPosition = positionMs ?: current.positionMs
        val nextDuration = durationMs ?: current.durationMs
        val nextSpeed = if (nextState == MediaObservation.PLAYBACK_PLAYING) 1f else 0f
        val nextSource = combineCaptureSources(current.captureSource, MediaObservation.SOURCE_MEDIA_BROWSER)
        val changed = current.mediaTitle != nextTitle ||
            current.mediaSubtitle != nextSubtitle ||
            current.playbackState != nextState ||
            current.positionMs != nextPosition ||
            current.durationMs != nextDuration ||
            current.playbackSpeed != nextSpeed ||
            current.captureSource != nextSource
        if (!changed) return false
        persistCurrent(
            current.copy(
                mediaTitle = nextTitle,
                mediaSubtitle = nextSubtitle,
                playbackState = nextState,
                positionMs = nextPosition,
                durationMs = nextDuration,
                playbackSpeed = nextSpeed,
                captureSource = nextSource,
                mediaStartedAt = current.mediaStartedAt ?: now,
                mediaConfidence = strongerConfidence(current.mediaConfidence, MediaObservation.CONFIDENCE_HIGH),
                updatedAt = now
            )
        )
        return true
    }

    fun observeMediaSession(
        runtimePackage: String,
        title: String?,
        subtitle: String?,
        playbackState: String?,
        positionMs: Long?,
        durationMs: Long?
    ): Boolean {
        val now = System.currentTimeMillis()
        val current = loadCurrent() ?: return false
        val policyPackage = PolicyConstants.sourceLockPolicyPackage(runtimePackage) ?: runtimePackage
        if (current.packageName != policyPackage) return false
        val nextTitle = title?.takeIf(String::isNotBlank) ?: current.mediaTitle
        val nextSubtitle = subtitle?.takeIf(String::isNotBlank) ?: current.mediaSubtitle
        val nextState = playbackState?.takeIf { it != MediaObservation.PLAYBACK_UNKNOWN } ?: current.playbackState
        val nextPosition = positionMs ?: current.positionMs
        val nextDuration = durationMs ?: current.durationMs
        val nextSpeed = if (nextState == MediaObservation.PLAYBACK_PLAYING) 1f else 0f
        val nextSource = combineCaptureSources(current.captureSource, MediaObservation.SOURCE_MEDIA_SESSION)
        val changed = current.mediaTitle != nextTitle ||
            current.mediaSubtitle != nextSubtitle ||
            current.playbackState != nextState ||
            current.positionMs != nextPosition ||
            current.durationMs != nextDuration ||
            current.playbackSpeed != nextSpeed ||
            current.captureSource != nextSource
        if (!changed) return false
        persistCurrent(
            current.copy(
                mediaTitle = nextTitle,
                mediaSubtitle = nextSubtitle,
                playbackState = nextState,
                positionMs = nextPosition,
                durationMs = nextDuration,
                playbackSpeed = nextSpeed,
                captureSource = nextSource,
                mediaStartedAt = current.mediaStartedAt ?: now,
                mediaConfidence = strongerConfidence(current.mediaConfidence, MediaObservation.CONFIDENCE_HIGH),
                updatedAt = now
            )
        )
        return true
    }

    fun refreshCurrentForUpload(now: Long = System.currentTimeMillis()) {
        val current = loadCurrent() ?: return
        persistCurrent(
            current.copy(
                positionMs = current.estimatedPosition(now),
                positionCapturedAt = current.positionMs?.let { now },
                updatedAt = now
            )
        )
    }

    fun observePackageOnly(runtimePackage: String): Boolean {
        if (runtimePackage == context.packageName || runtimePackage == "com.android.systemui") return false
        return observe(runtimePackage, null, emptyList(), emptyList(), null)
    }

    fun pendingHistory(): List<ActivityHistoryRecord> = store.pendingHistory()

    fun markUploaded(id: String) = store.markUploaded(id)

    fun pruneBefore(cutoff: Long) = store.pruneBefore(cutoff)

    private fun closeCurrentSessions(snapshot: ActivitySnapshot, endedAt: Long) {
        val closed = snapshot.withOverlayClosed(endedAt)
        closeMediaSession(closed, endedAt)
        if (endedAt - closed.appStartedAt >= MIN_SESSION_MS) {
            store.addHistory(
                ActivityHistoryRecord(
                    id = UUID.randomUUID().toString(),
                    type = ActivityHistoryRecord.TYPE_APP,
                    packageName = closed.packageName,
                    appLabel = closed.appLabel,
                    title = null,
                    subtitle = null,
                    startedAt = closed.appStartedAt,
                    endedAt = endedAt,
                    lastPositionMs = null,
                    durationMs = null,
                    playbackState = null,
                    confidence = null,
                    captureSource = closed.captureSource,
                    overlayMs = closed.overlayMs
                )
            )
        }
    }

    /** Returns the snapshot with any in-progress lock-overlay period folded into
     *  the accumulated overlayMs — the phone renders this as a lock marker. */
    private fun ActivitySnapshot.withOverlayClosed(now: Long): ActivitySnapshot {
        if (overlayState != ActivitySnapshot.OVERLAY_LOCKED) return this
        val startedAt = overlayStartedAt
            ?: return copy(overlayState = ActivitySnapshot.OVERLAY_NONE)
        return copy(
            overlayState = ActivitySnapshot.OVERLAY_NONE,
            overlayMs = overlayMs + (now - startedAt).coerceAtLeast(0L),
            overlayStartedAt = null
        )
    }

    private fun closeMediaSession(snapshot: ActivitySnapshot, endedAt: Long) {
        val mediaStartedAt = snapshot.mediaStartedAt ?: return
        if (snapshot.mediaTitle.isNullOrBlank() ||
            snapshot.mediaConfidence == MediaObservation.CONFIDENCE_LOW ||
            endedAt - mediaStartedAt < MIN_MEDIA_SESSION_MS
        ) {
            return
        }
        store.addHistory(
            ActivityHistoryRecord(
                id = UUID.randomUUID().toString(),
                type = ActivityHistoryRecord.TYPE_MEDIA,
                packageName = snapshot.packageName,
                appLabel = snapshot.appLabel,
                title = snapshot.mediaTitle,
                subtitle = snapshot.mediaSubtitle,
                startedAt = mediaStartedAt,
                endedAt = endedAt,
                lastPositionMs = snapshot.estimatedPosition(endedAt),
                durationMs = snapshot.durationMs,
                playbackState = snapshot.playbackState,
                confidence = snapshot.mediaConfidence,
                captureSource = snapshot.captureSource,
                overlayMs = snapshot.overlayMs
            )
        )
    }

    private fun ActivitySnapshot.clearMedia() = copy(
        mediaTitle = null,
        mediaSubtitle = null,
        playbackState = MediaObservation.PLAYBACK_UNKNOWN,
        positionMs = null,
        durationMs = null,
        positionCapturedAt = null,
        playbackSpeed = 0f,
        mediaStartedAt = null,
        mediaConfidence = null
    )

    private fun ActivitySnapshot.estimatedPosition(now: Long): Long? {
        val base = positionMs ?: return null
        val capturedAt = positionCapturedAt ?: return base
        val estimate = if (playbackState == MediaObservation.PLAYBACK_PLAYING) {
            base + ((now - capturedAt).coerceAtLeast(0L) * playbackSpeed).toLong()
        } else {
            base
        }
        return durationMs?.let { estimate.coerceAtMost(it) } ?: estimate
    }

    private fun appLabel(packageName: String): String {
        if (packageName in PolicyConstants.sourceLockPackages) return "Live TV"
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun collectNodes(
        root: AccessibilityNodeInfo?,
        eventText: List<CharSequence>,
        windowTitles: List<CharSequence>
    ): List<AccessibilityTextNode> {
        val output = mutableListOf<AccessibilityTextNode>()
        windowTitles.forEach { title ->
            title.toString().takeIf(String::isNotBlank)?.let {
                output += AccessibilityTextNode(it, WINDOW_TITLE_VIEW_ID)
            }
        }
        eventText.forEach { text ->
            text.toString().takeIf(String::isNotBlank)?.let {
                output += AccessibilityTextNode(it)
            }
        }
        if (root != null) collectNode(root, output, 0)
        return output.take(MAX_NODES)
    }

    private fun collectNode(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityTextNode>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH || output.size >= MAX_NODES) return
        node.text?.toString()?.takeIf(String::isNotBlank)?.let {
            output += AccessibilityTextNode(it, node.viewIdResourceName)
        }
        // Content-descriptions often carry the media title where the text is a
        // detail line (Nuvio: title in content-desc, "2h 1m" in text). Tag them
        // with a dedicated marker so the parser can prefer them as title
        // candidates without confusing plain text with view IDs.
        node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let {
            if (it != node.text?.toString()) {
                output += AccessibilityTextNode(it, CONTENT_DESCRIPTION_VIEW_ID)
            }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                collectNode(child, output, depth + 1)
                child.recycle()
            }
        }
    }

    private fun strongerConfidence(current: String?, next: String): String {
        val rank = mapOf(
            MediaObservation.CONFIDENCE_LOW to 0,
            MediaObservation.CONFIDENCE_MEDIUM to 1,
            MediaObservation.CONFIDENCE_HIGH to 2
        )
        return if ((rank[next] ?: 0) >= (rank[current] ?: -1)) next else current.orEmpty()
    }

    private fun combineCaptureSources(current: String, next: String): String {
        if (current == next) return current
        val parts = linkedSetOf<String>()
        current.split('+').filter(String::isNotBlank).forEach(parts::add)
        next.split('+').filter(String::isNotBlank).forEach(parts::add)
        return if (parts.size == 1) parts.first() else MediaObservation.SOURCE_COMBINED
    }

    companion object {
        private const val MAX_DEPTH = 18
        private const val MAX_NODES = 180
        private const val MIN_SESSION_MS = 2_000L
        private const val MIN_MEDIA_SESSION_MS = 3_000L
        private const val WINDOW_TITLE_VIEW_ID = "__window_title__"

        /** Marker viewId for content-description nodes in title selection. */
        const val CONTENT_DESCRIPTION_VIEW_ID = "__content_desc__"
    }
}
