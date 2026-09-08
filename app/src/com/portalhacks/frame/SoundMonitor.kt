package com.portalhacks.frame

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Monitors ambient sound level (0–100 amplitude scale) using low-overhead PCM audio.
 * No audio is recorded or stored. Automatically releases the microphone if a Portal
 * call is active. Requires RECORD_AUDIO permission; if not granted, cleanly remains idle.
 */
class SoundMonitor(
    private val context: Context,
    private val onLevel: (level: Int) -> Unit
) {
    companion object {
        private const val TAG = "SoundMonitor"
        private const val SAMPLE_RATE = 16000
        private const val PUBLISH_MS = 2000L
    }

    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "RECORD_AUDIO not granted; sound level monitoring disabled.")
            running.set(false)
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord buffer size invalid; sound monitoring disabled.")
            running.set(false)
            return
        }
        val bufSize = minBuf * 4

        Thread({
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val buf = ShortArray(SAMPLE_RATE / 25) // 640 samples = ~40ms
            var rec: AudioRecord? = null
            var sumSq = 0.0
            var count = 0
            var lastPublish = System.currentTimeMillis()

            fun release() {
                rec?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
                rec = null
                sumSq = 0.0
                count = 0
            }

            while (running.get()) {
                val mode = am?.mode ?: AudioManager.MODE_NORMAL
                val inCall = mode == AudioManager.MODE_IN_COMMUNICATION ||
                    mode == AudioManager.MODE_IN_CALL ||
                    mode == AudioManager.MODE_RINGTONE

                if (inCall) {
                    if (rec != null) {
                        Log.i(TAG, "Releasing mic for phone/Portal call")
                        release()
                    }
                    Thread.sleep(500)
                    continue
                }

                if (rec == null) {
                    val r = runCatching {
                        AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufSize
                        )
                    }.getOrNull()

                    if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
                        runCatching { r?.release() }
                        Thread.sleep(1500)
                        continue
                    }
                    runCatching { r.startRecording() }
                    rec = r
                    lastPublish = System.currentTimeMillis()
                    Log.i(TAG, "Mic initialized for sound level monitoring")
                }

                val n = rec?.read(buf, 0, buf.size) ?: -1
                if (n > 0) {
                    for (i in 0 until n) {
                        sumSq += buf[i].toLong() * buf[i]
                    }
                    count += n

                    val now = System.currentTimeMillis()
                    if (now - lastPublish >= PUBLISH_MS && count > 0) {
                        lastPublish = now
                        val rms = sqrt(sumSq / count)
                        val dbfs = if (rms > 1.0) 20.0 * log10(rms / 32768.0) else -90.0
                        val level = ((dbfs + 60.0) / 60.0 * 100.0).coerceIn(0.0, 100.0).toInt()
                        onLevel(level)
                        sumSq = 0.0
                        count = 0
                    }
                } else {
                    Log.i(TAG, "Mic read failed, backing off...")
                    release()
                    Thread.sleep(2500)
                }
            }
            release()
        }, "portal-sound-monitor").also { it.isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
    }
}
