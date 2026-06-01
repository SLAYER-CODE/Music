package com.music.app

import android.content.Context
import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.request.crossfade
import coil3.SingletonImageLoader
import com.music.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MusicApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: starting Koin")
        try {
            startKoin {
                androidContext(this@MusicApp)
                modules(appModule)
            }
            Log.d(TAG, "onCreate: Koin started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: Koin failed", e)
        }
    }

    override fun newImageLoader(context: Context): ImageLoader {
        Log.d(TAG, "newImageLoader: creating ImageLoader")
        return ImageLoader.Builder(context)
            .crossfade(true)
            .build()
    }

    companion object {
        private const val TAG = "MusicApp"
    }
}
