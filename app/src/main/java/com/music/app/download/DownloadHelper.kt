package com.music.app.download

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

@UnstableApi
interface DownloadHelper {

    companion object {
        const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "download_channel"
    }

    val downloadManager: DownloadManager
    val downloads: MutableStateFlow<Map<String, Download>>

    fun getDownload(songId: String): Flow<Download?>

    fun addDownload(songId: String, title: String)

    fun removeDownload(songId: String)
}
