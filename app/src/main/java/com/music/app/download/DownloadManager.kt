package com.music.app.download

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadManager(private val context: Context) {

    private val downloadDir: File
        get() = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(context.filesDir, "downloads")

    suspend fun download(url: String, songId: String, title: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                downloadDir.mkdirs()
                val fileName = "${sanitize(title)}_$songId.mp3"
                val file = File(downloadDir, fileName)

                if (file.exists()) return@withContext Result.success(file.absolutePath)

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.connect()

                connection.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }

                Result.success(file.absolutePath)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun getDownloadPath(songId: String): String? {
        val files = downloadDir.listFiles { file ->
            file.name.endsWith("_${songId}.mp3")
        }
        return files?.firstOrNull()?.absolutePath
    }

    fun deleteDownload(songId: String): Boolean {
        val path = getDownloadPath(songId) ?: return false
        return File(path).delete()
    }

    private fun sanitize(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)
    }
}
