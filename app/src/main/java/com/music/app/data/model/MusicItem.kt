package com.music.app.data.model

sealed class MusicItem(
    open val id: String,
    open val title: String,
    open val subtitle: String,
    open val label: String,
    open val thumbnailUrl: String?
) : Comparable<MusicItem> {
    override fun compareTo(other: MusicItem): Int =
        title.compareTo(other.title, ignoreCase = true)

    data class YouTube(val song: Song) : MusicItem(
        id = song.id,
        title = song.title,
        subtitle = song.artists,
        label = song.durationText,
        thumbnailUrl = song.thumbnailUrl
    )

    data class Local(val file: LocalAudioFile, val localThumbnail: String? = null) : MusicItem(
        id = file.id,
        title = file.title,
        subtitle = file.artist,
        label = file.durationText,
        thumbnailUrl = localThumbnail
    )
}
