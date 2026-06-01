package com.music.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity("songs")
data class Song(
    @PrimaryKey val id: String,
    val title: String,
    val artists: String,
    val durationText: String,
    val thumbnailUrl: String?,
    val albumName: String?,
    val albumId: String?,
    val downloadPath: String? = null
)
