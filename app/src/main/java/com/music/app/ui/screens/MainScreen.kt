package com.music.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.music.app.data.model.MusicItem
import com.music.app.ui.components.CachedTimeSpan
import com.music.app.ui.components.PlayerBar

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    currentItem: MusicItem?,
    isPlaying: Boolean,
    onSongClick: (MusicItem) -> Unit,
    onPlayerBarClick: () -> Unit,
    lastPlayedItem: MusicItem? = null,
    cachedTimeSpans: List<CachedTimeSpan> = emptyList(),
    currentPosition: Long = 0L,
    duration: Long = 0L,
    isOnline: Boolean = true,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottom > 0

    Scaffold(
        modifier = modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                PlayerBar(
                    item = currentItem,
                    isPlaying = isPlaying,
                    onPlayPause = {
                        if (currentItem != null) {
                            viewModel.togglePlayPause()
                        } else {
                            lastPlayedItem?.let { viewModel.playMusicItem(it) }
                        }
                    },
                    onClick = {
                        if (currentItem != null) onPlayerBarClick()
                        else lastPlayedItem?.let { onSongClick(it) }
                    },
                    fallbackItem = lastPlayedItem,
                    cachedTimeSpans = cachedTimeSpans,
                    currentPositionMs = currentPosition,
                    durationMs = duration,
                    isOnline = isOnline
                )
                if (!isKeyboardVisible) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.Home, contentDescription = null) },
                            label = { Text("Home") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.Search, contentDescription = null) },
                            label = { Text("Search") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> HomeScreen(
                viewModel = viewModel,
                onSongClick = onSongClick,
                modifier = Modifier.padding(padding)
            )
            1 -> SearchScreen(
                viewModel = viewModel,
                onResultClick = { result ->
                    viewModel.saveToLocal(result)
                },
                modifier = Modifier.padding(padding)
            )
        }
    }
}
