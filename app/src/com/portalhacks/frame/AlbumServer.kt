package com.portalhacks.frame

import android.content.Context
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

                // Server status & diagnostics
                method == "GET" && rawPath == "/api/status" -> {
                    val enabledAlbums = Albums.enabled(prefs)
                    val status = JSONObject()
                        .put("status", "ok")
                        .put("version", "1.5.23")
                        .put("port", port)
                        .put("haBridgeMode", prefs.getBoolean(ConfigReceiver.KEY_HA_BRIDGE_MODE, ConfigReceiver.DEFAULT_HA_BRIDGE_MODE))
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
                    val body = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(body, read, contentLength - read)
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
        val kenBurns = prefs.getBoolean(ConfigReceiver.KEY_KEN_BURNS, ConfigReceiver.DEFAULT_KEN_BURNS)
        val showPairs = prefs.getBoolean(ConfigReceiver.KEY_PAIRS, ConfigReceiver.DEFAULT_PAIRS)
        val showClock = prefs.getBoolean(ConfigReceiver.KEY_CLOCK, ConfigReceiver.DEFAULT_CLOCK)
        val clock24h = prefs.getBoolean(ConfigReceiver.KEY_CLOCK_24H, ConfigReceiver.DEFAULT_CLOCK_24H)
        val showCaptions = prefs.getBoolean(ConfigReceiver.KEY_CAPTIONS, ConfigReceiver.DEFAULT_CAPTIONS)

        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>Portal Frame Slideshow</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; user-select: none; }
    html, body {
      width: 100%; height: 100%; overflow: hidden;
      background-color: #000000;
      font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "Helvetica Neue", Inter, Arial, sans-serif;
      color: #FFFFFF;
    }
    #stage {
      position: relative; width: 100%; height: 100%; overflow: hidden;
    }
    .slide-layer {
      position: absolute; top: 0; left: 0; width: 100%; height: 100%;
      opacity: 0; transition: opacity 1.2s ease-in-out;
      display: flex; align-items: center; justify-content: center;
      background-size: cover; background-position: center;
    }
    .slide-layer.active { opacity: 1; z-index: 2; }
    .slide-layer.previous { opacity: 0; z-index: 1; }
    
    .photo-single {
      width: 100%; height: 100%; object-fit: contain;
      ${if (kenBurns) "animation: kenBurns 20s ease-in-out alternate infinite;" else ""}
    }
    .photo-pair-container {
      display: flex; width: 100%; height: 100%; gap: 16px; padding: 16px;
      justify-content: center; align-items: center;
    }
    .photo-pair-item {
      flex: 1; height: 100%; object-fit: contain; border-radius: 12px;
    }
    
    @keyframes kenBurns {
      0% { transform: scale(1.0) translate(0, 0); }
      50% { transform: scale(1.06) translate(-1%, -1%); }
      100% { transform: scale(1.03) translate(1%, 1%); }
    }

    /* Ambient Overlay Widgets */
    #clock-widget {
      position: absolute; bottom: 32px; left: 36px; z-index: 10;
      display: ${if (showClock) "flex" else "none"}; flex-direction: column;
      text-shadow: 0 2px 16px rgba(0,0,0,0.85);
    }
    #clock-time {
      font-size: 64px; font-weight: 300; letter-spacing: -1px; line-height: 1;
    }
    #clock-date {
      font-size: 19px; font-weight: 400; opacity: 0.9; margin-top: 6px; letter-spacing: 0.2px;
    }

    #memory-badge {
      position: absolute; top: 32px; left: 36px; z-index: 10;
      display: ${if (showCaptions) "flex" else "none"}; align-items: center; gap: 8px;
      padding: 8px 16px; border-radius: 20px;
      background: rgba(0, 0, 0, 0.45); backdrop-filter: blur(20px);
      border: 1px solid rgba(255, 255, 255, 0.15);
      font-size: 15px; font-weight: 500; letter-spacing: 0.3px;
      text-shadow: 0 1px 4px rgba(0,0,0,0.8);
      opacity: 0; transition: opacity 0.8s ease-in-out;
    }
    #memory-badge.visible { opacity: 1; }

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
    <div id="layer-b" class="slide-layer"></div>

    <div id="memory-badge">
      <span id="badge-icon">✨</span>
      <span id="badge-text">Moments</span>
    </div>

    <div id="clock-widget">
      <div id="clock-time">12:00</div>
      <div id="clock-date">Wednesday, September 2</div>
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
      showPairs: $showPairs,
      clock24h: $clock24h,
      showCaptions: $showCaptions
    };

    let slides = [];
    let currentIndex = 0;
    let currentLayer = 'a';
    let slideTimer = null;

    function updateClock() {
      const now = new Date();
      let hours = now.getHours();
      const minutes = String(now.getMinutes()).padStart(2, '0');
      let timeStr = "";
      if (CONFIG.clock24h) {
        timeStr = String(hours).padStart(2, '0') + ":" + minutes;
      } else {
        const ampm = hours >= 12 ? ' PM' : ' AM';
        hours = hours % 12 || 12;
        timeStr = hours + ":" + minutes;
      }
      document.getElementById('clock-time').textContent = timeStr;

      const options = { weekday: 'long', month: 'long', day: 'numeric' };
      document.getElementById('clock-date').textContent = now.toLocaleDateString(undefined, options);
    }
    setInterval(updateClock, 1000);
    updateClock();

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

    function showSlide(index) {
      if (!slides || slides.length === 0) return;
      const slide = slides[index % slides.length];
      const targetLayerId = currentLayer === 'a' ? 'layer-b' : 'layer-a';
      const prevLayerId = currentLayer === 'a' ? 'layer-a' : 'layer-b';

      const targetEl = document.getElementById(targetLayerId);
      const prevEl = document.getElementById(prevLayerId);

      targetEl.innerHTML = '';
      const img = document.createElement('img');
      img.className = 'photo-single';
      img.src = slide.photoUrl || slide.id;
      targetEl.appendChild(img);

      targetEl.className = 'slide-layer active';
      prevEl.className = 'slide-layer previous';
      currentLayer = currentLayer === 'a' ? 'b' : 'a';

      // Update badge
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

      // Preload next image
      const nextIndex = (index + 1) % slides.length;
      if (slides[nextIndex]) {
        const pre = new Image();
        pre.src = slides[nextIndex].photoUrl || slides[nextIndex].id;
      }
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
    setInterval(fetchSlides, 60000); // Check for updated albums every minute

    // Tap to advance slide
    document.body.addEventListener('click', (e) => {
      if (e.target.tagName === 'A') return;
      nextSlide();
      clearInterval(slideTimer);
      slideTimer = setInterval(nextSlide, CONFIG.delaySec * 1000);
    });
  </script>
</body>
</html>
        """.trimIndent()
    }

    private fun getAddAlbumHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Add Album to Frame</title>
              <style>
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
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
                  padding: 32px 24px;
                  width: 100%;
                  max-width: 400px;
                  box-shadow: 0 8px 24px rgba(0,0,0,0.4);
                  box-sizing: border-box;
                  border: 1px solid #2C2C2C;
                }
                h1 {
                  font-size: 22px;
                  margin: 0 0 8px 0;
                  color: #FFFFFF;
                  font-weight: 600;
                  text-align: center;
                }
                p {
                  font-size: 14px;
                  color: #A0A0A0;
                  margin: 0 0 24px 0;
                  text-align: center;
                  line-height: 1.5;
                }
                label {
                  display: block;
                  font-size: 13px;
                  color: #888888;
                  margin-bottom: 8px;
                  font-weight: 500;
                }
                input[type="text"] {
                  width: 100%;
                  padding: 14px;
                  border: 1px solid #333333;
                  background-color: #151515;
                  color: #FFFFFF;
                  border-radius: 10px;
                  font-size: 15px;
                  box-sizing: border-box;
                  margin-bottom: 20px;
                  outline: none;
                  transition: border-color 0.2s;
                }
                input[type="text"]:focus {
                  border-color: #0078FF;
                }
                button {
                  width: 100%;
                  padding: 14px;
                  background-color: #0078FF;
                  color: #FFFFFF;
                  border: none;
                  border-radius: 10px;
                  font-size: 16px;
                  font-weight: 600;
                  cursor: pointer;
                  transition: background-color 0.2s;
                }
                button:hover {
                  background-color: #0066D6;
                }
                .footer {
                  margin-top: 24px;
                  font-size: 12px;
                  color: #666666;
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
                  <button type="submit">Add to Frame</button>
                </form>
              </div>
              <div class="footer">Portal Frame Screensaver</div>
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
