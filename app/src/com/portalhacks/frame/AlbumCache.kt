package com.portalhacks.frame

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/**
 * Dedicated disk-file cache (photo list + title) stored in `context.cacheDir/albums/<hash>.json`
 * rather than bloating `SharedPreferences`. Keeps `portalframe.xml` microscopic so routine
 * preference updates commit instantly without disk write lag or eMMC flash wear.
 *
 * Includes an in-memory hot cache for O(1) instant lookups and automatic migration from
 * legacy `album_cache_v4_*` keys stored in SharedPreferences.
 */
internal object AlbumCache {
    private const val TAG = "PortalFrame"
    private const val KEY_PREFIX = "album_cache_v4_"

    private fun legacyKey(url: String): String = KEY_PREFIX + Integer.toHexString(url.hashCode())

    const val MAX_CACHED_PHOTOS_PER_ALBUM = 300

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "album-cache-io").apply { priority = Thread.MIN_PRIORITY }
    }

    private var baseDir: File? = null

    // In-memory hot cache of (title, slides) keyed by url for instantaneous access
    private val memCache = Collections.synchronizedMap(object : LinkedHashMap<String, Pair<String?, List<Slide>>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String?, List<Slide>>>?): Boolean {
            return size > 16 // keep up to 16 albums in memory
        }
    })

    fun setContext(context: Context) {
        if (baseDir == null) {
            baseDir = File(context.applicationContext.cacheDir, "albums").apply { mkdirs() }
        }
    }

    private fun getFile(url: String): File {
        val dir = baseDir ?: File("/data/data/com.portalhacks.frame/cache/albums").apply { mkdirs() }
        if (!dir.exists()) dir.mkdirs()
        return File(dir, Integer.toHexString(url.hashCode()) + ".json")
    }

    /** Persist [photos] (and [title]) as the cache for [url], sorted descending by capture time. */
    @JvmStatic
    fun write(prefs: SharedPreferences, url: String, photos: List<Slide>, title: String?) {
        val sorted = SlideshowController.sortByCaptureDescending(photos)
        val capped = if (sorted.size > MAX_CACHED_PHOTOS_PER_ALBUM) {
            sorted.take(MAX_CACHED_PHOTOS_PER_ALBUM)
        } else {
            sorted
        }

        // Update in-memory hot cache immediately
        memCache[url] = Pair(title, capped)

        val arr = JSONArray()
        for (s in capped) {
            try {
                arr.put(
                    JSONObject()
                        .put("u", s.id)
                        .put("c", s.caption ?: "")
                        .put("t", s.timeMs)
                        .put("pt", s.portrait)
                        .put("loc", s.location ?: ""),
                )
            } catch (_: JSONException) {
                continue
            }
        }
        val obj = JSONObject()
        try {
            obj.put("url", url).put("title", title ?: "").put("photos", arr)
        } catch (_: JSONException) {
            return
        }

        val jsonStr = obj.toString()

        // Write to dedicated cache file asynchronously
        ioExecutor.execute {
            try {
                val f = getFile(url)
                val tmp = File(f.absolutePath + ".tmp")
                tmp.writeText(jsonStr, Charsets.UTF_8)
                tmp.renameTo(f)
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing album cache file for $url", e)
            }
        }

        // Clean up any legacy blob from SharedPreferences to shrink portalframe.xml
        if (prefs.contains(legacyKey(url))) {
            prefs.edit().remove(legacyKey(url)).apply()
        }

        Log.i(TAG, "cached ${capped.size} photos for album (sorted descending, newest first)")
    }

    private fun readObj(prefs: SharedPreferences, url: String?): JSONObject? {
        if (url == null) return null

        // 1. Try reading from dedicated cache file
        try {
            val f = getFile(url)
            if (f.exists() && f.length() > 0L) {
                val jsonStr = f.readText(Charsets.UTF_8)
                val o = JSONObject(jsonStr)
                if (o.optString("url") == url) return o
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading album cache file for $url", e)
        }

        // 2. Migration fallback: check legacy SharedPreferences
        val blob = prefs.getString(legacyKey(url), null) ?: return null
        return try {
            val o = JSONObject(blob)
            if (o.optString("url") == url) {
                // Migrate to file and remove from SharedPreferences
                ioExecutor.execute {
                    try {
                        val f = getFile(url)
                        val tmp = File(f.absolutePath + ".tmp")
                        tmp.writeText(blob, Charsets.UTF_8)
                        tmp.renameTo(f)
                        prefs.edit().remove(legacyKey(url)).apply()
                        Log.i(TAG, "Migrated album cache to file and cleared prefs for $url")
                    } catch (_: Exception) {}
                }
                o
            } else null
        } catch (_: JSONException) {
            null
        }
    }

    /** The cached photos for [url], or `null` if not cached. */
    @JvmStatic
    fun read(prefs: SharedPreferences, url: String?): List<Slide>? {
        if (url == null) return null
        memCache[url]?.let { return it.second }

        val obj = readObj(prefs, url) ?: return null
        val title = obj.optString("title", "")
        val arr = obj.optJSONArray("photos") ?: return null
        val out = ArrayList<Slide>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("u", "")
            if (id.isEmpty()) continue
            out.add(
                Slide(
                    id,
                    o.optString("c", "").ifEmpty { null },
                    o.optLong("t", Slide.NO_DATE),
                    o.optBoolean("pt", false),
                    o.optString("loc", "").ifEmpty { null },
                ),
            )
        }
        memCache[url] = Pair(title, out)
        return out
    }

    /** The cached album title for [url] (may be empty), or `null` if not cached. */
    @JvmStatic
    fun title(prefs: SharedPreferences, url: String?): String? {
        if (url == null) return null
        memCache[url]?.let { return it.first }
        val t = readObj(prefs, url)?.optString("title", "")
        return t
    }

    /** The first cached photo id (URL) for [url], or `null` if none cached. */
    @JvmStatic
    fun firstId(prefs: SharedPreferences, url: String?): String? =
        read(prefs, url)?.firstOrNull()?.id

    /** Drop the cache for [url] (when an album is removed). */
    @JvmStatic
    fun delete(prefs: SharedPreferences, url: String) {
        memCache.remove(url)
        ioExecutor.execute {
            try {
                getFile(url).delete()
            } catch (_: Exception) {}
        }
        prefs.edit().remove(legacyKey(url)).apply()
    }
}
