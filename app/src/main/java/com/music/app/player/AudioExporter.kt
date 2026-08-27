package com.music.app.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException

private const val TAG = "AudioExporter"

enum class ExportFormat(val extension: String, val mimeType: String, val displayName: String) {
    MP3("mp3", "audio/mpeg", "MP3"),
    AAC("aac", "audio/mp4a-latm", "AAC"),
    OGG("ogg", "audio/ogg", "OGG Vorbis"),
    WAV("wav", "audio/wav", "WAV (lossless)")
}

data class ExportResult(
    val success: Boolean,
    val outputPath: String,
    val message: String
)

object AudioExporter {

    fun exportFile(
        context: Context,
        inputFile: File,
        outputFormat: ExportFormat,
        onProgress: (Float) -> Unit = {}
    ): ExportResult {
        if (!inputFile.exists()) {
            return ExportResult(false, "", "Source file not found")
        }

        val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outputFileName = "${inputFile.nameWithoutExtension}.${outputFormat.extension}"
        val outputFile = File(outputDir, outputFileName)

        // Avoid overwriting — append (1), (2), etc.
        var finalFile = outputFile
        var counter = 1
        while (finalFile.exists()) {
            finalFile = File(outputDir, "${inputFile.nameWithoutExtension} ($counter).${outputFormat.extension}")
            counter++
        }

        return try {
            if (outputFormat == ExportFormat.WAV) {
                exportToWav(inputFile, finalFile, onProgress)
            } else {
                exportWithMuxer(inputFile, finalFile, outputFormat, onProgress)
            }
            ExportResult(true, finalFile.absolutePath, "Exported to ${finalFile.name}")
        } catch (e: IOException) {
            Log.e(TAG, "Export failed", e)
            ExportResult(false, "", "Export failed: ${e.message}")
        }
    }

    private fun exportWithMuxer(
        inputFile: File,
        outputFile: File,
        format: ExportFormat,
        onProgress: (Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        // Find audio track
        val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("No audio track found")

        extractor.selectTrack(audioTrackIndex)
        val inputFormat = extractor.getTrackFormat(audioTrackIndex)
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION)

        // Create output format
        val outputFormat = when (format) {
            ExportFormat.AAC -> MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            }
            ExportFormat.MP3 -> MediaFormat.createAudioFormat("audio/mpeg", sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            }
            ExportFormat.OGG -> MediaFormat.createAudioFormat("audio/ogg", sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            }
            else -> throw IOException("Unsupported muxer format: $format")
        }

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackIndex = muxer.addTrack(outputFormat)
        muxer.start()

        val codec = MediaCodec.createEncoderByType(outputFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastProgress = 0f

        while (!outputDone) {
            // Feed input
            if (!inputDone) {
                val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val sampleTime = extractor.sampleTime
                        codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, sampleTime, 0)
                        extractor.advance()

                        if (durationUs > 0) {
                            val progress = (sampleTime.toFloat() / durationUs).coerceIn(0f, 1f)
                            if (progress - lastProgress >= 0.01f) {
                                onProgress(progress)
                                lastProgress = progress
                            }
                        }
                    }
                }
            }

            // Drain output
            val outputBufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = codec.dequeueOutputBuffer(outputBufferInfo, 10_000)
            while (outputBufferIndex >= 0) {
                if (outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    outputBufferInfo.size = 0
                }
                if (outputBufferInfo.size > 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                    muxer.writeSampleData(muxerTrackIndex, outputBuffer, outputBufferInfo)
                }
                codec.releaseOutputBuffer(outputBufferIndex, false)

                if (outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                    break
                }
                outputBufferIndex = codec.dequeueOutputBuffer(outputBufferInfo, 10_000)
            }
        }

        onProgress(1f)
        codec.stop()
        codec.release()
        muxer.stop()
        muxer.release()
        extractor.release()
    }

    private fun exportToWav(
        inputFile: File,
        outputFile: File,
        onProgress: (Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("No audio track found")

        extractor.selectTrack(audioTrackIndex)
        val inputFormat = extractor.getTrackFormat(audioTrackIndex)
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val bitsPerSample = 16
        val durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION)

        // Decode to raw PCM first using MediaCodec decoder
        val decodeFormat = MediaFormat.createAudioFormat(
            inputFormat.getString(MediaFormat.KEY_MIME)!!,
            sampleRate,
            channelCount
        )

        val codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(decodeFormat, null, null, 0)
        codec.start()

        val pcmData = mutableListOf<Byte>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var lastProgress = 0f

        while (!inputDone) {
            val inputBufferIndex = codec.dequeueInputBuffer(10_000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()

                    if (durationUs > 0) {
                        val progress = (extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f)
                        if (progress - lastProgress >= 0.01f) {
                            onProgress(progress * 0.9f) // 90% for decode
                            lastProgress = progress
                        }
                    }
                }
            }

            val outputBufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = codec.dequeueOutputBuffer(outputBufferInfo, 10_000)
            while (outputBufferIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                if (outputBufferInfo.size > 0) {
                    val bytes = ByteArray(outputBufferInfo.size)
                    outputBuffer.position(outputBufferInfo.offset)
                    outputBuffer.limit(outputBufferInfo.offset + outputBufferInfo.size)
                    outputBuffer.get(bytes)
                    pcmData.addAll(bytes.toList())
                }
                codec.releaseOutputBuffer(outputBufferIndex, false)

                if (outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
                outputBufferIndex = codec.dequeueOutputBuffer(outputBufferInfo, 10_000)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        // Write WAV file
        val dataSize = pcmData.size
        val totalDataLen = dataSize + 36
        val byteRate = sampleRate * channelCount * bitsPerSample / 8

        outputFile.outputStream().use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(intToByteArray(totalDataLen))
            fos.write("WAVE".toByteArray())

            // fmt sub-chunk
            fos.write("fmt ".toByteArray())
            fos.write(intToByteArray(16)) // Sub-chunk size
            fos.write(shortToByteArray(1)) // PCM format
            fos.write(shortToByteArray(channelCount.toShort()))
            fos.write(intToByteArray(sampleRate))
            fos.write(intToByteArray(byteRate))
            fos.write(shortToByteArray((channelCount * bitsPerSample / 8).toShort()))
            fos.write(shortToByteArray(bitsPerSample.toShort()))

            // data sub-chunk
            fos.write("data".toByteArray())
            fos.write(intToByteArray(dataSize))
            fos.write(pcmData.toByteArray())
        }

        onProgress(1f)
    }

    fun scanCachedFiles(context: Context): List<Pair<String, File>> {
        val results = mutableListOf<Pair<String, File>>()

        // Stream cache
        val streamCacheDir = File(context.cacheDir, "media3/stream")
        if (streamCacheDir.exists()) {
            streamCacheDir.listFiles()?.filter { it.length() > 0 }?.forEach { file ->
                val id = file.name.removePrefix("yt://")
                results.add(id to file)
            }
        }

        // Download cache
        val dlCacheDir = File(context.cacheDir, "media3/downloads")
        if (dlCacheDir.exists()) {
            dlCacheDir.listFiles()?.filter { it.length() > 0 }?.forEach { file ->
                val id = file.name.removePrefix("yt://")
                if (results.none { it.second.absolutePath == file.absolutePath }) {
                    results.add(id to file)
                }
            }
        }

        return results
    }

    private fun intToByteArray(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte()
    )

    private fun shortToByteArray(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        (value.toInt() shr 8 and 0xFF).toByte()
    )
}
