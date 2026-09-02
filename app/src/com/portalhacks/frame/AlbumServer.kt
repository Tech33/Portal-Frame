package com.portalhacks.frame

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * High-performance local HTTP server running inside Portal-Frame (default port 8080).
 *
 * Provides:
 * 1. `GET /slideshow` or `GET /`: A standalone, responsive HTML5 Apple-styled photo slideshow
 *    with flip clock, weather, memory timeline captions, and smooth transitions for portal-ha-bridge
 *    and Home Assistant Webpage cards.
 * 2. `GET /api/slides`: JSON endpoint delivering the cached slide list from Google Photos & iCloud albums.
 * 3. `GET /api/photo?id=...`: Streams local cached image bytes directly to WebViews.
 * 4. `GET /api/status`: JSON server diagnostics and album counts.
 * 5. `GET /add` & `POST /add`: Phone QR-code pairing and album addition form.
 */
class AlbumServer(
    private val context: Context,
    val port: Int = DEFAULT_PORT,
    private var onUrlReceived: ((String) -> Boolean)? = null,
) {

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val cacheDir = File(context.cacheDir, "photos")

    fun setOnUrlReceived(callback: ((String) -> Boolean)?) {
        this.onUrlReceived = callback
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        thread(name = "AlbumServerThread") {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Local AlbumServer listening on http://127.0.0.1:$port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    thread(name = "AlbumClientWorker") {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Server socket error on port $port", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null
        Log.i(TAG, "Local AlbumServer stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val line = reader.readLine() ?: return

            // Parse request line: e.g. "GET /slideshow HTTP/1.1"
            val parts = line.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            var rawPath = parts[1]
            var queryString = ""
            if (rawPath.contains("?")) {
                val qIndex = rawPath.indexOf("?")
                queryString = rawPath.substring(qIndex + 1)
                rawPath = rawPath.substring(0, qIndex)
            }

            // Read headers
            var contentLength = 0
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isEmpty()) break
                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = headerLine.substring(15).trim().toIntOrNull() ?: 0
                }
            }

            val prefs = context.getSharedPreferences("portalframe", Context.MODE_PRIVATE)

            when {
                // HTML5 Apple-styled slideshow endpoint
                method == "GET" && (rawPath == "/" || rawPath == "/slideshow" || rawPath == "/frame") -> {
                    val html = getSlideshowHtml(prefs)
                    sendResponse(socket, 200, "OK", "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
                }

                // JSON list of active slides
                method == "GET" && rawPath == "/api/slides" -> {
                    val json = getSlidesJson(prefs)
                    sendResponse(socket, 200, "OK", "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
                }

                // Cached photo image stream
                method == "GET" && rawPath == "/api/photo" -> {
                    val id = parseQueryParam(queryString, "id") ?: parseQueryParam(queryString, "url")
                    if (!id.isNullOrEmpty()) {
                        serveCachedPhoto(socket, id)
                    } else {
                        sendResponse(socket, 400, "Bad Request", "text/plain", "Missing id".toByteArray())
                    }
                }

                // Message banner web form
                method == "GET" && rawPath == "/message" -> {
                    val currentMsg = prefs.getString(ConfigReceiver.KEY_CUSTOM_MESSAGE, "") ?: ""
                    sendResponse(socket, 200, "OK", "text/html; charset=utf-8", getMessageHtml(currentMsg).toByteArray(Charsets.UTF_8))
                }

                // Submit or clear custom message banner
                method == "POST" && (rawPath == "/message" || rawPath == "/api/message") -> {
                    val body = CharArray(contentLength.coerceAtMost(64 * 1024))
                    var read = 0
                    while (read < body.size) {
                        val n = reader.read(body, read, body.size - read)
                        if (n == -1) break
                        read += n
                    }
                    val bodyStr = String(body)
                    val msg = parseFormParam(bodyStr, "message") ?: parseJsonMessage(bodyStr) ?: ""
                    val sanitized = sanitizeHtml(msg.trim().take(200))
                    prefs.edit().putString(ConfigReceiver.KEY_CUSTOM_MESSAGE, sanitized).apply()
                    context.sendBroadcast(Intent(ConfigReceiver.ACTION_SET_MESSAGE).putExtra("message", sanitized))
                    MqttManager.getInstance(context).publishAllStates()
                    sendResponse(socket, 200, "Success", "text/html; charset=utf-8", getMessageSuccessHtml(sanitized).toByteArray(Charsets.UTF_8))
                }

                method == "POST" && (rawPath == "/api/message/clear" || rawPath == "/message/clear") -> {
                    prefs.edit().remove(ConfigReceiver.KEY_CUSTOM_MESSAGE).apply()
                    context.sendBroadcast(Intent(ConfigReceiver.ACTION_CLEAR_MESSAGE))
                    MqttManager.getInstance(context).publishAllStates()
                    sendResponse(socket, 200, "Success", "application/json; charset=utf-8", "{\"status\":\"cleared\"}".toByteArray(Charsets.UTF_8))
                }

                // Save clock/date transform config from webview drag & pinch
                method == "POST" && rawPath == "/api/config" -> {
                    val body = CharArray(contentLength.coerceAtMost(64 * 1024))
                    var read = 0
                    while (read < body.size) {
                        val n = reader.read(body, read, body.size - read)
                        if (n == -1) break
                        read += n
                    }
                    val bodyStr = String(body)
                    try {
                        val json = JSONObject(bodyStr)
                        val editor = prefs.edit()
                        if (json.has("clock_dx")) editor.putFloat(ConfigReceiver.KEY_CLOCK_DX, json.getDouble("clock_dx").toFloat().coerceIn(-1.0f, 1.0f))
                        if (json.has("clock_dy")) editor.putFloat(ConfigReceiver.KEY_CLOCK_DY, json.getDouble("clock_dy").toFloat().coerceIn(-1.0f, 1.0f))
                        if (json.has("clock_scale")) editor.putFloat(ConfigReceiver.KEY_CLOCK_SCALE, json.getDouble("clock_scale").toFloat().coerceIn(0.5f, 3.0f))
                        if (json.has("clock_only_dx")) editor.putFloat(ConfigReceiver.KEY_CLOCK_ONLY_DX, json.getDouble("clock_only_dx").toFloat().coerceIn(-1.0f, 1.0f))
                        if (json.has("clock_only_dy")) editor.putFloat(ConfigReceiver.KEY_CLOCK_ONLY_DY, json.getDouble("clock_only_dy").toFloat().coerceIn(-1.0f, 1.0f))
                        if (json.has("clock_only_scale")) editor.putFloat(ConfigReceiver.KEY_CLOCK_ONLY_SCALE, json.getDouble("clock_only_scale").toFloat().coerceIn(0.5f, 3.0f))
                        editor.apply()
                        sendResponse(socket, 200, "OK", "application/json", "{\"status\":\"saved\"}".toByteArray(Charsets.UTF_8))
                    } catch (e: Exception) {
                        sendResponse(socket, 400, "Bad Request", "text/plain", e.message?.toByteArray() ?: "Error".toByteArray())
                    }
                }

                // Server status & diagnostics
                method == "GET" && rawPath == "/api/status" -> {
                    val enabledAlbums = Albums.enabled(prefs)
                    val status = JSONObject()
                        .put("status", "ok")
                        .put("version", "1.6.0")
                        .put("port", port)
                        .put("haBridgeMode", prefs.getBoolean(ConfigReceiver.KEY_HA_BRIDGE_MODE, ConfigReceiver.DEFAULT_HA_BRIDGE_MODE))
                        .put("mqttEnabled", prefs.getBoolean(ConfigReceiver.KEY_MQTT_ENABLED, ConfigReceiver.DEFAULT_MQTT_ENABLED))
                        .put("albumsCount", enabledAlbums.size)
                        .put("slideshowUrl", "http://127.0.0.1:$port/slideshow")
                    sendResponse(socket, 200, "OK", "application/json; charset=utf-8", status.toString().toByteArray(Charsets.UTF_8))
                }

                // Form to add album via QR code
                method == "GET" && rawPath == "/add" -> {
                    sendResponse(socket, 200, "OK", "text/html; charset=utf-8", getAddAlbumHtml().toByteArray(Charsets.UTF_8))
                }

                // Submit new album URL
                method == "POST" && (rawPath == "/add" || rawPath == "/api/add") -> {
                    val body = CharArray(contentLength.coerceAtMost(64 * 1024))
                    var read = 0
                    while (read < body.size) {
                        val n = reader.read(body, read, body.size - read)
                        if (n == -1) break
                        read += n
                    }
                    val bodyStr = String(body)
                    val url = parseFormUrl(bodyStr)
                    if (url != null && handleNewAlbumUrl(prefs, url)) {
                        sendResponse(socket, 200, "Success", "text/html; charset=utf-8", getSuccessHtml().toByteArray(Charsets.UTF_8))
                    } else {
                        sendResponse(socket, 400, "Bad Request", "text/html; charset=utf-8", getErrorHtml().toByteArray(Charsets.UTF_8))
                    }
                }

                else -> {
                    sendResponse(socket, 404, "Not Found", "text/plain", "Not Found".toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client request", e)
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun handleNewAlbumUrl(prefs: SharedPreferences, url: String): Boolean {
        val listener = onUrlReceived
        if (listener != null) {
            return listener.invoke(url)
        }
        if (PhotoSources.matches(url)) {
            val added = Albums.add(prefs, url)
            if (added) {
                Log.i(TAG, "Album added via local web form: $url")
            }
            return true
        }
        return false
    }

    private fun serveCachedPhoto(socket: Socket, id: String) {
        val file = File(cacheDir, md5(id) + ".img")
        if (file.exists() && file.length() > 0) {
            val out = socket.getOutputStream()
            val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${file.length()}\r\n" +
                    "Cache-Control: public, max-age=86400\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(headers.toByteArray(Charsets.UTF_8))
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }
            out.flush()
        } else {
            // Redirect to remote image if not yet downloaded to local disk
            if (id.startsWith("http://") || id.startsWith("https://")) {
                val out = socket.getOutputStream()
                val headers = "HTTP/1.1 302 Found\r\n" +
                        "Location: $id\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(headers.toByteArray(Charsets.UTF_8))
                out.flush()
            } else {
                sendResponse(socket, 404, "Not Found", "text/plain", "Image not found".toByteArray())
            }
        }
    }

    private fun getSlidesJson(prefs: SharedPreferences): String {
        val enabledAlbums = Albums.enabled(prefs)
        val buckets = ArrayList<List<Slide>>(enabledAlbums.size)
        for (url in enabledAlbums) {
            buckets.add(AlbumCache.read(prefs, url) ?: emptyList())
        }

        val playback = prefs.getString(
            ConfigReceiver.KEY_ALBUM_PLAYBACK,
            ConfigReceiver.DEFAULT_ALBUM_PLAYBACK
        ) ?: ConfigReceiver.DEFAULT_ALBUM_PLAYBACK

        val slides = when (playback) {
            "album_priority" -> buckets.flatten()
            else -> interleaveSlides(buckets)
        }

        val arr = JSONArray()
        for (s in slides) {
            val obj = JSONObject()
                .put("id", s.id)
                .put("caption", s.caption ?: "")
                .put("timeMs", s.timeMs)
                .put("portrait", s.portrait)
                .put("photoUrl", "/api/photo?id=" + java.net.URLEncoder.encode(s.id, "UTF-8"))
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun interleaveSlides(buckets: List<List<Slide>>): List<Slide> {
        val nonNull = buckets.filter { it.isNotEmpty() }
        if (nonNull.isEmpty()) return emptyList()
        val total = nonNull.sumOf { it.size }
        val out = ArrayList<Slide>(total)
        val iters = nonNull.map { it.iterator() }
        while (out.size < total) {
            for (it in iters) {
                if (it.hasNext()) out.add(it.next())
            }
        }
        return out
    }

    private fun parseQueryParam(queryString: String, key: String): String? {
        if (queryString.isEmpty()) return null
        val pairs = queryString.split("&")
        for (pair in pairs) {
            val parts = pair.split("=")
            if (parts.size == 2 && parts[0] == key) {
                return try {
                    URLDecoder.decode(parts[1], "UTF-8")
                } catch (_: Exception) {
                    parts[1]
                }
            }
        }
        return null
    }

    private fun parseFormUrl(body: String): String? {
        val pairs = body.split("&")
        for (pair in pairs) {
            val parts = pair.split("=")
            if (parts.size == 2 && parts[0] == "url") {
                return try {
                    URLDecoder.decode(parts[1], "UTF-8")
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    private fun sendResponse(socket: Socket, statusCode: Int, statusText: String, contentType: String, content: ByteArray) {
        val out: OutputStream = socket.getOutputStream()
        val headers = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${content.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(content)
        out.flush()
    }

    private fun md5(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(s.toByteArray(Charsets.UTF_8))
        return String.format("%032x", BigInteger(1, digest))
    }

    private fun getSlideshowHtml(prefs: SharedPreferences): String {
        val delaySec = (prefs.getLong(ConfigReceiver.KEY_DELAY_MS, ConfigReceiver.DEFAULT_DELAY_MS) / 1000).coerceAtLeast(3)
        val fadeMs = prefs.getLong(ConfigReceiver.KEY_FADE_MS, ConfigReceiver.DEFAULT_FADE_MS).coerceIn(400L, 3000L)
        val fadeSec = fadeMs / 1000f
        val transitionMode = prefs.getString(ConfigReceiver.KEY_TRANSITION, ConfigReceiver.DEFAULT_TRANSITION) ?: "slide"
        val kenBurns = prefs.getBoolean(ConfigReceiver.KEY_KEN_BURNS, ConfigReceiver.DEFAULT_KEN_BURNS)
        val zoomFill = prefs.getBoolean(ConfigReceiver.KEY_ZOOM_FILL, ConfigReceiver.DEFAULT_ZOOM_FILL)
        val showPairs = prefs.getBoolean(ConfigReceiver.KEY_PAIRS, ConfigReceiver.DEFAULT_PAIRS)
        val showClock = prefs.getBoolean(ConfigReceiver.KEY_CLOCK, ConfigReceiver.DEFAULT_CLOCK)
        val clock24h = prefs.getBoolean(ConfigReceiver.KEY_CLOCK_24H, ConfigReceiver.DEFAULT_CLOCK_24H)
        val showCaptions = prefs.getBoolean(ConfigReceiver.KEY_CAPTIONS, ConfigReceiver.DEFAULT_CAPTIONS)
        val weatherFahrenheit = prefs.getBoolean(ConfigReceiver.KEY_WEATHER_FAHRENHEIT, ConfigReceiver.DEFAULT_WEATHER_FAHRENHEIT)

        val clockDx = prefs.getFloat(ConfigReceiver.KEY_CLOCK_DX, ConfigReceiver.DEFAULT_CLOCK_DX)
        val clockDy = prefs.getFloat(ConfigReceiver.KEY_CLOCK_DY, ConfigReceiver.DEFAULT_CLOCK_DY)
        val clockScale = prefs.getFloat(ConfigReceiver.KEY_CLOCK_SCALE, ConfigReceiver.DEFAULT_CLOCK_SCALE)

        val dateDx = prefs.getFloat(ConfigReceiver.KEY_DATE_DX, ConfigReceiver.DEFAULT_DATE_DX)
        val dateDy = prefs.getFloat(ConfigReceiver.KEY_DATE_DY, ConfigReceiver.DEFAULT_DATE_DY)
        val dateScale = prefs.getFloat(ConfigReceiver.KEY_DATE_SCALE, ConfigReceiver.DEFAULT_DATE_SCALE)

        val clockOnlyDx = prefs.getFloat(ConfigReceiver.KEY_CLOCK_ONLY_DX, ConfigReceiver.DEFAULT_CLOCK_ONLY_DX)
        val clockOnlyDy = prefs.getFloat(ConfigReceiver.KEY_CLOCK_ONLY_DY, ConfigReceiver.DEFAULT_CLOCK_ONLY_DY)
        val clockOnlyScale = prefs.getFloat(ConfigReceiver.KEY_CLOCK_ONLY_SCALE, ConfigReceiver.DEFAULT_CLOCK_ONLY_SCALE)

        val nightClock = prefs.getBoolean(ConfigReceiver.KEY_NIGHT_CLOCK, ConfigReceiver.DEFAULT_NIGHT_CLOCK)
        val nightStartMin = prefs.getInt(ConfigReceiver.KEY_NIGHT_CLOCK_START_MIN, ConfigReceiver.DEFAULT_NIGHT_CLOCK_START_MIN)
        val nightEndMin = prefs.getInt(ConfigReceiver.KEY_NIGHT_CLOCK_END_MIN, ConfigReceiver.DEFAULT_NIGHT_CLOCK_END_MIN)
        val nightWarmth = prefs.getBoolean(ConfigReceiver.KEY_NIGHT, ConfigReceiver.DEFAULT_NIGHT)

        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>Portal Frame Slideshow</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; user-select: none; -webkit-user-select: none; }
    html, body {
      width: 100%; height: 100%; overflow: hidden;
      background-color: #000000;
      font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "Helvetica Neue", Inter, Arial, sans-serif;
      color: #FFFFFF;
    }
    #stage {
      position: relative; width: 100%; height: 100%; overflow: hidden; background: #000000;
    }
    
    .slide-layer {
      position: absolute; top: 0; left: 0; width: 100%; height: 100%;
      will-change: transform, opacity;
      display: flex; align-items: center; justify-content: center;
      opacity: 0; z-index: 1;
      ${if (transitionMode == "slide") {
            "transition: transform ${fadeSec}s cubic-bezier(0.22, 1, 0.36, 1), opacity ${fadeSec}s ease;"
        } else {
            "transition: opacity ${fadeSec}s ease-in-out, transform ${fadeSec}s ease-in-out;"
        }}
    }
    
    .slide-layer.active {
      opacity: 1; z-index: 2;
      transform: translate3d(0, 0, 0) scale(1);
    }
    
    .slide-layer.incoming {
      ${if (transitionMode == "slide") "transform: translate3d(100%, 0, 0);" else if (transitionMode == "fade_zoom") "transform: scale(1.06);" else "transform: translate3d(0, 0, 0);"}
      opacity: 0;
    }
    
    .slide-layer.outgoing {
      ${if (transitionMode == "slide") "transform: translate3d(-30%, 0, 0);" else if (transitionMode == "fade_zoom") "transform: scale(0.96);" else "transform: translate3d(0, 0, 0);"}
      opacity: 0; z-index: 1;
    }

    /* Blurred Background Fill Layer */
    .photo-container {
      position: relative; width: 100%; height: 100%; overflow: hidden;
      display: flex; align-items: center; justify-content: center;
    }
    
    .bg-blur {
      position: absolute; top: -10%; left: -10%; width: 120%; height: 120%;
      background-size: cover; background-position: center;
      filter: blur(44px) brightness(0.55) saturate(1.3);
      transform: scale(1.12);
      z-index: 1;
      ${if (kenBurns) "animation: kenBurnsBg 26s ease-in-out alternate infinite;" else ""}
    }
    
    .scrim {
      position: absolute; top: 0; left: 0; width: 100%; height: 100%;
      background: rgba(0, 0, 0, 0.28);
      z-index: 2;
    }
    
    .fg-photo {
      position: absolute; top: 0; left: 0; width: 100%; height: 100%;
      object-fit: ${if (zoomFill) "cover" else "contain"};
      z-index: 3;
      filter: drop-shadow(0 14px 40px rgba(0,0,0,0.65));
      ${if (kenBurns) "animation: kenBurnsFg 24s ease-in-out alternate infinite;" else ""}
    }
    
    /* Smart Dual-Photo Pairing */
    .photo-pair-container {
      display: flex; width: 100%; height: 100%; gap: 16px; padding: 20px;
      justify-content: center; align-items: center; z-index: 3;
      position: relative;
    }
    .photo-pair-item {
      flex: 1; height: 100%; object-fit: contain; border-radius: 14px;
      filter: drop-shadow(0 12px 36px rgba(0,0,0,0.6));
    }
    
    @keyframes kenBurnsFg {
      0% { transform: scale(1.0) translate(0, 0); }
      50% { transform: scale(1.06) translate(-1.2%, -1%); }
      100% { transform: scale(1.03) translate(1%, 1.2%); }
    }
    
    @keyframes kenBurnsBg {
      0% { transform: scale(1.12) translate(0, 0); }
      50% { transform: scale(1.18) translate(1.5%, 1%); }
      100% { transform: scale(1.14) translate(-1%, -1.2%); }
    }

    /* Ambient Overlay Widgets */
    #clock-widget {
      position: absolute; bottom: 32px; left: 36px; z-index: 10;
      display: ${if (showClock) "flex" else "none"}; flex-direction: column;
      text-shadow: 0 3px 20px rgba(0,0,0,0.85);
      transform: translate(${clockDx * 100}vw, ${clockDy * 100}vh) scale(${clockScale});
      transform-origin: bottom left;
      touch-action: none;
      cursor: grab;
    }
    
    #clock-time {
      font-size: 68px; font-weight: 300; letter-spacing: -1.5px; line-height: 1;
      font-variant-numeric: tabular-nums;
    }
    
    #clock-date {
      font-size: 20px; font-weight: 400; opacity: 0.92; margin-top: 6px; letter-spacing: 0.2px;
      transform: translate(${dateDx * 100}vw, ${dateDy * 100}vh) scale(${dateScale});
      transform-origin: bottom left;
    }

    #memory-badge {
      position: absolute; top: 32px; left: 36px; z-index: 10;
      display: ${if (showCaptions) "flex" else "none"}; align-items: center; gap: 8px;
      padding: 9px 18px; border-radius: 22px;
      background: rgba(0, 0, 0, 0.45); backdrop-filter: blur(24px); -webkit-backdrop-filter: blur(24px);
      border: 1px solid rgba(255, 255, 255, 0.16);
      font-size: 15px; font-weight: 500; letter-spacing: 0.3px;
      text-shadow: 0 1px 4px rgba(0,0,0,0.8);
      opacity: 0; transition: opacity 0.8s ease-in-out;
    }
    #memory-badge.visible { opacity: 1; }

    /* Scheduled Full-Screen Night Clock */
    #night-clock-view {
      position: absolute; top: 0; left: 0; width: 100%; height: 100%;
      background-color: #000000; z-index: 50;
      display: none; align-items: center; justify-content: center;
      text-align: center;
      opacity: 0; transition: opacity 1.2s ease-in-out;
    }
    #night-clock-view.active { display: flex; opacity: 1; }
    
    #night-clock-box {
      display: flex; flex-direction: column; align-items: center; justify-content: center;
      color: ${if (nightWarmth) "#FFB340" else "#FFFFFF"};
      text-shadow: 0 4px 30px ${if (nightWarmth) "rgba(255,179,64,0.35)" else "rgba(255,255,255,0.25)"};
      transform: translate(${clockOnlyDx * 100}vw, ${clockOnlyDy * 100}vh) scale(${clockOnlyScale});
      transform-origin: center center;
    }
    #night-clock-time {
      font-size: 130px; font-weight: 300; letter-spacing: -3px; line-height: 1;
      font-variant-numeric: tabular-nums;
    }
    #night-clock-date {
      font-size: 24px; font-weight: 400; opacity: 0.85; margin-top: 14px; letter-spacing: 0.3px;
    }
    
    #night-exit-btn {
      position: absolute; top: 32px; right: 36px; z-index: 60;
      background: rgba(255, 255, 255, 0.15); backdrop-filter: blur(20px); -webkit-backdrop-filter: blur(20px);
      border: 1px solid rgba(255, 255, 255, 0.25);
      color: #FFFFFF; font-size: 16px; font-weight: 500;
      padding: 10px 24px; border-radius: 20px; cursor: pointer;
      outline: none;
    }

    #empty-state {
      position: absolute; top: 0; left: 0; width: 100%; height: 100%;
      display: flex; flex-direction: column; align-items: center; justify-content: center;
      z-index: 5; text-align: center; padding: 24px;
    }
    #empty-state h1 { font-size: 28px; margin-bottom: 12px; font-weight: 600; }
    #empty-state p { font-size: 16px; opacity: 0.7; max-width: 440px; line-height: 1.5; }
    #empty-state a {
      margin-top: 20px; display: inline-block; padding: 12px 24px;
      background: #007AFF; color: #FFF; border-radius: 10px;
      text-decoration: none; font-weight: 600; font-size: 15px;
    }
  </style>
