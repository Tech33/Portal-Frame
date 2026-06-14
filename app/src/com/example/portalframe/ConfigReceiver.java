package com.example.portalframe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Lets the Google Photos shared-album URL be set over ADB without rebuilding:
 *
 *   adb shell am broadcast -n com.example.portalframe/.ConfigReceiver \
 *       --es url "https://photos.app.goo.gl/XXXXXXXX"
 *
 * Clear it (revert to bundled samples) with:
 *   adb shell am broadcast -n com.example.portalframe/.ConfigReceiver --es url ""
 */
public class ConfigReceiver extends BroadcastReceiver {

    static final String PREFS = "portalframe";
    static final String KEY_ALBUM = "album_url";

    // Slideshow settings (written by PhotosActivity, read by SlideshowController).
    static final String KEY_DELAY_MS = "delay_ms";   // ms each photo is held
    static final String KEY_SHUFFLE = "shuffle";     // boolean: random order
    static final String KEY_FADE_MS = "fade_ms";     // ms auto crossfade duration
    static final String KEY_PAIRS = "pairs";         // boolean: pair portraits side-by-side
    static final long DEFAULT_DELAY_MS = 6000L;
    static final long DEFAULT_FADE_MS = 1200L;
    static final boolean DEFAULT_PAIRS = true;

    // Cached photo list so the screensaver starts straight from the album (no
    // bundled-sample flash). KEY_PHOTO_CACHE_URL records which album it's for.
    // v3: bumped for the new per-photo schema (capture time + portrait flag) and
    // the album title; captions are now derived at display time, not cached.
    static final String KEY_PHOTO_CACHE = "photo_cache_v3";
    static final String KEY_PHOTO_CACHE_URL = "photo_cache_url_v3";
    static final String KEY_ALBUM_TITLE = "album_title_v3";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !intent.hasExtra("url")) {
            return;
        }
        String url = intent.getStringExtra("url");
        url = url == null ? "" : url.trim();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ALBUM, url).apply();
        Log.i("PortalFrame", "album_url set to: '" + url + "'");
    }
}
