package com.music.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.music.app.data.model.MusicItem
import com.music.app.ui.components.SongItem
import kotlinx.coroutines.launch

private val groupColors = listOf(
    Color(0xFF2196F3), Color(0xFF9C27B0), Color(0xFFFF9800),
    Color(0xFF4CAF50), Color(0xFFE91E63), Color(0xFF00BCD4),
    Color(0xFF795548), Color(0xFF607D8B), Color(0xFF3F51B5),
    Color(0xFFCDDC39), Color(0xFFFF5722), Color(0xFF009688)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onSongClick: (MusicItem, List<MusicItem>?) -> Unit,
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val combinedSongs by viewModel.combinedSongs.collectAsState()
    val cachedPercentages by viewModel.cachedPercentages.collectAsState()
    val context = LocalContext.current
    var permissionDeniedPermanently by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    val hasPermission = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "permissionResult: granted=$granted")
        if (granted) {
            permissionDeniedPermanently = false
            viewModel.scanDeviceSongs()
        } else {
            val activity = context as? Activity
            if (activity == null || !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                permissionDeniedPermanently = true
            }
        }
    }

    var filterText by remember { mutableStateOf("") }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    val filteredSongs = remember(combinedSongs, filterText) {
        if (filterText.isBlank()) combinedSongs
        else combinedSongs.filter {
            it.title.contains(filterText, ignoreCase = true) ||
                it.subtitle.contains(filterText, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = filterText,
                        onValueChange = { filterText = it },
                        placeholder = { Text("Search songs...", style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (filterText.isNotEmpty()) {
                                IconButton(onClick = { filterText = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                onClick = { showMenu = false; viewModel.scanDeviceSongs() },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = { showMenu = false; onSettingsClick() },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (!hasPermission) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (permissionDeniedPermanently)
                        "Storage permission is blocked.\nEnable it in Settings to access your music."
                    else "Grant storage permission to see your music files",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (permissionDeniedPermanently) {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        } else permissionLauncher.launch(permission)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) { Text(if (permissionDeniedPermanently) "Open Settings" else "Grant Permission") }
            }
        } else {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // 2 tabs: Todo + Playlist
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        TabRowDefaults.PrimaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage])
                        )
                    },
                    divider = {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                        text = {
                            Text("Todo", fontWeight = if (pagerState.currentPage == 0) FontWeight.Bold else FontWeight.Normal)
                        }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                        text = {
                            Text("Playlist", fontWeight = if (pagerState.currentPage == 1) FontWeight.Bold else FontWeight.Normal)
                        }
                    )
                }

                // Pages
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    when (page) {
                        0 -> TodoPage(filteredSongs, combinedSongs, viewModel) { item, _ -> onSongClick(item, null) }
                        1 -> PlaylistPage(viewModel, filterText, onSongClick)
                    }
                }
            }
        }
    }
}

// ── Todo: flat song list ────────────────────────────────────────

@Composable
private fun TodoPage(
    songs: List<MusicItem>,
    allSongs: List<MusicItem>,
    viewModel: MainViewModel,
    onSongClick: (MusicItem, List<MusicItem>?) -> Unit
) {
    val currentItem by viewModel.currentItem.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val loadingItemIds by viewModel.loadingItemIds.collectAsState()
    val cachedPercentages by viewModel.cachedPercentages.collectAsState()

    if (songs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (allSongs.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Loading your music...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("No songs match your search", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 0.dp, top = 4.dp, end = 0.dp, bottom = 120.dp)
        ) {
            items(songs, key = { it.id }) { item ->
                SongItem(
                    item = item,
                    isCurrentItem = item.id == currentItem?.id,
                    isPlaying = isPlaying && item.id == currentItem?.id,
                    isBuffering = isBuffering && item.id == currentItem?.id,
                    isCloud = item is MusicItem.YouTube,
                    isLoading = loadingItemIds.contains(item.id),
                    isDisabled = !isOnline && item is MusicItem.YouTube && (cachedPercentages[item.id] ?: 0f) <= 0f,
                    cachedPercentage = cachedPercentages[item.id],
                    onClick = { onSongClick(item, null) }
                )
            }
        }
    }
}

// ── Playlist: grouped grid with expandable sections ─────────────

