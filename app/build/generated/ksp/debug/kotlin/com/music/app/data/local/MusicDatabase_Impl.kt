package com.music.app.`data`.local

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class MusicDatabase_Impl : MusicDatabase() {
  private val _songDao: Lazy<SongDao> = lazy {
    SongDao_Impl(this)
  }

  private val _cachedLocalSongDao: Lazy<CachedLocalSongDao> = lazy {
    CachedLocalSongDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(2,
        "c7ed953cc0d1949ce1da4240efaeb780", "634f3afe5fb79db0f8302341a6bd1114") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `songs` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artists` TEXT NOT NULL, `durationText` TEXT NOT NULL, `thumbnailUrl` TEXT, `albumName` TEXT, `albumId` TEXT, `downloadPath` TEXT, PRIMARY KEY(`id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `cached_songs` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, `durationMs` INTEGER NOT NULL, `uri` TEXT NOT NULL, `size` INTEGER NOT NULL, `dateAdded` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'c7ed953cc0d1949ce1da4240efaeb780')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `songs`")
        connection.execSQL("DROP TABLE IF EXISTS `cached_songs`")
      }

      public override fun onCreate(connection: SQLiteConnection) {
      }

      public override fun onOpen(connection: SQLiteConnection) {
        internalInitInvalidationTracker(connection)
      }

      public override fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override fun onValidateSchema(connection: SQLiteConnection):
          RoomOpenDelegate.ValidationResult {
        val _columnsSongs: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsSongs.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSongs.put("title", TableInfo.Column("title", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSongs.put("artists", TableInfo.Column("artists", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSongs.put("durationText", TableInfo.Column("durationText", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSongs.put("thumbnailUrl", TableInfo.Column("thumbnailUrl", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSongs.put("albumName", TableInfo.Column("albumName", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSongs.put("albumId", TableInfo.Column("albumId", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSongs.put("downloadPath", TableInfo.Column("downloadPath", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysSongs: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesSongs: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoSongs: TableInfo = TableInfo("songs", _columnsSongs, _foreignKeysSongs,
            _indicesSongs)
        val _existingSongs: TableInfo = read(connection, "songs")
        if (!_infoSongs.equals(_existingSongs)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |songs(com.music.app.data.model.Song).
              | Expected:
              |""".trimMargin() + _infoSongs + """
              |
              | Found:
              |""".trimMargin() + _existingSongs)
        }
        val _columnsCachedSongs: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsCachedSongs.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsCachedSongs.put("title", TableInfo.Column("title", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsCachedSongs.put("artist", TableInfo.Column("artist", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsCachedSongs.put("album", TableInfo.Column("album", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsCachedSongs.put("durationMs", TableInfo.Column("durationMs", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCachedSongs.put("uri", TableInfo.Column("uri", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsCachedSongs.put("size", TableInfo.Column("size", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsCachedSongs.put("dateAdded", TableInfo.Column("dateAdded", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysCachedSongs: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesCachedSongs: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoCachedSongs: TableInfo = TableInfo("cached_songs", _columnsCachedSongs,
            _foreignKeysCachedSongs, _indicesCachedSongs)
        val _existingCachedSongs: TableInfo = read(connection, "cached_songs")
        if (!_infoCachedSongs.equals(_existingCachedSongs)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |cached_songs(com.music.app.data.model.CachedLocalSong).
              | Expected:
              |""".trimMargin() + _infoCachedSongs + """
              |
              | Found:
              |""".trimMargin() + _existingCachedSongs)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "songs", "cached_songs")
  }

  public override fun clearAllTables() {
    super.performClear(false, "songs", "cached_songs")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(SongDao::class, SongDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(CachedLocalSongDao::class,
        CachedLocalSongDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override
      fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>):
      List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun songDao(): SongDao = _songDao.value

  public override fun cachedLocalSongDao(): CachedLocalSongDao = _cachedLocalSongDao.value
}
