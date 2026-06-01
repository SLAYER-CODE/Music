package com.music.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity("cached_songs")
data class CachedLocalSong(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val uri: String,
    val size: Long,
    val dateAdded: Long
) {
    fun toLocalAudioFile() = LocalAudioFile(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        uri = uri,
        size = size,
        dateAdded = dateAdded
    )
}

fun LocalAudioFile.toCachedEntity() = CachedLocalSong(
    id = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    uri = uri,
    size = size,
    dateAdded = dateAdded
)
