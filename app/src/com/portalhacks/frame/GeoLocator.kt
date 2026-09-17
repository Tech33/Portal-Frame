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
 */
object GeoLocator {

    private const val TAG = "GeoLocator"
    private const val TIMEOUT_MS = 6000

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

        // 1. Try GeoJS
        try {
            val res = httpGet("https://get.geojs.io/v1/ip/geo.json")
            val obj = JSONObject(res)
            val city = obj.optString("city", "").trim()
            val country = obj.optString("country", "").trim()
            val lat = obj.optString("latitude", "")
            val lon = obj.optString("longitude", "")
            if (city.isNotEmpty()) {
                saveLocation(context, city, country)
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
                if (city.isNotEmpty()) {
                    saveLocation(context, city, country)
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
                if (city.isNotEmpty()) {
                    saveLocation(context, city, country)
                    return Location(city, country, lat, lon)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ip-api.com resolution failed: ${e.message}")
        }

        if (cachedCity.isNotEmpty()) {
            return Location(cachedCity, cachedCountry)
        }
        return null
    }

    private fun saveLocation(context: Context, city: String, country: String) {
        val prefs = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(ConfigReceiver.KEY_DEVICE_CITY, city)
            .putString(ConfigReceiver.KEY_DEVICE_COUNTRY, country)
            .apply()
        Log.i(TAG, "Updated device location to $city, $country")
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
