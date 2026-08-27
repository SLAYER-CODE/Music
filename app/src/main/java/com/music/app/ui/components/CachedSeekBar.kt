package com.music.app.ui.components

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToLong

private val TAG = "CachedSeekBar"

data class CachedTimeSpan(
    val startMs: Long,
    val endMs: Long
)

@Composable
fun CachedSeekBar(
    currentPositionMs: Long,
    durationMs: Long,
    cachedSpans: List<CachedTimeSpan>,
    isOnline: Boolean,
    isLocal: Boolean = false,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackHeight: Dp = 6.dp,
    thumbRadius: Dp = 8.dp
) {
    val localBg = Color(0xFF424242)
    val localPlayed = Color(0xFF00E676)
    val deadZoneColor = Color(0xFF1B5E20)
    val playedGreen = Color(0xFF00E676)
    val cachedOrange = Color(0xFFFF9800)
    val thumbColor = Color(0xFF00E676)
    var dragFraction by remember { mutableStateOf(-1f) }
    var pendingSeekTargetMs by remember { mutableStateOf(-1L) }
    var previousPositionMs by remember { mutableStateOf(-1L) }
    var previousDurationMs by remember { mutableStateOf(-1L) }

    LaunchedEffect(durationMs) {
        if (previousDurationMs > 0L && durationMs != previousDurationMs) {
            dragFraction = -1f
            pendingSeekTargetMs = -1L
        }
        previousDurationMs = durationMs
    }

    LaunchedEffect(currentPositionMs, pendingSeekTargetMs) {
        if (pendingSeekTargetMs >= 0L) {
            if (abs(currentPositionMs - pendingSeekTargetMs) < 300L) {
                dragFraction = -1f
                pendingSeekTargetMs = -1L
            } else if (previousPositionMs > 0L && currentPositionMs < previousPositionMs - 5000L) {
                dragFraction = -1f
                pendingSeekTargetMs = -1L
            }
        }
        previousPositionMs = currentPositionMs
    }

    val displayFraction = if (dragFraction >= 0f) dragFraction
        else if (durationMs > 0L) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        else 0f

    fun isCachedPosition(timeMs: Long): Boolean =
        isLocal || (timeMs >= 0L && cachedSpans.any { timeMs in it.startMs..it.endMs })

    val gestures = if (!enabled) Modifier else Modifier
        .pointerInput(isOnline, cachedSpans, durationMs, isLocal) {
            detectTapGestures(onTap = { offset ->
                if (durationMs <= 0L) return@detectTapGestures
                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                val targetMs = (fraction * durationMs).roundToLong().coerceIn(0, durationMs)
                val inSpan = cachedSpans.any { targetMs in it.startMs..it.endMs }
                Log.d(TAG, "tap: x=${offset.x} durationMs=$durationMs fraction=$fraction targetMs=$targetMs spans=${cachedSpans.map{"${it.startMs}..${it.endMs}"}} inSpan=$inSpan")
                if (!isOnline && !isLocal && !inSpan)
                    return@detectTapGestures
                val seekMs = if (isOnline || isLocal) targetMs
                    else resolveSeekPosition(targetMs, cachedSpans)
                Log.d(TAG, "tap: resolveSeekPosition -> seekMs=$seekMs")
                dragFraction = fraction
                pendingSeekTargetMs = seekMs
                onSeek(seekMs)
            })
        }
        .pointerInput(isOnline, cachedSpans, durationMs, isLocal) {
            detectDragGestures(onDrag = { change, _ ->
                change.consume()
                if (durationMs <= 0L) return@detectDragGestures
                val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                val targetMs = (fraction * durationMs).roundToLong().coerceIn(0, durationMs)
                if (isOnline || isLocal || cachedSpans.any { targetMs in it.startMs..it.endMs }) {
                    dragFraction = fraction
                    if (isOnline || isLocal) {
                        onSeek(targetMs)
                    }
                }
            }, onDragEnd = {
                if (dragFraction >= 0f) {
                    val targetMs = (dragFraction * durationMs).roundToLong().coerceIn(0, durationMs)
                    if (isOnline || isLocal || cachedSpans.any { targetMs in it.startMs..it.endMs }) {
                        val seekMs = if (isOnline || isLocal) targetMs
                            else resolveSeekPosition(targetMs, cachedSpans)
                        pendingSeekTargetMs = seekMs
                        onSeek(seekMs)
                    }
                }
            })
        }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight + thumbRadius * 2)
            .then(gestures)
    ) {
        val thumbR = thumbRadius.toPx()
        val trackTop = thumbR
        val trackH = trackHeight.toPx()
        val totalW = size.width - thumbR * 2
        val left = thumbR

        if (isLocal) {
            // Local track: gray bg + green played progress
            drawRoundRect(
                color = localBg,
                topLeft = Offset(left, trackTop),
                size = Size(totalW, trackH),
                cornerRadius = CornerRadius(trackH / 2, trackH / 2)
            )
            if (durationMs > 0L && currentPositionMs > 0L) {
                val playedFrac = (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                drawRoundRect(
                    color = localPlayed,
                    topLeft = Offset(left, trackTop),
                    size = Size((playedFrac * totalW).coerceAtLeast(1f), trackH),
                    cornerRadius = CornerRadius(trackH / 2, trackH / 2)
                )
            }
        } else {
            // YouTube track: dark green dead zones + green/orange cache blocks
            drawRoundRect(
                color = deadZoneColor,
                topLeft = Offset(left, trackTop),
                size = Size(totalW, trackH),
                cornerRadius = CornerRadius(trackH / 2, trackH / 2)
            )
            for (span in cachedSpans) {
                val playedStart = maxOf(span.startMs, 0L)
                val playedEnd = minOf(span.endMs, currentPositionMs)
                if (playedEnd > playedStart) {
                    val fx = playedStart.toFloat() / durationMs.toFloat()
                    val tx = playedEnd.toFloat() / durationMs.toFloat()
                    drawRoundRect(
                        color = playedGreen,
                        topLeft = Offset(left + fx * totalW, trackTop),
                        size = Size(((tx - fx) * totalW).coerceAtLeast(1f), trackH),
                        cornerRadius = CornerRadius(trackH / 2, trackH / 2)
                    )
                }
                val orangeStart = maxOf(span.startMs, currentPositionMs)
                val orangeEnd = minOf(span.endMs, durationMs)
                if (orangeEnd > orangeStart) {
                    val fx = orangeStart.toFloat() / durationMs.toFloat()
                    val tx = orangeEnd.toFloat() / durationMs.toFloat()
                    drawRoundRect(
                        color = cachedOrange,
                        topLeft = Offset(left + fx * totalW, trackTop),
                        size = Size(((tx - fx) * totalW).coerceAtLeast(1f), trackH),
                        cornerRadius = CornerRadius(trackH / 2, trackH / 2)
                    )
                }
            }
        }

        // Thumb
        val thumbX = left + displayFraction * totalW
        drawCircle(
            color = thumbColor,
            radius = thumbR,
            center = Offset(thumbX, trackTop + trackH / 2)
        )
    }
}

private fun resolveSeekPosition(targetMs: Long, spans: List<CachedTimeSpan>): Long {
    if (spans.size >= 2) {
        val penult = spans[spans.size - 2].startMs
        if (targetMs >= penult) {
            Log.d(TAG, "resolveSeekPosition: target=$targetMs >= penult=$penult, retrocede to $penult")
            return penult.coerceAtLeast(0L)
        }
    }
    Log.d(TAG, "resolveSeekPosition: target=$targetMs < penult or <2 spans, return exact=$targetMs")
    return targetMs.coerceAtLeast(0L)
}
