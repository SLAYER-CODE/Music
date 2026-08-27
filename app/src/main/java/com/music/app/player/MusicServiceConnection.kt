package com.music.app.player

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.ExecutionException

data class MediaItemTriple(val uri: String, val title: String, val artist: String)

class MusicServiceConnection(private val context: Context) {

    private var mediaController: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var listener: Listener? = null

    interface Listener {
        fun onConnected(player: Player)
        fun onDisconnected()
    }

    fun connect(listener: Listener) {
        Log.d(TAG, "connect: attempting to connect to MusicService")
        this.listener = listener

        // Cancel any in-flight connection attempt
        controllerFuture?.let { future ->
            if (!future.isDone) {
                future.cancel(true)
                Log.d(TAG, "connect: cancelled previous in-flight future")
            }
        }

        // Release previous controller before creating a new one
        mediaController?.let { old ->
            old.release()
            Log.d(TAG, "connect: released previous controller")
        }
        mediaController = null
        controllerFuture = null

        val sessionToken = SessionToken(
            context,
            ComponentName(context, MusicService::class.java)
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val controller = future.get()
                mediaController = controller
                listener.onConnected(controller)
                Log.d(TAG, "connect: connected successfully")
            } catch (e: ExecutionException) {
                Log.e(TAG, "connect: failed to get controller", e)
                // If cancelled, don't notify — it's expected
                if (future.isCancelled) return@addListener
            } catch (e: InterruptedException) {
                Log.e(TAG, "connect: interrupted", e)
            }
        }, MoreExecutors.directExecutor())
    }

    fun playList(tracks: List<MediaItemTriple>, startIndex: Int) {
        Log.d(TAG, "playList: startIndex=$startIndex total=${tracks.size}")
        mediaController?.let { controller ->
            val mediaItems = tracks.map { (uri, title, artist) ->
                MediaItem.Builder()
                    .setMediaId(uri)
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(title)
                            .setArtist(artist)
                            .build()
                    )
                    .build()
            }
            controller.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
            controller.prepare()
            controller.play()
        }
    }

    fun togglePlayPause() {
        Log.d(TAG, "togglePlayPause")
        mediaController?.let { c ->
            if (c.playWhenReady) c.pause() else c.play()
        }
    }

    fun seekTo(positionMs: Long) {
        Log.d(TAG, "seekTo: $positionMs ms")
        mediaController?.seekTo(positionMs)
    }

    fun skipNext() {
        Log.d(TAG, "skipNext")
        mediaController?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        Log.d(TAG, "skipPrevious")
        mediaController?.seekToPreviousMediaItem()
    }

    fun replaceTimelineEntry(oldMediaId: String, new: MediaItemTriple) {
        mediaController?.let { c ->
            val idx = (0 until c.mediaItemCount).firstOrNull {
                c.getMediaItemAt(it).mediaId == oldMediaId
            }
            if (idx == null) {
                Log.w(TAG, "replaceTimelineEntry: $oldMediaId not found in timeline")
                return
            }
            val curIdx = c.currentMediaItemIndex
            val curPos = c.currentPosition.coerceAtLeast(0L)
            val wasPlaying = c.playWhenReady
            val newItem = MediaItem.Builder()
                .setMediaId(new.uri)
                .setUri(new.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(new.title).setArtist(new.artist).build()
                )
                .build()
            val items = (0 until c.mediaItemCount).map { i ->
                if (i == idx) newItem else c.getMediaItemAt(i)
            }
            c.setMediaItems(items, curIdx, if (curPos > 0L) curPos else androidx.media3.common.C.TIME_UNSET)
            c.prepare()
            if (wasPlaying) c.play()
            Log.d(TAG, "replaceTimelineEntry: $oldMediaId -> ${new.uri}")
        }
    }

    fun setShuffleModeEnabled(enabled: Boolean) {
        Log.d(TAG, "setShuffleModeEnabled: $enabled")
        mediaController?.shuffleModeEnabled = enabled
    }

    fun setRepeatMode(mode: Int) {
        Log.d(TAG, "setRepeatMode: $mode")
        mediaController?.repeatMode = mode
    }

    val currentMediaItemIndex: Int
        get() = mediaController?.currentMediaItemIndex ?: 0

    fun disconnect() {
        Log.d(TAG, "disconnect: releasing controller")
        mediaController?.run {
            release()
            mediaController = null
        }
        listener?.onDisconnected()
    }

    companion object {
        private const val TAG = "MusicSvcConn"
    }
}