</head>
<body>
  <div id="stage">
    <div id="layer-a" class="slide-layer active"></div>
    <div id="layer-b" class="slide-layer incoming"></div>

    <div id="memory-badge">
      <span id="badge-icon">✨</span>
      <span id="badge-text">Moments</span>
    </div>

    <div id="clock-widget">
      <div id="clock-time">12:00</div>
      <div id="clock-date">Wednesday, September 2</div>
    </div>

    <!-- Full-Screen Night Clock -->
    <div id="night-clock-view">
      <div id="night-clock-box">
        <div id="night-clock-time">12:00 AM</div>
        <div id="night-clock-date">Wednesday, September 2</div>
      </div>
      <button id="night-exit-btn">Exit</button>
    </div>

    <div id="empty-state" style="display: none;">
      <h1>Portal Frame</h1>
      <p>No photos found in your shared albums yet. Add an album from your phone to start the slideshow.</p>
      <a href="/add" target="_blank">Add Album</a>
    </div>
  </div>

  <script>
    const CONFIG = {
      delaySec: $delaySec,
      fadeSec: $fadeSec,
      transitionMode: "$transitionMode",
      showPairs: $showPairs,
      zoomFill: $zoomFill,
      clock24h: $clock24h,
      showCaptions: $showCaptions,
      weatherFahrenheit: $weatherFahrenheit,
      nightClock: $nightClock,
      nightStartMin: $nightStartMin,
      nightEndMin: $nightEndMin
    };

    let slides = [];
    let currentIndex = 0;
    let currentLayer = 'a';
    let slideTimer = null;
    let nightExitUntilMs = 0;
    let weatherString = "";

    // Clock and Date updates
    function updateClock() {
      const now = new Date();
      let hours = now.getHours();
      const minutes = String(now.getMinutes()).padStart(2, '0');
      let timeStr = "";
      let nightTimeStr = "";
      if (CONFIG.clock24h) {
        timeStr = String(hours).padStart(2, '0') + ":" + minutes;
        nightTimeStr = timeStr;
      } else {
        const ampm = hours >= 12 ? ' PM' : ' AM';
        const displayHours = hours % 12 || 12;
        timeStr = displayHours + ":" + minutes;
        nightTimeStr = displayHours + ":" + minutes + " " + ampm;
      }
      document.getElementById('clock-time').textContent = timeStr;
      document.getElementById('night-clock-time').textContent = nightTimeStr;

      const options = { weekday: 'short', month: 'short', day: 'numeric' };
      const dateText = now.toLocaleDateString(undefined, options);
      const fullDateLine = weatherString ? (dateText + "   " + weatherString) : dateText;
      document.getElementById('clock-date').textContent = fullDateLine;
      document.getElementById('night-clock-date').textContent = fullDateLine;

      checkNightClock(now);
    }
    setInterval(updateClock, 1000);
    updateClock();

    function isMinuteInRange(minute, start, end) {
      if (start === end) return true;
      return start < end ? (minute >= start && minute < end) : (minute >= start || minute < end);
    }

    function checkNightClock(now) {
      if (!CONFIG.nightClock) return;
      const minute = now.getHours() * 60 + now.getMinutes();
      const inNightRange = isMinuteInRange(minute, CONFIG.nightStartMin, CONFIG.nightEndMin);
      const isExited = Date.now() < nightExitUntilMs;
      
      const nightView = document.getElementById('night-clock-view');
      const clockWidget = document.getElementById('clock-widget');
      const memoryBadge = document.getElementById('memory-badge');

      if (inNightRange && !isExited) {
        if (!nightView.classList.contains('active')) {
          nightView.classList.add('active');
          clockWidget.style.display = 'none';
          memoryBadge.style.display = 'none';
        }
      } else {
        if (nightView.classList.contains('active')) {
          nightView.classList.remove('active');
          clockWidget.style.display = CONFIG.showClock ? 'flex' : 'none';
          memoryBadge.style.display = CONFIG.showCaptions ? 'flex' : 'none';
        }
      }
    }

    document.getElementById('night-exit-btn').addEventListener('click', (e) => {
      e.stopPropagation();
      nightExitUntilMs = Date.now() + 5 * 60 * 1000; // 5 minute snooze
      document.getElementById('night-clock-view').classList.remove('active');
      document.getElementById('clock-widget').style.display = 'flex';
      nextSlide();
    });

    // Format Relative Memory Badges
    function formatRelativeDate(timeMs) {
      if (!timeMs || timeMs <= 0) return "";
      const now = Date.now();
      const diffDays = Math.round((now - timeMs) / (1000 * 60 * 60 * 24));
      if (diffDays <= 1) return "Today";
      if (diffDays === 2) return "Yesterday";
      if (diffDays < 7) return diffDays + " days ago";
      if (diffDays < 14) return "1 week ago";
      if (diffDays < 30) return Math.round(diffDays / 7) + " weeks ago";
      if (diffDays < 60) return "1 month ago";
      if (diffDays < 365) return Math.round(diffDays / 30) + " months ago";
      const years = Math.round(diffDays / 365);
      return years === 1 ? "1 year ago today ✨" : years + " years ago today ✨";
    }

    // Render Slide with Blurred Background Fill
    function showSlide(index) {
      if (!slides || slides.length === 0) return;
      const slide = slides[index % slides.length];
      const targetLayerId = currentLayer === 'a' ? 'layer-b' : 'layer-a';
      const prevLayerId = currentLayer === 'a' ? 'layer-a' : 'layer-b';

      const targetEl = document.getElementById(targetLayerId);
      const prevEl = document.getElementById(prevLayerId);

      targetEl.innerHTML = '';
      
      const photoUrl = slide.photoUrl || slide.id;
      
      const container = document.createElement('div');
      container.className = 'photo-container';

      if (!CONFIG.zoomFill) {
        const bgBlur = document.createElement('div');
        bgBlur.className = 'bg-blur';
        bgBlur.style.backgroundImage = 'url("' + photoUrl + '")';
        container.appendChild(bgBlur);

        const scrim = document.createElement('div');
        scrim.className = 'scrim';
        container.appendChild(scrim);
      }

      const fgPhoto = document.createElement('img');
      fgPhoto.className = 'fg-photo';
      fgPhoto.src = photoUrl;
      container.appendChild(fgPhoto);

      targetEl.appendChild(container);

      targetEl.className = 'slide-layer incoming';
      // Force reflow for smooth animation
      void targetEl.offsetWidth;

      targetEl.className = 'slide-layer active';
      prevEl.className = 'slide-layer outgoing';
      currentLayer = currentLayer === 'a' ? 'b' : 'a';

      // Update Memory Timeline Badge
      const badge = document.getElementById('memory-badge');
      const badgeText = document.getElementById('badge-text');
      const relTime = formatRelativeDate(slide.timeMs);
      const caption = slide.caption || relTime;
      if (CONFIG.showCaptions && caption) {
        badgeText.textContent = caption;
        badge.className = 'visible';
      } else {
        badge.className = '';
      }

      // Preload next 2 images
      const next1 = (index + 1) % slides.length;
      const next2 = (index + 2) % slides.length;
      if (slides[next1]) { new Image().src = slides[next1].photoUrl || slides[next1].id; }
      if (slides[next2]) { new Image().src = slides[next2].photoUrl || slides[next2].id; }
    }

    function nextSlide() {
      if (slides.length === 0) return;
      currentIndex = (currentIndex + 1) % slides.length;
      showSlide(currentIndex);
    }

    async function fetchSlides() {
      try {
        const res = await fetch('/api/slides');
        if (res.ok) {
          const data = await res.json();
          if (Array.isArray(data) && data.length > 0) {
            slides = data;
            document.getElementById('empty-state').style.display = 'none';
            if (!slideTimer) {
              showSlide(0);
              slideTimer = setInterval(nextSlide, CONFIG.delaySec * 1000);
            }
          } else {
            if (slides.length === 0) {
              document.getElementById('empty-state').style.display = 'flex';
            }
          }
        }
      } catch (e) {
        console.error("Failed to fetch slides", e);
      }
    }

    fetchSlides();
    setInterval(fetchSlides, 60000);

    // Interactive Drag & Pinch Clock Resizing
    (function enableClockGestures() {
      const widget = document.getElementById('clock-widget');
      let isDragging = false;
      let startX = 0, startY = 0;
      let curDx = ${clockDx}, curDy = ${clockDy}, curScale = ${clockScale};
      let initialDist = 0;

      widget.addEventListener('touchstart', (e) => {
        if (e.touches.length === 1) {
          isDragging = true;
          startX = e.touches[0].clientX - (curDx * window.innerWidth);
          startY = e.touches[0].clientY - (curDy * window.innerHeight);
        } else if (e.touches.length === 2) {
          isDragging = false;
          initialDist = Math.hypot(
            e.touches[0].clientX - e.touches[1].clientX,
            e.touches[0].clientY - e.touches[1].clientY
          );
        }
      });

      widget.addEventListener('touchmove', (e) => {
        if (isDragging && e.touches.length === 1) {
          curDx = (e.touches[0].clientX - startX) / window.innerWidth;
          curDy = (e.touches[0].clientY - startY) / window.innerHeight;
          widget.style.transform = 'translate(' + (curDx * 100) + 'vw, ' + (curDy * 100) + 'vh) scale(' + curScale + ')';
        } else if (e.touches.length === 2 && initialDist > 0) {
          const newDist = Math.hypot(
            e.touches[0].clientX - e.touches[1].clientX,
            e.touches[0].clientY - e.touches[1].clientY
          );
          curScale = Math.max(0.6, Math.min(2.5, curScale * (newDist / initialDist)));
          initialDist = newDist;
          widget.style.transform = 'translate(' + (curDx * 100) + 'vw, ' + (curDy * 100) + 'vh) scale(' + curScale + ')';
        }
      });

      widget.addEventListener('touchend', () => {
        isDragging = false;
        fetch('/api/config', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ clock_dx: curDx, clock_dy: curDy, clock_scale: curScale })
        }).catch(() => {});
      });
    })();

    // Tap anywhere to advance slide
    document.body.addEventListener('click', (e) => {
      if (e.target.tagName === 'A' || e.target.id === 'night-exit-btn' || e.target.closest('#clock-widget')) return;
      nextSlide();
      clearInterval(slideTimer);
      slideTimer = setInterval(nextSlide, CONFIG.delaySec * 1000);
    });
  </script>
