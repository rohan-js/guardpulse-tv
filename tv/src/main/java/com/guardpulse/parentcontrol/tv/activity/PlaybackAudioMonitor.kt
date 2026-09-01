package com.guardpulse.parentcontrol.tv.activity

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper

class PlaybackAudioMonitor(
    context: Context,
    private val onPlaybackChanged: (Boolean) -> Unit
) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var lastPlaying = false

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            val playing = configs.any { config ->
                config.audioAttributes?.usage == AudioAttributes.USAGE_MEDIA
            }
            if (!started || playing == lastPlaying) return
            lastPlaying = playing
            onPlaybackChanged(playing)
        }
    }

    fun start() {
        if (started) return
        started = true
        runCatching {
            audioManager?.registerAudioPlaybackCallback(callback, handler)
        }
        refreshFromCurrentState()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching {
            audioManager?.unregisterAudioPlaybackCallback(callback)
        }
    }

    fun refresh() {
        if (!started) return
        refreshFromCurrentState()
    }

    private fun refreshFromCurrentState() {
        val playing = audioManager
            ?.activePlaybackConfigurations
            ?.any { config ->
                config.audioAttributes?.usage == AudioAttributes.USAGE_MEDIA
            } == true
        lastPlaying = playing
        onPlaybackChanged(playing)
    }
}
