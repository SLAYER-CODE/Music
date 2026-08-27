package com.music.app.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.music.app.data.model.LocalAudioFile
import com.music.app.data.model.MusicItem
import com.music.app.data.model.Song
import com.music.app.download.MyDownloadService
import com.music.app.ui.components.CachedTimeSpan
import com.music.app.data.remote.InnertubeClient
import com.music.app.data.remote.SearchResult
import com.music.app.download.DownloadHelper
import com.music.app.domain.repository.MusicRepository
import com.music.app.player.DeviceMusicScanner
import com.music.app.player.MediaItemTriple
import com.music.app.player.MusicServiceConnection
import com.music.app.player.StreamResolver
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(
    private val repository: MusicRepository,
    private val innertube: InnertubeClient,
    private val downloadHelper: DownloadHelper,
    private val musicScanner: DeviceMusicScanner,
    private val musicServiceConnection: MusicServiceConnection,
    private val connectivityManager: ConnectivityManager,
    private val streamCache: Cache,
    private val streamResolver: StreamResolver,
    private val appContext: Context,
    private val okHttpClient: okhttp3.OkHttpClient
) : ViewModel() {

    val localSongs: StateFlow<List<Song>> = repository.getAllSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deviceSongs: StateFlow<List<LocalAudioFile>> = musicScanner.deviceSongs

    private val localThumbnails = MutableStateFlow<Map<String, String>>(emptyMap())

    private val recentPrefs = appContext.getSharedPreferences("recent", Context.MODE_PRIVATE)
    private val searchPrefs = appContext.getSharedPreferences("search", Context.MODE_PRIVATE)
    private val cacheStatePrefs = appContext.getSharedPreferences("cache_state", Context.MODE_PRIVATE)
    private val settingsPrefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _recentIds = MutableStateFlow(loadRecentIds())

    private fun loadRecentIds(): List<String> {
        val json = recentPrefs.getString("ids", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveRecentIds(ids: List<String>) {
        val arr = org.json.JSONArray(ids)
        recentPrefs.edit().putString("ids", arr.toString()).apply()
    }

    private fun loadRecentItem(id: String): MusicItem? {
        val title = recentPrefs.getString("title_$id", null) ?: return null
        val subtitle = recentPrefs.getString("subtitle_$id", "") ?: ""
        val thumb = recentPrefs.getString("thumb_$id", null)
        return MusicItem.YouTube(
            com.music.app.data.model.Song(
                id = id, title = title, artists = subtitle,
                thumbnailUrl = thumb, durationText = "", albumName = null, albumId = null
            )
        )
    }

    val lastPlayedItem: StateFlow<MusicItem?> = _recentIds.map { ids ->
        ids.firstOrNull()?.let { loadRecentItem(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun loadSearchQuery(): String {
        return searchPrefs.getString("query", "") ?: ""
    }

    private fun loadSearchState() {
        val json = searchPrefs.getString("results", null) ?: return
        try {
            val arr = org.json.JSONArray(json)
            val results = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SearchResult(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    artists = obj.optString("artists", ""),
                    durationText = obj.optString("durationText", ""),
                    thumbnailUrl = if (obj.isNull("thumbnailUrl")) null else obj.getString("thumbnailUrl"),
                    albumName = if (obj.isNull("albumName")) null else obj.getString("albumName")
                )
            }
            if (results.isNotEmpty()) {
                _searchResults.value = results
                Log.d(TAG, "loadSearchState: restored ${results.size} results for query='${searchPrefs.getString("query", "")}'")
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadSearchState: failed to parse", e)
        }
    }

    private fun saveSearchState(query: String, results: List<SearchResult>) {
        Log.d(TAG, "saveSearchState: saving ${results.size} results for query='$query'")
        val arr = org.json.JSONArray(results.map { r ->
            org.json.JSONObject().apply {
                put("id", r.id)
                put("title", r.title)
                put("artists", r.artists)
                put("durationText", r.durationText)
                put("thumbnailUrl", r.thumbnailUrl ?: org.json.JSONObject.NULL)
                put("albumName", r.albumName ?: org.json.JSONObject.NULL)
            }
        })
        searchPrefs.edit()
            .putString("query", query)
            .putString("results", arr.toString())
            .apply()
    }

    private fun saveCachePercentage(id: String, pct: Float) {
        cacheStatePrefs.edit().putFloat("pct_$id", pct).apply()
    }

    /**
     * Real cache scan, preserving the live entry of a saved song that is still
     * streaming as YouTube (its honest % must survive full-map replacements).
     */
    private suspend fun scanPercentagesWithSaved(): Map<String, Float> {
        val scanned = scanCachePercentages().toMutableMap()
        val cur = _currentItem.value?.takeIf { it is MusicItem.YouTube }?.id
        if (cur != null && _savedSongs.value.containsKey(cur) &&
            !isFilling(cur)
        ) {
            _cachedPercentages.value[cur]?.let { scanned[cur] = it }
        }
        return scanned
    }

    private fun saveCachePercentages(pcts: Map<String, Float>) {
        cacheStatePrefs.edit().apply {
            val existingKeys = cacheStatePrefs.all.keys.filter { it.startsWith("pct_") }
            for (key in existingKeys) {
                remove(key)
            }
            for ((id, pct) in pcts) {
                putFloat("pct_$id", pct)
            }
            apply()
        }
    }

    private fun loadCachePercentages(): Map<String, Float> {
        return cacheStatePrefs.all.entries
            .filter { it.key.startsWith("pct_") && it.value is Float }
            .associate { it.key.removePrefix("pct_") to (it.value as Float) }
    }

    private fun saveCacheSpans(id: String, spans: List<CachedTimeSpan>) {
        val str = spans.joinToString(SPANS_SEPARATOR) { "${it.startMs}$SPAN_PARTS_SEPARATOR${it.endMs}" }
        cacheStatePrefs.edit().putString("spans_$id", str).apply()
    }

    private fun loadCacheSpans(id: String): List<CachedTimeSpan> {
        val str = cacheStatePrefs.getString("spans_$id", "") ?: ""
        if (str.isEmpty()) return emptyList()
        return str.split(SPANS_SEPARATOR).map { part ->
            val (s, e) = part.split(SPAN_PARTS_SEPARATOR)
            CachedTimeSpan(startMs = s.toLong(), endMs = e.toLong())
        }
    }

    /** Saved-but-still-streaming tracks whose cached bytes can be freed on next track change */
    private val pendingPurgeIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Hands the given track over to its saved local file at the same spot.
     * Used when the stream would need data the cache doesn't have (seek past
     * coverage, offline exhaustion) — zero audible interruption, honest bar.
     */
    private fun handoffToLocal(id: String, reason: String) {
        val info = _savedSongs.value[id] ?: return
        musicServiceConnection.replaceTimelineEntry(
            "yt://$id", MediaItemTriple(info.uri, info.title, info.artist)
        )
        Log.d(TAG, "handoffToLocal: $id -> local file ($reason)")
    }

    private val _cachedPercentages = MutableStateFlow<Map<String, Float>>(emptyMap())
    val cachedPercentages: StateFlow<Map<String, Float>> = _cachedPercentages.asStateFlow()

    data class SavedSongInfo(
        val uri: String, val title: String, val artist: String, val fileName: String,
        val durationMs: Long = 0L, val size: Long = 0L, val dateAdded: Long = 0L,
        val thumbnailUri: String? = null
    ) {
        fun toLocalAudioFile(): LocalAudioFile = LocalAudioFile(
            id = uri, title = title, artist = artist, album = null,
            durationMs = durationMs, uri = uri, size = size, dateAdded = dateAdded
        )
    }

    private val _savedSongs = MutableStateFlow<Map<String, SavedSongInfo>>(emptyMap())
    val savedSongs: StateFlow<Map<String, SavedSongInfo>> = _savedSongs.asStateFlow()

    private val _exportingIds = MutableStateFlow<Set<String>>(emptySet())
    val exportingIds: StateFlow<Set<String>> = _exportingIds.asStateFlow()

    private val _saveLocation = MutableStateFlow(loadSaveLocation())
    val saveLocation: StateFlow<String> = _saveLocation.asStateFlow()

    private fun loadSaveLocation(): String =
        settingsPrefs.getString("save_location", null)
            ?: android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ).absolutePath

    fun setSaveLocation(path: String) {
        settingsPrefs.edit().putString("save_location", path).apply()
        _saveLocation.value = path
    }

    // ── Persisted settings ────────────────────────────────────────
    val autoDownload: StateFlow<Boolean> = MutableStateFlow(
        settingsPrefs.getBoolean("auto_download", false)
    ).asStateFlow()

    val wifiOnly: StateFlow<Boolean> = MutableStateFlow(
        settingsPrefs.getBoolean("wifi_only", true)
    ).asStateFlow()

    val highQuality: StateFlow<Boolean> = MutableStateFlow(
        settingsPrefs.getBoolean("high_quality", false)
    ).asStateFlow()

    fun setAutoDownload(value: Boolean) {
        settingsPrefs.edit().putBoolean("auto_download", value).apply()
        (autoDownload as MutableStateFlow).value = value
    }

    fun setWifiOnly(value: Boolean) {
        settingsPrefs.edit().putBoolean("wifi_only", value).apply()
        (wifiOnly as MutableStateFlow).value = value
    }

    fun setHighQuality(value: Boolean) {
        settingsPrefs.edit().putBoolean("high_quality", value).apply()
        (highQuality as MutableStateFlow).value = value
    }

    // ── Sleep timer ───────────────────────────────────────────────
    private var sleepTimerJob: kotlinx.coroutines.Job? = null
    private val _sleepTimerMinutes = MutableStateFlow<Int?>(null)
    val sleepTimerMinutes: StateFlow<Int?> = _sleepTimerMinutes.asStateFlow()

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        _sleepTimerMinutes.value = minutes
        sleepTimerJob = viewModelScope.launch {
            kotlinx.coroutines.delay(minutes * 60L * 1000L)
            // Pause playback
            musicServiceConnection.togglePlayPause()
            _sleepTimerMinutes.value = null
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerMinutes.value = null
    }

    // ── Clear cache helpers ───────────────────────────────────────
    fun clearAppCache(): Long {
        val size = appContext.cacheDir?.let {
            var total = 0L
            if (it.exists()) {
                it.listFiles()?.forEach { f -> total += f.length() }
            }
            total
        } ?: 0L
        runCatching { appContext.cacheDir?.deleteRecursively() }
        return size
    }

    fun clearDownloadCache(): Long {
        val cacheDir = java.io.File(appContext.cacheDir, "stream_cache_v2")
        val size = if (cacheDir.exists()) {
            cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        } else 0L
        runCatching { cacheDir.deleteRecursively() }
        return size
    }

    // ── Queue management ──────────────────────────────────────────
    fun removeFromQueue(index: Int) {
        if (index !in currentPlaylist.indices) return
        val wasPlaying = index == musicServiceConnection.currentMediaItemIndex
        currentPlaylist = currentPlaylist.toMutableList().also { it.removeAt(index) }
        _currentPlaylistFlow.value = currentPlaylist
        if (currentPlaylist.isEmpty()) {
            musicServiceConnection.togglePlayPause()
            _currentItem.value = null
            return
        }
        val tracks = currentPlaylist.map { mi ->
            when (mi) {
                is MusicItem.YouTube -> com.music.app.player.MediaItemTriple("yt://${mi.song.id}", mi.song.title, mi.song.artists)
                is MusicItem.Local -> com.music.app.player.MediaItemTriple(mi.file.uri, mi.file.title, mi.file.artist)
            }
        }
        val newIndex = if (wasPlaying) index.coerceAtMost(currentPlaylist.lastIndex) else musicServiceConnection.currentMediaItemIndex.coerceAtMost(currentPlaylist.lastIndex)
        musicServiceConnection.playList(tracks, newIndex)
    }

    fun moveInQueue(from: Int, to: Int) {
        if (from !in currentPlaylist.indices || to !in currentPlaylist.indices) return
        val currentIdx = musicServiceConnection.currentMediaItemIndex
        currentPlaylist = currentPlaylist.toMutableList().also {
            val item = it.removeAt(from)
            it.add(to, item)
        }
        _currentPlaylistFlow.value = currentPlaylist
        val newCurrentIdx = when {
            from == currentIdx -> to
            from < currentIdx && to >= currentIdx -> currentIdx - 1
            from > currentIdx && to <= currentIdx -> currentIdx + 1
            else -> currentIdx
        }
        val tracks = currentPlaylist.map { mi ->
            when (mi) {
                is MusicItem.YouTube -> com.music.app.player.MediaItemTriple("yt://${mi.song.id}", mi.song.title, mi.song.artists)
                is MusicItem.Local -> com.music.app.player.MediaItemTriple(mi.file.uri, mi.file.title, mi.file.artist)
            }
        }
        musicServiceConnection.playList(tracks, newCurrentIdx.coerceIn(0, currentPlaylist.lastIndex))
    }

    /** Guards against double-exporting the same fill from concurrent triggers. */
    private val exportingFillIds =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    val combinedSongs: StateFlow<List<MusicItem>> = combine(
        localSongs, deviceSongs, localThumbnails, _cachedPercentages, _savedSongs
    ) { songs, locals, thumbs, percentages, saved ->
        // Pending partial saves stay as YouTube entries (cache % keeps growing);
        // completed saves become Local entries
        val pendingIds = saved.keys.filter { isFilling(it) }.toSet()
        val youTubeItems = songs.filter { it.id !in saved.keys || it.id in pendingIds }
            .map { MusicItem.YouTube(it) }
        // Completed saves WIN over the MediaStore scan copy: they carry parsed
        // title/artist plus the sidecar cover; suppress the raw scanned duplicate.
        // Match by numeric row id — the scan yields external/audio/media/N while
        // our index stores external/file/N for the very same underlying file.
        fun mediaStoreRowId(uri: String): String? = uri.substringAfterLast('/').takeIf { it.toLongOrNull() != null }
        val savedCompleted = saved.filterKeys { it !in pendingIds }
        val savedRowIds = savedCompleted.values.mapNotNull { mediaStoreRowId(it.uri) }.toSet()
        val savedLocalItems = savedCompleted.values
            .map { MusicItem.Local(it.toLocalAudioFile(), it.thumbnailUri) }
        val localItems = locals.filter { mediaStoreRowId(it.uri) !in savedRowIds }
            .map { MusicItem.Local(it, thumbs[it.id]) } + savedLocalItems
        val savedDoneIds = savedCompleted.keys
        val allItems = youTubeItems + localItems
        allItems.sortedWith(compareBy<MusicItem> { item ->
            val pct = percentages[item.id]
            when {
                item.id !in savedDoneIds && pct != null && pct >= 1.0f -> 0
                item.id !in savedDoneIds && pct != null -> 1
                else -> 2
            }
        }.thenByDescending { item ->
            if (item.id !in savedDoneIds) percentages[item.id] ?: 0f else -1f
        }.thenBy { item ->
            item.title.lowercase()
        })
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        musicScanner.deviceSongs.value.map { MusicItem.Local(it) }.sorted()
    )

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    val lastSearchQuery: String = loadSearchQuery()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _currentItem = MutableStateFlow<MusicItem?>(null)
    val currentItem: StateFlow<MusicItem?> = _currentItem.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _pendingDeleteIntent = MutableStateFlow<IntentSender?>(null)
    val pendingDeleteIntent: StateFlow<IntentSender?> = _pendingDeleteIntent.asStateFlow()

    private val _pendingDeleteItem = MutableStateFlow<MusicItem?>(null)
    val pendingDeleteItem: StateFlow<MusicItem?> = _pendingDeleteItem.asStateFlow()

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _loadingItemIds = MutableStateFlow<Set<String>>(emptySet())
    val loadingItemIds: StateFlow<Set<String>> = _loadingItemIds.asStateFlow()

    private val _currentCachedSpans = MutableStateFlow<List<CachedTimeSpan>>(emptyList())
    val currentCachedSpans: StateFlow<List<CachedTimeSpan>> = _currentCachedSpans.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(androidx.media3.common.Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private var player: androidx.media3.common.Player? = null
    private var playerListener: androidx.media3.common.Player.Listener? = null
    private var cacheListener: Cache.Listener? = null
    private var isPolling = false
    private var lastSeekNano = 0L
    private var lastErrorPosMs = -1L
    private var consecutiveErrors = 0
    private var currentPlaylist: List<MusicItem> = emptyList()
    private val _currentPlaylistFlow = MutableStateFlow<List<MusicItem>>(emptyList())
    val currentPlaylistFlow: StateFlow<List<MusicItem>> = _currentPlaylistFlow.asStateFlow()
    private lateinit var connectivityCallback: ConnectivityManager.NetworkCallback
    val downloads = downloadHelper.downloads

    init {
        Log.d(TAG, "init: ViewModel created")
        Log.d(TAG, "init: deviceSongs count=${deviceSongs.value.size} (from Room cache)")
        Log.d(TAG, "init: combinedSongs count=${combinedSongs.value.size}")

        connectToPlayer()
        scan()
        monitorConnectivity()
        initCacheListener()
        loadSearchState()
        viewModelScope.launch { loadSavedSongs() }

        // Load persisted cache state
        _cachedPercentages.value = loadCachePercentages()
        _recentIds.value.firstOrNull()?.let { id ->
            _currentCachedSpans.value = loadCacheSpans(id)
        }
        viewModelScope.launch { loadCachedThumbnails() }
        // Restore current item from combinedSongs once ready
        viewModelScope.launch {
            combinedSongs.first { it.isNotEmpty() }
            val id = _recentIds.value.firstOrNull() ?: return@launch
            if (_currentItem.value == null) {
                _currentItem.value = combinedSongs.value.find { it.id == id }
            }
        }

        viewModelScope.launch {
            combinedSongs.collect { list ->
                val yt = list.count { it is MusicItem.YouTube }
                Log.d(TAG, "combinedSongs changed: total=${list.size} yt=$yt local=${list.size - yt}")
            }
        }

        // Unified save watcher: exports a fill when its download reaches
        // STATE_COMPLETED, and runs the startup sweep on the first emission.
        viewModelScope.launch {
            var swept = false
            downloadHelper.downloads.collect { map ->
                if (!swept) {
                    swept = true
                    startupSweep(map.keys)
                }
                for ((key, d) in map) {
                    if (!key.startsWith("yt://") || d.state != Download.STATE_COMPLETED) continue
                    val id = key.removePrefix("yt://")
                    if (isFilling(id) && exportingFillIds.add(id)) {
                        viewModelScope.launch {
                            try {
                                exportFromCache(id, fromSaveFlow = false)
                                if (isFilling(id) && !downloadCoverageComplete(id) && _isOnline.value) {
                                    val title = combinedSongs.value.find { it.id == id }?.title ?: id
                                    ensureFill(id, title)
                                }
                            } finally {
                                exportingFillIds.remove(id)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseDurationSec(text: String): Long {
        try {
            val parts = text.split(":").map { it.toLong() }
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                else -> 0
            }
        } catch (e: Exception) { return 0 }
    }

    private suspend fun scanCachePercentages(): Map<String, Float> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, Float>()
        val assumedBitrate = 16384L // 128 kbps ≈ bytes/sec

        Log.d(TAG, "scanCachePercentages: localSongs=${localSongs.value.size} combinedSongs=${combinedSongs.value.size} contentLengths=${streamResolver.contentLengths.size}")

        // Build duration lookup from localSongs (Room) + combinedSongs + StreamResolver
        val durations = mutableMapOf<String, Long>()
        for (song in localSongs.value) {
            val sec = parseDurationSec(song.durationText)
            if (sec > 0) durations[song.id] = sec
        }
        for (item in combinedSongs.value) {
            if (item is MusicItem.YouTube) {
                val sec = parseDurationSec(item.song.durationText)
                if (sec > 0 && item.id !in durations) durations[item.id] = sec
            }
        }
        // Fallback: duration from SharedPreferences (persisted when song was saved)
        for ((id, dt) in streamResolver.contentLengths) {
            if (id in durations) continue
            val cachedDt = streamResolver.getDuration(id)
            if (cachedDt != null) {
                val sec = parseDurationSec(cachedDt)
                if (sec > 0) durations[id] = sec
            }
        }
        Log.d(TAG, "scanCachePercentages: durations map has ${durations.size} entries")

        // 1. Items with known contentLength — most reliable
        for ((id, length) in streamResolver.contentLengths) {
            if (length <= 0L) continue
            val cached = mergedCachedRanges(id, length).sumOf { it.endExclusive - it.start }
            Log.d(TAG, "scan: step1 id=$id length=$length cached=$cached")
            if (cached <= 0L) continue
            if (cached < length) {
                val pct = cached.toFloat() / length.toFloat()
                result[id] = pct
                Log.d(TAG, "scan: step1 valid contentLength -> ${(pct*100).toInt()}%")
            } else {
                val sec = durations[id]
                Log.d(TAG, "scan: step1 corrupted contentLength (cached>=length) sec=$sec")
                if (sec != null) {
                    result[id] = (cached.toFloat() / (sec * assumedBitrate).toFloat())
                        .coerceIn(0.01f, 0.99f)
                    Log.d(TAG, "scan: step1 estimated from duration -> ${(result[id]!!*100).toInt()}%")
                }
            }
        }

        // 2. All cache keys — catch items missing from step 1
        val cacheKeys = (streamCache.keys.toList() +
            runCatching { downloadHelper.downloadCache.keys.toList() }.getOrDefault(emptyList())).distinct()
        Log.d(TAG, "scan: step2 cacheKeys=${cacheKeys.size} keys=${cacheKeys.take(5)}")
        for (key in cacheKeys) {
            if (!key.startsWith("yt://")) continue
            val id = key.removePrefix("yt://")
            if (id in result) continue
            val ranges = mergedCachedRanges(id)
            val totalCached = ranges.sumOf { it.endExclusive - it.start }
            Log.d(TAG, "scan: step2 id=$id ranges=${ranges.size} totalCached=$totalCached")
            if (totalCached <= 0L) continue
            val sec = durations[id]
            if (sec != null) {
                result[id] = (totalCached.toFloat() / (sec * assumedBitrate).toFloat())
                    .coerceIn(0.01f, 0.99f)
                Log.d(TAG, "scan: step2 estimated from duration -> ${(result[id]!!*100).toInt()}%")
            } else {
                val maxPos = ranges.maxOfOrNull { it.endExclusive } ?: totalCached
                result[id] = (totalCached.toFloat() / maxPos.toFloat())
                    .coerceIn(0.01f, 0.99f)
                Log.d(TAG, "scan: step2 span fallback -> ${(result[id]!!*100).toInt()}% (maxPos=$maxPos)")
            }
        }

        // 3. YouTube items in combinedSongs — catch anything new not yet in cache keys
        for (item in combinedSongs.value) {
            if (item !is MusicItem.YouTube) continue
            if (item.id in result) continue
            val ranges = mergedCachedRanges(item.id)
            val totalCached = ranges.sumOf { it.endExclusive - it.start }
            Log.d(TAG, "scan: step3 id=${item.id} durationText='${item.song.durationText}' ranges=${ranges.size} totalCached=$totalCached")
            if (totalCached <= 0L) continue
            val sec = durations[item.id]
            if (sec != null) {
                result[item.id] = (totalCached.toFloat() / (sec * assumedBitrate).toFloat())
                    .coerceIn(0.01f, 0.99f)
                Log.d(TAG, "scan: step3 estimated from duration -> ${(result[item.id]!!*100).toInt()}%")
            } else {
                val maxPos = ranges.maxOfOrNull { it.endExclusive } ?: totalCached
                result[item.id] = (totalCached.toFloat() / maxPos.toFloat())
                    .coerceIn(0.01f, 0.99f)
                Log.d(TAG, "scan: step3 span fallback -> ${(result[item.id]!!*100).toInt()}% (maxPos=$maxPos)")
            }
        }

        Log.d(TAG, "scanCachePercentages: result has ${result.size} entries")
        // Saved songs live in Downloads/ — they never report cache percentages
        val saved = _savedSongs.value.keys.filter { !isFilling(it) }
        result.keys.filter { it in saved }.forEach { result.remove(it) }
        result
    }

    private suspend fun loadCachedThumbnails() {
        val map = withContext(Dispatchers.IO) {
            val dir = File(appContext.filesDir, "thumbnails")
            if (!dir.exists()) return@withContext emptyMap()
            val files = dir.listFiles() ?: return@withContext emptyMap()
            files.filter { it.extension == "jpg" || it.extension == "png" }
                .associate { it.nameWithoutExtension to it.toURI().toString() }
        }
        if (map.isNotEmpty()) {
            localThumbnails.value = map
            Log.d(TAG, "loadCachedThumbnails: loaded ${map.size} thumbnails")
        }
    }

    private fun scan() {
        viewModelScope.launch {
            musicScanner.scan()
        }
    }

    private fun connectToPlayer() {
        musicServiceConnection.connect(object : MusicServiceConnection.Listener {
            override fun onConnected(connectedPlayer: androidx.media3.common.Player) {
                Log.d(TAG, "connectToPlayer: connected")
                player = connectedPlayer
                _shuffleEnabled.value = connectedPlayer.shuffleModeEnabled
                _repeatMode.value = connectedPlayer.repeatMode
                val listener = object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying currentPos=${connectedPlayer.currentPosition}")
                        _isPlaying.value = isPlaying
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val stateStr = when (playbackState) {
                            androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                            androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                            androidx.media3.common.Player.STATE_READY -> "READY"
                            androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN($playbackState)"
                        }
                        if (playbackState == androidx.media3.common.Player.STATE_READY) {
                            consecutiveErrors = 0
                            lastErrorPosMs = -1L
                        }
                        _isPlaying.value = playbackState == androidx.media3.common.Player.STATE_READY && connectedPlayer.playWhenReady
                        _isBuffering.value = playbackState == androidx.media3.common.Player.STATE_BUFFERING
                    }

                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        val index = connectedPlayer.currentMediaItemIndex
                        Log.d(TAG, "onMediaItemTransition: index=$index playlistSize=${currentPlaylist.size}")
                        if (index in currentPlaylist.indices) {
                            _currentItem.value = currentPlaylist[index]
                        }
                        _loadingItemIds.value = emptySet()
                        val curId = _currentItem.value?.id
                        // No-swap model housekeeping:
                        // 1) Entering a track whose file is saved → rewrite that timeline
                        //    entry to the local file (position ~0, imperceptible).
                        if (_currentItem.value is MusicItem.YouTube && curId != null && isSongFullySaved(curId)) {
                            _savedSongs.value[curId]?.let { info ->
                                musicServiceConnection.replaceTimelineEntry(
                                    "yt://$curId", MediaItemTriple(info.uri, info.title, info.artist)
                                )
                                Log.d(TAG, "onMediaItemTransition: $curId now plays from local file")
                            }
                        }
                        // 2) Leaving a track frees its deferred purge queue
                        val drain = synchronized(pendingPurgeIds) { pendingPurgeIds.filter { it != curId }.toList() }
                        if (drain.isNotEmpty()) {
                            pendingPurgeIds.removeAll(drain.toSet())
                            viewModelScope.launch {
                                delay(400) // let ExoPlayer detach from those spans first
                                for (pid in drain) purgeCaches(pid)
                                Log.d(TAG, "onMediaItemTransition: deferred purged $drain")
                            }
                        }
                        // Load persisted spans immediately for instant bar
                        _currentItem.value?.id?.let { id ->
                            _currentCachedSpans.value = loadCacheSpans(id)
                        }
                        consecutiveErrors = 0
                        lastErrorPosMs = -1L
                        updatePosition(connectedPlayer)
                        viewModelScope.launch { scanCurrentCacheSpans() }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "onPlayerError: errorCode=${error.errorCode} message=${error.message}", error)
                        _loadingItemIds.value = emptySet()

                        // Malformed URL — skip immediately
                        val isMalformedUrl = error.errorCode == 1004 &&
                            error.cause is HttpDataSource.HttpDataSourceException &&
                            error.cause?.cause?.message?.contains("Malformed URL") == true
                        if (isMalformedUrl && connectedPlayer.currentMediaItemIndex in currentPlaylist.indices) {
                            Log.w(TAG, "onPlayerError: malformed URL, skipping track")
                            val rawUri = currentPlaylist[connectedPlayer.currentMediaItemIndex].let {
                                if (it is MusicItem.YouTube) "yt://${it.id}" else it.id
                            }
                            skipOfflineTrack(connectedPlayer, rawUri)
                            _error.value = null
                            return
                        }

                        // EOFException online — stale cache from different format
                        val isEofException = error.errorCode == 2000 && error.cause?.let { cause ->
                            var c: Throwable? = cause
                            while (c != null) {
                                if (c is java.io.EOFException) return@let true
                                c = c.cause
                            }
                            false
                        } == true
                        if (isEofException && _isOnline.value && connectedPlayer.currentMediaItemIndex in currentPlaylist.indices) {
                            val item = currentPlaylist[connectedPlayer.currentMediaItemIndex]
                            if (item is MusicItem.YouTube) {
                                Log.w(TAG, "onPlayerError: EOFException online, purging stale cache for ${item.id}")
                                _isBuffering.value = false
                                _error.value = null
                                val rawUri = "yt://${item.id}"
                                streamResolver.removeContentLength(item.id)
                                viewModelScope.launch(Dispatchers.IO) {
                                    val spans = streamCache.getCachedSpans(rawUri).toList()
                                    for (span in spans) {
                                        try { streamCache.removeSpan(span) } catch (e: Exception) { Log.w(TAG, "removeSpan failed", e) }
                                    }
                                }
                                cacheStatePrefs.edit().remove("pct_${item.id}").remove("spans_${item.id}").apply()
                                _currentCachedSpans.value = emptyList()
                                streamResolver.clearOfflineFallback(rawUri)
                                streamResolver.invalidateUrlCache(item.id)
                                connectedPlayer.seekTo(connectedPlayer.currentMediaItemIndex, 0L)
                                connectedPlayer.play()
                                return
                            }
                        }

                        // 2008 = posición fuera de rango — URL stale, caché parcial, upstream falló
                        if ((error.errorCode == 2008) && _isOnline.value && connectedPlayer.currentMediaItemIndex in currentPlaylist.indices) {
                            val item = currentPlaylist[connectedPlayer.currentMediaItemIndex]
                            if (item is MusicItem.YouTube) {
                                Log.w(TAG, "onPlayerError: position out of range online, purging stale cache for ${item.id}")
                                _isBuffering.value = false
                                _error.value = null
                                val rawUri = "yt://${item.id}"
                                streamResolver.removeContentLength(item.id)
                                viewModelScope.launch(Dispatchers.IO) {
                                    val spans = streamCache.getCachedSpans(rawUri).toList()
                                    for (span in spans) {
                                        try { streamCache.removeSpan(span) } catch (e: Exception) { Log.w(TAG, "removeSpan failed", e) }
                                    }
                                }
                                cacheStatePrefs.edit().remove("pct_${item.id}").remove("spans_${item.id}").apply()
                                _currentCachedSpans.value = emptyList()
                                streamResolver.clearOfflineFallback(rawUri)
                                streamResolver.invalidateUrlCache(item.id)
                                connectedPlayer.seekTo(connectedPlayer.currentMediaItemIndex, 0L)
                                connectedPlayer.play()
                                return
                            }
                        }

                        // 1004 = CacheDataSource upstream con yt:// → Malformed URL (no purgar spans)
                        if ((error.errorCode == 1004) && _isOnline.value && connectedPlayer.currentMediaItemIndex in currentPlaylist.indices) {
                            val item = currentPlaylist[connectedPlayer.currentMediaItemIndex]
                            if (item is MusicItem.YouTube) {
                                Log.w(TAG, "onPlayerError: cache upstream with yt:// URI, retrying with bypass for ${item.id}")
                                _isBuffering.value = false
                                _error.value = null
                                val rawUri = "yt://${item.id}"
                                streamResolver.clearOfflineFallback(rawUri)
                                streamResolver.invalidateUrlCache(item.id)
                                streamResolver.bypassCacheNext(rawUri)
                                connectedPlayer.seekTo(connectedPlayer.currentMediaItemIndex, connectedPlayer.currentPosition)
                                connectedPlayer.play()
                                return
                            }
                        }

                        if (!_isOnline.value && connectedPlayer.currentMediaItemIndex in currentPlaylist.indices) {
                            val item = currentPlaylist[connectedPlayer.currentMediaItemIndex]
                            if (item is MusicItem.YouTube) {
                                // Saved file exists → hand off to local at the same
                                // position instead of running span/skip heuristics
                                if (isSongFullySaved(item.id)) {
                                    _savedSongs.value[item.id]?.let { info ->
                                        musicServiceConnection.replaceTimelineEntry(
                                            "yt://${item.id}", MediaItemTriple(info.uri, info.title, info.artist)
                                        )
                                        Log.d(TAG, "onPlayerError: ${item.id} handed off to local file")
                                    }
                                    _error.value = null
                                    return
                                }
                                val pos = connectedPlayer.currentPosition
                                val spans = _currentCachedSpans.value
                                val lastSpan = spans.lastOrNull()
                                val inAnySpan = spans.any { pos in it.startMs..it.endMs }
                                val inLastSpan = lastSpan != null && pos >= lastSpan.startMs && pos <= lastSpan.endMs
                                val rawUri = "yt://${item.id}"
                                Log.d(TAG, "onPlayerError: offline pos=$pos inAny=$inAnySpan inLast=$inLastSpan spans=${spans.map { "${it.startMs}..${it.endMs}" }}")

                                // Track consecutive errors at same position to break resume loops
                                if (abs(pos - lastErrorPosMs) < 500L) {
                                    consecutiveErrors++
                                } else {
                                    consecutiveErrors = 0
                                }
                                lastErrorPosMs = pos

                                if (consecutiveErrors >= 2) {
                                    Log.d(TAG, "onPlayerError: CONSECUTIVE ERROR #$consecutiveErrors, skip to next track")
                                    consecutiveErrors = 0
                                    lastErrorPosMs = -1L
                                    skipOfflineTrack(connectedPlayer, rawUri)
                                    _error.value = null
                                    return
                                }

                                val isRecentSeek = lastSeekNano > 0L &&
                                    (System.nanoTime() - lastSeekNano) < 5_000_000_000L
                                Log.d(TAG, "onPlayerError: recentSeek=$isRecentSeek lastSeekNano=$lastSeekNano consecErrors=$consecutiveErrors")

                                // If a recent seek caused the error, retrocede un bloque o skip
                                if (isRecentSeek) {
                                    if (spans.isEmpty() || !inAnySpan) {
                                        Log.d(TAG, "onPlayerError: RECENT SEEK dead zone, skip to next")
                                        skipOfflineTrack(connectedPlayer, rawUri)
                                        return
                                    }
                                    val spanIdx = spans.indexOfFirst { pos in it.startMs..it.endMs }
                                    if (spanIdx > 0) {
                                        val prevSpan = spans[spanIdx - 1]
                                        Log.d(TAG, "onPlayerError: RECENT SEEK retrocede un bloque from span $spanIdx (${spans[spanIdx].startMs}) to ${prevSpan.startMs}")
                                        lastSeekNano = 0L
                                        connectedPlayer.seekTo(connectedPlayer.currentMediaItemIndex, prevSpan.startMs)
                                        connectedPlayer.play()
                                        _error.value = null
                                        return
                                    }
                                    Log.d(TAG, "onPlayerError: RECENT SEEK at first span, resume")
                                    connectedPlayer.play()
                                    _error.value = null
                                    return
                                }

                                // Sequential playback (not a seek) → immediate skip, resume is useless offline
                                if (!isRecentSeek) {
                                    Log.d(TAG, "onPlayerError: SEQUENTIAL PLAYBACK end of cache, skip")
                                    skipOfflineTrack(connectedPlayer, rawUri)
                                    return
                                }

                                // Recent seek: skip if beyond cache, else resume
                                val shouldSkip = inLastSpan || !inAnySpan
                                if (shouldSkip) {
                                    Log.d(TAG, "onPlayerError: RECENT SEEK END (inLastSpan=$inLastSpan inAnySpan=$inAnySpan), skip")
                                    skipOfflineTrack(connectedPlayer, rawUri)
                                    return
                                }

                                Log.d(TAG, "onPlayerError: OFFLINE RESUME (recent seek, read-ahead gap, consecutiveErrors=$consecutiveErrors)")
                                connectedPlayer.play()
                                _error.value = null
                                return
                            }
                        }
                        _isBuffering.value = false
                        _error.value = "Playback error: ${error.message}"
                    }
                }
                playerListener = listener
                connectedPlayer.addListener(listener)
                updatePosition(connectedPlayer)
                startPositionPolling()
            }

            override fun onDisconnected() {
                Log.d(TAG, "connectToPlayer: disconnected")
                player = null
            }
        })
    }

    private fun startPositionPolling() {
        if (isPolling) return
        isPolling = true
        var cacheTick = 0
        viewModelScope.launch {
            while (true) {
                player?.let { p ->
                    _currentPosition.value = p.currentPosition.coerceAtLeast(0)
                    val dur = p.duration
                    _duration.value = if (dur > 0) dur else 0L
                }
                // Scan cache every ~1s (every 4th tick) for live cache progress
                if (++cacheTick % 4 == 0 && _currentItem.value is MusicItem.YouTube) {
                    viewModelScope.launch { scanCurrentCacheSpans() }
                }
                delay(250)
            }
        }
    }

    private fun monitorConnectivity() {
        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        _isOnline.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        Log.d(TAG, "monitorConnectivity: initial online=${_isOnline.value}")

        connectivityCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isOnline.value = true
                Log.d(TAG, "monitorConnectivity: online")
                _error.value = null
                // Remove offline fallback markers so resolver uses online resolution
                currentPlaylist.filterIsInstance<MusicItem.YouTube>()
                    .forEach { streamResolver.clearOfflineFallback("yt://${it.id}") }
                // Recover player on main thread (MediaController requires it)
                viewModelScope.launch {
                    val p = player
                    if (p != null && (p.playbackState == androidx.media3.common.Player.STATE_IDLE ||
                            p.playbackState == androidx.media3.common.Player.STATE_ENDED ||
                            p.playerError != null)) {
                        Log.d(TAG, "monitorConnectivity: recovering player from error/ended state")
                        p.stop()
                        p.prepare()
                        p.play()
                    }
                }
            }

            override fun onLost(network: Network) {
                viewModelScope.launch {
                    _cachedPercentages.value = scanPercentagesWithSaved()
                    saveCachePercentages(_cachedPercentages.value)
                }
                _isOnline.value = false
                val ytUris = currentPlaylist.filterIsInstance<MusicItem.YouTube>()
                    .map { "yt://${it.id}" }
                streamResolver.markOfflineFallbackForCached(ytUris, streamCache)
                Log.d(TAG, "monitorConnectivity: offline, cached=${_cachedPercentages.value.size} items")
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (!online) {
                    viewModelScope.launch {
                        _cachedPercentages.value = scanPercentagesWithSaved()
                        saveCachePercentages(_cachedPercentages.value)
                    }
                }
                _isOnline.value = online
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, connectivityCallback)
    }

    private suspend fun scanCurrentCacheSpans() {
        if (!_isOnline.value) {
            Log.d(TAG, "cacheSpans: offline, trusting persisted state")
            return
        }
        val item = _currentItem.value ?: run {
            Log.d(TAG, "cacheSpans: no current item, keeping persisted state")
            return
        }
        if (item is MusicItem.YouTube) {
            // Completed saves DO get real cache tracking here: this function only
            // ever inspects the CURRENT item, so a saved song reaching this point
            // is actively streaming (honest bar + exhaustion handoff need live spans).
            // Idle saved songs are stripped elsewhere; pendings keep growing.
            val key = "yt://${item.id}"
            val dur = _duration.value.coerceAtLeast(0L)
            val ranges = withContext(Dispatchers.IO) { mergedCachedRanges(item.id) }
            val cl = streamResolver.contentLengths[item.id]
            val assumedBitrate = 16384L
            val actualEndBytes = ranges.maxOfOrNull { it.endExclusive } ?: 0L
            val fragmentSize = 512L * 1024
            // Use contentLength if it matches actual bytes within 1 fragment,
            // otherwise use the best available reference to avoid false gaps
            val totalBytes = if (cl != null && cl > 0L && actualEndBytes > 0L &&
                (cl - actualEndBytes) in 0 until fragmentSize) {
                actualEndBytes
            } else if (cl != null && cl > 0L) {
                cl
            } else if (dur > 0L) {
                ((dur / 1000L) * assumedBitrate).coerceAtLeast(1L)
            } else {
                actualEndBytes
            }
            val totalCached = ranges.sumOf { it.endExclusive - it.start }
            Log.d(TAG, "cacheSpans: item=${item.id} cl=$cl dur=$dur totalBytes=$totalBytes actualEndBytes=$actualEndBytes fragmentSize=$fragmentSize ranges=${ranges.size} totalCached=$totalCached")

            // No cached data yet — wait for Cache.Listener to fire when data arrives
            if (totalCached <= 0L) {
                Log.d(TAG, "cacheSpans: no cached data yet, keeping persisted state")
                return
            }

            if (totalBytes > 0L && dur > 0L) {
                // Update percentage for this item only
                val pctRef = if (cl != null && cl > 0L && cl >= totalCached) cl else totalBytes.coerceAtLeast(1L)
                val pct = (totalCached.toFloat() / pctRef.toFloat()).coerceIn(0f, 1f)
                _cachedPercentages.value = _cachedPercentages.value + (item.id to pct)
                saveCachePercentage(item.id, pct)

                _currentCachedSpans.value = ranges.map { r ->
                    val startMs = ((r.start.toFloat() / totalBytes) * dur).toLong()
                    val endMs = ((r.endExclusive.toFloat() / totalBytes) * dur).toLong()
                    CachedTimeSpan(startMs = startMs, endMs = endMs)
                }
                saveCacheSpans(item.id, _currentCachedSpans.value)
            } else {
                Log.d(TAG, "cacheSpans: skipping pct/span save (totalBytes=$totalBytes dur=$dur) — dur not ready yet, keeping persisted state")
            }
        } else {
            Log.d(TAG, "cacheSpans: item is not YouTube (${item?.javaClass?.simpleName})")
            _currentCachedSpans.value = emptyList()
        }
    }

    private fun initCacheListener() {
        val simpleCache = streamCache as? SimpleCache ?: return
        val listener = object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) {
                checkAndScan(span)
            }
            override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
                checkAndScan(span)
            }
            override fun onSpanTouched(cache: Cache, span: CacheSpan, oldCache: CacheSpan) {
                checkAndScan(span)
            }
        }
        cacheListener = listener
        simpleCache.addListener("MainViewModel", listener)
        // Downloads fill the downloadCache in background — mirror their progress into the UI too
        (downloadHelper.downloadCache as? SimpleCache)?.addListener("MainViewModelDl", listener)
    }

    private fun checkAndScan(span: CacheSpan) {
        if (!_isOnline.value) return
        val current = _currentItem.value
        val savedId = (current as? MusicItem.YouTube)?.id
        val isCompletedSave = savedId != null && _savedSongs.value.containsKey(savedId) &&
            !isFilling(savedId)
        if (current is MusicItem.YouTube && span.key == "yt://${current.id}" && !isCompletedSave
        ) {
            viewModelScope.launch { scanCurrentCacheSpans() }
        }
    }

    private suspend fun loadSavedSongs(): Unit = withContext(Dispatchers.IO) {
        val map = mutableMapOf<String, SavedSongInfo>()
        try {
            val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else
                MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.DURATION,
                MediaStore.Files.FileColumns.DATA
            )
            val selection: String
            val args: Array<String>
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
                args = arrayOf("Download%")
            } else {
                selection = "${MediaStore.Files.FileColumns.DATA} LIKE ?"
                args = arrayOf("%/Download/%")
            }
            // Classify by TRUE extension (last dot); locate id by LAST [xxxxxxxxxxx] bracket.
            // Tolerates any suffix junk: "[id].m4a.mp4", "[id].m4a (1) (2).jpg", etc.
            val idInBrackets = Regex("\\[([A-Za-z0-9_-]{11})\\]")
            val audioExts = setOf("webm", "m4a", "ogg", "opus", "mp4")
            val coverExts = setOf("jpg", "jpeg", "png")
            appContext.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val durCol = c.getColumnIndex(MediaStore.Files.FileColumns.DURATION)
                data class Row(val fid: Long, val name: String, val size: Long, val added: Long, val dur: Long, val path: String)
                val rows = mutableListOf<Row>()
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    val pathCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                    rows.add(Row(c.getLong(idCol), name, c.getLong(sizeCol), c.getLong(dateCol),
                        if (durCol >= 0) c.getLong(durCol) else 0L,
                        if (pathCol >= 0) c.getString(pathCol) ?: "" else ""))
                }
                fun uriOf(fid: Long) = android.content.ContentUris.withAppendedId(collection, fid).toString()

                // Best candidates per video id: largest audio file, newest cover
                data class Best(var audio: Row? = null, var cover: Row? = null,
                    var title: String = "", var artist: String = "")
                val perId = mutableMapOf<String, Best>()
                val junkFids = mutableListOf<Long>()
                for (r in rows) {
                    if (r.size <= 0L && r.name.startsWith(".pending-")) { junkFids.add(r.fid); continue }
                    val ext = r.name.substringAfterLast('.', "").lowercase()
                    val kind = when (ext) {
                        in audioExts -> 0
                        in coverExts -> 1
                        else -> -1
                    }
                    if (kind < 0) continue
                    // Zero-size rows are dead exports: failed writes leave undeletable
                    // ghosts on this device (MIUI no-op deletes). Never index them,
                    // otherwise they block re-saving ("Already saved").
                    if (r.size <= 0L) { junkFids.add(r.fid); continue }
                    // Physical truth first: provider rows can outlive their files on this device
                    val exists = r.path.isEmpty() || java.io.File(r.path).exists()
                    if (!exists) { junkFids.add(r.fid); continue }
                    val m = idInBrackets.findAll(r.name).lastOrNull() ?: run {
                        if (r.size <= 0L && r.name.contains('[')) junkFids.add(r.fid)
                        continue
                    }
                    val videoId = m.groupValues[1]
                    val b = perId.getOrPut(videoId) { Best() }
                    // Title - Artist from the prefix before the id bracket
                    val prefix = r.name.substring(0, m.range.first).trim()
                    if (prefix.length > b.title.length + b.artist.length) {
                        val parts = prefix.split(" - ", limit = 2)
                        b.title = parts.getOrNull(0)?.trim() ?: ""
                        b.artist = parts.getOrNull(1)?.trim() ?: ""
                    }
                    if (kind == 0) {
                        val cur = b.audio
                        if (cur == null || r.size > cur.size || (r.size == cur.size && r.fid < cur.fid)) b.audio = r
                        else junkFids.add(r.fid)
                    } else {
                        val cur = b.cover
                        if (cur == null || r.added > cur.added) {
                            cur?.let { junkFids.add(it.fid) }
                            b.cover = r
                        } else junkFids.add(r.fid)
                    }
                }

                // Self-heal: remove duplicate/loser rows we own (same [id] pattern or .pending junk)
                val downloadsUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI else collection
                for (fid in junkFids) {
                    try {
                        // Direct-uri delete on Downloads collection (WHERE-on-Files proved unreliable here)
                        var n = 0
                        runCatching {
                            n += appContext.contentResolver.delete(
                                android.content.ContentUris.withAppendedId(downloadsUri, fid), null, null
                            )
                        }
                        if (n == 0) runCatching {
                            n += appContext.contentResolver.delete(
                                android.content.ContentUris.withAppendedId(collection, fid), null, null
                            )
                        }
                        // Provider no-op fallback: delete the physical file directly
                        var fileDeleted = false
                        if (n == 0) {
                            rows.firstOrNull { it.fid == fid && it.path.isNotEmpty() }?.let { r ->
                                try { fileDeleted = java.io.File(r.path).delete() } catch (_: Exception) {}
                            }
                        }
                        Log.d(TAG, "loadSavedSongs: purged row $fid (deleted=$n fileDeleted=$fileDeleted)")
                    } catch (e: Exception) {
                        Log.w(TAG, "loadSavedSongs: purge failed for $fid", e)
                    }
                }

                for ((videoId, b) in perId) {
                    val audio = b.audio ?: continue
                    Log.d(TAG, "loadSavedSongs: $videoId audio=${audio.name.takeLast(20)} coverFid=${b.cover?.fid}")
                    map[videoId] = SavedSongInfo(
                        uri = uriOf(audio.fid),
                        title = b.title,
                        artist = b.artist,
                        fileName = audio.name,
                        durationMs = audio.dur,
                        size = audio.size,
                        dateAdded = audio.added,
                        thumbnailUri = b.cover?.let { uriOf(it.fid) }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadSavedSongs: failed", e)
        }
        _savedSongs.value = map
        Log.d(TAG, "loadSavedSongs: found ${map.size} saved songs")

        // Completed saves show REAL cache state while actually streaming (honest bar);
        // once idle they carry no cache badge at all. Pendings keep growth state.
        if (map.isNotEmpty()) {
            val liveStreamingId = _currentItem.value?.takeIf { it is MusicItem.YouTube }?.id
            val stripIds = map.keys
                .filter { !isFilling(it) && it != liveStreamingId }
            val editor = cacheStatePrefs.edit()
            var dirty = false
            for (id in stripIds) {
                if (cacheStatePrefs.all.containsKey("pct_$id")) { editor.remove("pct_$id"); dirty = true }
                if (cacheStatePrefs.all.containsKey("spans_$id")) { editor.remove("spans_$id"); dirty = true }
            }
            if (dirty) editor.apply()
            if (stripIds.isNotEmpty()) {
                _cachedPercentages.value = _cachedPercentages.value.filterKeys { it !in stripIds.toSet() }
            }
        }

        // Backfill missing cover art for already-saved songs (needs internet)
        val missingCovers = map.filterValues { it.thumbnailUri == null }
        if (missingCovers.isNotEmpty() && _isOnline.value && !_backfillRunning) {
            _backfillRunning = true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    for ((id, info) in missingCovers) {
                        val bytes = fetchThumbnailBytes(id) ?: continue
                        // Canonical name — never derived from possibly-suffixed fileName
                        val coverName = "${sanitizeFilename(info.title)} - ${sanitizeFilename(info.artist)} [$id].jpg"
                        val coverUri = insertCoverToDownloads(coverName, bytes) ?: continue
                        _savedSongs.value[id]?.let { cur ->
                            _savedSongs.value = _savedSongs.value + (id to cur.copy(thumbnailUri = coverUri.toString()))
                        }
                        Log.d(TAG, "loadSavedSongs: backfilled cover for $id")
                        viewModelScope.launch { refreshLiveItemArt(id) }
                    }
                    loadSavedSongs()
                } finally {
                    _backfillRunning = false
                }
            }
        }
    }

    @Volatile private var _backfillRunning = false

    private fun fetchThumbnailBytes(videoId: String): ByteArray? {
        for (url in listOf(
            "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        )) {
            try {
                okHttpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val bytes = resp.body?.bytes()
                    // Skip tiny gray placeholder images (~1KB)
                    if (bytes != null && bytes.size > 2048) return bytes
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchThumbnailBytes: failed $url", e)
            }
        }
        return null
    }

    private fun insertCoverToDownloads(name: String, bytes: ByteArray): android.net.Uri? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Replace previous COVERS only — never touch sibling audio files
                deleteSiblingsWithExts(name.substringBeforeLast('.'), setOf("jpg", "jpeg", "png"))
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = appContext.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                appContext.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null
                )
                uri
            } else {
                val f = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    name
                )
                f.outputStream().use { it.write(bytes) }
                android.net.Uri.fromFile(f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "insertCoverToDownloads: failed for $name", e)
            null
        }
    }

    fun playSavedSong(id: String) {
        // Fill in progress: keep streaming so the cache keeps growing toward completion
        if (isFilling(id)) {
            combinedSongs.value.find { it.id == id }?.let { playMusicItem(it) }
            return
        }
        val info = _savedSongs.value[id] ?: return
        viewModelScope.launch {
            Log.d(TAG, "playSavedSong: playing local file for $id -> ${info.uri}")
            val local = (combinedSongs.value.find { it.id == id } as? MusicItem.Local)
                ?: run {
                    val file = musicScanner.deviceSongs.value.find { it.uri == info.uri }
                        ?: LocalAudioFile(
                            id = info.uri, title = info.title, artist = info.artist, album = null,
                            durationMs = 0L, uri = info.uri, size = 0L, dateAdded = 0L
                        )
                    MusicItem.Local(file, info.thumbnailUri)
                }
            playMusicItem(local)
        }
    }

    private fun sanitizeFilename(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).trim()

    private data class ByteRange(val start: Long, val endExclusive: Long)

    private fun mergeRanges(ranges: List<Pair<Long, Long>>): List<ByteRange> {
        val sorted = ranges.filter { it.second > it.first }.sortedBy { it.first }
        val merged = mutableListOf<ByteRange>()
        for ((s, e) in sorted) {
            val last = merged.lastOrNull()
            if (last != null && s <= last.endExclusive) {
                if (e > last.endExclusive) merged[merged.lastIndex] = last.copy(endExclusive = e)
            } else {
                merged.add(ByteRange(s, e))
            }
        }
        return merged
    }

    private fun cachedRangesOf(cache: Cache, key: String): List<Pair<Long, Long>> =
        cache.getCachedSpans(key).filter { it.length > 0 }.map { it.position to it.position + it.length }

    /** Union of cached byte ranges across stream + download caches for a yt id */
    private fun mergedCachedRanges(id: String, upTo: Long? = null): List<ByteRange> {
        val key = "yt://$id"
        val raw = cachedRangesOf(streamCache, key) +
            runCatching { cachedRangesOf(downloadHelper.downloadCache, key) }.getOrDefault(emptyList())
        val clamped = if (upTo != null && upTo > 0L)
            raw.mapNotNull { (s, e) ->
                val ns = s.coerceAtMost(upTo); val ne = e.coerceAtMost(upTo)
                if (ne > ns) ns to ne else null
            }
        else raw
        return mergeRanges(clamped)
    }

    private suspend fun purgeCaches(id: String) = withContext(Dispatchers.IO) {
        // An active unified fill owns this id's download + spans — never purge it
        if (isFilling(id)) {
            Log.d(TAG, "purgeCaches: $id is filling — skipped")
            return@withContext
        }
        val key = "yt://$id"
        try {
            for (span in streamCache.getCachedSpans(key)) {
                try { streamCache.removeSpan(span) } catch (_: Exception) {}
            }
            Log.d(TAG, "purgeCaches: removed stream spans for $id")
        } catch (e: Exception) {
            Log.w(TAG, "purgeCaches: span removal failed for $id", e)
        }
        try { downloadHelper.removeDownload(dlKey(id)) } catch (e: Exception) {
            Log.w(TAG, "purgeCaches: removeDownload failed for $id", e)
        }
        streamResolver.removeContentLength(id)
        streamResolver.invalidateUrlCache(id)

        // MVVM: drop cache state for this id everywhere (list badges, persisted %)
        cacheStatePrefs.edit().remove("pct_$id").remove("spans_$id").apply()
        _cachedPercentages.value = _cachedPercentages.value - id
    }

    /**
     * Rebuilds the playlist entry and current item for [id] from the latest
     * SavedSongInfo so newly-inserted cover art / metadata reaches the player
     * without leaving the screen. No-op if the track isn't live.
     */
    private fun refreshLiveItemArt(id: String) {
        val info = _savedSongs.value[id] ?: return
        val refreshed = MusicItem.Local(info.toLocalAudioFile(), info.thumbnailUri)
        val idx = currentPlaylist.indexOfFirst { pl -> pl.id == refreshed.id }
        if (idx >= 0) {
            currentPlaylist = currentPlaylist.toMutableList().also { it[idx] = refreshed }
            _currentPlaylistFlow.value = currentPlaylist
        }
        if (_currentItem.value?.id == refreshed.id) {
            _currentItem.value = refreshed
            Log.d(TAG, "exportToSd: player artwork refreshed for $id")
        }
    }

    /**
     * Unified save-to-device: arms a Media3 download into downloadCache (key
     * yt://id) and immediately writes a snapshot of whatever contiguous prefix
     * is already cached, reusing any existing MediaStore row ("wt" mode, never
     * "(1)" copies). When the download reaches STATE_COMPLETED the downloads
     * watcher calls [exportFromCache] to rewrite the file in full.
     */
    private suspend fun exportToSd(id: String) = withContext(Dispatchers.IO) {
        if (_exportingIds.value.contains(id)) return@withContext
        if (isSongFullySaved(id)) {
            _error.value = "Already saved to device"
            return@withContext
        }
        _exportingIds.value = _exportingIds.value + id
        try {
            val item = combinedSongs.value.find { it.id == id }
                ?: run {
                    _error.value = "Song not found in library"
                    return@withContext
                }
            val mimeRaw = streamResolver.mimeTypes[id] ?: "audio/webm"
            val mime = mimeRaw.substringBefore(';').trim()
            val ext = when {
                mime.contains("webm") -> ".webm"
                mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> ".m4a"
                mime.contains("ogg") -> ".ogg"
                mime.contains("opus") -> ".opus"
                else -> ".webm"
            }
            val displayName = "${sanitizeFilename(item.title)} - ${sanitizeFilename(item.subtitle)} [$id]$ext"

            val key = dlKey(id)

            // Real coverage across BOTH caches — download spans share the yt:// key
            val segments = mergedCachedRanges(id)
            var contentLength = streamResolver.contentLengths[id] ?: 0L

            val resolved = try { streamResolver.resolveStreamUrlPublic(id) } catch (e: Exception) {
                Log.w(TAG, "exportToSd: resolve failed", e); null
            }
            if (resolved != null && resolved.contentLength > contentLength) {
                contentLength = resolved.contentLength
                streamResolver.setContentLength(id, contentLength)
            }

            // Contiguous prefix from byte 0 — the only part safe to write offline
            var prefixEnd = 0L
            for (seg in segments) {
                if (seg.start <= prefixEnd) prefixEnd = seg.endExclusive else break
            }

            val dlState = downloadHelper.downloads.value[key]?.state
            val alreadyTracked = dlState != null && dlState != Download.STATE_FAILED

            // Protection: nothing cached, offline and nothing queued → no empty file
            if (!_isOnline.value && prefixEnd <= 0L && !alreadyTracked) {
                _error.value = "Nothing cached yet and no internet — play online once first"
                return@withContext
            }
            Log.d(TAG, "exportToSd: id=$id mime=$mime ext=$ext name='$displayName' cachedPrefix=$prefixEnd cl=$contentLength url=${resolved?.url != null}")

            // Arm the fill BEFORE writing so badge/bar state stays consistent
            setFilling(id, displayName)
            ensureFill(id, item.title)

            // Immediate snapshot of the contiguous prefix. Reuses any existing
            // row ("wt" truncates it) — "(1)" duplicates can never accumulate.
            if (prefixEnd > 0L) {
                writeSnapshot(displayName, ext, id, prefixEnd)
            }
            if (dlState == Download.STATE_COMPLETED) {
                // Data was already fully downloaded before this press → done now
                exportFromCache(id, fromSaveFlow = true)
            } else {
                viewModelScope.launch { loadSavedSongs() }
                Log.d(TAG, "exportToSd: fill armed for $id (${prefixEnd / 1024} KB snapshot)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "exportToSd: failed", e)
            _error.value = "Save failed: ${e.message}"
        } finally {
            _exportingIds.value = _exportingIds.value - id
        }
    }

    /**
     * Resolves the export target for [displayName]: reuses an existing audio
     * row with the same base name and true extension, or creates a fresh
     * IS_PENDING MediaStore row. Returns the uri plus whether we created it.
     */
    private fun resolveExportTarget(displayName: String, ext: String): Pair<android.net.Uri, Boolean> {
        val base = displayName.substringBeforeLast('.')
        findExistingExportUri(base, ext)?.let { return it to false }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = appContext.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("Failed to create Downloads entry")
            return uri to true
        }
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            displayName
        )
        return android.net.Uri.fromFile(file) to true
    }

    private fun findExistingExportUri(base: String, ext: String): android.net.Uri? {
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        else MediaStore.Files.getContentUri("external")
        return try {
            appContext.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("$base%"),
                null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val n = c.getString(nameCol) ?: continue
                    if (n.substringAfterLast('.', "").equals(ext.removePrefix("."), ignoreCase = true)) {
                        return@findExistingExportUri android.content.ContentUris.withAppendedId(collection, c.getLong(idCol))
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "findExistingExportUri: failed base='$base'", e)
            null
        }
    }

    /**
     * Writes the contiguous cached prefix [0, prefixEnd) of [id] into the
     * export row in truncate mode. On failure deletes ONLY rows this call
     * created — a pre-existing user file is never touched by cleanup.
     */
    private suspend fun writeSnapshot(
        displayName: String,
        ext: String,
        id: String,
        prefixEnd: Long
    ): android.net.Uri = withContext(Dispatchers.IO) {
        var createdRow: android.net.Uri? = null
        try {
            val (targetUri, wasCreated) = resolveExportTarget(displayName, ext)
            if (wasCreated) createdRow = targetUri

            // Prefer one cache per write pass; spans inside a cache never overlap.
            val key = dlKey(id)
            val scSpans = streamCache.getCachedSpans(key).filter { it.length > 0 }.sortedBy { it.position }
            val dcSpans = runCatching {
                downloadHelper.downloadCache.getCachedSpans(key).filter { it.length > 0 }.sortedBy { it.position }
            }.getOrDefault(emptyList())

            val output = if (targetUri.scheme == "content")
                appContext.contentResolver.openOutputStream(targetUri, "wt")
                    ?: throw Exception("Cannot open output stream")
            else FileOutputStream(targetUri.path!!)
            var cursor = 0L
            try {
                while (cursor < prefixEnd) {
                    // Advance over whichever cache holds data at `cursor`
                    val span = (scSpans + dcSpans).firstOrNull {
                        cursor >= it.position && cursor < it.position + it.length && it.file?.exists() == true
                    } ?: break // gap → stop at the frontier
                    val take = minOf(span.position + span.length, prefixEnd) - cursor
                    span.file!!.inputStream().use { input ->
                        input.skip(cursor - span.position)
                        val buf = ByteArray(64 * 1024)
                        var left = take
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            output.write(buf, 0, n)
                            left -= n
                        }
                    }
                    cursor += take
                }
                output.flush()
            } finally {
                runCatching { output.close() }
            }

            if (targetUri.scheme == "content") {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                runCatching { appContext.contentResolver.update(targetUri, done, null, null) }
            }
            Log.d(TAG, "writeSnapshot: $id wrote $cursor bytes into $targetUri")
            targetUri
        } catch (e: Exception) {
            Log.e(TAG, "writeSnapshot: failed for $id", e)
            // Remove only rows this call created — never a pre-existing file
            createdRow?.let { runCatching { appContext.contentResolver.delete(it, null, null) } }
            throw e
        }
    }

    /**
     * Copies the completed Media3 download (key yt://id) from downloadCache
     * into the Downloads entry, replacing any partial snapshot. Invoked by the
     * downloads watcher on STATE_COMPLETED and by startup sweeps.
     */
    private suspend fun exportFromCache(id: String, fromSaveFlow: Boolean = false) = withContext(Dispatchers.IO) {
        if (!isFilling(id)) return@withContext // already exported / never armed
        // A snapshot write may hold the row — let it finish first (watcher/sweep only)
        if (!fromSaveFlow && _exportingIds.value.contains(id)) {
            _exportingIds.first { !it.contains(id) }
            if (!isFilling(id)) return@withContext
        }
        val key = dlKey(id)
        try {
            val spans = downloadHelper.downloadCache.getCachedSpans(key)
                .filter { it.length > 0 }.sortedBy { it.position }
            if (spans.isEmpty()) {
                Log.w(TAG, "exportFromCache: no spans for $id — staying armed")
                return@withContext
            }
            // Contiguity check: one merged range starting at byte 0
            val ranges = mergeRanges(spans.map { it.position to it.position + it.length })
            if (ranges.size != 1 || ranges.first().start != 0L) {
                Log.w(TAG, "exportFromCache: non-contiguous spans for $id (${ranges.size} runs) — staying armed")
                return@withContext
            }
            // Completeness check: never export less than the expected total
            val expected = maxOf(
                streamResolver.contentLengths[id] ?: 0L,
                downloadHelper.downloads.value[key]?.contentLength ?: 0L
            )
            val covered = ranges.first().endExclusive
            if (expected > 0L && covered < expected - 1024L) {
                Log.w(TAG, "exportFromCache: $id has $covered/$expected bytes — staying armed")
                return@withContext
            }

            val displayName = cacheStatePrefs.getString("fillname_$id", null)
                ?: combinedSongs.value.find { it.id == id }?.let {
                    "${sanitizeFilename(it.title)} - ${sanitizeFilename(it.subtitle)} [$id].webm"
                }
                ?: run {
                    Log.w(TAG, "exportFromCache: no name for $id — clearing fill marker")
                    clearFilling(id)
                    return@withContext
                }
            val ext = "." + displayName.substringAfterLast('.', "webm")

            val (targetUri, _) = resolveExportTarget(displayName, ext)
            val output = if (targetUri.scheme == "content")
                appContext.contentResolver.openOutputStream(targetUri, "wt")
                    ?: throw Exception("Cannot open output stream for export")
            else FileOutputStream(targetUri.path!!)
            try {
                for (span in spans) {
                    val f = span.file ?: continue
                    if (!f.exists()) continue
                    f.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                }
                output.flush()
            } finally {
                runCatching { output.close() }
            }
            if (targetUri.scheme == "content") {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                appContext.contentResolver.update(targetUri, done, null, null)
            }
            Log.d(TAG, "exportFromCache: $id fully written (${ranges.first().endExclusive / 1024} KB)")

            // Finalize as a completed save (library replacement is safe even
            // while playing this item — playback itself is never touched).
            repository.deleteSong(id)
            recentPrefs.edit()
                .remove("title_$id").remove("subtitle_$id").remove("thumb_$id")
                .apply()
            val filteredRecent = _recentIds.value.filter { it != id }
            _recentIds.value = filteredRecent
            saveRecentIds(filteredRecent)
            localThumbnails.value = localThumbnails.value - id
            File(File(appContext.filesDir, "thumbnails"), "$id.jpg").delete()
            clearFilling(id)

            viewModelScope.launch {
                musicScanner.scan()
                loadSavedSongs()
                refreshLiveItemArt(id)
            }
            pendingPurgeIds.add(id) // freed when the user leaves this track

            // Cover art now that we're done with the heavy write
            val info = _savedSongs.value[id]
            if (info?.thumbnailUri == null) try {
                fetchThumbnailBytes(id)?.let { bytes ->
                    insertCoverToDownloads(displayName.substringBeforeLast('.') + ".jpg", bytes)
                }
            } catch (e: Exception) {
                Log.w(TAG, "exportFromCache: cover save failed (non-fatal)", e)
            }

            _error.value = "Saved to device"
        } catch (e: Exception) {
            Log.e(TAG, "exportFromCache: failed for $id", e)
            // Stays armed — retried by the watcher/sweep on the next trigger
        }
    }

    /**
     * Deletes previous exports of the same song matching [base]% but ONLY those whose
     * true extension is in [exts] — never touches sibling files (audio vs cover).
     */
    private fun deleteSiblingsWithExts(base: String, exts: Set<String>) {
        val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        else MediaStore.Files.getContentUri("external")
        try {
            val victimFids = mutableListOf<Long>()
            appContext.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("$base%"),
                null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val n = c.getString(nameCol) ?: continue
                    val trueExt = n.substringAfterLast('.', "").lowercase()
                    if (trueExt in exts) victimFids.add(c.getLong(idCol))
                }
            }
            for (fid in victimFids) runCatching {
                appContext.contentResolver.delete(
                    android.content.ContentUris.withAppendedId(uri, fid), null, null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteSiblingsWithExts: failed base='$base'", e)
        }
    }

    /** One-time cleanup of the retired pc_/pce_/pcd_ pending-partial markers. */
    private fun stripLegacyPendingMarkers() {
        val editor = cacheStatePrefs.edit()
        var dirty = false
        for (k in cacheStatePrefs.all.keys) {
            if (k.startsWith("pc_") || k.startsWith("pce_") || k.startsWith("pcd_")) {
                editor.remove(k); dirty = true
            }
        }
        if (dirty) editor.apply()
    }

    /**
     * Downloads created before the yt:// key unification are invisible to every
     * span lookup — drop them so they can't squat in the 512MB LRU forever.
     */
    private fun migrateLegacyDownloads(keys: Set<String>) {
        for (key in keys.filter { !it.startsWith("yt://") }) {
            Log.d(TAG, "sweep: removing legacy raw-key download '$key'")
            try { downloadHelper.removeDownload(key) } catch (e: Exception) {
                Log.w(TAG, "sweep: legacy removal failed for '$key'", e)
            }
        }
    }

    /**
     * Fills armed before the app died: wake the download service (Media3 keeps
     * its queue across restarts), export anything already COMPLETED, and
     * requeue entries whose Media3 record vanished while the marker survived.
     */
    private suspend fun resumeInterruptedFills() {
        val fillingIds = cacheStatePrefs.all.keys
            .filter { it.startsWith("fill_") }
            .map { it.removePrefix("fill_") }
        if (fillingIds.isEmpty()) return
        Log.d(TAG, "sweep: ${fillingIds.size} interrupted fill(s): $fillingIds")
        try {
            DownloadService.start(appContext, MyDownloadService::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "sweep: failed to start download service", e)
        }
        for (id in fillingIds) {
            val title = combinedSongs.value.find { it.id == id }?.title ?: id
            when {
                downloadHelper.downloads.value[dlKey(id)]?.state == Download.STATE_COMPLETED &&
                    downloadCoverageComplete(id) -> exportFromCache(id)
                else -> ensureFill(id, title) // re-queue false completions, nulls, or not-yet-started
            }
        }
    }

    private suspend fun startupSweep(initialKeys: Set<String>) {
        stripLegacyPendingMarkers()
        migrateLegacyDownloads(initialKeys)
        resumeInterruptedFills()
        // The download index loads asynchronously — re-sweep once it settles so
        // late-arriving legacy keys / completed fills are handled too.
        delay(2500)
        migrateLegacyDownloads(downloadHelper.downloads.value.keys)
        resumeInterruptedFills()
    }

    @Volatile private var lastExhaustionCheckMs = 0L

    private fun updatePosition(player: androidx.media3.common.Player) {
        _currentPosition.value = player.currentPosition
        _duration.value = player.duration
        // Offline exhaustion: playhead reached the end of real cached data and the
        // song has a saved local file → seamless handoff instead of a stall/error.
        if (!_isOnline.value) {
            val now = System.currentTimeMillis()
            val item = _currentItem.value
            if (now - lastExhaustionCheckMs > 1200 &&
                item is MusicItem.YouTube && isSongFullySaved(item.id)
            ) {
                lastExhaustionCheckMs = now
                val cachedEndMs = _currentCachedSpans.value.maxOfOrNull { it.endMs } ?: 0L
                val pos = player.currentPosition
                if (cachedEndMs > 0L && pos >= cachedEndMs - 1500L && pos < player.duration) {
                    handoffToLocal(item.id, "offline cache exhausted at ${pos}ms (cachedEnd=${cachedEndMs}ms)")
                }
            }
        }
    }

    fun scanDeviceSongs() {
        viewModelScope.launch {
            musicScanner.scan()
        }
    }

    fun isDownloaded(songId: String) = downloadHelper.getDownload(songId).map { it != null }

    fun search(query: String) {
        _searchQueryDebounced.value = query
    }

    private val _searchQueryDebounced = MutableStateFlow("")

    init {
        viewModelScope.launch {
            _searchQueryDebounced
                .debounce(500L)
                .distinctUntilChanged()
                .collect { query ->
                    if (query.length < 3) return@collect
                    _isSearching.value = true
                    _error.value = null
                    Log.d(TAG, "search: query='$query' online=${_isOnline.value}")
                    if (!_isOnline.value) {
                        Log.d(TAG, "search: offline, keeping saved results")
                        _isSearching.value = false
                        return@collect
                    }
                    try {
                        val results = innertube.search(query)
                        _searchResults.value = results
                        if (results.isNotEmpty()) {
                            saveSearchState(query, results)
                        }
                        Log.d(TAG, "search: got ${results.size} results")
                    } catch (e: Exception) {
                        Log.e(TAG, "search: failed", e)
                        _searchResults.value = emptyList()
                    } finally {
                        _isSearching.value = false
                    }
                }
        }
    }

    fun playMusicItem(item: MusicItem, playlist: List<MusicItem>? = null) {
        Log.d(TAG, "playMusicItem: id=${item.id} title='${item.title}' playlistSize=${playlist?.size}")
        if (item is MusicItem.Local) {
            viewModelScope.launch {
                if (!musicScanner.fileExists(item.file.uri)) {
                    _error.value = "File not found: ${item.file.title}"
                    return@launch
                }
                playWithPlaylist(item, playlist)
            }
        } else {
            playWithPlaylist(item, playlist)
        }
    }

    private fun playWithPlaylist(item: MusicItem, customPlaylist: List<MusicItem>? = null) {
        val ids = _recentIds.value.toMutableList()
        ids.remove(item.id)
        ids.add(0, item.id)
        if (ids.size > 50) ids.removeAt(ids.lastIndex)
        _recentIds.value = ids
        saveRecentIds(ids)
        recentPrefs.edit()
            .putString("title_${item.id}", item.title)
            .putString("subtitle_${item.id}", item.subtitle)
            .putString("thumb_${item.id}", item.thumbnailUrl)
            .apply()
        val playlist = customPlaylist ?: combinedSongs.value
        val index = playlist.indexOfFirst { it.id == item.id }
        currentPlaylist = if (index >= 0) playlist else listOf(item)
        _currentPlaylistFlow.value = currentPlaylist
        _currentItem.value = item
        val loaded = loadCacheSpans(item.id)
        _currentCachedSpans.value = loaded
        if (item is MusicItem.YouTube) {
            _loadingItemIds.value = _loadingItemIds.value + item.id
        }
        val tracks = currentPlaylist.map { mi ->
            when (mi) {
                is MusicItem.YouTube -> com.music.app.player.MediaItemTriple("yt://${mi.song.id}", mi.song.title, mi.song.artists)
                is MusicItem.Local -> com.music.app.player.MediaItemTriple(mi.file.uri, mi.file.title, mi.file.artist)
            }
        }
        musicServiceConnection.playList(tracks, (index).coerceAtLeast(0))
        viewModelScope.launch {
            currentPlaylist.filterIsInstance<MusicItem.YouTube>().forEach { yt ->
                try { repository.saveSong(yt.song) } catch (e: Exception) { Log.e(TAG, "save: failed", e) }
            }
        }
    }

    fun deleteMusicItem(item: MusicItem) {
        Log.d(TAG, "deleteMusicItem: id=${item.id} title='${item.title}'")
        when (item) {
            is MusicItem.YouTube -> {
                viewModelScope.launch {
                    try {
                        repository.deleteSong(item.song.id)
                        clearFilling(item.song.id)
                        downloadHelper.removeDownload(dlKey(item.song.id))
                        Log.d(TAG, "deleteMusicItem: deleted YouTube song")
                        _error.value = "Deleted: ${item.title}"
                    } catch (e: Exception) {
                        Log.e(TAG, "deleteMusicItem: failed", e)
                        _error.value = "Failed to delete: ${e.message}"
                    }
                }
            }
            is MusicItem.Local -> {
                val intentSender = musicScanner.prepareDeleteIntent(item.file.uri)
                if (intentSender != null) {
                    _pendingDeleteIntent.value = intentSender
                    _pendingDeleteItem.value = item
                } else {
                    deleteLocalFileFinal(item)
                }
            }
        }
    }

    fun onDeleteIntentResult(success: Boolean) {
        val item = _pendingDeleteItem.value
        _pendingDeleteIntent.value = null
        _pendingDeleteItem.value = null
        if (success && item is MusicItem.Local) {
            deleteLocalFileFinal(item)
        } else if (!success) {
            _error.value = "Deletion cancelled or failed"
        }
    }

    private fun deleteLocalFileFinal(item: MusicItem.Local) {
        viewModelScope.launch {
            try {
                val deleted = musicScanner.deleteDirectly(item.file.uri)
                if (deleted) {
                    Log.d(TAG, "deleteLocalFileFinal: file deleted")
                } else {
                    Log.w(TAG, "deleteLocalFileFinal: file may not have been deleted")
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteLocalFileFinal: direct delete failed", e)
            } finally {
                musicScanner.removeFromCacheAndList(item.file.uri)
                if (_currentItem.value?.id == item.id) {
                    _currentItem.value = null
                }
                _error.value = "Deleted: ${item.title}"
            }
        }
    }

    fun togglePlayPause() {
        musicServiceConnection.togglePlayPause()
    }

    private fun skipOfflineTrack(player: androidx.media3.common.Player, rawUri: String) {
        val nextUris = currentPlaylist.drop(player.currentMediaItemIndex + 1)
            .filterIsInstance<MusicItem.YouTube>()
            .map { "yt://${it.id}" }
        streamResolver.markOfflineFallbackForCached(nextUris, streamCache)
        val hasCachedNext = nextUris.any { uri ->
            streamCache.getCachedSpans(uri).any { it.length > 0L } ||
                runCatching {
                    downloadHelper.downloadCache.getCachedSpans(uri).any { it.length > 0L }
                }.getOrDefault(false)
        }
        if (nextUris.isEmpty() || !hasCachedNext) {
            Log.d(TAG, "skipOfflineTrack: no cached items remaining, stopping")
            player.stop()
            _error.value = "No cached tracks available offline"
            return
        }
        player.seekToNextMediaItem()
        player.play()
        _error.value = null
    }

    fun seekTo(position: Long) {
        lastSeekNano = System.nanoTime()
        consecutiveErrors = 0
        lastErrorPosMs = -1L
        Log.d(TAG, "seekTo: position=$position lastSeekNano=$lastSeekNano")
        val p = player
        if (p != null && p.currentMediaItemIndex in currentPlaylist.indices) {
            val item = currentPlaylist[p.currentMediaItemIndex]
            if (item is MusicItem.YouTube && isSongFullySaved(item.id)) {
                val maxCached = _currentCachedSpans.value.maxOfOrNull { it.endMs } ?: 0L
                if (position > maxCached + 500L) {
                    // Target lies in uncached territory: switch to the saved local
                    // file instead of re-fetching that block over the network.
                    handoffToLocal(item.id, "seek to ${position}ms past cachedEnd=${maxCached}ms")
                }
            } else if (_isOnline.value && item is MusicItem.YouTube) {
                val maxCached = _currentCachedSpans.value.maxOfOrNull { it.endMs }
                if (maxCached == null || position > maxCached) {
                    Log.d(TAG, "seekTo: posición=$position fuera de caché ($maxCached), forzando upstream para ${item.id}")
                    streamResolver.bypassCacheNext("yt://${item.id}")
                }
            }
        }
        musicServiceConnection.seekTo(position)
        viewModelScope.launch { scanCurrentCacheSpans() }
    }

    fun skipNext() {
        musicServiceConnection.skipNext()
    }

    fun skipPrevious() {
        musicServiceConnection.skipPrevious()
    }

    fun toggleShuffle() {
        val enabled = !_shuffleEnabled.value
        _shuffleEnabled.value = enabled
        Log.d(TAG, "toggleShuffle: enabled=$enabled")
        musicServiceConnection.setShuffleModeEnabled(enabled)
    }

    fun cycleRepeatMode() {
        val next = when (_repeatMode.value) {
            androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
            androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
        _repeatMode.value = next
        Log.d(TAG, "cycleRepeatMode: next=$next")
        musicServiceConnection.setRepeatMode(next)
    }

    fun saveToLocal(result: SearchResult) {
        Log.d(TAG, "saveToLocal: id=${result.id} title='${result.title}' duration='${result.durationText}'")
        viewModelScope.launch {
            try {
                streamResolver.setDuration(result.id, result.durationText)
                val song = Song(
                    id = result.id,
                    title = result.title,
                    artists = result.artists,
                    durationText = result.durationText,
                    thumbnailUrl = result.thumbnailUrl,
                    albumName = result.albumName,
                    albumId = null
                )
                repository.saveSong(song)
                _currentItem.value = MusicItem.YouTube(song)
                playMusicItem(_currentItem.value!!)
                Log.d(TAG, "saveToLocal: saved and playing")
            } catch (e: Exception) {
                Log.e(TAG, "saveToLocal: failed", e)
            }
        }
    }

    fun toggleDownload(songId: String, title: String) {
        Log.d(TAG, "toggleDownload: songId=$songId title='$title'")
        viewModelScope.launch {
            val key = dlKey(songId)
            val download = downloadHelper.downloads.value[key]
            if (download != null) {
                Log.d(TAG, "toggleDownload: removing download")
                clearFilling(songId) // cancelling also disarms any unified fill
                downloadHelper.removeDownload(key)
            } else {
                if (!_isOnline.value) {
                    _error.value = "No internet — download queued, starts automatically when online"
                }
                Log.d(TAG, "toggleDownload: adding download")
                downloadHelper.addDownload(key, title)
            }
        }
    }

    fun downloadSong(result: SearchResult) {
        Log.d(TAG, "downloadSong: id=${result.id} title='${result.title}'")
        viewModelScope.launch {
            _error.value = null
            try {
                streamResolver.setDuration(result.id, result.durationText)
                val song = Song(
                    id = result.id,
                    title = result.title,
                    artists = result.artists,
                    durationText = result.durationText,
                    thumbnailUrl = result.thumbnailUrl,
                    albumName = result.albumName,
                    albumId = null
                )
                repository.saveSong(song)
                downloadHelper.addDownload(dlKey(result.id), result.title)
                Log.d(TAG, "downloadSong: song saved and download started")
            } catch (e: Exception) {
                Log.e(TAG, "downloadSong: failed", e)
                _error.value = "Download failed: ${e.message}"
            }
        }
    }

    /** Unified cache key for a YouTube id — used by BOTH caches and downloads. */
    private fun dlKey(id: String) = "yt://$id"

    /** Number of songs with any data in streamCache or downloadCache. */
    fun cachedSongCount(): Int = runCatching {
        val streamKeys = streamCache.keys.mapNotNull {
            it.removePrefix("yt://").takeIf { id -> id.isNotEmpty() }
        }
        val dlKeys = runCatching {
            downloadHelper.downloadCache.keys.mapNotNull {
                it.removePrefix("yt://").takeIf { id -> id.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
        (streamKeys + dlKeys).distinct().size
    }.getOrDefault(0)

    /** Number of Media3 downloads that are queued/downloading but not completed. */
    fun incompleteDownloadCount(): Int = downloadHelper.downloads.value.values.count {
        it.state in intArrayOf(
            androidx.media3.exoplayer.offline.Download.STATE_QUEUED,
            androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING,
            androidx.media3.exoplayer.offline.Download.STATE_RESTARTING
        )
    }

    /** Resume all incomplete Media3 downloads. */
    fun resumeAllDownloads() {
        downloadHelper.downloads.value.values.forEach { dl ->
            if (dl.state in intArrayOf(
                    androidx.media3.exoplayer.offline.Download.STATE_QUEUED,
                    androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING,
                    androidx.media3.exoplayer.offline.Download.STATE_RESTARTING
                )
            ) {
                val id = dl.request.id.removePrefix("yt://")
                val title = combinedSongs.value.find { it.id == id }?.title ?: id
                downloadHelper.addDownload(dl.request.id, title)
            }
        }
    }

    /** True while a unified fill (save-to-device) is armed but not yet exported. */
    private fun isFilling(id: String): Boolean = cacheStatePrefs.getInt("fill_$id", 0) == 1

    private fun setFilling(id: String, displayName: String) {
        cacheStatePrefs.edit()
            .putInt("fill_$id", 1)
            .putString("fillname_$id", displayName)
            .apply()
    }

    private fun clearFilling(id: String) {
        cacheStatePrefs.edit()
            .remove("fill_$id")
            .remove("fillname_$id")
            .apply()
    }

    /** True when the Media3 download cache contains the expected amount of data. */
    private fun downloadCoverageComplete(id: String): Boolean {
        val key = dlKey(id)
        val spans = runCatching {
            downloadHelper.downloadCache.getCachedSpans(key).filter { it.length > 0 }
        }.getOrDefault(emptyList())
        if (spans.isEmpty()) return false
        val ranges = mergeRanges(spans.map { it.position to it.position + it.length })
        if (ranges.size != 1 || ranges.first().start != 0L) return false
        val expected = maxOf(
            streamResolver.contentLengths[id] ?: 0L,
            downloadHelper.downloads.value[key]?.contentLength ?: 0L
        )
        if (expected <= 0L) return true // can't know — assume ok
        return ranges.first().endExclusive >= expected - 1024L
    }

    /**
     * Queue the Media3 download for [id] unless it already exists and is
     * legitimately complete. A false-completion (past EOF before filling all
     * bytes) is removed and re-queued so the fill can converge.
     */
    private fun ensureFill(id: String, title: String) {
        val key = dlKey(id)
        val existing = downloadHelper.downloads.value[key]
        if (existing != null) {
            if (existing.state == Download.STATE_COMPLETED &&
                !downloadCoverageComplete(id) && _isOnline.value
            ) {
                Log.d(TAG, "ensureFill: falsely-completed download $id — redoing")
                downloadHelper.removeDownload(key)
            } else {
                return // downloading/queued/stopped — let it ride
            }
        }
        downloadHelper.addDownload(key, title)
    }

    /** True when the song has a COMPLETE local copy (not an in-progress fill). */
    fun isSongFullySaved(id: String): Boolean =
        _savedSongs.value.containsKey(id) && !isFilling(id)

    /** Returns (isSaving, progress) for the UI download button. */
    fun saveState(id: String): Pair<Boolean, Float> {
        val key = dlKey(id)
        val dl = downloadHelper.downloads.value[key] ?: return false to 0f
        val saving = isFilling(id) && dl.state != Download.STATE_COMPLETED
        val pct = if (dl.contentLength > 0)
            (dl.percentDownloaded).coerceIn(0f, 1f) else 0f
        return saving to pct
    }

    fun saveToDevice(id: String) {
        if (_exportingIds.value.contains(id)) return
        if (isSongFullySaved(id)) {
            _error.value = "Already saved to device"
            return
        }
        viewModelScope.launch { exportToSd(id) }
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared: cleaning up")

        // Remove Player.Listener to avoid leaking ViewModel via MediaController
        playerListener?.let { listener ->
            player?.removeListener(listener)
            playerListener = null
            Log.d(TAG, "onCleared: removed Player.Listener")
        }

        // Remove Cache.Listener to avoid leaking ViewModel via SimpleCache
        cacheListener?.let { listener ->
            (streamCache as? SimpleCache)?.removeListener("MainViewModel", listener)
            cacheListener = null
            Log.d(TAG, "onCleared: removed Cache.Listener")
        }

        // Disconnect from MusicService
        musicServiceConnection.disconnect()

        isPolling = false
        connectivityManager.unregisterNetworkCallback(connectivityCallback)
        Log.d(TAG, "onCleared: done")
    }

    // ── Category helpers ─────────────────────────────────────────────

    fun songsByCategory(category: String): List<MusicItem> = when (category) {
        "Playlist" -> combinedSongs.value.filter { item ->
            val album = when (item) {
                is MusicItem.YouTube -> item.song.albumName
                is MusicItem.Local -> item.file.album
            }
            !album.isNullOrBlank()
        }
        "NCS" -> combinedSongs.value.filter { item ->
            val src = when (item) {
                is MusicItem.YouTube -> item.song.artists
                is MusicItem.Local -> item.file.artist
            }
            src.contains("NCS", ignoreCase = true) ||
                item.title.contains("NCS", ignoreCase = true)
        }
        "Downloads" -> combinedSongs.value.filter { isSongFullySaved(it.id) }
        "Cache" -> combinedSongs.value.filter { item ->
            val pct = cachedPercentages.value[item.id] ?: 0f
            pct > 0f || isFilling(item.id)
        }
        "All" -> combinedSongs.value.filter { item ->
            isSongFullySaved(item.id) || (cachedPercentages.value[item.id] ?: 0f) > 0f || isFilling(item.id)
        }
        "Artist" -> combinedSongs.value.filter { it.subtitle.isNotBlank() }
        else -> combinedSongs.value
    }

    fun groupedByAlbum(songs: List<MusicItem>): Map<String, List<MusicItem>> =
        songs.groupBy { item ->
            when (item) {
                is MusicItem.YouTube -> item.song.albumName
                is MusicItem.Local -> item.file.album
            }?.takeIf { it.isNotBlank() } ?: "No album"
        }

    fun groupedByArtist(songs: List<MusicItem>): Map<String, List<MusicItem>> =
        songs.groupBy { item ->
            when (item) {
                is MusicItem.YouTube -> item.song.artists
                is MusicItem.Local -> item.file.artist
            }?.takeIf { it.isNotBlank() } ?: "Unknown"
        }

    /** Build playlist groups by title similarity (common words/patterns). */
    fun playlistGroups(): Map<String, List<MusicItem>> {
        val all = combinedSongs.value
        if (all.isEmpty()) return emptyMap()

        // Normalize each title into a set of significant words
        fun extractWords(title: String): Set<String> {
            val clean = title.lowercase()
                .replace(Regex("[\\[\\]()]"), " ")
                .replace(Regex("[-–—]"), " ")
                .replace(Regex("[^a-záéíóúñü\\s]"), " ")
                .trim()
            return clean.split(Regex("\\s+"))
                .filter { it.length >= 2 }
                .toSet()
        }

        // Build word → songs index
        val wordIndex = mutableMapOf<String, MutableList<MusicItem>>()
        all.forEach { item ->
            val words = extractWords(item.title)
            words.forEach { word ->
                wordIndex.getOrPut(word) { mutableListOf() }.add(item)
            }
        }

        // Keep words that appear in 2+ songs
        val significantWords = wordIndex.filter { it.value.size >= 2 }

        // Assign each song to the group with the MOST overlapping words
        // (to avoid duplicate assignment)
        val songGroups = mutableMapOf<String, MutableSet<String>>() // word → set of song ids
        val songBestGroup = mutableMapOf<String, String>() // song id → best group word

        // Sort words by number of songs (most popular first)
        val sortedWords = significantWords.entries.sortedByDescending { it.value.size }

        sortedWords.forEach { (word, songs) ->
            val songIds = songs.map { it.id }.toSet()
            // For each song in this group, check if it already has a better group
            val undecided = songIds.filter { it !in songBestGroup }
            // If most songs in this group are undecided, this word becomes the group
            if (undecided.size >= 2) {
                songGroups[word] = songIds.toMutableSet()
                songIds.forEach { songBestGroup[it] = word }
            }
        }

        // Build final groups
        val groups = mutableMapOf<String, MutableList<MusicItem>>()
        songGroups.forEach { (word, ids) ->
            val songs = all.filter { it.id in ids }
            if (songs.size >= 2) {
                // Use the word as group name, capitalize first letter
                val displayName = word.replaceFirstChar { it.uppercase() }
                groups[displayName] = songs.toMutableList()
            }
        }

        return groups.toSortedMap(compareByDescending<String> { groups[it]?.size ?: 0 })
    }

    fun downloadGroup(): List<MusicItem> =
        combinedSongs.value.filter { isSongFullySaved(it.id) }

    fun cacheGroup(): List<MusicItem> =
        combinedSongs.value.filter { item ->
            val pct = cachedPercentages.value[item.id] ?: 0f
            pct > 0f
        }

    companion object {
        private const val TAG = "MainViewModel"
        private const val SPANS_SEPARATOR = ";"
        private const val SPAN_PARTS_SEPARATOR = ","
    }
}
