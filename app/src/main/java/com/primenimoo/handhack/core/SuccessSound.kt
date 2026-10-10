package com.primenimoo.handhack.core

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.primenimoo.handhack.R

/**
 * Plays the success sound chosen by the user (the bundled res/raw asset) exactly like the iOS
 * version plays its bundled mp3. Each play builds a fresh player so a rapid second success cannot
 * cut the first one, and releases it once the sound ends.
 */
object SuccessSound {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun play(context: Context, volume: Float = 0.9f) {
        runCatching {
            val player = MediaPlayer.create(context, R.raw.success_sound) ?: return
            player.setVolume(volume, volume)
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { instance, _, _ ->
                instance.release()
                true
            }
            player.start()
        }
    }

    /**
     * Play with a short safety delay so an on-load race on very slow devices cannot drop the
     * sound before MediaPlayer finished preparing.
     */
    fun playDelayed(context: Context, delayMillis: Long = 120L) {
        mainHandler.postDelayed({ play(context) }, delayMillis)
    }
}