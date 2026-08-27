package com.music.app.ui.screens

import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.app.download.DownloadHelper
import com.music.app.player.CacheType
import org.koin.compose.koinInject
import java.io.File

private val ColorCache = Color(0xFFFF9800)       // Orange
private val ColorDownloads = Color(0xFF4CAF50)    // Green
private val ColorSongs = Color(0xFF2196F3)        // Blue
private val ColorSystem = Color(0xFF9C27B0)       // Purple
private val ColorFree = Color(0xFFE0E0E0)         // Light gray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var cacheSize by remember { mutableLongStateOf(0L) }
    var downloadSize by remember { mutableLongStateOf(0L) }
    var savedSongsSize by remember { mutableLongStateOf(0L) }
    var totalSpace by remember { mutableLongStateOf(0L) }
    var usedSpace by remember { mutableLongStateOf(0L) }
    var cachedSongs by remember { mutableIntStateOf(0) }
    var incompleteDls by remember { mutableIntStateOf(0) }

    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showDeleteDownloadsDialog by remember { mutableStateOf(false) }

    // Calculate sizes on first composition
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Cache size
        cacheSize = runCatching {
            context.cacheDir?.let { dir ->
                var size = 0L
                dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
                size
            } ?: 0L
        }.getOrDefault(0L)

        // Downloads size (Media3 download cache)
        downloadSize = runCatching {
            File(context.cacheDir, "media3/downloads").let { dir ->
                if (dir.exists()) {
                    var size = 0L
                    dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
                    size
                } else 0L
            }
        }.getOrDefault(0L)

        // Saved songs size (our exported files in Download folder)
        savedSongsSize = runCatching {
            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            if (downloadDir.exists()) {
                downloadDir.listFiles()
                    ?.filter { it.name.startsWith("[") && (it.extension == "m4a" || it.extension == "mp4" || it.extension == "jpg") }
                    ?.sumOf { it.length() } ?: 0L
            } else 0L
        }.getOrDefault(0L)

        // Device storage
        val stat = StatFs(context.cacheDir.absolutePath)
        totalSpace = stat.totalBytes
        usedSpace = stat.availableBytes.let { totalSpace - it }

        // Cache stats
        cachedSongs = viewModel.cachedSongCount()
        incompleteDls = viewModel.incompleteDownloadCount()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Total storage bar
            Column {
                Text(
                    text = "Device Storage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                val totalGB = totalSpace / (1024.0 * 1024 * 1024)
                val usedGB = usedSpace / (1024.0 * 1024 * 1024)
                val freeGB = (totalSpace - usedSpace) / (1024.0 * 1024 * 1024)
                Text(
                    text = "${"%.1f".format(usedGB)} GB used of ${"%.1f".format(totalGB)} GB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (totalSpace > 0) (usedSpace.toFloat() / totalSpace.toFloat()).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = ColorFree,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${"%.1f".format(freeGB)} GB free",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Breakdown bars
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Breakdown",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                StorageBar(
                    label = "Cache",
                    sizeBytes = cacheSize,
                    color = ColorCache,
                    totalBytes = totalSpace
                )
                StorageBar(
                    label = "Downloads",
                    sizeBytes = downloadSize,
                    color = ColorDownloads,
                    totalBytes = totalSpace
                )
                StorageBar(
                    label = "Saved Songs",
                    sizeBytes = savedSongsSize,
                    color = ColorSongs,
                    totalBytes = totalSpace
                )
            }

            // Legend
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LegendItem(color = ColorCache, label = "App cache")
                LegendItem(color = ColorDownloads, label = "Download cache")
                LegendItem(color = ColorSongs, label = "Saved songs (Downloads folder)")
                LegendItem(color = ColorFree, label = "Free space")
            }

            // Cache info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Cached Songs",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$cachedSongs ${if (cachedSongs == 1) "song" else "songs"} have cached data in memory",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (incompleteDls > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Incomplete Downloads",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                val dlWord = if (incompleteDls == 1) "download" else "downloads"
                                Text(
                                    text = "$incompleteDls $dlWord waiting to complete",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = {
                                    viewModel.resumeAllDownloads()
                                    incompleteDls = viewModel.incompleteDownloadCount()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ColorDownloads
                                )
                            ) {
                                Text("Complete")
                            }
                        }
                    }
                }
            }

            // Clean actions
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Clean up",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = { showClearCacheDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ColorCache
                    ),
                    enabled = cacheSize > 0
                ) {
                    Text("Clear App Cache (${formatSize(cacheSize)})")
                }
                Button(
                    onClick = { showDeleteDownloadsDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ColorDownloads
                    ),
                    enabled = downloadSize > 0
                ) {
                    Text("Clear Download Cache (${formatSize(downloadSize)})")
                }
            }
        }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear App Cache?") },
            text = { Text("This will clear ${formatSize(cacheSize)} of cached data. Your saved songs and settings will not be affected.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { context.cacheDir?.deleteRecursively() }
                    cacheSize = 0L
                    showClearCacheDialog = false
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDownloadsDialog = false },
            title = { Text("Clear Download Cache?") },
            text = { Text("This will clear ${formatSize(downloadSize)} of download cache. Saved songs in your Downloads folder will not be affected.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        File(context.cacheDir, "media3/downloads").deleteRecursively()
                    }
                    downloadSize = 0L
                    showDeleteDownloadsDialog = false
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDownloadsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StorageBar(
    label: String,
    sizeBytes: Long,
    color: Color,
    totalBytes: Long
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = formatSize(sizeBytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (totalBytes > 0) (sizeBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = ColorFree,
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(12.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${"%.1f".format(bytes / 1024.0)} KB"
    if (bytes < 1024 * 1024 * 1024) return "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    return "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
}
