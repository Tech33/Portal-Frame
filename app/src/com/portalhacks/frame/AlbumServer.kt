package com.portalhacks.frame

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A lightweight ServerSocket-based HTTP server that runs temporarily while the Add Album screen
 * is active, allowing users to scan a QR code and paste the album URL from their phone.
 * Pure Java/Android socket API; no dependency on sun.net package at compile time.
 */
class AlbumServer(private val port: Int, private val onUrlReceived: (String) -> Boolean) {

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false

    fun start() {
        isRunning = true
        thread(name = "AlbumServer") {
            try {
                serverSocket = ServerSocket(port)
                Log.i("PortalFrame", "Local server started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    thread {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("PortalFrame", "Server socket error", e)
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
        Log.i("PortalFrame", "Local server stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val line = reader.readLine() ?: return
            
            // Expected line format: "GET / HTTP/1.1" or "POST /add HTTP/1.1"
            val parts = line.split(" ")
            if (parts.size < 2) return
            
            val method = parts[0]
            val path = parts[1]

            // Read request headers
            var contentLength = 0
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isEmpty()) break // end of headers
                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = headerLine.substring(15).trim().toIntOrNull() ?: 0
                }
            }

            if (method.equals("POST", ignoreCase = true) && path == "/add") {
                val body = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(body, read, contentLength - read)
                    if (n == -1) break
                    read += n
                }
                val bodyStr = String(body)
                val url = parseFormUrl(bodyStr)
                if (url != null && onUrlReceived(url)) {
                    sendHtmlResponse(socket, 200, "Success", getSuccessHtml())
                } else {
                    sendHtmlResponse(socket, 400, "Bad Request", getErrorHtml())
                }
            } else if (method.equals("GET", ignoreCase = true) && path == "/") {
                sendHtmlResponse(socket, 200, "OK", getRootHtml())
            } else {
                sendResponse(socket, 404, "Not Found", "text/plain", "Not Found".toByteArray())
            }
        } catch (e: Exception) {
            Log.e("PortalFrame", "Error handling client socket", e)
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun parseFormUrl(body: String): String? {
        val pairs = body.split("&")
        for (pair in pairs) {
            val parts = pair.split("=")
            if (parts.size == 2 && parts[0] == "url") {
                return try {
                    URLDecoder.decode(parts[1], "UTF-8")
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
    }

    private fun sendHtmlResponse(socket: Socket, statusCode: Int, statusText: String, html: String) {
        sendResponse(socket, statusCode, statusText, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
    }

    private fun sendResponse(socket: Socket, statusCode: Int, statusText: String, contentType: String, content: ByteArray) {
        val out: OutputStream = socket.getOutputStream()
        val headers = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${content.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(content)
        out.flush()
    }

    private fun getRootHtml(): String {
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
}
