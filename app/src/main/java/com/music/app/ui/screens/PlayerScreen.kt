package com.music.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.music.app.ui.components.CachedSeekBar
import com.music.app.ui.components.CachedTimeSpan
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.app.data.model.MusicItem

@Composable
fun PlayerScreen(
    item: MusicItem?,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    currentPosition: Long,
    duration: Long,
    isDownloaded: Boolean,
    isSaving: Boolean = false,
    savingProgress: Float = 0f,
    cachedPercentage: Float = -1f,
    cachedTimeSpans: List<CachedTimeSpan> = emptyList(),
    isOnline: Boolean = true,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    shuffleEnabled: Boolean = false,
    repeatMode: Int = androidx.media3.common.Player.REPEAT_MODE_OFF,
    onToggleShuffle: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    onToggleDownload: () -> Unit,
    onSaveToDevice: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    sleepTimerMinutes: Int? = null,
    onStartSleepTimer: (Int) -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    queueSize: Int = 0,
    onShowQueue: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (item == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("No song selected", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete song?") },
            text = {
                Text("Are you sure you want to delete \"${item.title}\"?\nThis will remove the file from your device.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSleepTimerDialog) {
        val options = listOf(5, 10, 15, 30, 45, 60)
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("Sleep Timer") },
            text = {
                if (sleepTimerMinutes != null) {
                    Text("Timer active: $sleepTimerMinutes minutes remaining")
                } else {
                    Column {
                        Text("Stop playback after:")
                        Spacer(Modifier.height(8.dp))
                        options.chunked(3).forEach { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                row.forEach { minutes ->
                                    TextButton(
                                        onClick = {
                                            showSleepTimerDialog = false
                                            onStartSleepTimer(minutes)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("${minutes}m")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (sleepTimerMinutes != null) {
                    TextButton(onClick = {
                        showSleepTimerDialog = false
                        onCancelSleepTimer()
                    }) {
                        Text("Cancel Timer", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSleepTimerDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "Now Playing",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                if (queueSize > 1) {
                    IconButton(onClick = onShowQueue) {
                        Icon(
                            Icons.Default.List,
                            contentDescription = "Queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (item is MusicItem.YouTube) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isSaving) "Downloading ${(savingProgress * 100).toInt().coerceIn(0, 100)}%"
                                        else if (isDownloaded) "Remove download" else "Download"
                                    )
                                },
                                onClick = {
                                    if (!isSaving) {
                                        showMenu = false
                                        onToggleDownload()
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        if (isSaving) Icons.Default.HourglassBottom
                                        else if (isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                                        contentDescription = null,
                                        tint = if (isSaving) MaterialTheme.colorScheme.primary
                                        else if (isDownloaded) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                enabled = !isSaving
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isSaving) "Saving ${(savingProgress * 100).toInt().coerceIn(0, 100)}%"
                                        else "Save to Device"
                                    )
                                },
                                onClick = {
                                    if (!isSaving) {
                                        showMenu = false
                                        onSaveToDevice()
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        if (isSaving) Icons.Default.HourglassBottom else Icons.Default.Save,
                                        contentDescription = null,
                                        tint = if (isSaving) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                enabled = !isSaving
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (sleepTimerMinutes != null) "Sleep Timer (${sleepTimerMinutes}m)"
                                    else "Sleep Timer"
                                )
                            },
                            onClick = {
                                showMenu = false
                                showSleepTimerDialog = true
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Timer,
                                    contentDescription = null,
                                    tint = if (sleepTimerMinutes != null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                showDeleteDialog = true
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        if (item.thumbnailUrl != null) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = item.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = item.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (item is MusicItem.Local) {
            Text(
                text = "Local",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
        } else if (isBuffering) {
            Text(
                text = "Loading...",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
        } else if (cachedPercentage in 0f..1f) {
            val pct = (cachedPercentage * 100).toInt()
            Text(
                text = if (cachedPercentage >= 1f) "Downloaded" else "$pct% cached",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(24.dp))

        CachedSeekBar(
            currentPositionMs = currentPosition,
            durationMs = duration,
            cachedSpans = cachedTimeSpans,
            isOnline = isOnline,
            isLocal = item is MusicItem.Local,
            onSeek = onSeek,
            modifier = Modifier.fillMaxWidth(),
            trackHeight = 6.dp,
            thumbRadius = 8.dp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration(currentPosition),
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = if (shuffleEnabled) "Shuffle on" else "Shuffle off",
                    modifier = Modifier.size(28.dp),
                    tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(8.dp))

            IconButton(onClick = onSkipPrevious) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Button(
                onClick = onPlayPause,
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(64.dp),
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            IconButton(onClick = onSkipNext) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            val repeatIcon = when (repeatMode) {
                androidx.media3.common.Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                androidx.media3.common.Player.REPEAT_MODE_ALL -> Icons.Default.Repeat
                else -> Icons.Default.Repeat
            }
            val repeatActive = repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF
            IconButton(onClick = onCycleRepeat) {
                Icon(
                    repeatIcon,
                    contentDescription = when (repeatMode) {
                        androidx.media3.common.Player.REPEAT_MODE_ONE -> "Repeat one"
                        androidx.media3.common.Player.REPEAT_MODE_ALL -> "Repeat all"
                        else -> "Repeat off"
                    },
                    modifier = Modifier.size(28.dp),
                    tint = if (repeatActive) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
