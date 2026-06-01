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
import com.music.app.data.model.LocalAudioFile
import com.music.app.data.model.MusicItem
import com.music.app.data.model.Song
import com.music.app.ui.components.CachedTimeSpan
import com.music.app.data.remote.InnertubeClient
import com.music.app.data.remote.SearchResult
import com.music.app.download.DownloadHelper
import com.music.app.domain.repository.MusicRepository
import com.music.app.player.DeviceMusicScanner
import com.music.app.player.MusicServiceConnection
import com.music.app.player.StreamResolver
import java.io.File
import java.net.URL
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val appContext: Context
) : ViewModel() {

    val localSongs: StateFlow<List<Song>> = repository.getAllSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deviceSongs: StateFlow<List<LocalAudioFile>> = musicScanner.deviceSongs

    private val localThumbnails = MutableStateFlow<Map<String, String>>(emptyMap())

    private val recentPrefs = appContext.getSharedPreferences("recent", Context.MODE_PRIVATE)
    private val searchPrefs = appContext.getSharedPreferences("search", Context.MODE_PRIVATE)
    private val cacheStatePrefs = appContext.getSharedPreferences("cache_state", Context.MODE_PRIVATE)
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

    private val _cachedPercentages = MutableStateFlow<Map<String, Float>>(emptyMap())
    val cachedPercentages: StateFlow<Map<String, Float>> = _cachedPercentages.asStateFlow()

    val combinedSongs: StateFlow<List<MusicItem>> = combine(
        localSongs, deviceSongs, localThumbnails, _cachedPercentages
    ) { songs, locals, thumbs, percentages ->
        val youTubeItems = songs.map { MusicItem.YouTube(it) }
        val localItems = locals.map { MusicItem.Local(it, thumbs[it.id]) }
        (youTubeItems + localItems).sortedWith(compareBy<MusicItem> { item ->
            val pct = percentages[item.id]
            when {
                pct != null && pct >= 1.0f -> 0
                pct != null -> 1
                else -> 2
            }
        }.thenByDescending { item ->
            percentages[item.id] ?: 0f
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

    private var player: androidx.media3.common.Player? = null
    private var playerListener: androidx.media3.common.Player.Listener? = null
    private var cacheListener: Cache.Listener? = null
    private var isPolling = false
    private var lastSeekNano = 0L
    private var lastErrorPosMs = -1L
    private var consecutiveErrors = 0
    private var currentPlaylist: List<MusicItem> = emptyList()
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

        // Load persisted cache state
        _cachedPercentages.value = loadCachePercentages()
        // If online, scan current item's actual cache to correct stale prefs
        if (_isOnline.value) {
            _recentIds.value.firstOrNull()?.let { id ->
                loadCacheSpans(id).let { loaded ->
                    if (loaded.isNotEmpty()) {
                        _currentCachedSpans.value = loaded
                        viewModelScope.launch { scanCurrentCacheSpans() }
                    }
                }
            }
        }
        viewModelScope.launch { loadCachedThumbnails() }

        viewModelScope.launch {
            combinedSongs.collect { list ->
                val yt = list.count { it is MusicItem.YouTube }
                Log.d(TAG, "combinedSongs changed: total=${list.size} yt=$yt local=${list.size - yt}")
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
            val cached = streamCache.getCachedBytes("yt://$id", 0, length).coerceAtMost(length)
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
        val cacheKeys = streamCache.keys.toList()
        Log.d(TAG, "scan: step2 cacheKeys=${cacheKeys.size} keys=${cacheKeys.take(5)}")
        for (key in cacheKeys) {
            if (!key.startsWith("yt://")) continue
            val id = key.removePrefix("yt://")
            if (id in result) continue
            val spans = streamCache.getCachedSpans(key)
            val totalCached = spans.sumOf { it.length }
            Log.d(TAG, "scan: step2 id=$id spans=${spans.size} totalCached=$totalCached")
            if (totalCached <= 0L) continue
            val sec = durations[id]
            if (sec != null) {
                result[id] = (totalCached.toFloat() / (sec * assumedBitrate).toFloat())
                    .coerceIn(0.01f, 0.99f)
                Log.d(TAG, "scan: step2 estimated from duration -> ${(result[id]!!*100).toInt()}%")
            } else {
                val maxPos = spans.maxOfOrNull { it.position + it.length } ?: totalCached
                result[id] = (totalCached.toFloat() / maxPos.toFloat())
                    .coerceIn(0.01f, 0.99f)
                Log.d(TAG, "scan: step2 span fallback -> ${(result[id]!!*100).toInt()}% (maxPos=$maxPos)")
            }
        }

        // 3. YouTube items in combinedSongs — catch anything new not yet in cache keys
        for (item in combinedSongs.value) {
            if (item !is MusicItem.YouTube) continue
            if (item.id in result) continue
            val spans = streamCache.getCachedSpans("yt://${item.id}")
            val totalCached = spans.sumOf { it.length }
            Log.d(TAG, "scan: step3 id=${item.id} durationText='${item.song.durationText}' spans=${spans.size} totalCached=$totalCached")
            if (totalCached <= 0L) continue
            val sec = durations[item.id]
            if (sec != null) {
                result[item.id] = (totalCached.toFloat() / (sec * assumedBitrate).toFloat())
                    .coerceIn(0.01f, 0.99f)
                Log.d(TAG, "scan: step3 estimated from duration -> ${(result[item.id]!!*100).toInt()}%")
            } else {
                val maxPos = spans.maxOfOrNull { it.position + it.length } ?: totalCached
                result[item.id] = (totalCached.toFloat() / maxPos.toFloat())
                    .coerceIn(0.01f, 0.99f)
                Log.d(TAG, "scan: step3 span fallback -> ${(result[item.id]!!*100).toInt()}% (maxPos=$maxPos)")
            }
        }

        Log.d(TAG, "scanCachePercentages: result has ${result.size} entries")
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
                        // Load persisted spans immediately for instant bar
                        _currentItem.value?.id?.let { id ->
                            val loaded = loadCacheSpans(id)
                            if (loaded.isNotEmpty()) _currentCachedSpans.value = loaded
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
                                connectedPlayer.seekTo(connectedPlayer.currentMediaItemIndex, 0L)
                                connectedPlayer.play()
                                return
                            }
                        }

                        if (!_isOnline.value && connectedPlayer.currentMediaItemIndex in currentPlaylist.indices) {
                            val item = currentPlaylist[connectedPlayer.currentMediaItemIndex]
                            if (item is MusicItem.YouTube) {
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
                    _cachedPercentages.value = scanCachePercentages()
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
                        _cachedPercentages.value = scanCachePercentages()
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
        val item = _currentItem.value
        if (item is MusicItem.YouTube) {
            val key = "yt://${item.id}"
            val dur = _duration.value.coerceAtLeast(0L)
            val spans = withContext(Dispatchers.IO) { streamCache.getCachedSpans(key) }
            val cl = streamResolver.contentLengths[item.id]
            val assumedBitrate = 16384L
            val actualEndBytes = spans.maxOfOrNull { it.position + it.length } ?: 0L
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
            val totalCached = spans.sumOf { it.length }
            Log.d(TAG, "cacheSpans: item=${item.id} cl=$cl dur=$dur totalBytes=$totalBytes actualEndBytes=$actualEndBytes fragmentSize=$fragmentSize spans=${spans.size} totalCached=$totalCached")
            spans.forEachIndexed { i, s ->
                Log.d(TAG, "cacheSpans:  raw[$i] pos=${s.position} len=${s.length}")
            }

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

                _currentCachedSpans.value = spans.map { span ->
                    val startMs = ((span.position.toFloat() / totalBytes) * dur).toLong()
                    val endMs = (((span.position + span.length).toFloat() / totalBytes) * dur).toLong()
                    Log.d(TAG, "cacheSpans:  span pos=${span.position} len=${span.length} -> ${startMs}ms..${endMs}ms")
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
    }

    private fun checkAndScan(span: CacheSpan) {
        if (!_isOnline.value) return
        val current = _currentItem.value
        if (current is MusicItem.YouTube && span.key == "yt://${current.id}") {
            viewModelScope.launch { scanCurrentCacheSpans() }
        }
    }

    private suspend fun exportToSd(id: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "exportToSd: start id=$id")
        val key = "yt://$id"
        val length = streamResolver.contentLengths[id] ?: run {
            Log.w(TAG, "exportToSd: unknown contentLength for $id")
            return@withContext
        }
        val rawMime = streamResolver.mimeTypes[id] ?: "audio/webm"
        val mime = if (rawMime == "audio/webm") "audio/mp4" else rawMime // MediaStore rejects audio/webm
        val ext = when {
            mime.contains("mp4") || mime.contains("m4a") -> ".m4a"
            mime.contains("ogg") -> ".ogg"
            mime.contains("opus") -> ".opus"
            mime.contains("webm") || mime.contains("webma") -> ".m4a"
            else -> ".m4a"
        }

        val spans = streamCache.getCachedSpans(key).sortedBy { it.position }
        var pos = 0L
        for (span in spans) {
            if (span.position != pos) {
                Log.w(TAG, "exportToSd: gap at $pos for $id")
                return@withContext
            }
            pos += span.length
        }
        if (pos < length) {
            Log.w(TAG, "exportToSd: incomplete cache for $id ($pos/$length)")
            return@withContext
        }

        val item = combinedSongs.value.find { it.id == id } ?: run {
            Log.w(TAG, "exportToSd: item not found in combinedSongs for $id")
            return@withContext
        }

        // Download and save thumbnail locally for offline use
        if (item.thumbnailUrl != null) {
            try {
                val thumbDir = File(appContext.filesDir, "thumbnails").also { it.mkdirs() }
                val thumbFile = File(thumbDir, "$id.jpg")
                val url = URL(item.thumbnailUrl)
                url.openStream().use { input ->
                    thumbFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                localThumbnails.value = localThumbnails.value + (id to thumbFile.toURI().toString())
                Log.d(TAG, "exportToSd: saved thumbnail for $id")
            } catch (e: Exception) {
                Log.w(TAG, "exportToSd: failed to save thumbnail for $id", e)
            }
        }

        val artist = item.subtitle.ifEmpty { "Unknown Artist" }
        val displayName = "${item.title} - $artist$ext"

        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        try {
            val uri = appContext.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues
            ) ?: throw Exception("Failed to create MediaStore entry")

            appContext.contentResolver.openOutputStream(uri)?.use { output ->
                for (span in spans.sortedBy { it.position }) {
                    val file = span.file ?: continue
                    file.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
            appContext.contentResolver.update(uri, contentValues, null, null)

            Log.d(TAG, "exportToSd: saved to $displayName")

            repository.deleteSong(id)
            Log.d(TAG, "exportToSd: deleted YouTube song from Room")

            // Remove cached spans and invalidate URL cache so playback restarts from 0
            val spanList = spans.toList()
            for (span in spanList) {
                streamCache.removeSpan(span)
            }
            streamResolver.removeContentLength(id)
            _cachedPercentages.value = _cachedPercentages.value - id
            cacheStatePrefs.edit().remove("pct_$id").remove("spans_$id").apply()

            musicScanner.scan()

            _error.value = "Saved to device: ${item.title}"
        } catch (e: Exception) {
            Log.e(TAG, "exportToSd: failed", e)
            _error.value = "Save failed: ${e.message}"
        }
    }

    private fun updatePosition(player: androidx.media3.common.Player) {
        _currentPosition.value = player.currentPosition
        _duration.value = player.duration
    }

    fun scanDeviceSongs() {
        viewModelScope.launch {
            musicScanner.scan()
        }
    }

    fun isDownloaded(songId: String) = downloadHelper.getDownload(songId).map { it != null }

    fun search(query: String) {
        viewModelScope.launch {
            _isSearching.value = true
            _error.value = null
            Log.d(TAG, "search: query='$query' online=${_isOnline.value}")
            if (!_isOnline.value) {
                Log.d(TAG, "search: offline, keeping saved results")
                _isSearching.value = false
                return@launch
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

    fun playMusicItem(item: MusicItem) {
        Log.d(TAG, "playMusicItem: id=${item.id} title='${item.title}'")
        if (item is MusicItem.Local) {
            viewModelScope.launch {
                if (!musicScanner.fileExists(item.file.uri)) {
                    _error.value = "File not found: ${item.file.title}"
                    return@launch
                }
                playWithPlaylist(item)
            }
        } else {
            playWithPlaylist(item)
        }
    }

    private fun playWithPlaylist(item: MusicItem) {
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
        val playlist = combinedSongs.value
        val index = playlist.indexOfFirst { it.id == item.id }
        currentPlaylist = if (index >= 0) playlist else listOf(item)
        _currentItem.value = item
        val loaded = loadCacheSpans(item.id)
        if (loaded.isNotEmpty()) _currentCachedSpans.value = loaded
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
                        downloadHelper.removeDownload(item.song.id)
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
            streamCache.getCachedSpans(uri).any { it.length > 0L }
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
        musicServiceConnection.seekTo(position)
        viewModelScope.launch { scanCurrentCacheSpans() }
    }

    fun skipNext() {
        musicServiceConnection.skipNext()
    }

    fun skipPrevious() {
        musicServiceConnection.skipPrevious()
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
            val download = downloadHelper.downloads.value[songId]
            if (download != null) {
                Log.d(TAG, "toggleDownload: removing download")
                downloadHelper.removeDownload(songId)
            } else {
                Log.d(TAG, "toggleDownload: adding download")
                downloadHelper.addDownload(songId, title)
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
                downloadHelper.addDownload(result.id, result.title)
                Log.d(TAG, "downloadSong: song saved and download started")
            } catch (e: Exception) {
                Log.e(TAG, "downloadSong: failed", e)
                _error.value = "Download failed: ${e.message}"
            }
        }
    }

    fun saveToDevice(id: String) {
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

    companion object {
        private const val TAG = "MainViewModel"
        private const val SPANS_SEPARATOR = ";"
        private const val SPAN_PARTS_SEPARATOR = ","
    }
}
