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
    private var lastPersistAt = 0L

    // PackageManager IPC per accessibility event was pure waste: labels only
    // matter when the app changes, and they never change during a session.
    private val labelCache = HashMap<String, String>()

    // Read caches: loadCurrent() sits on the accessibility hot path (every
    // event), so re-parsing the JSON prefs snapshot per event is pure waste
    // when nothing changed. The in-memory mirror is written on every persist
    // and reloaded after a process restart.
    @Volatile
    private var memCurrent: ActivitySnapshot? = null

    private fun loadCurrent(): ActivitySnapshot? {
        memCurrent?.let { return it }
        return store.current()?.also { memCurrent = it }
    }

    private fun persistCurrent(snapshot: ActivitySnapshot) {
        memCurrent = snapshot
        lastPersistAt = System.currentTimeMillis()
        store.saveCurrent(snapshot)
    }

    /**
     * Persist only on meaningful change. A position-only advance is NOT a
     * meaningful change: the parent extrapolates the playhead from
     * positionCapturedAt + playbackSpeed, so position rides the heartbeat.
     * The heartbeat keeps updatedAt (the parent's 90s staleness clock) fresh
     * even while a video plays with an otherwise static snapshot.
     */
    private fun persistIfMeaningful(
        snapshot: ActivitySnapshot,
        current: ActivitySnapshot?,
        now: Long
    ): Boolean {
        val realChange = snapshot != current
        val onlyPosition = realChange && current != null &&
            snapshot.copy(
                positionMs = current.positionMs,
                positionCapturedAt = current.positionCapturedAt
            ) == current
        memCurrent = snapshot
        if ((realChange && !onlyPosition) || now - lastPersistAt >= SNAPSHOT_PERSIST_HEARTBEAT_MS) {
            persistCurrent(snapshot.copy(updatedAt = now))
            return true
        }
        return false
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
        val appChanged = current == null || current.packageName != policyPackage
        var snapshot = if (appChanged) {
            current?.let { closeCurrentSessions(it, now) }
            pendingMediaSignature = null
            pendingMediaCount = 0
            ActivitySnapshot(
                runtimePackage = runtimePackage,
                packageName = policyPackage,
                appLabel = appLabel(policyPackage),
                appStartedAt = now,
                overlayState = ActivitySnapshot.OVERLAY_NONE,
                updatedAt = now
            )
        } else {
            // No updatedAt bump here: persistIfMeaningful decides when the
            // snapshot is worth a disk write (per-event bumps made EVERY
            // accessibility event a SharedPreferences write during playback).
            requireNotNull(current).withOverlayClosed(now).copy(
                runtimePackage = runtimePackage
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
                    captureSource = combineCaptureSources(snapshot.captureSource, media.captureSource)
                )
            }
        }

        return persistIfMeaningful(snapshot, current, now)
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
        // A genuinely new title starts a new media session — same rule as the
        // accessibility path, so an autoplaying playlist does not become one
        // giant session that carries the first episode's start time.
        var base = current
        if (base.mediaTitle != null && nextTitle != null && base.mediaTitle != nextTitle) {
            closeMediaSession(base, now)
            base = base.clearMedia()
        }
        val changed = base.mediaTitle != nextTitle ||
            base.mediaSubtitle != nextSubtitle ||
            base.playbackState != nextState ||
            base.positionMs != nextPosition ||
            base.durationMs != nextDuration ||
            base.playbackSpeed != nextSpeed ||
            base.captureSource != nextSource
        if (!changed) return false
        return persistIfMeaningful(
            base.copy(
                mediaTitle = nextTitle,
                mediaSubtitle = nextSubtitle,
                playbackState = nextState,
                positionMs = nextPosition,
                durationMs = nextDuration,
                playbackSpeed = nextSpeed,
                captureSource = nextSource,
                mediaStartedAt = base.mediaStartedAt ?: now,
                mediaConfidence = strongerConfidence(base.mediaConfidence, MediaObservation.CONFIDENCE_HIGH)
            ),
            current,
            now
        )
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
        var base = current
        if (base.mediaTitle != null && nextTitle != null && base.mediaTitle != nextTitle) {
            closeMediaSession(base, now)
            base = base.clearMedia()
        }
        val changed = base.mediaTitle != nextTitle ||
            base.mediaSubtitle != nextSubtitle ||
            base.playbackState != nextState ||
            base.positionMs != nextPosition ||
            base.durationMs != nextDuration ||
            base.playbackSpeed != nextSpeed ||
            base.captureSource != nextSource
        if (!changed) return false
        return persistIfMeaningful(
            base.copy(
                mediaTitle = nextTitle,
                mediaSubtitle = nextSubtitle,
                playbackState = nextState,
                positionMs = nextPosition,
                durationMs = nextDuration,
                playbackSpeed = nextSpeed,
                captureSource = nextSource,
                mediaStartedAt = base.mediaStartedAt ?: now,
                mediaConfidence = strongerConfidence(base.mediaConfidence, MediaObservation.CONFIDENCE_HIGH)
            ),
            current,
            now
        )
    }

    fun observePackageOnly(runtimePackage: String): Boolean {
        // Own-package events must reach observe(): the PIN wall being foreground
        // is exactly how overlayState=locked gets recorded (and folded back into
        // overlayMs when the wall leaves). Only transient systemui windows skip.
        if (runtimePackage == "com.android.systemui") return false
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

    private fun appLabel(packageName: String): String =
        labelCache.getOrPut(packageName) { computeAppLabel(packageName) }

    private fun computeAppLabel(packageName: String): String {
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

        // The parent Activity tab treats updatedAt older than 90s as stale, so
        // a static-but-foreground snapshot must still refresh its clock well
        // inside that window — 15s, instead of the old every-event write.
        private const val SNAPSHOT_PERSIST_HEARTBEAT_MS = 15_000L

        /** Marker viewId for content-description nodes in title selection. */
        const val CONTENT_DESCRIPTION_VIEW_ID = "__content_desc__"
    }
}
