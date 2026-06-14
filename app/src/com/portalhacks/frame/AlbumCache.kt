package com.portalhacks.frame

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Per-album cache (photo list + title) in the shared `portalframe` prefs, so both the
 * slideshow ([SlideshowComposeActivity]) and the settings previews ([SettingsActivity])
 * start from each album without re-fetching. One JSON object per album, keyed by a hash
 * of its URL (the stored `url` is re-checked on read to guard against hash collisions).
 *
 * Stored as JSON (no raw newlines/tabs) so the value survives SharedPreferences' XML
 * round-trip intact.
 */
internal object AlbumCache {
    private const val TAG = "PortalFrame"
    private const val KEY_PREFIX = "album_cache_v4_"

    private fun key(url: String): String = KEY_PREFIX + Integer.toHexString(url.hashCode())

    /** Persist [photos] (and [title]) as the cache for [url]. */
    @JvmStatic
    fun write(prefs: SharedPreferences, url: String, photos: List<Slide>, title: String?) {
        val arr = JSONArray()
        for (s in photos) {
            try {
                arr.put(
                    JSONObject()
                        .put("u", s.id)
                        .put("c", s.caption ?: "")
                        .put("t", s.timeMs)
                        .put("pt", s.portrait),
                )
            } catch (ignored: JSONException) {
                continue
            }
        }
        val obj = JSONObject()
        try {
            obj.put("url", url).put("title", title ?: "").put("photos", arr)
        } catch (e: JSONException) {
            return
        }
        prefs.edit().putString(key(url), obj.toString()).apply()
        Log.i(TAG, "cached ${photos.size} photos for album")
    }

    private fun readObj(prefs: SharedPreferences, url: String?): JSONObject? {
        if (url == null) return null
        val blob = prefs.getString(key(url), null) ?: return null
        return try {
            val o = JSONObject(blob)
            if (o.optString("url") == url) o else null // hash-collision guard
        } catch (e: JSONException) {
            null
        }
    }

    /** The cached photos for [url], or `null` if not cached. */
    @JvmStatic
    fun read(prefs: SharedPreferences, url: String?): List<Slide>? {
        val arr = readObj(prefs, url)?.optJSONArray("photos") ?: return null
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
                ),
            )
        }
        return out
    }

    /** The cached album title for [url] (may be empty), or `null` if not cached. */
    @JvmStatic
    fun title(prefs: SharedPreferences, url: String?): String? =
        readObj(prefs, url)?.optString("title", "")

    /** The first cached photo id (URL) for [url], or `null` if none cached. */
    @JvmStatic
    fun firstId(prefs: SharedPreferences, url: String?): String? =
        read(prefs, url)?.firstOrNull()?.id

    /** Drop the cache for [url] (when an album is removed). */
    @JvmStatic
    fun delete(prefs: SharedPreferences, url: String) {
        prefs.edit().remove(key(url)).apply()
    }
}
