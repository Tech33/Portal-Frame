package com.portalhacks.frame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Direct native local Wi-Fi Sonos discovery and playback controller for Portal-Frame.
 *
 * Uses UPnP / SSDP on port 1900 to discover Sonos ZonePlayer speakers on the home subnet,
 * and AVTransport / RenderingControl SOAP endpoints on port 1400 to query live playback metadata,
 * album artwork, and volume, delivering unified transport control directly to the Nest Hub card.
 */
object SonosMonitor {

    private const val TAG = "SonosMonitor"
    private const val SSDP_PORT = 1900
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SONOS_PORT = 1400

    data class Speaker(
        val ip: String,
        var roomName: String = "Sonos",
        var isPlaying: Boolean = false,
        var title: String = "",
        var artist: String = "",
        var album: String = "",
        var artUrl: String = "",
        var artBitmap: Bitmap? = null,
        var volume: Int = 50,
        var lastUpdated: Long = System.currentTimeMillis()
    )

    private val speakers = ConcurrentHashMap<String, Speaker>()
    @Volatile
    var activeSpeakerIp: String? = null
        private set

    private val isRunning = AtomicBoolean(false)
    private var multicastLock: WifiManager.MulticastLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        if (isRunning.getAndSet(true)) return
        Log.i(TAG, "Starting Sonos native Wi-Fi monitor")

        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("portal_sonos_multicast")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire multicast lock for SSDP", e)
        }

        // Register action callback with unified MediaMonitor
        MediaMonitor.sonosActionCallback = { action, arg ->
            val ip = activeSpeakerIp ?: speakers.keys.firstOrNull()
            if (ip != null) {
                thread(name = "Sonos-Action") {
                    when (action) {
                        "play_pause" -> {
                            val spk = speakers[ip]
                            if (spk?.isPlaying == true) pause(ip) else play(ip)
                        }
                        "next" -> next(ip)
                        "prev" -> prev(ip)
                        "volume" -> if (arg is Int) setVolume(ip, arg)
                    }
                }
            }
        }

        // Background worker loop
        thread(name = "SonosMonitor-Worker", isDaemon = true) {
            var lastDiscoveryMs = 0L
            while (isRunning.get()) {
                val now = System.currentTimeMillis()

                // Discover speakers every 45 seconds or if none known
                if (speakers.isEmpty() || now - lastDiscoveryMs > 45000L) {
                    discoverSpeakers()
                    lastDiscoveryMs = now
                }

                // Poll active and known speakers
                pollSpeakers()

                try {
                    Thread.sleep(3000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            multicastLock?.release()
        } catch (_: Exception) {}
        multicastLock = null
    }

    private fun discoverSpeakers() {
        try {
            val query = ("M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n").toByteArray(Charsets.UTF_8)

            val group = InetAddress.getByName(SSDP_ADDRESS)
            val socket = DatagramSocket().apply {
                soTimeout = 2000
                broadcast = true
            }

            val packet = DatagramPacket(query, query.size, group, SSDP_PORT)
            socket.send(packet)

            val buf = ByteArray(2048)
            val endAt = System.currentTimeMillis() + 2200L

            while (System.currentTimeMillis() < endAt) {
                try {
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    val ip = resp.address.hostAddress ?: continue
                    if (!speakers.containsKey(ip)) {
                        val speaker = Speaker(ip = ip)
                        speakers[ip] = speaker
                        resolveSpeakerRoomName(speaker)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    break
                } catch (_: Exception) {
                    break
                }
            }
            socket.close()
        } catch (e: Exception) {
            Log.d(TAG, "SSDP search finished: ${speakers.size} Sonos speakers known")
        }
    }

    private fun resolveSpeakerRoomName(speaker: Speaker) {
        thread(name = "Sonos-Resolve-${speaker.ip}") {
            try {
                val url = URL("http://${speaker.ip}:$SONOS_PORT/xml/device_description.xml")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val room = extractTag(body, "roomName").ifEmpty { extractTag(body, "friendlyName") }
                    if (room.isNotEmpty()) {
                        speaker.roomName = room
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun pollSpeakers() {
        if (speakers.isEmpty()) return

        // If local player on Portal is actively playing, don't override with Sonos
        if (MediaMonitor.activeController?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) {
            return
        }

        var foundPlayingSpeaker: Speaker? = null

        for ((_, speaker) in speakers) {
            val stateXml = sendSoap(
                ip = speaker.ip,
                endpoint = "/MediaRenderer/AVTransport/Control",
                service = "AVTransport:1",
                action = "GetTransportInfo",
                bodyXml = "<InstanceID>0</InstanceID>"
            )
            if (stateXml == null) {
                speaker.isPlaying = false
                continue
            }

            val transportState = extractTag(stateXml, "CurrentTransportState").uppercase()
            if (transportState != "PLAYING") {
                speaker.isPlaying = false
                speaker.title = ""
                speaker.artist = ""
                speaker.album = ""
                speaker.artBitmap = null
                speaker.artUrl = ""
                continue
            }

            // Get Position & Track Metadata
            val posXml = sendSoap(
                ip = speaker.ip,
                endpoint = "/MediaRenderer/AVTransport/Control",
                service = "AVTransport:1",
                action = "GetPositionInfo",
                bodyXml = "<InstanceID>0</InstanceID>"
            )

            if (posXml == null) {
                speaker.isPlaying = false
                speaker.title = ""
                speaker.artist = ""
                speaker.album = ""
                speaker.artBitmap = null
                speaker.artUrl = ""
                continue
            }

            // Check TrackURI for TV / line-in / auxiliary standby streams
            val trackUri = extractTag(posXml, "TrackURI").ifEmpty { extractTag(posXml, "URI") }
            val rawMeta = extractTag(posXml, "TrackMetaData")
            val decodedMeta = unescapeXml(rawMeta)
            val trackTitle = extractTag(decodedMeta, "dc:title").trim()

            if (isTvOrAuxUri(trackUri) || isDummyTitle(trackTitle)) {
                // Not actively playing music (e.g. TV idle/on, line-in, or no track info)
                speaker.isPlaying = false
                speaker.title = ""
                speaker.artist = ""
                speaker.album = ""
                speaker.artBitmap = null
                speaker.artUrl = ""
                continue
            }

            // Valid music playback detected
            speaker.isPlaying = true
            speaker.title = trackTitle
            speaker.artist = extractTag(decodedMeta, "dc:creator").ifEmpty { extractTag(decodedMeta, "r:albumArtist") }
            speaker.album = extractTag(decodedMeta, "upnp:album")

            val rawArtPath = extractTag(decodedMeta, "upnp:albumArtURI").ifEmpty {
                extractTag(decodedMeta, "r:albumArtURI")
            }
            val artPath = unescapeXml(rawArtPath).replace("&amp;", "&")
            if (artPath.isNotEmpty()) {
                val fullArtUrl = if (artPath.startsWith("http")) artPath else "http://${speaker.ip}:$SONOS_PORT$artPath"
                if (fullArtUrl != speaker.artUrl || speaker.artBitmap == null) {
                    speaker.artUrl = fullArtUrl
                    speaker.artBitmap = fetchBitmap(fullArtUrl)
                }
            } else {
                speaker.artUrl = ""
                speaker.artBitmap = null
            }

            // Get Volume
            val volXml = sendSoap(
                ip = speaker.ip,
                endpoint = "/MediaRenderer/RenderingControl/Control",
                service = "RenderingControl:1",
                action = "GetVolume",
                bodyXml = "<InstanceID>0</InstanceID><Channel>Master</Channel>"
            )
            if (volXml != null) {
                val volStr = extractTag(volXml, "CurrentVolume")
                speaker.volume = volStr.toIntOrNull() ?: speaker.volume
            }

            foundPlayingSpeaker = speaker
            break
        }

        if (foundPlayingSpeaker != null) {
            activeSpeakerIp = foundPlayingSpeaker.ip
            MediaMonitor.update(
                isPlaying = true,
                title = foundPlayingSpeaker.title,
                artist = foundPlayingSpeaker.artist,
                album = foundPlayingSpeaker.album,
                art = foundPlayingSpeaker.artBitmap,
                source = "Sonos",
                deviceName = "${foundPlayingSpeaker.roomName} Sonos",
                volume = foundPlayingSpeaker.volume
            )
        } else {
            // No Sonos speaker is actively streaming music
            val wasSonos = activeSpeakerIp != null || MediaMonitor.currentState.source.contains("Sonos", ignoreCase = true)
            activeSpeakerIp = null
            if (wasSonos) {
                MediaMonitor.clear()
            }
        }
    }

    fun play(ip: String) {
        sendSoap(
            ip = ip,
            endpoint = "/MediaRenderer/AVTransport/Control",
            service = "AVTransport:1",
            action = "Play",
            bodyXml = "<InstanceID>0</InstanceID><Speed>1</Speed>"
        )
    }

    fun pause(ip: String) {
        sendSoap(
            ip = ip,
            endpoint = "/MediaRenderer/AVTransport/Control",
            service = "AVTransport:1",
            action = "Pause",
            bodyXml = "<InstanceID>0</InstanceID>"
        )
    }

    fun next(ip: String) {
        sendSoap(
            ip = ip,
            endpoint = "/MediaRenderer/AVTransport/Control",
            service = "AVTransport:1",
            action = "Next",
            bodyXml = "<InstanceID>0</InstanceID>"
        )
    }

    fun prev(ip: String) {
        sendSoap(
            ip = ip,
            endpoint = "/MediaRenderer/AVTransport/Control",
            service = "AVTransport:1",
            action = "Previous",
            bodyXml = "<InstanceID>0</InstanceID>"
        )
    }

    fun setVolume(ip: String, volume: Int) {
        val safe = volume.coerceIn(0, 100)
        sendSoap(
            ip = ip,
            endpoint = "/MediaRenderer/RenderingControl/Control",
            service = "RenderingControl:1",
            action = "SetVolume",
            bodyXml = "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$safe</DesiredVolume>"
        )
    }

    private fun sendSoap(
        ip: String,
        endpoint: String,
        service: String,
        action: String,
        bodyXml: String
    ): String? {
        return try {
            val url = URL("http://$ip:$SONOS_PORT$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:$service#$action\"")

            val envelope = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:$action xmlns:u=\"urn:schemas-upnp-org:service:$service\">" +
                bodyXml +
                "</u:$action></s:Body></s:Envelope>").toByteArray(Charsets.UTF_8)

            conn.outputStream.use { it.write(envelope) }

            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchBitmap(urlStr: String): Bitmap? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "PortalFrame/1.0")
            conn.connectTimeout = 3500
            conn.readTimeout = 4500
            if (conn.responseCode in 200..299) {
                BitmapFactory.decodeStream(conn.inputStream)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    internal fun isTvOrAuxUri(uri: String): Boolean {
        val lower = uri.lowercase()
        return lower.contains("x-sonos-htastream") ||
            lower.contains("x-sonos-vli") ||
            lower.contains(":spdif") ||
            lower.contains("x-rincon-stream") ||
            lower.contains("x-rincon-audiodoc") ||
            (lower.contains("x-sonos-http") && lower.contains("linein")) ||
            lower.startsWith("netbios:")
    }

    internal fun isDummyTitle(title: String): Boolean {
        val t = title.trim()
        return t.isEmpty() ||
            t.equals("TV", ignoreCase = true) ||
            t.equals("Audio In", ignoreCase = true) ||
            t.equals("Line-in", ignoreCase = true) ||
            t.equals("Line In", ignoreCase = true) ||
            t.equals("NOT_IMPLEMENTED", ignoreCase = true) ||
            t.equals("Playing on Sonos", ignoreCase = true) ||
            t.equals("Sonos", ignoreCase = true) ||
            t.equals("Silence", ignoreCase = true)
    }

    private fun extractTag(xml: String, tag: String): String {
        val open = "<$tag"
        val close = "</$tag>"
        val startIdx = xml.indexOf(open)
        if (startIdx == -1) return ""
        val contentStart = xml.indexOf('>', startIdx) + 1
        val endIdx = xml.indexOf(close, contentStart)
        if (endIdx == -1) return ""
        return xml.substring(contentStart, endIdx).trim()
    }

    private fun unescapeXml(text: String): String {
        return text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }
}
