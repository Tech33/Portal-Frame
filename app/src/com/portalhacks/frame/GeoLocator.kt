package com.portalhacks.frame

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves device geographical location (city, country, coordinates) using redundant IP geolocation APIs.
 * Ensures Portals get accurately geotagged with zero manual configuration.
 *
 * Caches coordinates with a 24-hour TTL so weather refreshes don't spam external geolocation APIs.
 */
object GeoLocator {

    private const val TAG = "GeoLocator"
    private const val TIMEOUT_MS = 6000
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000 // 24 hours

    private const val KEY_DEVICE_LAT = "device_latitude"
    private const val KEY_DEVICE_LON = "device_longitude"
    private const val KEY_DEVICE_LOC_TIME = "device_loc_time"

    data class Location(
        val city: String,
        val country: String,
        val latitude: String = "",
        val longitude: String = ""
    )

    fun resolveLocationAsync(context: Context, onComplete: ((Location?) -> Unit)? = null) {
        Thread {
            val loc = resolveLocation(context)
            onComplete?.invoke(loc)
        }.start()
    }

    fun resolveLocation(context: Context): Location? {
        val prefs = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        val cachedCity = prefs.getString(ConfigReceiver.KEY_DEVICE_CITY, "") ?: ""
        val cachedCountry = prefs.getString(ConfigReceiver.KEY_DEVICE_COUNTRY, "") ?: ""
        val cachedLat = prefs.getString(KEY_DEVICE_LAT, "") ?: ""
        val cachedLon = prefs.getString(KEY_DEVICE_LON, "") ?: ""
        val lastResolved = prefs.getLong(KEY_DEVICE_LOC_TIME, 0L)
        val now = System.currentTimeMillis()

        // Fast path: if we already have valid coordinates that are less than 24 hours old, reuse them
        if (cachedCity.isNotEmpty() && cachedLat.isNotEmpty() && cachedLon.isNotEmpty() && (now - lastResolved) < CACHE_TTL_MS) {
            return Location(cachedCity, cachedCountry, cachedLat, cachedLon)
        }

        // 1. Try GeoJS
        try {
            val res = httpGet("https://get.geojs.io/v1/ip/geo.json")
            val obj = JSONObject(res)
            val city = obj.optString("city", "").trim()
            val country = obj.optString("country", "").trim()
            val lat = obj.optString("latitude", "")
            val lon = obj.optString("longitude", "")
            if (city.isNotEmpty() && lat.isNotEmpty() && lon.isNotEmpty()) {
                saveLocation(context, city, country, lat, lon)
                return Location(city, country, lat, lon)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GeoJS resolution failed: ${e.message}")
        }

        // 2. Try ipwho.is
        try {
            val res = httpGet("https://ipwho.is/")
            val obj = JSONObject(res)
            if (obj.optBoolean("success", true)) {
                val city = obj.optString("city", "").trim()
                val country = obj.optString("country", "").trim()
                val lat = obj.optString("latitude", "")
                val lon = obj.optString("longitude", "")
                if (city.isNotEmpty() && lat.isNotEmpty() && lon.isNotEmpty()) {
                    saveLocation(context, city, country, lat, lon)
                    return Location(city, country, lat, lon)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ipwho.is resolution failed: ${e.message}")
        }

        // 3. Try ip-api.com
        try {
            val res = httpGet("http://ip-api.com/json/")
            val obj = JSONObject(res)
            if (obj.optString("status", "") == "success") {
                val city = obj.optString("city", "").trim()
                val country = obj.optString("country", "").trim()
                val lat = obj.optDouble("lat", 0.0).toString()
                val lon = obj.optDouble("lon", 0.0).toString()
                if (city.isNotEmpty() && lat.isNotEmpty() && lon.isNotEmpty()) {
                    saveLocation(context, city, country, lat, lon)
                    return Location(city, country, lat, lon)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ip-api.com resolution failed: ${e.message}")
        }

        // Fallback: use existing cached location even if older than TTL
        if (cachedCity.isNotEmpty()) {
            return Location(cachedCity, cachedCountry, cachedLat, cachedLon)
        }
        return null
    }

    private fun saveLocation(context: Context, city: String, country: String, lat: String, lon: String) {
        val prefs = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(ConfigReceiver.KEY_DEVICE_CITY, city)
            .putString(ConfigReceiver.KEY_DEVICE_COUNTRY, country)
            .putString(KEY_DEVICE_LAT, lat)
            .putString(KEY_DEVICE_LON, lon)
            .putLong(KEY_DEVICE_LOC_TIME, System.currentTimeMillis())
            .apply()
        Log.i(TAG, "Updated device location to $city, $country ($lat, $lon)")
    }

    private fun httpGet(urlStr: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "PortalFrame/1.6")
            val stream = BufferedInputStream(conn.inputStream)
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(2048)
            var n: Int
            while (stream.read(chunk).also { n = it } != -1) {
                buf.write(chunk, 0, n)
            }
            return buf.toString("UTF-8")
        } finally {
            conn?.disconnect()
        }
    }
}