@Composable
private fun PlaylistPage(
    viewModel: MainViewModel,
    filterText: String,
    onSongClick: (MusicItem, List<MusicItem>?) -> Unit
) {
    val artistGroups = remember { viewModel.playlistGroups() }
    val downloads = remember { viewModel.downloadGroup() }
    val cache = remember { viewModel.cacheGroup() }
    val currentItem by viewModel.currentItem.collectAsState()

    // Filter by search text
    val filteredArtistGroups = remember(artistGroups, filterText) {
        if (filterText.isBlank()) artistGroups
        else artistGroups.filter { (key, items) ->
            key.contains(filterText, ignoreCase = true) ||
                items.any {
                    it.title.contains(filterText, ignoreCase = true) ||
                        it.subtitle.contains(filterText, ignoreCase = true)
                }
        }
    }
    val filteredDownloads = remember(downloads, filterText) {
        if (filterText.isBlank()) downloads
        else downloads.filter {
            it.title.contains(filterText, ignoreCase = true) ||
                it.subtitle.contains(filterText, ignoreCase = true)
        }
    }
    val filteredCache = remember(cache, filterText) {
        if (filterText.isBlank()) cache
        else cache.filter {
            it.title.contains(filterText, ignoreCase = true) ||
                it.subtitle.contains(filterText, ignoreCase = true)
        }
    }

    var expandedGroup by remember { mutableStateOf<String?>(null) }

    data class DisplayGroup(
        val key: String,
        val displayName: String,
        val songs: List<MusicItem>,
        val color: Color,
        val isSpecial: Boolean,
        val representativeTitle: String = "",
        val representativeThumbnail: String? = null
    )

    fun pickRepresentative(songs: List<MusicItem>): Pair<String, String?> {
        if (songs.isEmpty()) return "" to null
        val best = songs.minByOrNull { it.title.length } ?: songs.first()
        return best.title to best.thumbnailUrl
    }

    val specialGroups = listOf(
        DisplayGroup("_downloads_", "Downloads", filteredDownloads, Color(0xFF4CAF50), true, "Downloaded songs"),
        DisplayGroup("_cache_", "Cache", filteredCache, Color(0xFFFFC107), true, "Cached songs")
    ).filter { it.songs.isNotEmpty() }

    val artistDisplayGroups = filteredArtistGroups.entries
        .sortedByDescending { it.value.size }
        .mapIndexed { index, (key, songs) ->
            val (repTitle, repThumb) = pickRepresentative(songs)
            DisplayGroup(
                key = key,
                displayName = key,
                songs = songs,
                color = groupColors[index % groupColors.size],
                isSpecial = false,
                representativeTitle = repTitle,
                representativeThumbnail = repThumb
            )
        }

    val allGroups = specialGroups + artistDisplayGroups

    val playingGroupId = remember(currentItem, allGroups) {
        if (currentItem == null) null
        else allGroups.firstOrNull { group -> group.songs.any { it.id == currentItem!!.id } }?.key
    }

    if (allGroups.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (filterText.isBlank()) "No playlists found" else "No results for \"$filterText\"",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val rows = allGroups.chunked(2)
            rows.forEach { rowGroups ->
                item(key = "row_${rowGroups.first().key}") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        rowGroups.forEach { group ->
                            val isExpanded = expandedGroup == group.key
                            val isPlayingInGroup = playingGroupId == group.key
                            Box(modifier = Modifier.weight(1f)) {
                                GroupedCard(
                                    name = group.displayName,
                                    representativeTitle = group.representativeTitle,
                                    thumbnailUrl = group.representativeThumbnail,
                                    count = group.songs.size,
                                    color = group.color,
                                    isExpanded = isExpanded,
                                    isPlayingInGroup = isPlayingInGroup,
                                    onClick = {
                                        expandedGroup = if (isExpanded) null else group.key
                                    }
                                )
                            }
                        }
                        if (rowGroups.size < 2) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
                // Songs expanded right below the row
                rowGroups.filter { expandedGroup == it.key }.forEach { group ->
                    items(group.songs, key = { "song_${group.key}_${it.id}" }) { item ->
                        SongItemCompact(
                            item = item,
                            viewModel = viewModel,
                            playlist = group.songs,
                            onSongClick = onSongClick
                        )
                    }
                }
            }
        }
    }
}

// ── Shared composables ──────────────────────────────────────────

@Composable
private fun GroupedCard(
    name: String,
    representativeTitle: String = "",
    thumbnailUrl: String? = null,
    count: Int,
    color: Color,
    isExpanded: Boolean,
    isPlayingInGroup: Boolean = false,
    onClick: () -> Unit
) {
    val displayTitle = representativeTitle.ifBlank { name }
    val playingBorderColor = Color(0xFF4CAF50)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isPlayingInGroup) Modifier.border(
                    width = 2.dp,
                    color = playingBorderColor,
                    shape = RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlayingInGroup) playingBorderColor.copy(alpha = 0.08f) else color.copy(alpha = 0.12f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                if (!thumbnailUrl.isNullOrBlank()) {
                    coil3.compose.AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = name.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = "$count ${if (count == 1) "song" else "songs"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SongItemCompact(
    item: MusicItem,
    viewModel: MainViewModel,
    playlist: List<MusicItem>? = null,
    onSongClick: (MusicItem, List<MusicItem>?) -> Unit
) {
    val currentItem by viewModel.currentItem.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val loadingItemIds by viewModel.loadingItemIds.collectAsState()
    val cachedPercentages by viewModel.cachedPercentages.collectAsState()

    SongItem(
        item = item,
        isCurrentItem = item.id == currentItem?.id,
        isPlaying = isPlaying && item.id == currentItem?.id,
        isBuffering = isBuffering && item.id == currentItem?.id,
        isCloud = item is MusicItem.YouTube,
        isLoading = loadingItemIds.contains(item.id),
        isDisabled = !isOnline && item is MusicItem.YouTube && (cachedPercentages[item.id] ?: 0f) <= 0f,
        cachedPercentage = cachedPercentages[item.id],
        onClick = { onSongClick(item, playlist) }
    )
}

private const val TAG = "HomeScreen"
