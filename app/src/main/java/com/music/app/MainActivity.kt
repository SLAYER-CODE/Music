package com.music.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.music.app.data.model.MusicItem
import com.music.app.ui.screens.MainScreen
import com.music.app.ui.screens.MainViewModel
import com.music.app.ui.screens.PlayerScreen
import com.music.app.ui.screens.SettingsScreen
import com.music.app.ui.screens.StorageScreen
import com.music.app.ui.screens.LocationPickerScreen
import com.music.app.ui.screens.ExportScreen
import com.music.app.ui.screens.QueueScreen
import com.music.app.ui.theme.MusicTheme
import org.koin.compose.koinInject

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "permissionResult: granted=$granted")
        if (granted) {
            viewModel?.scanDeviceSongs()
        }
    }

    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "writePermissionResult: granted=$granted")
        if (granted && pendingDeleteWritePermission != null) {
            viewModel?.deleteMusicItem(pendingDeleteWritePermission!!)
            pendingDeleteWritePermission = null
        } else {
            pendingDeleteWritePermission = null
        }
    }

    private val deleteIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        Log.d(TAG, "deleteIntentResult: resultCode=${result.resultCode}")
        viewModel?.onDeleteIntentResult(result.resultCode == RESULT_OK)
    }

    private var viewModel: MainViewModel? = null
    private var pendingDeleteWritePermission: MusicItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: starting MainActivity")
        enableEdgeToEdge()

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        requestAudioPermission()

        setContent {
            Log.d(TAG, "setContent: rendering UI")
            MusicTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: MainViewModel = koinInject()
                    viewModel = vm
                    val currentItem by vm.currentItem.collectAsState()
                    val isPlaying by vm.isPlaying.collectAsState()
                    val currentPosition by vm.currentPosition.collectAsState()
                    val duration by vm.duration.collectAsState()
                    val downloads by vm.downloads.collectAsState()
                    val cachedPercentages by vm.cachedPercentages.collectAsState()
                    val error by vm.error.collectAsState()
                    val lastPlayedItem by vm.lastPlayedItem.collectAsState()
                    val pendingIntent by vm.pendingDeleteIntent.collectAsState()
                    val isOnline by vm.isOnline.collectAsState()
                    val isBuffering by vm.isBuffering.collectAsState()
                    val cachedTimeSpans by vm.currentCachedSpans.collectAsState()
                    val shuffleEnabled by vm.shuffleEnabled.collectAsState()
                    val repeatMode by vm.repeatMode.collectAsState()
                    val savedSongs by vm.savedSongs.collectAsState()
                    val sleepTimerMinutes by vm.sleepTimerMinutes.collectAsState()
                    val currentPlaylist by vm.currentPlaylistFlow.collectAsState()
                    var showPlayer by remember { mutableStateOf(false) }
                    var showSettings by remember { mutableStateOf(false) }
                    var showStorage by remember { mutableStateOf(false) }
                    var showLocation by remember { mutableStateOf(false) }
                    var showExport by remember { mutableStateOf(false) }
                    var showQueue by remember { mutableStateOf(false) }
                    val saveLocation by vm.saveLocation.collectAsState()

                    LaunchedEffect(error) {
                        if (error != null) {
                            Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                            vm.clearError()
                        }
                    }

                    LaunchedEffect(pendingIntent) {
                        if (pendingIntent != null) {
                            val request = androidx.activity.result.IntentSenderRequest.Builder(pendingIntent!!).build()
                            deleteIntentLauncher.launch(request)
                        }
                    }

                    BackHandler(enabled = showPlayer) {
                        showPlayer = false
                    }

                    BackHandler(enabled = showSettings) {
                        showSettings = false
                    }

                    BackHandler(enabled = showStorage) {
                        showStorage = false
                    }

                    BackHandler(enabled = showLocation) {
                        showLocation = false
                    }

                    BackHandler(enabled = showExport) {
                        showExport = false
                    }

                    BackHandler(enabled = showQueue) {
                        showQueue = false
                    }

                    if (showQueue) {
                        QueueScreen(
                            queue = currentPlaylist,
                            currentIndex = currentPlaylist.indexOfFirst { it.id == currentItem?.id }.coerceAtLeast(0),
                            onBack = { showQueue = false },
                            onRemove = { vm.removeFromQueue(it) },
                            onMove = { from, to -> vm.moveInQueue(from, to) }
                        )
                    } else if (showExport) {
                        ExportScreen(onBack = { showExport = false })
                    } else if (showLocation) {
                        LocationPickerScreen(
                            currentPath = saveLocation,
                            onPathSelected = { vm.setSaveLocation(it) },
                            onBack = { showLocation = false }
                        )
                    } else if (showStorage) {
                        StorageScreen(viewModel = vm, onBack = { showStorage = false })
                    } else if (showSettings) {
                        SettingsScreen(
                            viewModel = vm,
                            onBack = { showSettings = false },
                            onStorageClick = { showStorage = true },
                            onLocationClick = { showLocation = true },
                            onExportClick = { showExport = true }
                        )
                    } else if (showPlayer && currentItem != null) {
                        val saveSt = if (currentItem is MusicItem.YouTube)
                            vm.saveState((currentItem as MusicItem.YouTube).song.id) else false to 0f
                        PlayerScreen(
                            item = currentItem,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            currentPosition = currentPosition,
                            duration = duration,
                            isDownloaded = currentItem is MusicItem.YouTube &&
                                    downloads.containsKey("yt://${(currentItem as MusicItem.YouTube).song.id}"),
                            isSaving = saveSt.first,
                            savingProgress = saveSt.second,
                            cachedPercentage = cachedPercentages[currentItem?.id] ?: -1f,
                            cachedTimeSpans = cachedTimeSpans,
                            isOnline = isOnline,
                            onPlayPause = { vm.togglePlayPause() },
                            onSeek = { vm.seekTo(it) },
                            onSkipNext = { vm.skipNext() },
                            onSkipPrevious = { vm.skipPrevious() },
                            shuffleEnabled = shuffleEnabled,
                            repeatMode = repeatMode,
                            onToggleShuffle = { vm.toggleShuffle() },
                            onCycleRepeat = { vm.cycleRepeatMode() },
                            onToggleDownload = {
                                val item = currentItem
                                if (item is MusicItem.YouTube) {
                                    vm.toggleDownload(item.song.id, item.song.title)
                                }
                            },
                            onSaveToDevice = {
                                val item = currentItem
                                if (item is MusicItem.YouTube) {
                                    vm.saveToDevice(item.song.id)
                                }
                            },
                            onDismiss = { showPlayer = false },
                            onDelete = {
                                val item = currentItem
                                if (item != null) {
                                    if (item is MusicItem.Local && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                        val wp = Manifest.permission.WRITE_EXTERNAL_STORAGE
                                        if (ContextCompat.checkSelfPermission(this@MainActivity, wp) != PackageManager.PERMISSION_GRANTED) {
                                            pendingDeleteWritePermission = item
                                            writePermissionLauncher.launch(wp)
                                            return@PlayerScreen
                                        }
                                    }
                                    vm.deleteMusicItem(item)
                                }
                            },
                            sleepTimerMinutes = sleepTimerMinutes,
                            onStartSleepTimer = { vm.startSleepTimer(it) },
                            onCancelSleepTimer = { vm.cancelSleepTimer() },
                            queueSize = currentPlaylist.size,
                            onShowQueue = { showQueue = true }
                        )
                    } else {
                        val cachedPercentage = currentItem?.let { cachedPercentages[it.id] } ?: -1f
                        MainScreen(
                            viewModel = vm,
                            currentItem = currentItem,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            lastPlayedItem = lastPlayedItem,
                            cachedTimeSpans = cachedTimeSpans,
                            cachedPercentage = cachedPercentage,
                            currentPosition = currentPosition,
                            duration = duration,
                            isOnline = isOnline,
                            onSettingsClick = { showSettings = true },
                            onSongClick = { item, playlist ->
                                if (item.id == currentItem?.id) {
                                    showPlayer = true
                                } else {
                                    vm.playMusicItem(item, playlist)
                                }
                            },
                            onPlayerBarClick = { showPlayer = true },
                            onSearchResultClick = { result ->
                                if (vm.isSongFullySaved(result.id)) {
                                    vm.playSavedSong(result.id)
                                    return@MainScreen
                                }
                                val isCached = (cachedPercentages[result.id] ?: 0f) > 0f ||
                                        downloads.containsKey(result.id)
                                vm.saveToLocal(result)
                                if (!isCached) {
                                    showPlayer = true
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "requestAudioPermission: requesting $permission")
            requestPermissionLauncher.launch(permission)
        } else {
            Log.d(TAG, "requestAudioPermission: already granted")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
