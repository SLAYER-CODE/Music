package com.music.app.download

import android.app.Notification
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import com.music.app.R
import androidx.media3.exoplayer.scheduler.PlatformScheduler

private const val JOB_ID = 8888
private const val FOREGROUND_NOTIFICATION_ID = 8989

@UnstableApi
class MyDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DownloadHelper.DOWNLOAD_NOTIFICATION_CHANNEL_ID,
    R.string.download_notification, 0
) {

    override fun getDownloadManager(): DownloadManager {
        Log.d(TAG, "getDownloadManager: called")
        val helper = DownloadHelperImpl.instance
        val downloadNotificationHelper = helper.getDownloadNotificationHelper()
        helper.downloadManager.addListener(
            TerminalStateNotificationHelper(
                this,
                downloadNotificationHelper,
                FOREGROUND_NOTIFICATION_ID + 1
            )
        )
        Log.d(TAG, "getDownloadManager: returning manager")
        return helper.downloadManager
    }

    override fun getScheduler(): PlatformScheduler? {
        Log.d(TAG, "getScheduler: called")
        return PlatformScheduler(this, JOB_ID)
    }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        Log.d(TAG, "getForegroundNotification: ${downloads.size} downloads")
        return NotificationCompat
            .Builder(this, DownloadHelper.DOWNLOAD_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText("${downloads.size} in progress")
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: MyDownloadService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: MyDownloadService destroyed")
    }

    private class TerminalStateNotificationHelper(
        private val context: Context,
        private val notificationHelper: DownloadNotificationHelper,
        firstNotificationId: Int
    ) : DownloadManager.Listener {
        private var nextNotificationId: Int = firstNotificationId

        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?
        ) {
            val notification: Notification = when (download.state) {
                Download.STATE_COMPLETED -> {
                    Log.d(TAG, "onDownloadChanged: COMPLETED id=${download.request.id}")
                    notificationHelper.buildDownloadCompletedNotification(
                        context,
                        android.R.drawable.stat_sys_download_done,
                        null,
                        Util.fromUtf8Bytes(download.request.data)
                    )
                }
                Download.STATE_FAILED -> {
                    Log.e(TAG, "onDownloadChanged: FAILED id=${download.request.id} error=$finalException")
                    notificationHelper.buildDownloadFailedNotification(
                        context,
                        android.R.drawable.stat_notify_error,
                        null,
                        Util.fromUtf8Bytes(download.request.data)
                    )
                }
                else -> return
            }
            NotificationUtil.setNotification(context, nextNotificationId++, notification)
        }
    }

    companion object {
        private const val TAG = "MyDownloadService"
    }
}
