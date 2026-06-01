package com.music.app.player

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MusicService : MediaSessionService(), KoinComponent {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private lateinit var audioHandler: AudioHandler
    private val resolvingFactory: ResolvingDataSource.Factory by inject()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: starting MusicService")

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        try {
            val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(
                this,
                resolvingFactory
            )

            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(dataSourceFactory, DefaultExtractorsFactory())
                )
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
            Log.d(TAG, "onCreate: ExoPlayer created")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: ExoPlayer creation failed", e)
        }

        if (player == null) {
            Log.e(TAG, "onCreate: player is null, cannot register AudioHandler")
            // Don't crash — service will be non-functional but won't loop-crash
        } else {
            audioHandler = AudioHandler(this, player!!)
            audioHandler.register()
            Log.d(TAG, "onCreate: AudioHandler registered")
        }

        val sessionIntent = packageManager?.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        } ?: Intent(this, MusicService::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, sessionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val p = player
        if (p != null) {
            try {
                mediaSession = MediaSession.Builder(this, p)
                    .setSessionActivity(pendingIntent)
                    .build()
                Log.d(TAG, "onCreate: MediaSession created")
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: MediaSession creation failed", e)
            }
        } else {
            Log.e(TAG, "onCreate: player is null, cannot create MediaSession")
        }

        try {
            DefaultMediaNotificationProvider(this)
                .apply { setSmallIcon(android.R.drawable.ic_media_play) }
                .also(::setMediaNotificationProvider)
            Log.d(TAG, "onCreate: MediaNotificationProvider set")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: MediaNotificationProvider failed", e)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        Log.d(TAG, "onGetSession: package=${controllerInfo.packageName}")
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved")
        val p = player ?: return
        if (!p.playWhenReady || p.mediaItemCount == 0) {
            Log.d(TAG, "onTaskRemoved: stopping self")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: cleaning up")
        audioHandler.unregister()
        mediaSession?.let { session ->
            session.release()
            Log.d(TAG, "onDestroy: MediaSession released")
        }
        player?.let { p ->
            p.release()
            Log.d(TAG, "onDestroy: player released")
        }
        mediaSession = null
        player = null
        super.onDestroy()
        Log.d(TAG, "onDestroy: done")
    }

    companion object {
        private const val TAG = "MusicService"
    }
}
