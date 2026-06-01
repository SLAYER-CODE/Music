package com.music.app.`data`.local

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.music.app.`data`.model.Song
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class SongDao_Impl(
  __db: RoomDatabase,
) : SongDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfSong: EntityInsertAdapter<Song>
  init {
    this.__db = __db
    this.__insertAdapterOfSong = object : EntityInsertAdapter<Song>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `songs` (`id`,`title`,`artists`,`durationText`,`thumbnailUrl`,`albumName`,`albumId`,`downloadPath`) VALUES (?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: Song) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.title)
        statement.bindText(3, entity.artists)
        statement.bindText(4, entity.durationText)
        val _tmpThumbnailUrl: String? = entity.thumbnailUrl
        if (_tmpThumbnailUrl == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpThumbnailUrl)
        }
        val _tmpAlbumName: String? = entity.albumName
        if (_tmpAlbumName == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpAlbumName)
        }
        val _tmpAlbumId: String? = entity.albumId
        if (_tmpAlbumId == null) {
          statement.bindNull(7)
        } else {
          statement.bindText(7, _tmpAlbumId)
        }
        val _tmpDownloadPath: String? = entity.downloadPath
        if (_tmpDownloadPath == null) {
          statement.bindNull(8)
        } else {
          statement.bindText(8, _tmpDownloadPath)
        }
      }
    }
  }

  public override suspend fun insertSong(song: Song): Unit = performSuspending(__db, false, true) {
      _connection ->
    __insertAdapterOfSong.insert(_connection, song)
  }

  public override suspend fun insertSongs(songs: List<Song>): Unit = performSuspending(__db, false,
      true) { _connection ->
    __insertAdapterOfSong.insert(_connection, songs)
  }

  public override fun getAllSongs(): Flow<List<Song>> {
    val _sql: String = "SELECT * FROM songs ORDER BY title ASC"
    return createFlow(__db, false, arrayOf("songs")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfArtists: Int = getColumnIndexOrThrow(_stmt, "artists")
        val _columnIndexOfDurationText: Int = getColumnIndexOrThrow(_stmt, "durationText")
        val _columnIndexOfThumbnailUrl: Int = getColumnIndexOrThrow(_stmt, "thumbnailUrl")
        val _columnIndexOfAlbumName: Int = getColumnIndexOrThrow(_stmt, "albumName")
        val _columnIndexOfAlbumId: Int = getColumnIndexOrThrow(_stmt, "albumId")
        val _columnIndexOfDownloadPath: Int = getColumnIndexOrThrow(_stmt, "downloadPath")
        val _result: MutableList<Song> = mutableListOf()
        while (_stmt.step()) {
          val _item: Song
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtists: String
          _tmpArtists = _stmt.getText(_columnIndexOfArtists)
          val _tmpDurationText: String
          _tmpDurationText = _stmt.getText(_columnIndexOfDurationText)
          val _tmpThumbnailUrl: String?
          if (_stmt.isNull(_columnIndexOfThumbnailUrl)) {
            _tmpThumbnailUrl = null
          } else {
            _tmpThumbnailUrl = _stmt.getText(_columnIndexOfThumbnailUrl)
          }
          val _tmpAlbumName: String?
          if (_stmt.isNull(_columnIndexOfAlbumName)) {
            _tmpAlbumName = null
          } else {
            _tmpAlbumName = _stmt.getText(_columnIndexOfAlbumName)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpDownloadPath: String?
          if (_stmt.isNull(_columnIndexOfDownloadPath)) {
            _tmpDownloadPath = null
          } else {
            _tmpDownloadPath = _stmt.getText(_columnIndexOfDownloadPath)
          }
          _item =
              Song(_tmpId,_tmpTitle,_tmpArtists,_tmpDurationText,_tmpThumbnailUrl,_tmpAlbumName,_tmpAlbumId,_tmpDownloadPath)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getDownloadedSongs(): Flow<List<Song>> {
    val _sql: String = "SELECT * FROM songs WHERE downloadPath IS NOT NULL ORDER BY title ASC"
    return createFlow(__db, false, arrayOf("songs")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfArtists: Int = getColumnIndexOrThrow(_stmt, "artists")
        val _columnIndexOfDurationText: Int = getColumnIndexOrThrow(_stmt, "durationText")
        val _columnIndexOfThumbnailUrl: Int = getColumnIndexOrThrow(_stmt, "thumbnailUrl")
        val _columnIndexOfAlbumName: Int = getColumnIndexOrThrow(_stmt, "albumName")
        val _columnIndexOfAlbumId: Int = getColumnIndexOrThrow(_stmt, "albumId")
        val _columnIndexOfDownloadPath: Int = getColumnIndexOrThrow(_stmt, "downloadPath")
        val _result: MutableList<Song> = mutableListOf()
        while (_stmt.step()) {
          val _item: Song
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtists: String
          _tmpArtists = _stmt.getText(_columnIndexOfArtists)
          val _tmpDurationText: String
          _tmpDurationText = _stmt.getText(_columnIndexOfDurationText)
          val _tmpThumbnailUrl: String?
          if (_stmt.isNull(_columnIndexOfThumbnailUrl)) {
            _tmpThumbnailUrl = null
          } else {
            _tmpThumbnailUrl = _stmt.getText(_columnIndexOfThumbnailUrl)
          }
          val _tmpAlbumName: String?
          if (_stmt.isNull(_columnIndexOfAlbumName)) {
            _tmpAlbumName = null
          } else {
            _tmpAlbumName = _stmt.getText(_columnIndexOfAlbumName)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpDownloadPath: String?
          if (_stmt.isNull(_columnIndexOfDownloadPath)) {
            _tmpDownloadPath = null
          } else {
            _tmpDownloadPath = _stmt.getText(_columnIndexOfDownloadPath)
          }
          _item =
              Song(_tmpId,_tmpTitle,_tmpArtists,_tmpDurationText,_tmpThumbnailUrl,_tmpAlbumName,_tmpAlbumId,_tmpDownloadPath)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getSongById(id: String): Song? {
    val _sql: String = "SELECT * FROM songs WHERE id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfArtists: Int = getColumnIndexOrThrow(_stmt, "artists")
        val _columnIndexOfDurationText: Int = getColumnIndexOrThrow(_stmt, "durationText")
        val _columnIndexOfThumbnailUrl: Int = getColumnIndexOrThrow(_stmt, "thumbnailUrl")
        val _columnIndexOfAlbumName: Int = getColumnIndexOrThrow(_stmt, "albumName")
        val _columnIndexOfAlbumId: Int = getColumnIndexOrThrow(_stmt, "albumId")
        val _columnIndexOfDownloadPath: Int = getColumnIndexOrThrow(_stmt, "downloadPath")
        val _result: Song?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtists: String
          _tmpArtists = _stmt.getText(_columnIndexOfArtists)
          val _tmpDurationText: String
          _tmpDurationText = _stmt.getText(_columnIndexOfDurationText)
          val _tmpThumbnailUrl: String?
          if (_stmt.isNull(_columnIndexOfThumbnailUrl)) {
            _tmpThumbnailUrl = null
          } else {
            _tmpThumbnailUrl = _stmt.getText(_columnIndexOfThumbnailUrl)
          }
          val _tmpAlbumName: String?
          if (_stmt.isNull(_columnIndexOfAlbumName)) {
            _tmpAlbumName = null
          } else {
            _tmpAlbumName = _stmt.getText(_columnIndexOfAlbumName)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpDownloadPath: String?
          if (_stmt.isNull(_columnIndexOfDownloadPath)) {
            _tmpDownloadPath = null
          } else {
            _tmpDownloadPath = _stmt.getText(_columnIndexOfDownloadPath)
          }
          _result =
              Song(_tmpId,_tmpTitle,_tmpArtists,_tmpDurationText,_tmpThumbnailUrl,_tmpAlbumName,_tmpAlbumId,_tmpDownloadPath)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun searchSongs(query: String): Flow<List<Song>> {
    val _sql: String =
        "SELECT * FROM songs WHERE title LIKE '%' || ? || '%' OR artists LIKE '%' || ? || '%'"
    return createFlow(__db, false, arrayOf("songs")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, query)
        _argIndex = 2
        _stmt.bindText(_argIndex, query)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfArtists: Int = getColumnIndexOrThrow(_stmt, "artists")
        val _columnIndexOfDurationText: Int = getColumnIndexOrThrow(_stmt, "durationText")
        val _columnIndexOfThumbnailUrl: Int = getColumnIndexOrThrow(_stmt, "thumbnailUrl")
        val _columnIndexOfAlbumName: Int = getColumnIndexOrThrow(_stmt, "albumName")
        val _columnIndexOfAlbumId: Int = getColumnIndexOrThrow(_stmt, "albumId")
        val _columnIndexOfDownloadPath: Int = getColumnIndexOrThrow(_stmt, "downloadPath")
        val _result: MutableList<Song> = mutableListOf()
        while (_stmt.step()) {
          val _item: Song
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtists: String
          _tmpArtists = _stmt.getText(_columnIndexOfArtists)
          val _tmpDurationText: String
          _tmpDurationText = _stmt.getText(_columnIndexOfDurationText)
          val _tmpThumbnailUrl: String?
          if (_stmt.isNull(_columnIndexOfThumbnailUrl)) {
            _tmpThumbnailUrl = null
          } else {
            _tmpThumbnailUrl = _stmt.getText(_columnIndexOfThumbnailUrl)
          }
          val _tmpAlbumName: String?
          if (_stmt.isNull(_columnIndexOfAlbumName)) {
            _tmpAlbumName = null
          } else {
            _tmpAlbumName = _stmt.getText(_columnIndexOfAlbumName)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpDownloadPath: String?
          if (_stmt.isNull(_columnIndexOfDownloadPath)) {
            _tmpDownloadPath = null
          } else {
            _tmpDownloadPath = _stmt.getText(_columnIndexOfDownloadPath)
          }
          _item =
              Song(_tmpId,_tmpTitle,_tmpArtists,_tmpDurationText,_tmpThumbnailUrl,_tmpAlbumName,_tmpAlbumId,_tmpDownloadPath)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteSong(id: String) {
    val _sql: String = "DELETE FROM songs WHERE id = ?"
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

  public override suspend fun updateDownloadPath(id: String, path: String) {
    val _sql: String = "UPDATE songs SET downloadPath = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, path)
        _argIndex = 2
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
