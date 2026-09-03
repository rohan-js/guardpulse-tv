package com.guardpulse.parentcontrol.tv.activity

/**
 * Process-wide bridge between [com.guardpulse.parentcontrol.tv.activity.MediaSessionListenerService]
 * (the NotificationListenerService component) and the TvActivityTracker owned by the
 * accessibility service. Both components share one process but must not each hold a
 * tracker instance — the snapshot cache would diverge — so the service emits into this
 * hub and the accessibility service registers a listener that routes into the tracker.
 */
object MediaSessionHub {
    interface Listener {
        fun onSessionMedia(
            runtimePackage: String,
            title: String?,
            subtitle: String?,
            playbackState: String?,
            positionMs: Long?,
            durationMs: Long?
        )
    }

    @Volatile
    private var listener: Listener? = null

    /** Packages that currently hold an active media session (title-capture evidence). */
    @Volatile
    var sessionPackages: Set<String> = emptySet()
        private set

    fun setListener(value: Listener?) {
        listener = value
    }

    fun emitSessionMedia(
        runtimePackage: String,
        title: String?,
        subtitle: String?,
        playbackState: String?,
        positionMs: Long?,
        durationMs: Long?
    ) {
        listener?.onSessionMedia(runtimePackage, title, subtitle, playbackState, positionMs, durationMs)
    }

    fun setSessionPackages(packages: Set<String>) {
        sessionPackages = packages
    }
}
