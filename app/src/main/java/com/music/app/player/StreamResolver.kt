package com.music.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class StreamResolver(context: Context) {

    private val prefs = context.getSharedPreferences("stream_meta", Context.MODE_PRIVATE)
    private val urlCache = ConcurrentHashMap<String, CachedStream>()
    private val lastResolvedUrl = ConcurrentHashMap<String, String>().apply {
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("lastUrl_") && value is String) {
                put(key.removePrefix("lastUrl_"), value)
            }
        }
    }
    private val offlineFallbackCache = ConcurrentHashMap<String, Boolean>()
    private val bypassNextResolve = ConcurrentHashMap<String, Boolean>()
    val contentLengths = ConcurrentHashMap<String, Long>().apply {
        // Load persisted content lengths
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("cl_") && value is Number) {
                put(key.removePrefix("cl_"), value.toLong())
            }
        }
    }
    val mimeTypes = ConcurrentHashMap<String, String>().apply {
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("mt_") && value is String) {
                put(key.removePrefix("mt_"), value)
            }
        }
    }

    fun setContentLength(id: String, length: Long) {
        contentLengths[id] = length
        prefs.edit().putLong("cl_$id", length).apply()
    }

    fun removeContentLength(id: String) {
        contentLengths.remove(id)
        prefs.edit().remove("cl_$id").remove("mt_$id").remove("dur_$id").remove("lastUrl_$id").apply()
    }

    fun invalidateUrlCache(id: String) {
        urlCache.remove(id)
        lastResolvedUrl.remove(id)
    }

    private fun setLastResolvedUrl(id: String, url: String) {
        prefs.edit().putString("lastUrl_$id", url).apply()
    }

    private fun getLastResolvedUrl(id: String): String? {
        return prefs.getString("lastUrl_$id", null)
    }

    private val durations = ConcurrentHashMap<String, String>().apply {
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("dur_") && value is String) {
                put(key.removePrefix("dur_"), value)
            }
        }
    }

    fun setDuration(id: String, durationText: String) {
        if (durationText.isNotBlank()) {
            durations[id] = durationText
            prefs.edit().putString("dur_$id", durationText).apply()
        }
    }

    fun getDuration(id: String): String? = durations[id]

    fun setMimeType(id: String, mime: String) {
        mimeTypes[id] = mime
        prefs.edit().putString("mt_$id", mime).apply()
    }

    data class CachedStream(
        val url: String,
        val contentLength: Long,
        val expiresAt: Long
    )

    private fun resolveStreamUrl(songId: String): CachedStream = runBlocking(Dispatchers.IO) {
        Log.d(TAG, "resolveStreamUrl: resolving songId=$songId")

        val cached = urlCache[songId]
        if (cached != null) {
            if (System.currentTimeMillis() < cached.expiresAt) {
                Log.d(TAG, "resolveStreamUrl: using cached URL, expires in ${cached.expiresAt - System.currentTimeMillis()}ms")
                if (cached.contentLength <= 0L) {
                    val storedCl = contentLengths[songId]
                    if (storedCl != null && storedCl > 0L) {
                        Log.d(TAG, "resolveStreamUrl: overriding cached contentLength=0 with stored=$storedCl")
                        return@runBlocking cached.copy(contentLength = storedCl)
                    }
                }
                return@runBlocking cached
            }
            Log.d(TAG, "resolveStreamUrl: cached URL expired")
        }

        val locale = Locale.getDefault()
        val gl = ContentCountry(locale.country)
        val hl = Localization(locale.language)
        val cpn = (1..12).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

        Log.d(TAG, "resolveStreamUrl: calling YoutubeStreamHelper with gl=${locale.country} hl=${locale.language}")

        // Try Android first; fall back to iOS if no audio-only adaptive formats
        val root = try {
            val androidResp = YoutubeStreamHelper.getAndroidReelPlayerResponse(gl, hl, songId, cpn)
            val androidRoot = JsonParser.`object`().from(JsonWriter.string(androidResp))
            val sd = androidRoot.getObject("streamingData")
            if (sd != null) {
                val adaptive = sd.getArray("adaptiveFormats")
                var hasAudio = false
                if (adaptive != null) {
                    for (i in 0 until adaptive.size) {
                        val f = adaptive.getObject(i)
                        if (f != null && f.getString("mimeType", "").startsWith("audio/")) {
                            hasAudio = true
                            break
                        }
                    }
                }
                if (hasAudio) {
                    Log.d(TAG, "resolveStreamUrl: Android response has audio adaptiveFormats")
                    androidRoot
                } else {
                    Log.w(TAG, "resolveStreamUrl: Android lacks audio adaptiveFormats, trying iOS")
                    val iosResp = YoutubeStreamHelper.getIosPlayerResponse(gl, hl, songId, cpn, null)
                    JsonParser.`object`().from(JsonWriter.string(iosResp))
                }
            } else {
                Log.w(TAG, "resolveStreamUrl: Android no streamingData, trying iOS")
                val iosResp = YoutubeStreamHelper.getIosPlayerResponse(gl, hl, songId, cpn, null)
                JsonParser.`object`().from(JsonWriter.string(iosResp))
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveStreamUrl: Android failed, trying iOS", e)
            val iosResp = YoutubeStreamHelper.getIosPlayerResponse(gl, hl, songId, cpn, null)
            JsonParser.`object`().from(JsonWriter.string(iosResp))
        }

        // Extract duration from videoDetails and persist it
        val videoDetails = root.getObject("videoDetails")
        if (videoDetails != null) {
            val lenStr = videoDetails.getString("lengthSeconds", null)
            if (lenStr != null) {
                val totalSec = lenStr.toLongOrNull()
                if (totalSec != null && totalSec > 0) {
                    val mins = totalSec / 60
                    val secs = totalSec % 60
                    val durText = if (mins >= 60) {
                        "${mins / 60}:${String.format("%02d", mins % 60)}:${String.format("%02d", secs)}"
                    } else {
                        "$mins:${String.format("%02d", secs)}"
                    }
                    setDuration(songId, durText)
                    Log.d(TAG, "resolveStreamUrl: extracted duration=$durText from videoDetails")
                }
            }
        }

        val streamingData = root.getObject("streamingData")
        if (streamingData == null) {
            Log.e(TAG, "resolveStreamUrl: no streamingData in response")
            throw IOException("No streamingData in response")
        }

        Log.d(TAG, "resolveStreamUrl: streamingData: ${streamingData.toString().take(500)}")

        val formatsArray = streamingData.getArray("formats")
        val adaptiveFormatsArray = streamingData.getArray("adaptiveFormats")
        Log.d(TAG, "resolveStreamUrl: formats=${formatsArray?.size} adaptiveFormats=${adaptiveFormatsArray?.size}")

        fun collectFormats(array: com.grack.nanojson.JsonArray?): List<Pair<Long, JsonObject>> {
            val result = mutableListOf<Pair<Long, JsonObject>>()
            if (array == null) return result
            for (i in 0 until array.size) {
                val format = array.getObject(i) ?: continue
                val url = format.getString("url", null)
                val cipher = format.getString("signatureCipher", null) ?: format.getString("cipher", null)
                val bitrate = format.getLong("bitrate", 0L)
                if (url != null) {
                    result.add(bitrate to format)
                } else if (cipher != null) {
                    result.add(bitrate to format)
                }
            }
            return result
        }

        // Prioritize audio-only formats from adaptiveFormats, fall back to combined formats
        val adaptiveFormats = collectFormats(adaptiveFormatsArray)
        val combinedFormats = collectFormats(formatsArray)

        // Debug: log ALL adaptive formats (even those without url/cipher) to find audio tracks
        if (adaptiveFormatsArray != null) {
            for (i in 0 until minOf(adaptiveFormatsArray.size, 5)) {
                val f = adaptiveFormatsArray.getObject(i) ?: continue
                val itag = f.getInt("itag", -1)
                val mt = f.getString("mimeType", "NONE")
                val hasUrl = f.get("url") != null
                val hasSigCipher = f.get("signatureCipher") != null
                val hasCipher = f.get("cipher") != null
                val cl = f.get("contentLength")
                Log.d(TAG, "  rawAdaptive[$i]: itag=$itag mime=$mt hasUrl=$hasUrl sigCipher=$hasSigCipher cipher=$hasCipher contentLength=$cl")
            }
        }
        Log.d(TAG, "  adaptiveFormats collected=${adaptiveFormats.size}")

        val audioFormats = adaptiveFormats.filter { it.second.getString("mimeType", "").startsWith("audio/") }
        Log.d(TAG, "resolveStreamUrl: audioFormats found=${audioFormats.size} out of ${adaptiveFormats.size}")
        val fallbackFormats = if (audioFormats.isEmpty()) {
            Log.w(TAG, "resolveStreamUrl: no audio-only formats in adaptiveFormats, using all formats (${adaptiveFormats.size} + ${combinedFormats.size})")
            adaptiveFormats + combinedFormats
        } else {
            audioFormats
        }

        val (selectedBitrate, format) = (fallbackFormats.filter { it.second.getString("url", null) != null })
            .maxByOrNull { it.first }
            ?: (fallbackFormats.filter { it.second.getString("url", null) == null })
                .maxByOrNull { it.first }
            ?: throw IOException("No playable format found")

        val mime = format.getString("mimeType", "audio/webm")
        Log.d(TAG, "resolveStreamUrl: selected format bitrate=$selectedBitrate mime=$mime")

        val url = format.getString("url", null)
        val cipher = format.getString("signatureCipher", null) ?: format.getString("cipher", null)

        val streamUrl = if (url != null) {
            Log.d(TAG, "resolveStreamUrl: has direct URL")
            url
        } else if (cipher != null) {
            Log.d(TAG, "resolveStreamUrl: has cipher, deobfuscating")
            val query = Uri.parse("http://x/$cipher")
            val s = query.getQueryParameter("s") ?: throw IOException("No signature in cipher")
            val sp = query.getQueryParameter("sp") ?: "sig"
            val baseUrl = query.getQueryParameter("url") ?: throw IOException("No url in cipher")
            val deobfuscated = YoutubeJavaScriptPlayerManager.deobfuscateSignature(songId, s)
            Log.d(TAG, "resolveStreamUrl: signature deobfuscated successfully")
            "$baseUrl&$sp=$deobfuscated"
        } else {
            throw IOException("No url or cipher")
        }

        Log.d(TAG, "resolveStreamUrl: deobfuscating throttling parameter")
        val finalUrl = YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(songId, streamUrl)
        var contentLength = format.getLong("contentLength", 0L)
        if (contentLength == 0L) {
            val clStr = format.getString("contentLength", null)
            if (clStr != null) contentLength = clStr.toLongOrNull() ?: 0L
        }
        val expiresIn = streamingData.getLong("expiresInSeconds", 3600L) * 1000L

        val mimeType = format.getString("mimeType", "audio/webm")
        setMimeType(songId, mimeType)
        setContentLength(songId, contentLength)

        Log.d(TAG, "resolveStreamUrl: success, contentLength=$contentLength mimeType=$mimeType expiresIn=${expiresIn}ms")

        val result = CachedStream(finalUrl, contentLength, System.currentTimeMillis() + expiresIn)
        urlCache[songId] = result
        result
    }

    fun markOfflineFallback(rawUri: String) {
        offlineFallbackCache[rawUri] = true
    }

    fun clearOfflineFallback(rawUri: String) {
        offlineFallbackCache.remove(rawUri)
    }

    fun bypassCacheNext(rawUri: String) {
        bypassNextResolve[rawUri] = true
    }

    fun isOfflineFallback(rawUri: String): Boolean = offlineFallbackCache.containsKey(rawUri)

    fun markOfflineFallbackForCached(rawUris: Collection<String>, vararg caches: Cache) {
        for (uri in rawUris) {
            if (offlineFallbackCache.containsKey(uri)) continue
            val hasData = caches.any { cache ->
                cache.getCachedSpans(uri).any { it.length > 0L }
            }
            if (hasData) {
                offlineFallbackCache[uri] = true
            }
        }
    }

    private fun subrangeForSpan(spans: List<CacheSpan>, offSpec: DataSpec, safePos: Long, vararg caches: Cache): DataSpec {
        val sorted = spans.sortedBy { it.position }
        val targetSpan = sorted.find { safePos in it.position until (it.position + it.length) }
            ?: sorted.filter { it.position <= safePos }.maxByOrNull { it.position }
            ?: sorted.minByOrNull { it.position }
        if (targetSpan != null) {
            val clampedPos = safePos.coerceIn(targetSpan.position, targetSpan.position + targetSpan.length - 1L)
            var contiguousEnd = targetSpan.position + targetSpan.length
            val targetIdx = sorted.indexOf(targetSpan)
            for (i in (targetIdx + 1) until sorted.size) {
                val next = sorted[i]
                if (next.position == contiguousEnd) {
                    contiguousEnd = next.position + next.length
                } else {
                    break
                }
            }
            val safeLen = (contiguousEnd - clampedPos).coerceAtLeast(1)
            Log.d(TAG, "resolver: offline subrange safePos=$safePos clampedPos=$clampedPos safeLen=$safeLen contiguousEnd=$contiguousEnd spans=${sorted.size}")
            return offSpec.buildUpon().setPosition(clampedPos).setLength(safeLen).build()
        }
        return offSpec
    }

    fun resolver(vararg caches: Cache): ResolvingDataSource.Resolver {
        return ResolvingDataSource.Resolver { dataSpec ->
            val rawUri = dataSpec.uri.toString()
            val uriStr = rawUri.removePrefix("yt://")
            Log.d(TAG, "resolver: resolving dataSpec uri=$uriStr pos=${dataSpec.position}")

            if (uriStr.startsWith("content://") || uriStr.startsWith("file://")) {
                Log.d(TAG, "resolver: local file URI, passing through")
                return@Resolver dataSpec
            }

            val skipCache = bypassNextResolve.remove(rawUri) == true

            // Cache-first: servir inmediatamente si hay datos en disco
            if (!skipCache && !offlineFallbackCache.containsKey(rawUri)) {
                val spans = caches.flatMap { it.getCachedSpans(rawUri).toList() }
                val inSpan = spans.any { dataSpec.position in it.position until (it.position + it.length) }
                if (inSpan) {
                    Log.d(TAG, "resolver: cache-first for $uriStr, ${spans.size} spans, pos=${dataSpec.position}")
                    val offSpec = dataSpec.buildUpon().setKey(rawUri).build()
                    return@Resolver subrangeForSpan(spans, offSpec, dataSpec.position, *caches)
                }
            }

            // Offline fallback: solo si la posición está realmente en un span
            if (!skipCache && offlineFallbackCache.containsKey(rawUri)) {
                val spans = caches.flatMap { it.getCachedSpans(rawUri).toList() }
                val inSpan = spans.any { dataSpec.position in it.position until (it.position + it.length) }
                if (inSpan) {
                    val offSpec = dataSpec.buildUpon().setKey(rawUri).build()
                    Log.d(TAG, "resolver: offline fallback for $uriStr")
                    return@Resolver subrangeForSpan(spans, offSpec, dataSpec.position, *caches)
                }
                // posición en gap: no usar offline fallback, resolver online
            }

            val (streamUrl, contentLength) = try {
                val oldMime = mimeTypes[uriStr]
                val cached = resolveStreamUrl(uriStr)
                if (cached.contentLength > 0L) {
                    setContentLength(uriStr, cached.contentLength)
                }
                lastResolvedUrl[uriStr] = cached.url
                setLastResolvedUrl(uriStr, cached.url)
                // Only purge when format changes (different mimeType) to prevent incompatible data
                val newMime = mimeTypes[uriStr]
                if (oldMime != null && oldMime != newMime) {
                    Log.w(TAG, "resolver: format changed $oldMime → $newMime, purging stale cache for $uriStr")
                    for (cache in caches) {
                        for (span in cache.getCachedSpans(rawUri)) {
                            try { cache.removeSpan(span) } catch (_: Exception) { Log.w(TAG, "resolver: failed to remove stale span") }
                        }
                    }
                }
                cached.url to cached.contentLength
            } catch (e: Exception) {
                val spans = caches.flatMap { it.getCachedSpans(rawUri).toList() }
                if (spans.isNotEmpty()) {
                    offlineFallbackCache[rawUri] = true
                    val offSpec = dataSpec.buildUpon().setKey(rawUri).build()
                    Log.w(TAG, "resolver: offline catch for $uriStr, key=$rawUri")
                    return@Resolver subrangeForSpan(spans, offSpec, dataSpec.position, *caches)
                }
                throw e
            }

            if (skipCache) {
                val clearMeta = ContentMetadataMutations().apply {
                    remove(ContentMetadata.KEY_CONTENT_LENGTH)
                }
                for (c in caches) {
                    c.applyContentMetadataMutations(rawUri, clearMeta)
                }
            }

            Log.d(TAG, "resolver: resolved URL, contentLength=$contentLength pos=${dataSpec.position}")
            dataSpec
                .buildUpon()
                .setUri(streamUrl.toUri())
                .setKey(rawUri)
                .build()
        }
    }

    companion object {
        private const val TAG = "StreamResolver"
    }
}
