package com.music.app.download

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

@UnstableApi
class DownloadHelperImpl(
    private val dataSourceFactory: DataSource.Factory,
    private val context: Context,
    private val downloadCache: Cache
) : DownloadHelper {

    private val executor = Executors.newCachedThreadPool()

    override val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    override val downloadManager: DownloadManager by lazy {
        Log.d(TAG, "downloadManager lazy init: creating DownloadManager")
        val manager = DownloadManager(
            context,
            StandaloneDatabaseProvider(context),
            downloadCache,
            dataSourceFactory,
            executor
        )
        manager.maxParallelDownloads = 3
        manager.minRetryCount = 2
        manager.requirements = Requirements(Requirements.NETWORK)
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                Log.d(TAG, "onDownloadChanged: id=${download.request.id} state=${download.state} finalException=$finalException")
                syncDownloads(download)
            }

            override fun onDownloadRemoved(
                downloadManager: DownloadManager,
                download: Download
            ) {
                Log.d(TAG, "onDownloadRemoved: id=${download.request.id}")
                syncDownloads(download)
            }
        })
        Log.d(TAG, "downloadManager lazy init: created")
        manager
    }

    private var downloadNotificationHelper: DownloadNotificationHelper? = null

    init {
        Log.d(TAG, "init: loading existing downloads from index (async)")
        executor.execute {
            try {
                val results = mutableMapOf<String, Download>()
                val cursor = downloadManager.downloadIndex.getDownloads()
                while (cursor.moveToNext()) {
                    results[cursor.download.request.id] = cursor.download
                    Log.d(TAG, "init: found existing download id=${cursor.download.request.id} state=${cursor.download.state}")
                }
                downloads.value = results
                Log.d(TAG, "init: loaded ${results.size} existing downloads")
            } catch (e: Exception) {
                Log.e(TAG, "init: failed to load downloads", e)
            }
        }
    }

    @Synchronized
    private fun syncDownloads(download: Download) =
        downloads.update { map ->
            map.toMutableMap().apply { set(download.request.id, download) }
        }

    override fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    fun getDownloadNotificationHelper(): DownloadNotificationHelper {
        if (downloadNotificationHelper == null) {
            Log.d(TAG, "getDownloadNotificationHelper: creating new helper")
            downloadNotificationHelper = DownloadNotificationHelper(context, DownloadHelper.DOWNLOAD_NOTIFICATION_CHANNEL_ID)
        }
        return downloadNotificationHelper!!
    }

    override fun addDownload(songId: String, title: String) {
        Log.d(TAG, "addDownload: songId=$songId title='$title'")
        val downloadRequest = DownloadRequest.Builder(songId, songId.toUri())
            .setCustomCacheKey(songId)
            .setData(title.encodeToByteArray())
            .build()
        try {
            DownloadService.sendAddDownload(
                context,
                MyDownloadService::class.java,
                downloadRequest,
                true
            )
            Log.d(TAG, "addDownload: sent to MyDownloadService")
        } catch (e: Exception) {
            Log.e(TAG, "addDownload: failed to send", e)
        }
    }

    override fun removeDownload(songId: String) {
        Log.d(TAG, "removeDownload: songId=$songId")
        try {
            DownloadService.sendRemoveDownload(
                context,
                MyDownloadService::class.java,
                songId,
                false
            )
            Log.d(TAG, "removeDownload: sent removal")
        } catch (e: Exception) {
            Log.e(TAG, "removeDownload: failed", e)
        }
    }

    companion object {
        private const val TAG = "DownloadHelper"
        lateinit var instance: DownloadHelperImpl
    }
}
