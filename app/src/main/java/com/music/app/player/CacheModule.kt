package com.music.app.player

import android.content.Context
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.QualifierValue
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.File

private const val CACHE_DIR = "exo_cache"
private const val DOWNLOAD_DIR = "exo_downloads"

enum class CacheType : Qualifier {
    CACHE, DOWNLOAD;
    override val value: QualifierValue = toString().lowercase()
}

val cacheModule = module {
    single<Cache>(CacheType.CACHE) {
        val context: Context = androidContext()
        val cacheDir = File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
        Log.d("CacheModule", "Creating persistent stream cache at ${cacheDir.absolutePath}")
        SimpleCache(cacheDir, NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
    }

    single<Cache>(CacheType.DOWNLOAD) {
        val context: Context = androidContext()
        val downloadDir = File(context.filesDir, DOWNLOAD_DIR).also { it.mkdirs() }
        Log.d("CacheModule", "Creating download cache at ${downloadDir.absolutePath}")
        SimpleCache(downloadDir, LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024), StandaloneDatabaseProvider(context))
    }

    single { OkHttpClient.Builder().build() }

    single { StreamResolver(androidContext()) }

    single<Downloader> {
        Log.d("CacheModule", "Initializing NewPipe with custom OkHttp downloader")
        val okHttpClient = OkHttpClient.Builder().build()
        val downloader = object : Downloader() {
            override fun execute(request: Request): Response {
                Log.v("NewPipeDL", "execute: ${request.httpMethod()} ${request.url()}")
                val req = okhttp3.Request.Builder()
                    .url(request.url())
                    .method(request.httpMethod(), request.dataToSend()?.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    .apply {
                        request.headers().forEach { (name, values) ->
                            values.forEach { addHeader(name, it) }
                        }
                    }
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                val resp = okHttpClient.newCall(req).execute()
                if (resp.code == 429) {
                    resp.close()
                    throw ReCaptchaException("reCaptcha requested", request.url())
                }
                val body = resp.body.string()
                return Response(resp.code, resp.message, resp.headers.toMultimap(), body, resp.request.url.toString())
            }
        }
        NewPipe.init(downloader)
        Log.d("CacheModule", "NewPipe initialized successfully")
        downloader
    }

    single<DefaultDataSource.Factory> {
        val context: Context = androidContext()
        val engine: OkHttpClient = get()
        Log.d("CacheModule", "Creating DefaultDataSource.Factory with OkHttp")
        DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(engine).setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        )
    }

    single<ResolvingDataSource.Factory> {
        val streamCache = get<Cache>(CacheType.CACHE)
        val downloadCache = get<Cache>(CacheType.DOWNLOAD)
        val defaultFactory = get<DefaultDataSource.Factory>()
        val resolver = get<StreamResolver>()
        get<Downloader>() // ensure NewPipe is initialized before resolving streams

        Log.d("CacheModule", "Creating ResolvingDataSource.Factory")

        val cacheFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setUpstreamDataSourceFactory(
                CacheDataSource.Factory()
                    .setCache(streamCache)
                    .setUpstreamDataSourceFactory(defaultFactory)
                    .setCacheWriteDataSinkFactory(
                        CacheDataSink.Factory()
                            .setCache(streamCache)
                            .setFragmentSize(512L * 1024)
                    )
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            )

        ResolvingDataSource.Factory(cacheFactory, resolver.resolver(streamCache, downloadCache))
    }

    /** Upstream for the Media3 DownloadManager: full-URL resolution, no cache subranges. */
    single<DataSource.Factory>(named("downloadDsf")) {
        val defaultFactory = get<DefaultDataSource.Factory>()
        val resolver = get<StreamResolver>()
        get<Downloader>() // ensure NewPipe is initialized before resolving streams
        Log.d("CacheModule", "Creating download DataSource.Factory with downloadResolver")
        ResolvingDataSource.Factory(defaultFactory, resolver.downloadResolver())
    }
}
