package com.guardpulse.parentcontrol.tv.activity

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.guardpulse.parentcontrol.tv.fallback.FallbackStateStore
import kotlin.math.abs

/**
 * Feeds active media-session metadata into [MediaSessionHub], which routes it
 * to the TvActivityTracker owned by the accessibility service. Notification
 * access makes this service a privileged session controller, so it can observe
 * sessions from apps that publish no MediaBrowserService.
 */
class MediaSessionListenerService : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var listenerRegistered = false
    private var pollScheduled = false
    private var lastEmitted: SessionMedia? = null
    private lateinit var fallbackStore: FallbackStateStore
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { _ ->
            runCatching { refreshSessions() }
        }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollScheduled = false
            refreshSessions()
        }
    }

    override fun onCreate() {
        super.onCreate()
        fallbackStore = FallbackStateStore(this)
        mediaSessionManager =
            getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    // getActiveSessions is only valid once the system reports this service as
    // connected, so both the listener registration and the first read happen
    // here rather than in onCreate.
    override fun onListenerConnected() {
        super.onListenerConnected()
        val manager = mediaSessionManager ?: return
        runCatching {
            // A null ComponentName listens for every active session.
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, null, mainHandler)
        }
        listenerRegistered = true
        refreshSessions()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (listenerRegistered) {
            mediaSessionManager?.let { manager ->
                runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
            }
            listenerRegistered = false
        }
        mediaSessionManager = null
        super.onDestroy()
    }

    /**
     * Single read path for the sessions-changed callback and the 2-second poll.
     * At most one observation is forwarded per tick, and the poll stays alive
     * only while some session is still playing.
     */
    private fun refreshSessions() {
        val manager = mediaSessionManager ?: return
        val controllers = runCatching { manager.getActiveSessions(null) }
            .getOrNull()
            .orEmpty()
        MediaSessionHub.setSessionPackages(controllers.map { it.packageName }.toSet())
        val observations = controllers.mapNotNull(::extractSessionMedia)
        pickObservation(observations)?.let(::emit)
        if (observations.any { it.playbackState == MediaObservation.PLAYBACK_PLAYING }) {
            schedulePoll()
        } else {
            cancelPoll()
        }
    }

    private fun extractSessionMedia(controller: MediaController): SessionMedia? {
        // Sessions die mid-read; a single guard covers the IPC-backed
        // metadata/playbackState getters and their fields.
        return runCatching {
            val metadata = controller.metadata
            val state = controller.playbackState
            SessionMedia(
                packageName = controller.packageName,
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                subtitle = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                playbackState = mapPlaybackState(state?.state),
                positionMs = state?.position?.takeIf { it >= 0L },
                durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L }
            )
        }.getOrNull()
    }

    private fun mapPlaybackState(state: Int?): String {
        return when (state) {
            PlaybackState.STATE_PLAYING -> MediaObservation.PLAYBACK_PLAYING
            PlaybackState.STATE_PAUSED -> MediaObservation.PLAYBACK_PAUSED
            PlaybackState.STATE_BUFFERING -> MediaObservation.PLAYBACK_BUFFERING
            PlaybackState.STATE_STOPPED -> MediaObservation.PLAYBACK_STOPPED
            else -> MediaObservation.PLAYBACK_UNKNOWN
        }
    }

    private fun pickObservation(observations: List<SessionMedia>): SessionMedia? {
        val foreground = runCatching { fallbackStore.lastForeground() }.getOrNull()
        if (foreground != null) {
            observations.firstOrNull { it.packageName == foreground }?.let { return it }
        }
        return observations.firstOrNull { it.playbackState == MediaObservation.PLAYBACK_PLAYING }
    }

    private fun emit(observation: SessionMedia) {
        val last = lastEmitted
        if (last != null && sameWithinPositionDelta(last, observation)) return
        lastEmitted = observation
        MediaSessionHub.emitSessionMedia(
            observation.packageName,
            observation.title,
            observation.subtitle,
            observation.playbackState,
            observation.positionMs,
            observation.durationMs
        )
    }

    /** Positions tick about once per second, so a repeat observation whose
     *  only movement is <1s of position is not worth forwarding. */
    private fun sameWithinPositionDelta(last: SessionMedia, next: SessionMedia): Boolean {
        if (last.copy(positionMs = null) != next.copy(positionMs = null)) return false
        val lastPosition = last.positionMs ?: return next.positionMs == null
        val nextPosition = next.positionMs ?: return false
        return abs(nextPosition - lastPosition) < POSITION_DELTA_MS
    }

    private fun schedulePoll() {
        if (pollScheduled) return
        pollScheduled = true
        mainHandler.postDelayed(pollRunnable, REFRESH_INTERVAL_MS)
    }

    private fun cancelPoll() {
        if (!pollScheduled) return
        pollScheduled = false
        mainHandler.removeCallbacks(pollRunnable)
    }

    private data class SessionMedia(
        val packageName: String,
        val title: String?,
        val subtitle: String?,
        val playbackState: String?,
        val positionMs: Long?,
        val durationMs: Long?
    )

    companion object {
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        private const val REFRESH_INTERVAL_MS = 2_000L
        private const val POSITION_DELTA_MS = 1_000L
    }
}
