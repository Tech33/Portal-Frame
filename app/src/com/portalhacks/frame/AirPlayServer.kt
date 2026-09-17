package com.portalhacks.frame

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Built-in lightweight AirPlay 1 Audio Receiver for Meta Portal.
 *
 * Advertises over mDNS (Bonjour) as an AirPlay audio speaker using Android's NsdManager.
 * Streams lossless audio from iOS / iPadOS / macOS via RTSP/RTP and extracts track metadata
 * (title, artist, album cover) directly into MediaMonitor.
 */
class AirPlayServer(
    private val context: Context,
    private val rtspPort: Int = 5000
) {
    companion object {
        private const val TAG = "AirPlayServer"
        private const val SAMPLE_RATE = 44100
    }

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var raopRegistrationListener: NsdManager.RegistrationListener? = null
    private var airplayRegistrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var audioTrack: AudioTrack? = null
    private var audioSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null

    @Volatile
    private var alacDecoder: AlacDecoder = AlacDecoder()

    @Volatile
    private var currentTitle: String = ""
    @Volatile
    private var currentArtist: String = ""
    @Volatile
    private var currentAlbum: String = ""

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        val prefs = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ConfigReceiver.KEY_AIRPLAY_ENABLED, ConfigReceiver.DEFAULT_AIRPLAY_ENABLED)) {
            Log.i(TAG, "AirPlay disabled in settings")
            isRunning.set(false)
            return
        }

        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("AirPlayBonjour")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Acquired Wi-Fi MulticastLock for AirPlay Bonjour")
        } catch (e: Exception) {
            Log.w(TAG, "Failed acquiring Wi-Fi multicast lock", e)
        }

        thread(name = "AirPlay-RTSP") {
            try {
                serverSocket = ServerSocket(rtspPort)
                Log.i(TAG, "AirPlay RTSP server listening on port $rtspPort")
                registerBonjourService()

                while (isRunning.get()) {
                    val socket = serverSocket?.accept() ?: break
                    thread(name = "AirPlay-Client") {
                        handleRtspClient(socket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "AirPlay server error", e)
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        unregisterBonjourService()
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null

        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null
        stopAudioPlayback()
        Log.i(TAG, "AirPlay server stopped")
    }

    private fun registerBonjourService() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val displayName = ConfigReceiver.getDeviceDisplayName(context)
            val mac = getMacAddressClean()

            // 1. Register _raop._tcp (Remote Audio Output Protocol on port 5000)
            val raopInfo = NsdServiceInfo().apply {
                serviceType = "_raop._tcp"
                serviceName = "$mac@$displayName"
                port = rtspPort
                setAttribute("ch", "2")
                setAttribute("cn", "0,1")
                setAttribute("sr", "44100")
                setAttribute("ss", "16")
                setAttribute("tp", "TCP,UDP")
                setAttribute("sm", "false")
                setAttribute("sv", "false")
                setAttribute("da", "true")
                setAttribute("et", "0,1")
                setAttribute("md", "0,1,2")
                setAttribute("vn", "3")
                setAttribute("txtvers", "1")
                setAttribute("sf", "0x4")
                setAttribute("vs", "220.68")
            }

            raopRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo?) {
                    Log.i(TAG, "Bonjour RAOP registered: ${info?.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "Bonjour RAOP registration failed: code=$errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo?) {
                    Log.i(TAG, "Bonjour RAOP unregistered")
                }
                override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
            }
            nsdManager?.registerService(raopInfo, NsdManager.PROTOCOL_DNS_SD, raopRegistrationListener)

            // 2. Register _airplay._tcp (AirPlay device discovery on port 7000)
            val airplayInfo = NsdServiceInfo().apply {
                serviceType = "_airplay._tcp"
                serviceName = displayName
                port = 7000
                setAttribute("deviceid", mac)
                setAttribute("features", "0x5A7FFFF7,0x1E")
                setAttribute("model", "AppleTV3,2")
                setAttribute("srcvers", "220.68")
                setAttribute("flags", "0x4")
                setAttribute("pk", "b07727d6f6cd534b47a62ec7be7bed045cdd6985")
                setAttribute("pi", "2e388006-13ba-4041-9a67-25dd4a43d536")
                setAttribute("vv", "2")
            }

            airplayRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo?) {
                    Log.i(TAG, "Bonjour AirPlay registered: ${info?.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "Bonjour AirPlay registration failed: code=$errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo?) {
                    Log.i(TAG, "Bonjour AirPlay unregistered")
                }
                override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
            }
            nsdManager?.registerService(airplayInfo, NsdManager.PROTOCOL_DNS_SD, airplayRegistrationListener)

        } catch (e: Exception) {
            Log.w(TAG, "Failed registering AirPlay Bonjour services", e)
        }
    }

    private fun unregisterBonjourService() {
        try {
            raopRegistrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (ignored: Exception) {}
        raopRegistrationListener = null

        try {
            airplayRegistrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (ignored: Exception) {}
        airplayRegistrationListener = null
    }

    private fun handleRtspClient(socket: Socket) {
        var clientAudioPort = 0
        var clientControlPort = 0
        var serverAudioPort = 6000
        var serverControlPort = 6001

        try {
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

            while (isRunning.get()) {
                val reqLine = reader.readLine() ?: break
                val parts = reqLine.split(" ")
                if (parts.size < 2) break
                val method = parts[0].uppercase()

                var cseq = "1"
                var contentLength = 0
                var contentType = ""
                var transport = ""

                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                    val lower = h.lowercase(java.util.Locale.US)
                    when {
                        lower.startsWith("cseq:") -> cseq = h.substring(5).trim()
                        lower.startsWith("content-length:") -> contentLength = h.substring(15).trim().toIntOrNull() ?: 0
                        lower.startsWith("content-type:") -> contentType = h.substring(13).trim()
                        lower.startsWith("transport:") -> transport = h.substring(10).trim()
                    }
                }

                val bodyBytes = if (contentLength > 0) {
                    readExactBytes(input, contentLength)
                } else {
                    ByteArray(0)
                }

                when (method) {
                    "OPTIONS" -> {
                        val resp = "RTSP/1.0 200 OK\r\n" +
                                "CSeq: $cseq\r\n" +
                                "Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, SET_PARAMETER, GET_PARAMETER\r\n\r\n"
                        out.write(resp.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    "ANNOUNCE" -> {
                        parseAnnounceSdp(bodyBytes)
                        val resp = "RTSP/1.0 200 OK\r\n" +
                                "CSeq: $cseq\r\n\r\n"
                        out.write(resp.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    "SETUP" -> {
                        // Parse client ports
                        val mControl = "control_port=(\\d+)".toRegex().find(transport)
                        val mTiming = "timing_port=(\\d+)".toRegex().find(transport)
                        if (mControl != null) clientControlPort = mControl.groupValues[1].toInt()

                        serverAudioPort = (6000..6100).random()
                        serverControlPort = serverAudioPort + 1

                        setupAudioSocket(serverAudioPort)

                        val resp = "RTSP/1.0 200 OK\r\n" +
                                "CSeq: $cseq\r\n" +
                                "Transport: RTP/AVP/UDP;unicast;mode=record;server_port=$serverAudioPort;control_port=$serverControlPort\r\n" +
                                "Session: 1\r\n" +
                                "Audio-Jack-Status: connected\r\n\r\n"
                        out.write(resp.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    "RECORD" -> {
                        startAudioTrack()
                        MediaMonitor.airPlayActionCallback = { action ->
                            // Local transport control hook
                        }
                        MediaMonitor.update(
                            isPlaying = true,
                            title = if (currentTitle.isNotEmpty()) currentTitle else "AirPlay Audio",
                            artist = if (currentArtist.isNotEmpty()) currentArtist else "Streaming from Apple Device",
                            album = currentAlbum,
                            source = "AirPlay"
                        )
                        val resp = "RTSP/1.0 200 OK\r\n" +
                                "CSeq: $cseq\r\n" +
                                "Audio-Latency: 11025\r\n\r\n"
                        out.write(resp.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    "SET_PARAMETER" -> {
                        handleSetParameter(contentType, bodyBytes)
                        val resp = "RTSP/1.0 200 OK\r\n" +
                                "CSeq: $cseq\r\n\r\n"
                        out.write(resp.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    "FLUSH" -> {
                        audioTrack?.flush()
                        val resp = "RTSP/1.0 200 OK\r\n" +
                                "CSeq: $cseq\r\n\r\n"
                        out.write(resp.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    "TEARDOWN" -> {
                        stopAudioPlayback()
                        MediaMonitor.clear()
                        val resp = "RTSP/1.0 200 OK\r\n" +
                                "CSeq: $cseq\r\n" +
                                "Connection: close\r\n\r\n"
                        out.write(resp.toByteArray(Charsets.UTF_8))
                        out.flush()
                        break
                    }
                    else -> {
                        val resp = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n"
                        out.write(resp.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client session closed or errored: ${e.message}")
        } finally {
            try { socket.close() } catch (ignored: Exception) {}
        }
    }

    private fun parseAnnounceSdp(body: ByteArray) {
        val sdp = String(body, Charsets.UTF_8)
        currentTitle = ""
        currentArtist = ""
        currentAlbum = ""
        for (line in sdp.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("a=fmtp:")) {
                alacDecoder = AlacDecoder.parseFmtp(trimmed)
                Log.i(TAG, "Initialized AlacDecoder from SDP: frameLength=${alacDecoder.frameLength}, channels=${alacDecoder.channels}")
            }
        }
    }

    private fun handleSetParameter(contentType: String, body: ByteArray) {
        when {
            contentType.startsWith("image/", ignoreCase = true) -> {
                try {
                    val bmp = BitmapFactory.decodeByteArray(body, 0, body.size)
                    if (bmp != null) {
                        MediaMonitor.update(
                            isPlaying = true,
                            title = if (currentTitle.isNotEmpty()) currentTitle else "AirPlay Audio",
                            artist = if (currentArtist.isNotEmpty()) currentArtist else "Streaming from Apple Device",
                            album = currentAlbum,
                            art = bmp,
                            source = "AirPlay"
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error decoding AirPlay album art", e)
                }
            }
            contentType.contains("dmap-tagged", ignoreCase = true) -> {
                parseDmapMetadata(body)
            }
            contentType.startsWith("text/parameters", ignoreCase = true) -> {
                val str = String(body, Charsets.UTF_8)
                if (str.startsWith("volume:")) {
                    val volDb = str.substring(7).trim().toFloatOrNull() ?: 0f
                    applyVolume(volDb)
                }
            }
        }
    }

    private fun parseDmapMetadata(bytes: ByteArray) {
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val tag = String(bytes, offset, 4, Charsets.ISO_8859_1)
            val len = ((bytes[offset + 4].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 5].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 6].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 7].toInt() and 0xFF)
            offset += 8
            if (offset + len > bytes.size || len < 0) break

            val valBytes = bytes.copyOfRange(offset, offset + len)
            val strVal = String(valBytes, Charsets.UTF_8)

            when (tag) {
                "minm" -> currentTitle = strVal
                "asar" -> currentArtist = strVal
                "asal" -> currentAlbum = strVal
            }
            offset += len
        }

        if (currentTitle.isNotEmpty() || currentArtist.isNotEmpty()) {
            MediaMonitor.update(
                isPlaying = true,
                title = currentTitle,
                artist = currentArtist,
                album = currentAlbum,
                source = "AirPlay"
            )
        }
    }

    private fun applyVolume(volDb: Float) {
        // AirPlay volume is typically -30.0dB to 0.0dB (-144 is mute)
        val track = audioTrack ?: return
        if (volDb <= -140f) {
            track.setVolume(0f)
            return
        }
        val linear = Math.pow(10.0, (volDb / 20.0).toDouble()).toFloat().coerceIn(0f, 1f)
        track.setVolume(linear)
    }

    private fun setupAudioSocket(port: Int) {
        try {
            audioSocket?.close()
            audioSocket = DatagramSocket(port)
            val buf = ByteArray(4096)

            thread(name = "AirPlay-Audio-UDP") {
                val packet = DatagramPacket(buf, buf.size)
                while (isRunning.get()) {
                    try {
                        val sock = audioSocket ?: break
                        sock.receive(packet)
                        val len = packet.length
                        // Skip 12-byte RTP header
                        if (len > 12) {
                            try {
                                val pcm = alacDecoder.decodeFrame(buf, 12, len - 12)
                                audioTrack?.write(pcm, 0, pcm.size)
                            } catch (_: Exception) {
                                audioTrack?.write(buf, 12, len - 12)
                            }
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting up UDP audio socket on $port", e)
        }
    }

    private fun startAudioTrack() {
        if (audioTrack != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

    private fun stopAudioPlayback() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (ignored: Exception) {}
        audioTrack = null
        try {
            audioSocket?.close()
        } catch (ignored: Exception) {}
        audioSocket = null
    }

    private fun readExactBytes(input: InputStream, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length)
        val buf = ByteArray(4096)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buf, 0, Math.min(buf.size, remaining))
            if (read == -1) break
            out.write(buf, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun getMacAddressClean(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.equals("wlan0", ignoreCase = true) || intf.name.equals("eth0", ignoreCase = true)) {
                    val mac = intf.hardwareAddress ?: continue
                    val sb = StringBuilder()
                    for (b in mac) {
                        sb.append(String.format("%02X", b))
                    }
                    if (sb.isNotEmpty()) return sb.toString()
                }
            }
        } catch (ignored: Exception) {}
        return "001122334455"
    }
}