</body>
</html>
        """.trimIndent()
    }

    private fun parseFormParam(body: String, key: String): String? {
        val pairs = body.split("&")
        for (pair in pairs) {
            val parts = pair.split("=")
            if (parts.size == 2 && parts[0] == key) {
                return try {
                    URLDecoder.decode(parts[1], "UTF-8")
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    private fun parseJsonMessage(body: String): String? {
        return try {
            val json = JSONObject(body)
            if (json.has("message")) json.getString("message") else null
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeHtml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }

    private fun getAddAlbumHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
              <title>Add Album to Frame</title>
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "Segoe UI", Roboto, sans-serif;
                  background-color: #0E0E10;
                  color: #FFFFFF;
                  padding: 24px;
                  display: flex;
                  flex-direction: column;
                  align-items: center;
                  justify-content: center;
                  min-height: 100vh;
                }
                .card {
                  background-color: #1C1C1E;
                  border-radius: 20px;
                  padding: 32px 24px;
                  width: 100%;
                  max-width: 440px;
                  box-shadow: 0 16px 36px rgba(0,0,0,0.5);
                  border: 1px solid rgba(255,255,255,0.1);
                }
                h1 {
                  font-size: 22px;
                  margin-bottom: 8px;
                  font-weight: 700;
                  text-align: center;
                }
                p {
                  font-size: 14px;
                  color: #8E8E93;
                  margin-bottom: 24px;
                  text-align: center;
                  line-height: 1.5;
                }
                label {
                  display: block;
                  font-size: 13px;
                  color: #A1A1A6;
                  margin-bottom: 8px;
                  font-weight: 600;
                }
                input[type="text"] {
                  width: 100%;
                  padding: 16px;
                  border: 1px solid #38383A;
                  background-color: #2C2C2E;
                  color: #FFFFFF;
                  border-radius: 12px;
                  font-size: 15px;
                  margin-bottom: 12px;
                  outline: none;
                  transition: border-color 0.2s;
                }
                input[type="text"]:focus {
                  border-color: #007AFF;
                }
                .btn-row {
                  display: flex;
                  gap: 10px;
                  margin-bottom: 16px;
                }
                .btn-secondary {
                  flex: 1;
                  padding: 12px;
                  background-color: #2C2C2E;
                  color: #FFFFFF;
                  border: 1px solid #38383A;
                  border-radius: 10px;
                  font-size: 14px;
                  font-weight: 600;
                  cursor: pointer;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  gap: 6px;
                }
                .btn-primary {
                  width: 100%;
                  padding: 16px;
                  background-color: #007AFF;
                  color: #FFFFFF;
                  border: none;
                  border-radius: 12px;
                  font-size: 16px;
                  font-weight: 600;
                  cursor: pointer;
                  box-shadow: 0 4px 14px rgba(0,122,255,0.4);
                }
                .footer {
                  margin-top: 24px;
                  font-size: 12px;
                  color: #636366;
                  text-align: center;
                }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>Add Shared Album</h1>
                <p>Paste the shared album link from Google Photos or iCloud to display it on your Portal.</p>
                <form method="POST" action="/add">
                  <label for="url">Album Link</label>
                  <input type="text" id="url" name="url" placeholder="https://photos.app.goo.gl/..." required autocomplete="off" autofocus>
                  <div class="btn-row">
                    <button type="button" class="btn-secondary" onclick="pasteClipboard()">📋 Paste</button>
                    <button type="button" class="btn-secondary" onclick="clearField()">✕ Clear</button>
                  </div>
                  <button type="submit" class="btn-primary">Add to Frame</button>
                </form>
              </div>
              <div class="footer">Meta Portal Frame</div>

              <script>
                async function pasteClipboard() {
                  try {
                    const text = await navigator.clipboard.readText();
                    if (text) { document.getElementById('url').value = text; }
                  } catch (_) {}
                }
                function clearField() {
                  document.getElementById('url').value = '';
                  document.getElementById('url').focus();
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getMessageHtml(currentMsg: String): String {
        val safeCurrent = sanitizeHtml(currentMsg)
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
              <title>Post Announcement to Frame</title>
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "Segoe UI", Roboto, sans-serif;
                  background-color: #0E0E10;
                  color: #FFFFFF;
                  padding: 24px;
                  display: flex;
                  flex-direction: column;
                  align-items: center;
                  justify-content: center;
                  min-height: 100vh;
                }
                .card {
                  background-color: #1C1C1E;
                  border-radius: 20px;
                  padding: 32px 24px;
                  width: 100%;
                  max-width: 440px;
                  box-shadow: 0 16px 36px rgba(0,0,0,0.5);
                  border: 1px solid rgba(255,255,255,0.1);
                }
                h1 {
                  font-size: 22px;
                  margin-bottom: 8px;
                  font-weight: 700;
                  text-align: center;
                }
                p {
                  font-size: 14px;
                  color: #8E8E93;
                  margin-bottom: 20px;
                  text-align: center;
                  line-height: 1.5;
                }
                .active-box {
                  background: rgba(0, 122, 255, 0.15);
                  border: 1px solid rgba(0, 122, 255, 0.35);
                  border-radius: 12px;
                  padding: 12px 16px;
                  margin-bottom: 20px;
                  font-size: 14px;
                  color: #64D2FF;
                  text-align: center;
                }
                label {
                  display: block;
                  font-size: 13px;
                  color: #A1A1A6;
                  margin-bottom: 8px;
                  font-weight: 600;
                }
                textarea {
                  width: 100%;
                  padding: 14px;
                  border: 1px solid #38383A;
                  background-color: #2C2C2E;
                  color: #FFFFFF;
                  border-radius: 12px;
                  font-size: 15px;
                  min-height: 90px;
                  margin-bottom: 12px;
                  outline: none;
                  resize: none;
                  font-family: inherit;
                }
                textarea:focus {
                  border-color: #007AFF;
                }
                .emoji-bar {
                  display: flex;
                  gap: 8px;
                  margin-bottom: 16px;
                  overflow-x: auto;
                  padding-bottom: 4px;
                }
                .emoji-btn {
                  padding: 6px 12px;
                  background: #2C2C2E;
                  border: 1px solid #38383A;
                  border-radius: 20px;
                  font-size: 16px;
                  cursor: pointer;
                }
                .btn-row {
                  display: flex;
                  gap: 10px;
                  margin-top: 6px;
                }
                .btn-primary {
                  flex: 2;
                  padding: 16px;
                  background-color: #007AFF;
                  color: #FFFFFF;
                  border: none;
                  border-radius: 12px;
                  font-size: 16px;
                  font-weight: 600;
                  cursor: pointer;
                  box-shadow: 0 4px 14px rgba(0,122,255,0.4);
                }
                .btn-clear {
                  flex: 1;
                  padding: 16px;
                  background-color: #FF3B30;
                  color: #FFFFFF;
                  border: none;
                  border-radius: 12px;
                  font-size: 15px;
                  font-weight: 600;
                  cursor: pointer;
                }
                .footer {
                  margin-top: 24px;
                  font-size: 12px;
                  color: #636366;
                  text-align: center;
                }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>Post Announcement</h1>
                <p>Display a custom banner on the Portal Frame (e.g. birthdays, welcome notes, or celebrations).</p>
                ${if (safeCurrent.isNotEmpty()) "<div class=\"active-box\"><strong>Currently active:</strong><br>$safeCurrent</div>" else ""}
                <form method="POST" action="/message">
                  <label for="message">Banner Message</label>
                  <textarea id="message" name="message" placeholder="🎉 Happy 30th Birthday Sarah! 🎂" required autofocus></textarea>
                  <div class="emoji-bar">
                    <button type="button" class="emoji-btn" onclick="addEmoji('🎉')">🎉</button>
                    <button type="button" class="emoji-btn" onclick="addEmoji('🎂')">🎂</button>
                    <button type="button" class="emoji-btn" onclick="addEmoji('❤️')">❤️</button>
                    <button type="button" class="emoji-btn" onclick="addEmoji('✨')">✨</button>
                    <button type="button" class="emoji-btn" onclick="addEmoji('🏡')">🏡</button>
                    <button type="button" class="emoji-btn" onclick="addEmoji('⭐')">⭐</button>
                  </div>
                  <div class="btn-row">
                    <button type="submit" class="btn-primary">Post Banner</button>
                    <button type="button" class="btn-clear" onclick="clearMessage()">✕ Clear</button>
                  </div>
                </form>
              </div>
              <div class="footer">Meta Portal Frame</div>

              <script>
                function addEmoji(e) {
                  const ta = document.getElementById('message');
                  ta.value += e;
                  ta.focus();
                }
                async function clearMessage() {
                  try {
                    await fetch('/api/message/clear', { method: 'POST' });
                    window.location.reload();
                  } catch (_) {}
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getMessageSuccessHtml(msg: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Message Posted</title>
              <style>
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  background-color: #0E0E10;
                  color: #E0E0E0;
                  padding: 24px;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  min-height: 100vh;
                  box-sizing: border-box;
                }
                .card {
                  background-color: #1C1C1E;
                  border-radius: 20px;
                  padding: 40px 24px;
                  width: 100%;
                  max-width: 400px;
                  box-shadow: 0 16px 36px rgba(0,0,0,0.5);
                  border: 1px solid rgba(255,255,255,0.1);
                  text-align: center;
                }
                .icon { font-size: 48px; color: #34C759; margin-bottom: 16px; }
                h1 { font-size: 22px; margin-bottom: 8px; color: #FFFFFF; font-weight: 700; }
                p { font-size: 15px; color: #8E8E93; line-height: 1.5; margin-bottom: 20px; }
                a { display: inline-block; padding: 12px 24px; background: #007AFF; color: #FFF; border-radius: 10px; text-decoration: none; font-weight: 600; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="icon">✓</div>
                <h1>Banner Posted!</h1>
                <p>Your message is now floating live on the Portal Frame:</p>
                <p style="color: #FFFFFF; font-weight: 600;">"$msg"</p>
                <a href="/message">Back</a>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getSuccessHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Success</title>
              <style>
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  background-color: #121212;
                  color: #E0E0E0;
                  margin: 0;
                  padding: 24px;
                  display: flex;
                  flex-direction: column;
                  align-items: center;
                  justify-content: center;
                  min-height: 100vh;
                  box-sizing: border-box;
                }
                .card {
                  background-color: #1E1E1E;
                  border-radius: 16px;
                  padding: 40px 24px;
                  width: 100%;
                  max-width: 400px;
                  box-shadow: 0 8px 24px rgba(0,0,0,0.4);
                  box-sizing: border-box;
                  border: 1px solid #2C2C2C;
                  text-align: center;
                }
                .icon {
                  font-size: 48px;
                  color: #4CAF50;
                  margin-bottom: 16px;
                }
                h1 {
                  font-size: 22px;
                  margin: 0 0 8px 0;
                  color: #FFFFFF;
                  font-weight: 600;
                }
                p {
                  font-size: 14px;
                  color: #A0A0A0;
                  margin: 0;
                  line-height: 1.5;
                }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="icon">✓</div>
                <h1>Album Added Successfully!</h1>
                <p>Your Portal has been updated and will start displaying this album shortly. You can now close this tab.</p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getErrorHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Invalid Link</title>
              <style>
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  background-color: #121212;
                  color: #E0E0E0;
                  margin: 0;
                  padding: 24px;
                  display: flex;
                  flex-direction: column;
                  align-items: center;
                  justify-content: center;
                  min-height: 100vh;
                  box-sizing: border-box;
                }
                .card {
                  background-color: #1E1E1E;
                  border-radius: 16px;
                  padding: 40px 24px;
                  width: 100%;
                  max-width: 400px;
                  box-shadow: 0 8px 24px rgba(0,0,0,0.4);
                  box-sizing: border-box;
                  border: 1px solid #2C2C2C;
                  text-align: center;
                }
                .icon {
                  font-size: 48px;
                  color: #F44336;
                  margin-bottom: 16px;
                }
                h1 {
                  font-size: 22px;
                  margin: 0 0 8px 0;
                  color: #FFFFFF;
                  font-weight: 600;
                }
                p {
                  font-size: 14px;
                  color: #A0A0A0;
                  margin: 0 0 20px 0;
                  line-height: 1.5;
                }
                a {
                  display: inline-block;
                  padding: 10px 20px;
                  background-color: #333;
                  color: #fff;
                  text-decoration: none;
                  border-radius: 8px;
                  font-weight: 600;
                  font-size: 14px;
                }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="icon">✗</div>
                <h1>Invalid Link</h1>
                <p>That link doesn't look like a valid Google Photos or iCloud shared album link. Please check the URL and try again.</p>
                <a href="/">Go Back</a>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val TAG = "PortalFrameServer"

        @Volatile
        private var instance: AlbumServer? = null

        @JvmStatic
        @Synchronized
        fun startServer(context: Context, port: Int = DEFAULT_PORT): AlbumServer {
            val existing = instance
            if (existing != null && existing.isRunning) {
                return existing
            }
            val server = AlbumServer(context.applicationContext, port)
            server.start()
            instance = server
            return server
        }

        @JvmStatic
        @Synchronized
        fun stopServer() {
            instance?.stop()
            instance = null
        }

        @JvmStatic
        fun get(): AlbumServer? = instance
    }
}
