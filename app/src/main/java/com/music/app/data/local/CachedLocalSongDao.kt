package com.music.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.music.app.data.model.CachedLocalSong

@Dao
interface CachedLocalSongDao {

    @Query("SELECT * FROM cached_songs ORDER BY title ASC")
    suspend fun getAllSongs(): List<CachedLocalSong>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<CachedLocalSong>)

    @Query("DELETE FROM cached_songs")
    suspend fun deleteAll()

    @Query("DELETE FROM cached_songs WHERE id = :id")
    suspend fun deleteById(id: String)
}
