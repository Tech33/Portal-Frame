package com.portalhacks.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Lets shared albums (Google Photos or iCloud) be managed over ADB without rebuilding:
 *
 *   # add an album (repeat to add several)
 *   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver \
 *       --es url "https://photos.app.goo.gl/XXXXXXXX"
 *   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver \
 *       --es url "https://www.icloud.com/sharedalbum/#XXXXXXXX"
 *
 *   # remove one album / clear all (revert to bundled samples)
 *   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --es remove_url "https://…"
 *   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --es url ""
 */
class ConfigReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent == null) {
            return
        }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // This receiver is exported (so albums can be managed over ADB), which means any
        // installed app could broadcast to it. `url` adds a recognised shared-album link
        // (empty clears ALL albums); `remove_url` removes one. Unrecognised links are
        // ignored so a hostile broadcast can't point the frame at an arbitrary URL.
        if (intent.hasExtra("url")) {
            val url = intent.getStringExtra("url")?.trim() ?: ""
            when {
                url.isEmpty() -> { Albums.clear(prefs); Log.i("PortalFrame", "albums cleared") }
                isAlbumUrl(url) -> if (Albums.add(prefs, url)) Log.i("PortalFrame", "album added: '$url'")
                else -> Log.w("PortalFrame", "ignoring unrecognised album_url")
            }
        }
        if (intent.hasExtra("remove_url")) {
            val url = intent.getStringExtra("remove_url")?.trim() ?: ""
            if (url.isNotEmpty()) {
                Albums.remove(prefs, url)
                Log.i("PortalFrame", "album removed: '$url'")
            }
        }
        for (pair in arrayOf("enable_url" to true, "disable_url" to false)) {
            if (intent.hasExtra(pair.first)) {
                val url = intent.getStringExtra(pair.first)?.trim() ?: ""
                if (url.isNotEmpty()) {
                    Albums.setEnabled(prefs, url, pair.second)
                    Log.i("PortalFrame", "album ${if (pair.second) "resumed" else "stopped"}: '$url'")
                }
            }
        }

        // Toggle the screensaver guard over ADB (keeps Frame as the dream even when the
        // launcher reclaims the slot on rotation). Needs WRITE_SECURE_SETTINGS granted:
        //   adb shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --ez guard true
        if (intent.hasExtra("guard")) {
            val on = intent.getBooleanExtra("guard", true)
            prefs.edit().putBoolean(KEY_GUARD, on).apply()
            if (on) {
                Screensaver.claim(ctx)
                ScreensaverGuardService.start(ctx)
                Log.i("PortalFrame", "screensaver guard enabled")
            } else {
                ScreensaverGuardService.stop(ctx)
                Log.i("PortalFrame", "screensaver guard disabled")
            }
        }

        val ed = prefs.edit()
        var any = false
        for (e in BOOL_EXTRAS) {
            if (intent.hasExtra(e[0])) {
                val value = intent.getBooleanExtra(e[0], true)
                ed.putBoolean(e[1], value)
                Log.i("PortalFrame", "${e[1]} set to: $value")
                any = true
            }
        }
        for (key in arrayOf(KEY_CHIME_START_MIN, KEY_CHIME_END_MIN)) {
            if (intent.hasExtra(key)) {
                val value = intent.getIntExtra(key, -1)
                if (value >= 0) {
                    ed.putInt(key, value)
                    Log.i("PortalFrame", "$key set to: $value")
                    any = true
                }
            }
        }
        if (any) {
            ed.apply()
        }
    }

    companion object {
        const val PREFS = "portalframe"
        const val KEY_ALBUM = "album_url" // legacy single album (migrated into KEY_ALBUMS)
        const val KEY_ALBUMS = "album_urls" // JSON array of configured album URLs
        const val KEY_ALBUMS_DISABLED = "album_urls_disabled" // JSON array of stopped album URLs
        const val KEY_GUARD = "screensaver_guard" // boolean: keep re-asserting Frame as the dream
        const val KEY_ALBUM_PLAYBACK = "album_playback" // string: shuffled merge or album priority

        // Slideshow settings (written by PhotosActivity, read by SlideshowController).
        const val KEY_DELAY_MS = "delay_ms"     // ms each photo is held
        const val KEY_SHUFFLE = "shuffle"       // boolean: random order
        const val KEY_FADE_MS = "fade_ms"       // ms auto crossfade duration
        const val KEY_TRANSITION = "transition" // string: slideshow transition mode
        const val KEY_PAIRS = "pairs"           // boolean: pair two photos to fill the screen
        const val KEY_KEN_BURNS = "ken_burns"   // boolean: cinematic pan/zoom
        const val KEY_KEN_BURNS_SCALE = "ken_burns_scale" // float: pan/zoom intensity (0.3 .. 2.0)
        const val KEY_ANNOUNCEMENT_CHANNEL = "announcement_channel" // string: broadcast channel ID
        const val KEY_CLOCK = "clock"           // boolean: clock + weather overlay
        const val KEY_CLOCK_FLIP = "clock_flip" // boolean: show full-screen web flip clock
        const val KEY_WEATHER_FAHRENHEIT = "weather_fahrenheit" // boolean: weather temp unit
        const val KEY_CLOCK_24H = "clock_24h"   // boolean: explicit 24-hour clock mode
        const val KEY_CLOCK_LOW_LIGHT = "clock_low_light" // boolean: clock-only in low light
        const val KEY_NIGHT_CLOCK = "night_clock" // boolean: show full-screen clock on schedule
        const val KEY_BATTERY = "battery"       // boolean: show battery percentage widget
        const val KEY_CHIME = "chime"           // boolean: hourly chime active
        const val KEY_CHIME_START_MIN = "chime_start_min" // start minutes after midnight
        const val KEY_CHIME_END_MIN = "chime_end_min"     // end minutes after midnight
        const val KEY_NIGHT_CLOCK_START_MIN = "night_clock_start_min" // minutes after midnight
        const val KEY_NIGHT_CLOCK_END_MIN = "night_clock_end_min" // minutes after midnight
        const val KEY_NIGHT = "night"           // boolean: warm night dimming
        const val KEY_ON_THIS_DAY = "on_this_day" // boolean: surface memories
        const val KEY_CAPTIONS = "captions"     // boolean: photo date captions
        const val KEY_FACE = "face_framing"     // boolean: face-aware Ken Burns target
        const val KEY_AMBIENT = "ambient_color" // boolean: per-photo color glow
        const val KEY_ENHANCE = "auto_enhance"  // boolean: on-device auto-levels + vibrance
        const val KEY_ZOOM_FILL = "zoom_fill"   // boolean: zoom-crop SINGLE photos to fill (vs whole
                                                // photo over a blurred fill). Pairs always fill.
        const val KEY_BLUR_RADIUS = "blur_radius" // int: background fill blur radius (1..8)
        const val KEY_RECENT_FIRST = "recent_first" // boolean: sort slideshow newest photos first
        // Clock widget transform (set by long-press-drag/pinch on the screensaver). dx/dy are the
        // translation from the default bottom-left anchor as a fraction of screen W/H; scale is a
        // size multiplier. Floats.
        const val KEY_CLOCK_DX = "clock_dx"
        const val KEY_CLOCK_DY = "clock_dy"
        const val KEY_CLOCK_SCALE = "clock_scale"
        // Date/weather overlay transform (similar to clock: dx/dy as fractions, scale as multiplier).
        const val KEY_DATE_DX = "date_dx"
        const val KEY_DATE_DY = "date_dy"
        const val KEY_DATE_SCALE = "date_scale"
        const val KEY_CLOCK_ONLY_DX = "clock_only_dx"
        const val KEY_CLOCK_ONLY_DY = "clock_only_dy"
        const val KEY_CLOCK_ONLY_SCALE = "clock_only_scale"
        const val KEY_UPDATE_AUTO_CHECK = "update_auto_check" // check GitHub on Settings open
        const val KEY_LAST_UPDATE_CHECK_MS = "last_update_check_ms"
        const val KEY_FONT_SCALE = "font_scale" // float multiplier for universal text sizing (1.0f, 1.15f, 1.30f, 1.50f)
        const val KEY_HA_BRIDGE_MODE = "ha_bridge_mode" // Home Assistant & portal-ha-bridge web kiosk mode

        // MQTT and Embedded Home Assistant keys
        const val KEY_MQTT_ENABLED = "mqtt_enabled"
        const val KEY_MQTT_HOST = "mqtt_host"
        const val KEY_MQTT_PORT = "mqtt_port"
        const val KEY_MQTT_USER = "mqtt_user"
        const val KEY_MQTT_PASS = "mqtt_pass"
        const val KEY_MQTT_TOPIC_PREFIX = "mqtt_topic_prefix"

        const val KEY_CUSTOM_MESSAGE = "custom_overlay_message"
        const val KEY_HA_EMBEDDED = "ha_embedded"
        const val KEY_HA_URL = "ha_url"
        const val KEY_HA_IDLE_TIMEOUT_SEC = "ha_idle_timeout_sec"
        const val KEY_HA_BUTTON = "ha_button"

        // Broadcast Intent Actions
        const val ACTION_SHOW_DASHBOARD = "com.portalhacks.frame.SHOW_DASHBOARD"
        const val ACTION_SHOW_SLIDESHOW = "com.portalhacks.frame.SHOW_SLIDESHOW"
        const val ACTION_NEXT_PHOTO = "com.portalhacks.frame.NEXT_PHOTO"
        const val ACTION_PREV_PHOTO = "com.portalhacks.frame.PREV_PHOTO"
        const val ACTION_SET_MESSAGE = "com.portalhacks.frame.SET_MESSAGE"
        const val ACTION_CLEAR_MESSAGE = "com.portalhacks.frame.CLEAR_MESSAGE"
        const val ACTION_WAKE = "com.portalhacks.frame.WAKE"
        const val ACTION_SLEEP = "com.portalhacks.frame.SLEEP"

        /** Stable URL — always serves the latest release's version.json asset. */
        const val UPDATE_MANIFEST_URL =
            "https://github.com/Tech33/Portal-Frame/releases/latest/download/version.json"
        const val DEFAULT_CHIME = false
        const val DEFAULT_CHIME_START_MIN = 8 * 60
        const val DEFAULT_CHIME_END_MIN = 22 * 60
        const val DEFAULT_DELAY_MS = 6000L
        const val DEFAULT_FADE_MS = 1200L

        const val DEFAULT_TRANSITION = "slide"
        const val DEFAULT_ALBUM_PLAYBACK = "shuffled_merge"

        const val DEFAULT_PAIRS = true
        const val DEFAULT_KEN_BURNS = true
        const val DEFAULT_KEN_BURNS_SCALE = 1.0f
        const val DEFAULT_ANNOUNCEMENT_CHANNEL = "portal_broadcast"
        const val DEFAULT_CLOCK = true
        const val DEFAULT_CLOCK_FLIP = false
        const val DEFAULT_WEATHER_FAHRENHEIT = false
        const val DEFAULT_CLOCK_24H = false
        const val DEFAULT_CLOCK_LOW_LIGHT = false
        const val DEFAULT_NIGHT_CLOCK = false
        const val DEFAULT_BATTERY = true
        const val DEFAULT_NIGHT_CLOCK_START_MIN = 22 * 60
        const val DEFAULT_NIGHT_CLOCK_END_MIN = 7 * 60
        const val DEFAULT_NIGHT = true
        const val DEFAULT_ON_THIS_DAY = true
        const val DEFAULT_CAPTIONS = true
        const val DEFAULT_FACE = true
        const val DEFAULT_AMBIENT = true
        const val DEFAULT_ENHANCE = false
        const val DEFAULT_ZOOM_FILL = false // default: whole photo over a blurred fill (no zoom)
        const val DEFAULT_BLUR_RADIUS = 3
        const val DEFAULT_RECENT_FIRST = false
        const val DEFAULT_CLOCK_DX = 0f
        const val DEFAULT_CLOCK_DY = 0f
        const val DEFAULT_CLOCK_SCALE = 1f
        const val DEFAULT_DATE_DX = 0f
        const val DEFAULT_DATE_DY = 0f
        const val DEFAULT_DATE_SCALE = 1f
        const val DEFAULT_CLOCK_ONLY_DX = 0f
        const val DEFAULT_CLOCK_ONLY_DY = 0f
        const val DEFAULT_CLOCK_ONLY_SCALE = 1f
        const val DEFAULT_UPDATE_AUTO_CHECK = true
        const val DEFAULT_FONT_SCALE = 1.0f
        const val DEFAULT_HA_BRIDGE_MODE = false

        const val DEFAULT_MQTT_ENABLED = false
        const val DEFAULT_MQTT_HOST = ""
        const val DEFAULT_MQTT_PORT = 1883
        const val DEFAULT_MQTT_USER = ""
        const val DEFAULT_MQTT_PASS = ""
        const val DEFAULT_MQTT_TOPIC_PREFIX = "portal"

        const val DEFAULT_CUSTOM_MESSAGE = ""
        const val DEFAULT_HA_EMBEDDED = false
        const val DEFAULT_HA_URL = ""
        const val DEFAULT_HA_IDLE_TIMEOUT_SEC = 60
        const val DEFAULT_HA_BUTTON = true

        // ADB-settable boolean extras (extra name -> pref key) for quick testing
        private val BOOL_EXTRAS = arrayOf(
            arrayOf("shuffle", KEY_SHUFFLE), arrayOf("pairs", KEY_PAIRS), arrayOf("ken_burns", KEY_KEN_BURNS),
            arrayOf("clock", KEY_CLOCK), arrayOf("weather_fahrenheit", KEY_WEATHER_FAHRENHEIT),
            arrayOf("clock_low_light", KEY_CLOCK_LOW_LIGHT), arrayOf("night_clock", KEY_NIGHT_CLOCK),
            arrayOf("night", KEY_NIGHT), arrayOf("on_this_day", KEY_ON_THIS_DAY),
            arrayOf("captions", KEY_CAPTIONS), arrayOf("face_framing", KEY_FACE), arrayOf("ambient_color", KEY_AMBIENT),
            arrayOf("auto_enhance", KEY_ENHANCE), arrayOf("zoom_fill", KEY_ZOOM_FILL),
            arrayOf("battery", KEY_BATTERY),
            arrayOf("chime", KEY_CHIME),
            arrayOf("recent_first", KEY_RECENT_FIRST),
            arrayOf("ha_bridge_mode", KEY_HA_BRIDGE_MODE),
            arrayOf("mqtt_enabled", KEY_MQTT_ENABLED),
            arrayOf("ha_embedded", KEY_HA_EMBEDDED),
            arrayOf("ha_button", KEY_HA_BUTTON),
        )

        /** True for a recognised shared-album HTTPS link (Google Photos or iCloud). */
        fun isAlbumUrl(s: String?): Boolean = PhotoSources.matches(s)
    }
}
