package com.music.app.`data`.local

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.music.app.`data`.model.CachedLocalSong
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class CachedLocalSongDao_Impl(
  __db: RoomDatabase,
) : CachedLocalSongDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfCachedLocalSong: EntityInsertAdapter<CachedLocalSong>
  init {
    this.__db = __db
    this.__insertAdapterOfCachedLocalSong = object : EntityInsertAdapter<CachedLocalSong>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `cached_songs` (`id`,`title`,`artist`,`album`,`durationMs`,`uri`,`size`,`dateAdded`) VALUES (?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: CachedLocalSong) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.title)
        statement.bindText(3, entity.artist)
        val _tmpAlbum: String? = entity.album
        if (_tmpAlbum == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpAlbum)
        }
        statement.bindLong(5, entity.durationMs)
        statement.bindText(6, entity.uri)
        statement.bindLong(7, entity.size)
        statement.bindLong(8, entity.dateAdded)
      }
    }
  }

  public override suspend fun insertAll(songs: List<CachedLocalSong>): Unit =
      performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfCachedLocalSong.insert(_connection, songs)
  }

  public override suspend fun getAllSongs(): List<CachedLocalSong> {
    val _sql: String = "SELECT * FROM cached_songs ORDER BY title ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfArtist: Int = getColumnIndexOrThrow(_stmt, "artist")
        val _columnIndexOfAlbum: Int = getColumnIndexOrThrow(_stmt, "album")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "durationMs")
        val _columnIndexOfUri: Int = getColumnIndexOrThrow(_stmt, "uri")
        val _columnIndexOfSize: Int = getColumnIndexOrThrow(_stmt, "size")
        val _columnIndexOfDateAdded: Int = getColumnIndexOrThrow(_stmt, "dateAdded")
        val _result: MutableList<CachedLocalSong> = mutableListOf()
        while (_stmt.step()) {
          val _item: CachedLocalSong
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtist: String
          _tmpArtist = _stmt.getText(_columnIndexOfArtist)
          val _tmpAlbum: String?
          if (_stmt.isNull(_columnIndexOfAlbum)) {
            _tmpAlbum = null
          } else {
            _tmpAlbum = _stmt.getText(_columnIndexOfAlbum)
          }
          val _tmpDurationMs: Long
          _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          val _tmpUri: String
          _tmpUri = _stmt.getText(_columnIndexOfUri)
          val _tmpSize: Long
          _tmpSize = _stmt.getLong(_columnIndexOfSize)
          val _tmpDateAdded: Long
          _tmpDateAdded = _stmt.getLong(_columnIndexOfDateAdded)
          _item =
              CachedLocalSong(_tmpId,_tmpTitle,_tmpArtist,_tmpAlbum,_tmpDurationMs,_tmpUri,_tmpSize,_tmpDateAdded)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteAll() {
    val _sql: String = "DELETE FROM cached_songs"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteById(id: String) {
    val _sql: String = "DELETE FROM cached_songs WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
