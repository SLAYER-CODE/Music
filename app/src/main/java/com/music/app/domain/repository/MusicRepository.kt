package com.music.app.domain.repository

import com.music.app.data.local.SongDao
import com.music.app.data.model.Song
import kotlinx.coroutines.flow.Flow

class MusicRepository(private val songDao: SongDao) {

    fun getAllSongs(): Flow<List<Song>> = songDao.getAllSongs()

    fun getDownloadedSongs(): Flow<List<Song>> = songDao.getDownloadedSongs()

    suspend fun getSongById(id: String): Song? = songDao.getSongById(id)

    suspend fun saveSong(song: Song) = songDao.insertSong(song)

    suspend fun saveSongs(songs: List<Song>) = songDao.insertSongs(songs)

    suspend fun deleteSong(id: String) = songDao.deleteSong(id)

    suspend fun markDownloaded(id: String, path: String) =
        songDao.updateDownloadPath(id, path)

    fun searchLocal(query: String): Flow<List<Song>> = songDao.searchSongs(query)
}
