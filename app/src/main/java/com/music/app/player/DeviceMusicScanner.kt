package com.music.app.player

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.music.app.data.local.CachedLocalSongDao
import com.music.app.data.model.LocalAudioFile
import com.music.app.data.model.toCachedEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class DeviceMusicScanner(
    private val context: Context,
    private val cachedDao: CachedLocalSongDao
) {

    private val _deviceSongs = MutableStateFlow<List<LocalAudioFile>>(emptyList())

    val deviceSongs: StateFlow<List<LocalAudioFile>> = _deviceSongs.asStateFlow()

    suspend fun scan() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "scan: starting scan")

            // Quick-load cached songs from Room so the UI is populated ASAP
            try {
                val cached = cachedDao.getAllSongs().map { it.toLocalAudioFile() }
                if (cached.isNotEmpty()) {
                    _deviceSongs.value = cached
                    Log.d(TAG, "scan: quick-loaded ${cached.size} cached songs")
                }
            } catch (e: Exception) {
                Log.e(TAG, "scan: failed to load cached songs", e)
            }

            try {
                val songs = mutableListOf<LocalAudioFile>()
                val contentResolver: ContentResolver = context.contentResolver
                val collection: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED
                )

                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 10000"
                val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

                val cursor: Cursor? = contentResolver.query(
                    collection,
                    projection,
                    selection,
                    null,
                    sortOrder
                )

                cursor?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                    val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                    val count = c.count
                    Log.d(TAG, "scan: found $count audio files")

                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val title = c.getString(titleCol) ?: "Unknown"
                        val artist = c.getString(artistCol) ?: "Unknown Artist"
                        val album = if (albumCol >= 0) c.getString(albumCol) else null
                        val duration = c.getLong(durationCol)
                        val size = c.getLong(sizeCol)
                        val dateAdded = c.getLong(dateCol)

                        val uri = Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id.toString()
                        ).toString()

                        if (duration > 10000) {
                            songs.add(
                                LocalAudioFile(
                                    id = uri,
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    durationMs = duration,
                                    uri = uri,
                                    size = size,
                                    dateAdded = dateAdded
                                )
                            )
                        }
                    }
                } ?: Log.w(TAG, "scan: cursor is null (no permission?)")

                Log.d(TAG, "scan: loaded ${songs.size} songs")

                try {
                    cachedDao.deleteAll()
                    cachedDao.insertAll(songs.map { it.toCachedEntity() })
                    Log.d(TAG, "scan: saved ${songs.size} songs to Room cache")
                } catch (e: Exception) {
                    Log.e(TAG, "scan: failed to save cache", e)
                }

                _deviceSongs.value = songs
            } catch (e: SecurityException) {
                Log.e(TAG, "scan: permission denied", e)
                _deviceSongs.value = emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "scan: failed", e)
                _deviceSongs.value = emptyList()
            }
        }
    }

    suspend fun fileExists(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "fileExists: check failed for $uri", e)
            false
        }
    }

    fun prepareDeleteIntent(uri: String): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            MediaStore.createDeleteRequest(
                context.contentResolver,
                listOf(Uri.parse(uri))
            ).intentSender
        } catch (e: Exception) {
            Log.e(TAG, "prepareDeleteIntent: failed", e)
            null
        }
    }

    suspend fun deleteDirectly(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val rows = context.contentResolver.delete(Uri.parse(uri), null, null)
            (rows > 0).also { success ->
                if (success) Log.d(TAG, "deleteDirectly: deleted $uri")
                else Log.w(TAG, "deleteDirectly: no rows deleted for $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteDirectly: failed for $uri", e)
            false
        }
    }

    suspend fun removeFromCacheAndList(uri: String) {
        withContext(Dispatchers.IO) {
            try {
                cachedDao.deleteById(uri)
                Log.d(TAG, "removeFromCacheAndList: removed $uri from Room")
            } catch (e: Exception) {
                Log.e(TAG, "removeFromCacheAndList: failed to remove from Room", e)
            }
            _deviceSongs.value = _deviceSongs.value.filter { it.uri != uri }
        }
    }

    companion object {
        private const val TAG = "DeviceMusicScanner"
    }
}
