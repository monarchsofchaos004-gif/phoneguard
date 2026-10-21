package com.pg.sentinel

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper

/**
 * 遠端響鈴（找手機）：家人端送 `ring [秒]`（預設 60、5～300），手機用鬧鐘聲道大聲響，
 * 靜音／震動模式也聽得到；響的期間把鬧鐘音量開到最大，停了就調回原本的音量。
 * 時間到、家人端送 `stopring`、或在 App 裡按「停止響鈴」就停。只在主執行緒呼叫。
 */
object Ringer {
    private var player: MediaPlayer? = null
    private var app: Context? = null
    private var restoreVol = -1
    private val h = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stop() }

    fun start(ctx: Context, seconds: Int): String {
        stop()
        val sec = seconds.coerceIn(5, 300)
        val c = ctx.applicationContext
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return "ring:no-sound"
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            restoreVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(c, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
            app = c
            h.postDelayed(stopper, sec * 1000L)
            "ring ${sec}s"
        } catch (e: Exception) {
            stop()
            "ring:fail ${e.javaClass.simpleName}"
        }
    }

    fun stop(): String {
        h.removeCallbacks(stopper)
        val was = player != null
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        val c = app
        if (c != null && restoreVol >= 0) {
            try {
                (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .setStreamVolume(AudioManager.STREAM_ALARM, restoreVol, 0)
            } catch (_: Exception) {}
        }
        restoreVol = -1
        return if (was) "ring-stopped" else "ring-idle"
    }

    val ringing: Boolean get() = player != null
}
