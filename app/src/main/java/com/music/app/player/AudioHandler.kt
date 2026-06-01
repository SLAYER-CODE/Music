package com.music.app.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.Player

class AudioHandler(
    context: Context,
    private val player: Player
) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val callback = Callback()

    fun register() {
        Log.d(TAG, "register: registering AudioDeviceCallback")
        manager?.registerAudioDeviceCallback(callback, null)
    }

    fun unregister() {
        Log.d(TAG, "unregister: unregistering AudioDeviceCallback")
        manager?.unregisterAudioDeviceCallback(callback)
    }

    private inner class Callback : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            val types = addedDevices.map { it.type }
            Log.d(TAG, "onAudioDevicesAdded: types=$types isPlaying=${player.isPlaying}")
            if (player.isPlaying) return
            if (addedDevices.any { it.isSink && it.type in SUPPORTED_TYPES }) {
                Log.d(TAG, "onAudioDevicesAdded: playing device connected, resuming playback")
                player.play()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            val types = removedDevices.map { it.type }
            Log.d(TAG, "onAudioDevicesRemoved: types=$types")
        }
    }

    companion object {
        private const val TAG = "AudioHandler"
        private val SUPPORTED_TYPES = intArrayOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
    }
}
