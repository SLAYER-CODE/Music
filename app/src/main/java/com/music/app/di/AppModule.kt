package com.music.app.di

import android.content.Context
import android.net.ConnectivityManager
import androidx.media3.datasource.cache.Cache
import com.music.app.data.local.MusicDatabase
import com.music.app.data.remote.InnertubeClient
import com.music.app.domain.repository.MusicRepository
import com.music.app.download.DownloadHelper
import com.music.app.download.DownloadHelperImpl
import com.music.app.player.CacheType
import com.music.app.player.DeviceMusicScanner
import com.music.app.player.MusicServiceConnection
import com.music.app.player.StreamResolver
import com.music.app.player.cacheModule
import com.music.app.ui.screens.MainViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    includes(cacheModule)

    single { MusicDatabase.getInstance(androidContext()) }
    single { get<MusicDatabase>().songDao() }
    single { get<MusicDatabase>().cachedLocalSongDao() }
    single { MusicRepository(get()) }
    single { InnertubeClient() }
    single { MusicServiceConnection(androidContext()) }
    single { DeviceMusicScanner(androidContext(), get()) }
    single { androidContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    single<DownloadHelper> {
        val ctx = androidContext()
        val dsf = get<androidx.media3.datasource.DataSource.Factory>(named("downloadDsf"))
        val cache = get<androidx.media3.datasource.cache.Cache>(com.music.app.player.CacheType.DOWNLOAD)
        DownloadHelperImpl(dsf, ctx, cache).also { DownloadHelperImpl.instance = it }
    }

    viewModel {
        MainViewModel(
            get(), get(), get(), get(), get(), get(),
            get<Cache>(CacheType.CACHE), get(), androidContext(), get()
        )
    }
}
