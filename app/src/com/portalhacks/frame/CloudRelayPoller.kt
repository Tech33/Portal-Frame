package com.portalhacks.frame

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 24/7 Background Cloud Relay Poller & Command Dispatcher.
 *
 * Ensures commands sent via message.html or the cloud relay (new family albums, announcement
 * notes, OTA updates, rename commands, companion app installs) are continuously fetched,
 * decrypted, processed, and cached to disk even when the SlideshowComposeActivity is closed
 * (e.g. user is on Aloha launcher or another app is running).
 */
object CloudRelayPoller {
    private const val TAG = "PortalFrame"
    private const val THROTTLE_MS = 10_000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cloud-relay-poller").apply { priority = Thread.MIN_PRIORITY }
    }
    private val isPolling = AtomicBoolean(false)
    private var lastPollElapsedMs = 0L

    /**
     * Poll the cloud relay if throttle has passed or if [force] is true.
     * Safe to call from any thread or service at any frequency.
     */
    fun poll(context: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPollElapsedMs < THROTTLE_MS) {
            return
        }
        if (!isPolling.compareAndSet(false, true)) {
            return
        }

        executor.execute {
            try {
                lastPollElapsedMs = SystemClock.elapsedRealtime()
                val prefs = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
                val channel = prefs.getString(
                    ConfigReceiver.KEY_ANNOUNCEMENT_CHANNEL,
                    ConfigReceiver.DEFAULT_ANNOUNCEMENT_CHANNEL
                )?.trim() ?: ConfigReceiver.DEFAULT_ANNOUNCEMENT_CHANNEL
                if (channel.isEmpty()) return@execute

                val url = "https://keyvalue.immanuel.co/api/KeyVal/GetValue/cs79vqdm/${URLEncoder.encode(channel, "UTF-8")}"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000

                var resp = ""
                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    resp = reader.readLine()?.trim() ?: ""
                    reader.close()
                    if (resp.startsWith("\"") && resp.endsWith("\"")) {
                        resp = resp.substring(1, resp.length - 1)
                    }
                }
                conn.disconnect()

                if (resp.isNotEmpty() && resp != "null") {
                    val lastSeenRemote = prefs.getString("last_remote_announcement_seen", "") ?: ""
                    if (resp != lastSeenRemote) {
                        val aesKey = CryptoUtils.deriveAesKey(channel)
                        val decrypted = CryptoUtils.decrypt(resp, aesKey).trim()
                        if (decrypted.isNotEmpty()) {
                            prefs.edit().putString("last_remote_announcement_seen", resp).apply()
                            Log.i(TAG, "CloudRelayPoller: received new remote broadcast")
                            dispatchCommand(context.applicationContext, decrypted)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "CloudRelayPoller: fetch error (${e.message})")
            } finally {
                isPolling.set(false)
            }
        }
    }

    /**
     * Parse and dispatch remote commands. Updates SharedPreferences, pre-caches album photos,
     * triggers background updates, and notifies any active UI components.
     */
    fun dispatchCommand(context: Context, rawMsg: String) {
        val trimmed = rawMsg.trim()
        if (trimmed.isEmpty()) return
        val prefs = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)

        // 1. Clear Announcement Note
        if (trimmed == "__CLEAR__") {
            prefs.edit().remove(ConfigReceiver.KEY_CUSTOM_MESSAGE).apply()
            context.sendBroadcast(
                Intent(ConfigReceiver.ACTION_CLEAR_MESSAGE)
                    .setPackage(context.packageName)
            )
            Log.i(TAG, "CloudRelayPoller: cleared custom message")
            return
        }

        // 2. Remote OTA Update: #update or #ota
        if (trimmed.equals("#update", ignoreCase = true) || trimmed.equals("#ota", ignoreCase = true) ||
            trimmed.contains("#update") || trimmed.contains("#ota")) {
            Log.i(TAG, "CloudRelayPoller: triggering background auto-update")
            AutoUpdateWorker.triggerNow(context)
            context.sendBroadcast(
                Intent(ConfigReceiver.ACTION_SET_MESSAGE)
                    .setPackage(context.packageName)
                    .putExtra("message", trimmed)
                    .putExtra("from_poller", true)
            )
            return
        }

        // 3. Remote Spotify Install: #install:spotify or #spotify
        if (trimmed.contains("#install:spotify", ignoreCase = true) || trimmed.equals("#spotify", ignoreCase = true) || trimmed.startsWith("#spotify:", ignoreCase = true)) {
            val parts = trimmed.split(":")
            val targetId = if (parts.size >= 3) parts[2].trim() else if (parts.size == 2 && !parts[1].equals("spotify", true)) parts[1].trim() else "all"
            if (matchesDevice(context, targetId)) {
                Log.i(TAG, "CloudRelayPoller: initiating background Spotify install for target: $targetId")
                CompanionAppInstaller.installSpotify(context)
                context.sendBroadcast(
                    Intent(ConfigReceiver.ACTION_SET_MESSAGE)
                        .setPackage(context.packageName)
                        .putExtra("message", trimmed)
                        .putExtra("from_poller", true)
                )
            }
            return
        }

        // 4. Remote Apple Music Install: #install:applemusic or #applemusic
        if (trimmed.contains("#install:applemusic", ignoreCase = true) || trimmed.equals("#applemusic", ignoreCase = true) || trimmed.startsWith("#applemusic:", ignoreCase = true)) {
            val parts = trimmed.split(":")
            val targetId = if (parts.size >= 3) parts[2].trim() else if (parts.size == 2 && !parts[1].equals("applemusic", true)) parts[1].trim() else "all"
            if (matchesDevice(context, targetId)) {
                Log.i(TAG, "CloudRelayPoller: initiating background Apple Music install for target: $targetId")
                CompanionAppInstaller.installAppleMusic(context)
                context.sendBroadcast(
                    Intent(ConfigReceiver.ACTION_SET_MESSAGE)
                        .setPackage(context.packageName)
                        .putExtra("message", trimmed)
                        .putExtra("from_poller", true)
                )
            }
            return
        }

        // 5. Remote Family Channel Switch: #channel:<name>, #setchannel:<name>, or #family:<name>
        val channelMatch = Regex("""#(?:channel|setchannel|family):([A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        if (channelMatch != null) {
            val rawName = channelMatch.groupValues[1].trim()
            val clean = rawName.lowercase(Locale.US).replace(" ", "_")
            val finalChannel = if (clean.isEmpty()) ConfigReceiver.DEFAULT_ANNOUNCEMENT_CHANNEL else clean
            prefs.edit().putString(ConfigReceiver.KEY_ANNOUNCEMENT_CHANNEL, finalChannel).apply()
            Log.i(TAG, "CloudRelayPoller: family channel switched to: $finalChannel")
            context.sendBroadcast(
                Intent(ConfigReceiver.ACTION_SET_MESSAGE)
                    .setPackage(context.packageName)
                    .putExtra("message", trimmed)
                    .putExtra("from_poller", true)
            )
            return
        }

        // 6. Remote Rename Command: #rename:<id>:<name> or #setname:<id>:<name> or #rename:<name>
        val renameMatch = Regex("""#(?:rename|setname):([A-Za-z0-9_.-]+):([^\n\r]+)""", RegexOption.IGNORE_CASE).find(trimmed)
            ?: Regex("""#(?:rename|setname):([^\n\r:]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        if (renameMatch != null) {
            val hasTarget = renameMatch.groupValues.size > 2
            val targetId = if (hasTarget) renameMatch.groupValues[1].trim() else "all"
            val newName = if (hasTarget) renameMatch.groupValues[2].trim() else renameMatch.groupValues[1].trim()
            if (matchesDevice(context, targetId) && newName.isNotEmpty()) {
                prefs.edit().putString(ConfigReceiver.KEY_CUSTOM_NICKNAME, newName).apply()
                Log.i(TAG, "CloudRelayPoller: device renamed to: $newName")
                context.sendBroadcast(
                    Intent(ConfigReceiver.ACTION_SET_NICKNAME)
                        .setPackage(context.packageName)
                        .putExtra("nickname", newName)
                )
                context.sendBroadcast(
                    Intent(ConfigReceiver.ACTION_SET_MESSAGE)
                        .setPackage(context.packageName)
                        .putExtra("message", trimmed)
                        .putExtra("from_poller", true)
                )
            }
            return
        }

        // 7. Remote Album Sync: #setalbum:<urls>, #addalbum:<urls>, or raw photos URLs
        val albumUrlRegex = Regex("""https?://(?:photos\.app\.goo\.gl/[^\s,"';|]+|photos\.google\.com/[^\s,"';|]+|share\.icloud\.com/photos/[^\s,"';|]+)""")
        val setAlbumMatch = Regex("""#(?:setalbum|replacealbum|setalbums):([^\r\n]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        val addAlbumMatch = Regex("""#(?:addalbum|addalbums|album):([^\r\n]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        val rawUrlMatch = albumUrlRegex.find(trimmed)

        if (setAlbumMatch != null || addAlbumMatch != null || (rawUrlMatch != null && !trimmed.contains("#showcase:"))) {
            val replace = setAlbumMatch != null || Albums.list(prefs).isEmpty()
            val targetText = setAlbumMatch?.groupValues?.get(1)
                ?: addAlbumMatch?.groupValues?.get(1)
                ?: if (!trimmed.contains("#showcase:")) trimmed else ""

            val extractedUrls = albumUrlRegex.findAll(targetText).map { it.value.trim() }.distinct().toList()

            if (extractedUrls.isNotEmpty()) {
                if (replace) {
                    Albums.clear(prefs)
                }
                for (u in extractedUrls) {
                    Albums.add(prefs, u)
                }
                Log.i(TAG, "CloudRelayPoller: saved ${extractedUrls.size} album URLs (replace=$replace)")

                // Download and cache photos to disk immediately in the background so they are
                // ready the instant the user re-opens the Frame app or screensaver activates
                for (u in extractedUrls) {
                    try {
                        val album = PhotoSources.fetch(u)
                        if (album.slides.isNotEmpty()) {
                            AlbumCache.write(prefs, u, album.slides, album.title)
                            Log.i(TAG, "CloudRelayPoller: background cached ${album.slides.size} slides for: $u")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "CloudRelayPoller: background album fetch failed for $u", e)
                    }
                }

                // If broadcast also included a note message, apply the note
                var remainingText = trimmed
                if (setAlbumMatch != null) remainingText = remainingText.replace(setAlbumMatch.value, "")
                if (addAlbumMatch != null) remainingText = remainingText.replace(addAlbumMatch.value, "")
                for (u in extractedUrls) remainingText = remainingText.replace(u, "")
                remainingText = remainingText.replace("#setalbum:", "").replace("#addalbum:", "").trim()

                if (remainingText.isNotEmpty()) {
                    prefs.edit().putString(ConfigReceiver.KEY_CUSTOM_MESSAGE, remainingText).apply()
                }

                context.sendBroadcast(
                    Intent(ConfigReceiver.ACTION_SET_MESSAGE)
                        .setPackage(context.packageName)
                        .putExtra("message", trimmed)
                        .putExtra("from_poller", true)
                )
                return
            }
        }

        // 8. Announcement Note & Showcase Tags
        var displayMsg = trimmed
        val hashMatch = Regex("""#(?:showcase:(?:date:)?|date:|showcase:|location:)?([A-Za-z0-9_.-]+)""").find(trimmed)
        if (hashMatch != null) {
            val fullTag = hashMatch.groupValues[0]
            displayMsg = trimmed.replace(fullTag, "").trim()
        }

        if (displayMsg.isNotBlank()) {
            prefs.edit().putString(ConfigReceiver.KEY_CUSTOM_MESSAGE, displayMsg).apply()
        } else {
            prefs.edit().remove(ConfigReceiver.KEY_CUSTOM_MESSAGE).apply()
        }
        Log.i(TAG, "CloudRelayPoller: updated custom message to: '$displayMsg'")

        context.sendBroadcast(
            Intent(ConfigReceiver.ACTION_SET_MESSAGE)
                .setPackage(context.packageName)
                .putExtra("message", trimmed)
                .putExtra("from_poller", true)
        )
    }

    fun matchesDevice(context: Context, targetId: String): Boolean {
        if (targetId.equals("all", ignoreCase = true)) return true
        val prefs = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        val myShortId = ConfigReceiver.getDeviceShortId(context)
        val currentNickname = prefs.getString(ConfigReceiver.KEY_CUSTOM_NICKNAME, "")?.trim() ?: ""
        val currentDisplay = ConfigReceiver.getDeviceDisplayName(context)
        val currentCity = prefs.getString(ConfigReceiver.KEY_DEVICE_CITY, "")?.trim() ?: ""
        val devId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: ""

        return targetId.equals(myShortId, ignoreCase = true) ||
                (devId.isNotEmpty() && devId.endsWith(targetId, ignoreCase = true)) ||
                (currentNickname.isNotEmpty() && currentNickname.contains(targetId, ignoreCase = true)) ||
                currentDisplay.contains(targetId, ignoreCase = true) ||
                (currentCity.isNotEmpty() && targetId.contains(currentCity, ignoreCase = true))
    }
}
