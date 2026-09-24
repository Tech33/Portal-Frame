package com.portalhacks.frame

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Smooth, elegant audio visualizer engine for Portal-Frame.
 *
 * Provides liquid-smooth, gentle frequency spectrum analysis (5 frequency bands:
 * Sub-Bass, Bass/Low-Mid, Midrange/Vocals, High-Mid, and Treble) to UI media cards.
 *
 * Architecture:
 * 1. Native Output Stream Capture (android.media.audiofx.Visualizer(0)):
 *    Attaches to Android's master audio output mix when local media (Spotify, AirPlay receiver, YouTube)
 *    is playing on the Portal. Captures hardware digital audio FFT and applies gentle low-pass smoothing.
 * 2. Harmonic Fluid Wave Engine (for Sonos & remote playback):
 *    When audio is playing on Sonos or when hardware capture is inactive, generates a slow,
 *    soothing, multi-harmonic fluid equalizer wave derived from the song's identity.
 *    Completely avoids microphone recording or acoustic mic capture.
 */
object AudioVisualizerEngine {

    private const val TAG = "AudioVisualizerEngine"

    interface Listener {
        fun onAudioBands(bands: FloatArray)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isPlaying = false
    @Volatile
    private var currentSource = ""
    @Volatile
    private var currentTitle = ""

    private val isEngineRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    // Native Visualizer handle for local playback
    private var systemVisualizer: Visualizer? = null
    @Volatile
    private var lastSystemFftMs = 0L

    // Current smoothed output bands (0..4)
    private val outputBands = FloatArray(5) { 0.04f }
    private val targetBands = FloatArray(5) { 0.15f }
    private val peakGains = FloatArray(5) { 20f }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.onAudioBands(outputBands)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
        if (listeners.isEmpty() && !isPlaying) {
            stopEngine()
        }
    }

    fun updatePlaybackState(playing: Boolean, context: Context, title: String = "", source: String = "") {
        currentTitle = title
        currentSource = source
        val wasPlaying = isPlaying
        isPlaying = playing

        if (playing) {
            if (!isEngineRunning.get()) {
                startEngine(context.applicationContext)
            }
        } else if (wasPlaying) {
            // Settle smoothly to resting dots
            mainHandler.postDelayed({
                if (!isPlaying) {
                    stopEngine()
                    outputBands.fill(0f)
                    dispatchBands()
                }
            }, 500L)
        }
    }

    @Synchronized
    private fun startEngine(appContext: Context) {
        if (isEngineRunning.getAndSet(true)) return

        // Try initializing native Visualizer(0) for local playback
        tryStartSystemVisualizer(appContext)

        // Start smooth fluid wave worker thread
        workerThread = Thread({
            smoothWorkerLoop()
        }, "AudioVisualizer-Engine").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    @Synchronized
    private fun stopEngine() {
        isEngineRunning.set(false)
        stopSystemVisualizer()
        workerThread?.interrupt()
        workerThread = null
    }

    private fun tryStartSystemVisualizer(context: Context) {
        if (currentSource.contains("Sonos", ignoreCase = true)) {
            // Sonos streams remotely over Wi-Fi; uses the smooth harmonic fluid wave engine
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val v = Visualizer(0)
            val range = Visualizer.getCaptureSizeRange()
            v.captureSize = range[0].coerceAtLeast(128).coerceAtMost(256)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null && fft.isNotEmpty()) {
                        processSystemFft(fft)
                    }
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            systemVisualizer = v
            Log.i(TAG, "Native System Visualizer(0) active")
        } catch (e: Throwable) {
            Log.d(TAG, "Native Visualizer(0) unavailable: ${e.message}")
            systemVisualizer = null
        }
    }

    private fun stopSystemVisualizer() {
        try {
            systemVisualizer?.enabled = false
            systemVisualizer?.release()
        } catch (_: Throwable) {}
        systemVisualizer = null
    }

    private fun processSystemFft(fft: ByteArray) {
        var totalEnergy = 0f
        val bandRanges = arrayOf(
            1..2,   // Sub-bass
            3..6,   // Bass / Low-mid
            7..14,  // Mid / Vocals
            15..28, // High-mid
            29..55  // Treble / Air
        )

        for (b in 0 until 5) {
            val range = bandRanges[b]
            var sumSq = 0f
            var count = 0
            for (k in range) {
                if (k * 2 + 1 < fft.size) {
                    val r = fft[k * 2].toFloat()
                    val i = fft[k * 2 + 1].toFloat()
                    sumSq += (r * r + i * i)
                    count++
                }
            }
            val mag = sqrt(sumSq / count.coerceAtLeast(1))
            totalEnergy += mag

            // Gentle Automatic Gain Control
            peakGains[b] = (peakGains[b] * 0.995f).coerceAtLeast(mag).coerceAtLeast(12f)
            val normalized = (mag / peakGains[b]).coerceIn(0.08f, 0.95f)

            // Heavy low-pass damping on target bands for slow, smooth movement
            targetBands[b] = targetBands[b] * 0.65f + normalized * 0.35f
        }

        if (totalEnergy > 4f) {
            lastSystemFftMs = SystemClock.uptimeMillis()
        }
    }

    private fun smoothWorkerLoop() {
        val titleHash = Math.abs(currentTitle.hashCode())
        // Gentle organic frequency offsets derived from song title
        val baseSpeed = 0.0018 + (titleHash % 10) * 0.00012 // slow, soothing ~3.2-3.8s wave period

        while (isEngineRunning.get()) {
            val now = SystemClock.uptimeMillis()
            val timeSinceSystemFft = now - lastSystemFftMs
            val useHarmonicWave = (timeSinceSystemFft > 1200L) || currentSource.contains("Sonos", ignoreCase = true)

            if (useHarmonicWave) {
                val t = now.toDouble()
                for (b in 0 until 5) {
                    // Smooth, multi-harmonic layered sine waves
                    val phase1 = (t * baseSpeed) + b * 0.75
                    val phase2 = (t * baseSpeed * 0.65) + b * 1.15 + (titleHash % 7)
                    val s1 = sin(phase1) * 0.38 + 0.50
                    val s2 = cos(phase2) * 0.12
                    val wave = (s1 + s2).toFloat().coerceIn(0.12f, 0.88f)

                    // Smooth blending into target
                    targetBands[b] = targetBands[b] * 0.70f + wave * 0.30f
                }
            }

            // Gentle, silky-smooth damping physics: slow, fluid transition
            for (b in 0 until 5) {
                val target = targetBands[b]
                val current = outputBands[b]
                outputBands[b] = current + (target - current) * 0.16f
            }

            dispatchBands()

            try {
                Thread.sleep(32L) // ~30 FPS smooth cadence
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private val dispatchSnapshot = FloatArray(5)
    private val isDispatchPending = AtomicBoolean(false)
    private val dispatchRunnable = Runnable {
        isDispatchPending.set(false)
        val copy = synchronized(dispatchSnapshot) { dispatchSnapshot.clone() }
        for (l in listeners) {
            l.onAudioBands(copy)
        }
    }

    private fun dispatchBands() {
        if (listeners.isEmpty()) return
        synchronized(dispatchSnapshot) {
            System.arraycopy(outputBands, 0, dispatchSnapshot, 0, 5)
        }
        if (!isDispatchPending.getAndSet(true)) {
            mainHandler.post(dispatchRunnable)
        }
    }
}
