package com.portalhacks.frame

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Bridges Meta Portal hardware sensors (Light, Accelerometer/Tap, Temperature, RGB)
 * to MQTT topics for Home Assistant integration.
 */
class SensorBridge(
    private val context: Context,
    private val onPublish: (topic: String, payload: String, qos: Int) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "SensorBridge"
        private const val RGB_TYPE = 65537
        private const val TAP_COOLDOWN_MS = 800L
        private const val TAP_RESET_MS = 1500L
        private const val LIGHT_THROTTLE_MS = 2000L
        private const val LIGHT_MIN_DELTA = 1.5f
        private const val GRAVITY_ALPHA = 0.85f
        private const val TEMP_THROTTLE_MS = 30000L
        private const val TEMP_MIN_DELTA = 0.2f
        private const val DEFAULT_TAP_THRESHOLD = 5.0f
    }

    var hasRgb = false
        private set
    var hasTemperature = false
        private set

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val thread = HandlerThread("portal-sensors").also { it.start() }
    private val handler = Handler(thread.looper)

    private val isCipher = Build.DEVICE.equals("cipher", ignoreCase = true)
    private val tapScale = if (isCipher) 0.25f else 1f

    @Volatile private var gravX = 0f
    @Volatile private var gravY = 0f
    @Volatile private var gravZ = 0f
    private var gravInit = false

    private var lastTapMs = 0L
    private var lastLightMs = 0L
    private var lastLux = Float.MIN_VALUE
    private var lastRgbMs = 0L
    private var lastTempMs = 0L
    private var lastTemp = Float.MIN_VALUE

    @Volatile private var deviceId: String = ""

    fun start(deviceId: String) {
        this.deviceId = deviceId
        val manager = sm ?: return

        manager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        manager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.type == RGB_TYPE }?.let {
            hasRgb = true
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        manager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let {
            hasTemperature = true
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        Log.i(TAG, "SensorBridge started: rgb=$hasRgb temp=$hasTemperature")
    }

    fun stop() {
        sm?.unregisterListener(this)
        thread.quitSafely()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> handleLight(event)
            Sensor.TYPE_ACCELEROMETER -> handleAccel(event)
            Sensor.TYPE_AMBIENT_TEMPERATURE -> handleTemp(event)
            RGB_TYPE -> handleRgb(event)
        }
    }

    private fun handleLight(event: SensorEvent) {
        val lux = event.values.getOrNull(0) ?: return
        val now = System.currentTimeMillis()
        if (now - lastLightMs < LIGHT_THROTTLE_MS && abs(lux - lastLux) < LIGHT_MIN_DELTA) return
        lastLightMs = now
        lastLux = lux
        val prefix = "portal-frame/$deviceId"
        onPublish("$prefix/illuminance/state", "%.1f".format(lux), 0)
    }

    private fun handleRgb(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (now - lastRgbMs < LIGHT_THROTTLE_MS) return
        lastRgbMs = now
        val r = event.values.getOrElse(0) { 0f }
        val g = event.values.getOrElse(1) { 0f }
        val b = event.values.getOrElse(2) { 0f }
        val prefix = "portal-frame/$deviceId"
        onPublish(
            "$prefix/rgb/state",
            """{"r":${"%.1f".format(r)},"g":${"%.1f".format(g)},"b":${"%.1f".format(b)}}""",
            0
        )
    }

    private fun handleTemp(event: SensorEvent) {
        val c = event.values.getOrNull(0) ?: return
        val now = System.currentTimeMillis()
        if (now - lastTempMs < TEMP_THROTTLE_MS && abs(c - lastTemp) < TEMP_MIN_DELTA) return
        lastTempMs = now
        lastTemp = c
        val prefix = "portal-frame/$deviceId"
        onPublish("$prefix/temperature/state", "%.1f".format(c), 0)
    }

    private fun handleAccel(event: SensorEvent) {
        val x = event.values.getOrElse(0) { 0f }
        val y = event.values.getOrElse(1) { 0f }
        val z = event.values.getOrElse(2) { 0f }

        val alpha = if (gravInit) GRAVITY_ALPHA else 0f
        gravX = alpha * gravX + (1 - alpha) * x
        gravY = alpha * gravY + (1 - alpha) * y
        gravZ = alpha * gravZ + (1 - alpha) * z
        gravInit = true

        val lx = x - gravX
        val ly = y - gravY
        val lz = z - gravZ
        val force = sqrt((lx * lx + ly * ly + lz * lz).toDouble()).toFloat()

        val now = System.currentTimeMillis()
        val threshold = DEFAULT_TAP_THRESHOLD * tapScale
        if (force > threshold && now - lastTapMs > TAP_COOLDOWN_MS) {
            lastTapMs = now
            val dir = when {
                abs(lx) >= abs(ly) && abs(lx) >= abs(lz) -> if (lx > 0) "right" else "left"
                abs(ly) >= abs(lx) && abs(ly) >= abs(lz) -> if (ly > 0) "down" else "up"
                else -> if (isCipher) { if (lz > 0) "up" else "down" } else { if (lz > 0) "front" else "back" }
            }
            Log.i(TAG, "Tap detected: $dir force=%.1f threshold=%.1f".format(force, threshold))
            val prefix = "portal-frame/$deviceId"
            onPublish("$prefix/tap/state", dir, 0)
            handler.removeCallbacksAndMessages("tap_reset")
            handler.postAtTime({
                onPublish("$prefix/tap/state", "none", 0)
            }, "tap_reset", SystemClock.uptimeMillis() + TAP_RESET_MS)
        }
    }
}
