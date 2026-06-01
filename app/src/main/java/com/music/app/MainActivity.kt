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
                    val cachedTimeSpans by vm.currentCachedSpans.collectAsState()
                    var showPlayer by remember { mutableStateOf(false) }
                    var pendingYouTubeClick by remember { mutableStateOf<MusicItem?>(null) }

                    LaunchedEffect(error) {
                        if (error != null) {
                            Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                            vm.clearError()
                            pendingYouTubeClick = null
                        }
                    }

                    LaunchedEffect(isPlaying, currentItem, pendingYouTubeClick) {
                        if (isPlaying && pendingYouTubeClick != null && currentItem?.id == pendingYouTubeClick?.id) {
                            showPlayer = true
                            pendingYouTubeClick = null
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

                    if (showPlayer && currentItem != null) {
                        PlayerScreen(
                            item = currentItem,
                            isPlaying = isPlaying,
                            currentPosition = currentPosition,
                            duration = duration,
                            isDownloaded = currentItem is MusicItem.YouTube &&
                                    downloads.containsKey((currentItem as MusicItem.YouTube).song.id),
                            cachedPercentage = cachedPercentages[currentItem?.id] ?: -1f,
                            cachedTimeSpans = cachedTimeSpans,
                            isOnline = isOnline,
                            onPlayPause = { vm.togglePlayPause() },
                            onSeek = { vm.seekTo(it) },
                            onSkipNext = { vm.skipNext() },
                            onSkipPrevious = { vm.skipPrevious() },
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
                            }
                        )
                    } else {
                        MainScreen(
                            viewModel = vm,
                            currentItem = currentItem,
                            isPlaying = isPlaying,
                            lastPlayedItem = lastPlayedItem,
                            cachedTimeSpans = cachedTimeSpans,
                            currentPosition = currentPosition,
                            duration = duration,
                            isOnline = isOnline,
                            onSongClick = { item ->
                                vm.playMusicItem(item)
                                if (item is MusicItem.YouTube) {
                                    pendingYouTubeClick = item
                                } else {
                                    showPlayer = true
                                }
                            },
                            onPlayerBarClick = { showPlayer = true }
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
