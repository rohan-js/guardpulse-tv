package com.guardpulse.parentcontrol.tv.activity

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.service.media.MediaBrowserService

class MediaBrowserProbe(
    context: Context,
    private val onMetadata: (
        runtimePackage: String,
        title: String?,
        subtitle: String?,
        playbackState: String?,
        positionMs: Long?,
        durationMs: Long?
    ) -> Unit
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private var currentPackage: String? = null
    private var currentComponent: ComponentName? = null
    private var currentMetadata: MediaMetadata? = null
    private var currentState: PlaybackState? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            currentMetadata = metadata
            emit()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            currentState = state
            emit()
        }
    }

    fun connect(packageName: String) {
        if (packageName == currentPackage) return
        disconnect()
        val serviceInfo = packageManager.queryIntentServices(
            Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(packageName),
            0
        ).firstOrNull()?.serviceInfo ?: return
        val component = ComponentName(serviceInfo.packageName, serviceInfo.name)
        currentPackage = packageName
        currentComponent = component
        browser = MediaBrowser(
            appContext,
            component,
            object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val token = browser?.sessionToken ?: return
                    controller = MediaController(appContext, token).also {
                        it.registerCallback(controllerCallback)
                    }
                    currentMetadata = controller?.metadata
                    currentState = controller?.playbackState
                    emit()
                }

                override fun onConnectionFailed() {
                    disconnect()
                }

                override fun onConnectionSuspended() {
                    disconnect()
                }
            },
            null
        ).also { it.connect() }
    }

    fun disconnect() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
        currentMetadata = null
        currentState = null
        runCatching { browser?.disconnect() }
        browser = null
        currentPackage = null
        currentComponent = null
    }

    private fun emit() {
        val packageName = currentPackage ?: return
        val metadata = currentMetadata
        val state = currentState
        val playbackState = when (state?.state) {
            PlaybackState.STATE_PLAYING -> MediaObservation.PLAYBACK_PLAYING
            PlaybackState.STATE_PAUSED -> MediaObservation.PLAYBACK_PAUSED
            PlaybackState.STATE_BUFFERING -> MediaObservation.PLAYBACK_BUFFERING
            PlaybackState.STATE_STOPPED -> MediaObservation.PLAYBACK_STOPPED
            else -> MediaObservation.PLAYBACK_UNKNOWN
        }
        val position = state?.position?.takeIf { it >= 0L }
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L }
        onMetadata(
            packageName,
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            playbackState,
            position,
            duration
        )
    }
}
