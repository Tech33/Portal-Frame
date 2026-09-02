package com.portalhacks.frame

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

/**
 * Lightweight, zero-dependency native MQTT 3.1.1 client for Portal-Frame.
 * Automatically discovers all device controls (Screen, Brightness, Volume, Battery,
 * Custom Messages, Photo Controls, Dashboard) into Home Assistant.
 */
class MqttManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PortalFrame-MqttWorker").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isRunning = AtomicBoolean(false)
    private var socket: Socket? = null
    private var outStream: DataOutputStream? = null
    private var inStream: DataInputStream? = null
    private var packetId = 1

    private val deviceId by lazy {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrEmpty()) "portal_$androidId" else "portal_frame"
    }

    private val deviceName = "Meta Portal Frame"

    companion object {
        private const val TAG = "PortalFrame-MQTT"
        @Volatile
        private var instance: MqttManager? = null

        fun getInstance(context: Context): MqttManager {
            return instance ?: synchronized(this) {
                instance ?: MqttManager(context).also { instance = it }
            }
        }

        fun startIfEnabled(context: Context) {
            val p = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
            if (p.getBoolean(ConfigReceiver.KEY_MQTT_ENABLED, ConfigReceiver.DEFAULT_MQTT_ENABLED)) {
                getInstance(context).start()
            } else {
                getInstance(context).stop()
            }
        }
    }

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            executor.execute { runClientLoop() }
        }
    }

    fun stop() {
        isRunning.set(false)
        executor.execute { closeSocket() }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        outStream = null
        inStream = null
    }

    private fun runClientLoop() {
        var backoffMs = 3000L
        while (isRunning.get()) {
            val host = prefs.getString(ConfigReceiver.KEY_MQTT_HOST, ConfigReceiver.DEFAULT_MQTT_HOST)?.trim() ?: ""
            val port = prefs.getInt(ConfigReceiver.KEY_MQTT_PORT, ConfigReceiver.DEFAULT_MQTT_PORT)
            val user = prefs.getString(ConfigReceiver.KEY_MQTT_USER, ConfigReceiver.DEFAULT_MQTT_USER)?.trim() ?: ""
            val pass = prefs.getString(ConfigReceiver.KEY_MQTT_PASS, ConfigReceiver.DEFAULT_MQTT_PASS)?.trim() ?: ""

            if (host.isEmpty()) {
                Log.i(TAG, "MQTT enabled but host is blank. Sleeping...")
                Thread.sleep(10000)
                continue
            }

            try {
                Log.i(TAG, "Connecting to MQTT broker $host:$port...")
                val s = if (port == 8883) {
                    SSLSocketFactory.getDefault().createSocket()
                } else {
                    Socket()
                }
                s.connect(InetSocketAddress(host, port), 10000)
                s.soTimeout = 60000 // 60s ping timeout
                socket = s

                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                val inS = DataInputStream(BufferedInputStream(s.getInputStream()))
                outStream = out
                inStream = inS

                // Send CONNECT
                sendConnect(out, deviceId, user, pass)

                // Read CONNACK
                val ackType = inS.readUnsignedByte()
                val ackLen = readRemainingLength(inS)
                val flags = inS.readUnsignedByte()
                val retCode = inS.readUnsignedByte()

                if (retCode != 0) {
                    Log.w(TAG, "MQTT connection rejected with code $retCode")
                    closeSocket()
                    Thread.sleep(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(60000L)
                    continue
                }

                Log.i(TAG, "Connected to Home Assistant MQTT Broker ✓")
                backoffMs = 3000L

                // Publish HA Auto-Discovery
                publishAutoDiscovery()

                // Subscribe to command topics
                subscribeToCommands()

                // Publish initial states
                publishAllStates()

                // Start read loop & ping ticker
                val pingRunnable = object : Runnable {
                    override fun run() {
                        if (isRunning.get() && socket != null) {
                            executor.execute { sendPing() }
                            mainHandler.postDelayed(this, 30000)
                        }
                    }
                }
                mainHandler.postDelayed(pingRunnable, 30000)

                // Read loop
                while (isRunning.get() && socket != null) {
                    val header = inS.readUnsignedByte()
                    val msgType = (header shr 4) and 0x0F
                    val len = readRemainingLength(inS)
                    val payload = ByteArray(len)
                    inS.readFully(payload)

                    when (msgType) {
                        3 -> handlePublish(payload) // PUBLISH
                        13 -> {} // PINGRESP
                        else -> {}
                    }
                }

            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "MQTT connection dropped (${e.message}). Reconnecting in ${backoffMs / 1000}s...")
                }
                closeSocket()
                Thread.sleep(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60000L)
            }
        }
    }

    private fun sendConnect(out: DataOutputStream, clientId: String, user: String, pass: String) {
        val payload = ByteArrayOutputStream()
        val pOut = DataOutputStream(payload)

        // Protocol Name: "MQTT"
        pOut.writeUTF("MQTT")
        pOut.writeByte(4) // 3.1.1

        var flags = 0x02 // Clean session
        if (user.isNotEmpty()) flags = flags or 0x80
        if (pass.isNotEmpty()) flags = flags or 0x40
        pOut.writeByte(flags)
        pOut.writeShort(60) // Keepalive 60s

        pOut.writeUTF(clientId)
        if (user.isNotEmpty()) pOut.writeUTF(user)
        if (pass.isNotEmpty()) pOut.writeUTF(pass)

        val body = payload.toByteArray()
        out.writeByte(0x10) // CONNECT
        writeRemainingLength(out, body.size)
        out.write(body)
        out.flush()
    }

    private fun sendPing() {
        try {
            outStream?.let { out ->
                out.writeByte(0xC0) // PINGREQ
                out.writeByte(0x00)
                out.flush()
            }
        } catch (_: Exception) {}
    }

    private fun subscribeToCommands() {
        val out = outStream ?: return
        val prefix = getPrefix()
        val topic = "$prefix/#"

        val payload = ByteArrayOutputStream()
        val pOut = DataOutputStream(payload)
        pOut.writeShort(packetId++)
        pOut.writeUTF(topic)
        pOut.writeByte(0) // QoS 0

        val body = payload.toByteArray()
        out.writeByte(0x82) // SUBSCRIBE
        writeRemainingLength(out, body.size)
        out.write(body)
        out.flush()
        Log.i(TAG, "Subscribed to $topic")
    }

    private fun publishAutoDiscovery() {
        val prefix = getPrefix()
        val devInfo = JSONObject()
            .put("identifiers", JSONArray().put(deviceId))
            .put("name", deviceName)
            .put("model", "Meta Portal")
            .put("manufacturer", "Meta")
            .put("sw_version", UpdateChecker.currentVersionName(appContext))

        // 1. Screen Power Switch
        publishJson(
            "homeassistant/switch/${deviceId}_screen/config",
            JSONObject()
                .put("name", "Screen")
                .put("unique_id", "${deviceId}_screen")
                .put("state_topic", "$prefix/screen/state")
                .put("command_topic", "$prefix/screen/set")
                .put("icon", "mdi:monitor")
                .put("device", devInfo)
        )

        // 2. Screen Brightness Light
        publishJson(
            "homeassistant/light/${deviceId}_brightness/config",
            JSONObject()
                .put("name", "Brightness")
                .put("unique_id", "${deviceId}_brightness")
                .put("state_topic", "$prefix/brightness/state")
                .put("command_topic", "$prefix/brightness/set")
                .put("brightness_state_topic", "$prefix/brightness/state")
                .put("brightness_command_topic", "$prefix/brightness/set")
                .put("brightness_scale", 100)
                .put("schema", "json")
                .put("device", devInfo)
        )

        // 3. Speaker Volume Slider
        publishJson(
            "homeassistant/number/${deviceId}_volume/config",
            JSONObject()
                .put("name", "Volume")
                .put("unique_id", "${deviceId}_volume")
                .put("state_topic", "$prefix/volume/state")
                .put("command_topic", "$prefix/volume/set")
                .put("min", 0)
                .put("max", 100)
                .put("step", 1)
                .put("icon", "mdi:volume-high")
                .put("device", devInfo)
        )

        // 4. Battery Sensor
        publishJson(
            "homeassistant/sensor/${deviceId}_battery/config",
            JSONObject()
                .put("name", "Battery")
                .put("unique_id", "${deviceId}_battery")
                .put("state_topic", "$prefix/battery/state")
                .put("unit_of_measurement", "%")
                .put("device_class", "battery")
                .put("device", devInfo)
        )

        // 5. Custom Overlay Message Text Entity
        publishJson(
            "homeassistant/text/${deviceId}_message/config",
            JSONObject()
                .put("name", "Banner Message")
                .put("unique_id", "${deviceId}_message")
                .put("state_topic", "$prefix/message/state")
                .put("command_topic", "$prefix/message/set")
                .put("icon", "mdi:message-badge")
                .put("device", devInfo)
        )

        // 6. Next / Previous Photo Buttons
        publishJson(
            "homeassistant/button/${deviceId}_next/config",
            JSONObject()
                .put("name", "Next Photo")
                .put("unique_id", "${deviceId}_next")
                .put("command_topic", "$prefix/next/set")
                .put("icon", "mdi:skip-next")
                .put("device", devInfo)
        )
        publishJson(
            "homeassistant/button/${deviceId}_prev/config",
            JSONObject()
                .put("name", "Previous Photo")
                .put("unique_id", "${deviceId}_prev")
                .put("command_topic", "$prefix/prev/set")
                .put("icon", "mdi:skip-previous")
                .put("device", devInfo)
        )

        // 7. Embedded Dashboard Switch
        publishJson(
            "homeassistant/switch/${deviceId}_dashboard/config",
            JSONObject()
                .put("name", "Home Assistant Dashboard")
                .put("unique_id", "${deviceId}_dashboard")
                .put("state_topic", "$prefix/dashboard/state")
                .put("command_topic", "$prefix/dashboard/set")
                .put("icon", "mdi:view-dashboard")
                .put("device", devInfo)
        )
    }

    private fun handlePublish(payload: ByteArray) {
        val pIn = DataInputStream(ByteArrayInputStream(payload))
        val topic = pIn.readUTF()
        val msgBytes = ByteArray(payload.size - (topic.length + 2))
        pIn.readFully(msgBytes)
        val msg = String(msgBytes, StandardCharsets.UTF_8).trim()

        Log.i(TAG, "MQTT Received [$topic] -> $msg")
        val prefix = getPrefix()

        when (topic) {
            "$prefix/screen/set" -> {
                val on = msg.equals("ON", ignoreCase = true)
                mainHandler.post {
                    if (on) {
                        appContext.sendBroadcast(Intent(ConfigReceiver.ACTION_SHOW_SLIDESHOW))
                    }
                    publishState("$prefix/screen/state", if (on) "ON" else "OFF")
                }
            }

            "$prefix/brightness/set" -> {
                try {
                    val percent = if (msg.startsWith("{")) {
                        val json = JSONObject(msg)
                        if (json.has("state") && json.getString("state") == "OFF") 0
                        else json.optInt("brightness", 80)
                    } else {
                        msg.toIntOrNull() ?: 80
                    }.coerceIn(0, 100)

                    val value255 = (percent * 255 / 100).coerceIn(1, 255)
                    Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value255)
                    publishState("$prefix/brightness/state", if (percent > 0) "{\"state\":\"ON\",\"brightness\":$percent}" else "{\"state\":\"OFF\",\"brightness\":0}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set brightness", e)
                }
            }

            "$prefix/volume/set" -> {
                try {
                    val percent = msg.toIntOrNull()?.coerceIn(0, 100) ?: return
                    val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetVol = (percent * maxVol / 100).coerceIn(0, maxVol)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                    publishState("$prefix/volume/state", percent.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set volume", e)
                }
            }

            "$prefix/message/set" -> {
                prefs.edit().putString(ConfigReceiver.KEY_CUSTOM_MESSAGE, msg).apply()
                appContext.sendBroadcast(Intent(ConfigReceiver.ACTION_SET_MESSAGE).putExtra("message", msg))
                publishState("$prefix/message/state", msg)
            }

            "$prefix/next/set" -> {
                appContext.sendBroadcast(Intent(ConfigReceiver.ACTION_NEXT_PHOTO))
            }

            "$prefix/prev/set" -> {
                appContext.sendBroadcast(Intent(ConfigReceiver.ACTION_PREV_PHOTO))
            }

            "$prefix/dashboard/set" -> {
                val show = msg.equals("ON", ignoreCase = true)
                if (show) {
                    appContext.sendBroadcast(Intent(ConfigReceiver.ACTION_SHOW_DASHBOARD))
                } else {
                    appContext.sendBroadcast(Intent(ConfigReceiver.ACTION_SHOW_SLIDESHOW))
                }
                publishState("$prefix/dashboard/state", if (show) "ON" else "OFF")
            }
        }
    }

    fun publishAllStates() {
        val prefix = getPrefix()
        publishState("$prefix/screen/state", "ON")
        publishState("$prefix/dashboard/state", "OFF")

        val curMsg = prefs.getString(ConfigReceiver.KEY_CUSTOM_MESSAGE, "") ?: ""
        publishState("$prefix/message/state", curMsg)

        // Brightness
        try {
            val curB = Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val percent = (curB * 100 / 255).coerceIn(0, 100)
            publishState("$prefix/brightness/state", "{\"state\":\"ON\",\"brightness\":$percent}")
        } catch (_: Exception) {}

        // Volume
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val curV = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val percent = if (maxV > 0) (curV * 100 / maxV) else 50
            publishState("$prefix/volume/state", percent.toString())
        } catch (_: Exception) {}

        // Battery
        try {
            val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = appContext.registerReceiver(null, filter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                val pct = (level * 100 / scale)
                publishState("$prefix/battery/state", pct.toString())
            }
        } catch (_: Exception) {}
    }

    private fun publishJson(topic: String, json: JSONObject) {
        publish(topic, json.toString().toByteArray(StandardCharsets.UTF_8), retain = true)
    }

    private fun publishState(topic: String, state: String) {
        publish(topic, state.toByteArray(StandardCharsets.UTF_8), retain = true)
    }

    private fun publish(topic: String, payload: ByteArray, retain: Boolean = false) {
        executor.execute {
            try {
                val out = outStream ?: return@execute
                val buf = ByteArrayOutputStream()
                val pOut = DataOutputStream(buf)
                pOut.writeUTF(topic)
                pOut.write(payload)

                val body = buf.toByteArray()
                var header = 0x30 // PUBLISH QoS 0
                if (retain) header = header or 0x01
                out.writeByte(header)
                writeRemainingLength(out, body.size)
                out.write(body)
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to publish topic $topic", e)
            }
        }
    }

    private fun getPrefix(): String {
        return prefs.getString(ConfigReceiver.KEY_MQTT_TOPIC_PREFIX, ConfigReceiver.DEFAULT_MQTT_TOPIC_PREFIX)
            ?.takeIf { it.isNotBlank() } ?: "portal"
    }

    private fun writeRemainingLength(out: OutputStream, length: Int) {
        var len = length
        do {
            var digit = len % 128
            len /= 128
            if (len > 0) digit = digit or 0x80
            out.write(digit)
        } while (len > 0)
    }

    private fun readRemainingLength(inS: InputStream): Int {
        var multiplier = 1
        var value = 0
        var digit: Int
        do {
            digit = inS.read()
            if (digit == -1) throw java.io.EOFException()
            value += (digit and 127) * multiplier
            multiplier *= 128
        } while ((digit and 128) != 0)
        return value
    }
}
