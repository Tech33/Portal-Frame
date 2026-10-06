package com.portalhacks.frame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Loads bitmaps off the main thread for both bundled assets ("slides/01.png")
 * and remote URLs ("https://..."). Remote bytes are cached on disk (so later
 * slideshow loops are instant and survive reboots) and decoded downsampled to
 * the screen size. A small in-memory LRU caches recently shown bitmaps.
 */
class ImageLoader(context: Context) {

    fun interface Callback {
        fun onLoaded(b: Bitmap?)
    }

    private val ctx: Context = context.applicationContext
    
    private val ioLoad: ExecutorService = Executors.newFixedThreadPool(2, ThreadFactory { r ->
        Thread(r, "image-loader-io-load").apply {
            priority = Thread.MAX_PRIORITY
        }
    })
    
    private val ioPrefetch: ExecutorService = Executors.newFixedThreadPool(1, ThreadFactory { r ->
        Thread(r, "image-loader-io-prefetch").apply {
            priority = Thread.MIN_PRIORITY
        }
    })
    
    private val main = Handler(Looper.getMainLooper())
    private val mem: LruCache<String, Bitmap>
    private val cacheDir: File

    init {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        mem = object : LruCache<String, Bitmap>(maxKb / 6) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return value.byteCount / 1024
            }

            override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
                if (evicted && oldValue !== newValue) {
                    releaseToPool(oldValue)
                }
            }
        }
        cacheDir = File(ctx.cacheDir, "photos_v2")
        cacheDir.mkdirs()
        ioPrefetch.execute {
            try {
                val old = File(ctx.cacheDir, "photos")
                if (old.exists()) {
                    old.deleteRecursively()
                }
            } catch (_: Exception) {}
        }
    }

    /** Shared background pool — reused for album fetches too. */
    fun executor(): ExecutorService {
        return ioPrefetch
    }

    fun clearMemory() {
        mem.evictAll()
        clearPool()
    }

    fun clearDiskAndMemoryCache() {
        mem.evictAll()
        clearPool()
        ioPrefetch.execute {
            try {
                cacheDir.listFiles()?.forEach { it.delete() }
            } catch (_: Exception) {}
        }
    }

    /** Proactively sheds bitmap cache on system memory pressure to ensure 24/7 stability. */
    fun trimMemory(level: Int) {
        clearPool()
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            mem.evictAll()
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            mem.trimToSize(mem.size() / 2)
        }
    }

    fun getCacheSizeBytes(): Long {
        return try {
            cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun load(id: String, reqW: Int, reqH: Int, zoomFill: Boolean, cb: Callback) {
        val key = fillKey(id, zoomFill)
        val cached = mem.get(key)
        if (cached != null) {
            cb.onLoaded(cached)
            return
        }
        ioLoad.execute {
            val bmp = loadSync(id, reqW, reqH, zoomFill)
            if (bmp != null) {
                mem.put(key, bmp)
                FaceFocus.detectAndCache(bmp)
                PhotoEnhance.precompute(bmp)
                AmbientColor.precompute(bmp)
            }
            main.post { cb.onLoaded(bmp) }
        }
    }

    /** Warm the cache for an id without a UI callback. */
    fun prefetch(id: String, reqW: Int, reqH: Int, zoomFill: Boolean) {
        val key = fillKey(id, zoomFill)
        if (mem.get(key) != null) {
            return
        }
        ioPrefetch.execute {
            val b = loadSync(id, reqW, reqH, zoomFill)
            if (b != null) {
                mem.put(key, b)
                FaceFocus.detectAndCache(b)
                PhotoEnhance.precompute(b)
                AmbientColor.precompute(b)
            }
        }
    }

    /** Cache key that distinguishes the two fill modes (zoom-crop vs whole photo + blur). */
    private fun fillKey(id: String, zoomFill: Boolean): String {
        if (zoomFill) return "$id|z"
        val prefs = ctx.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        val blur = prefs.getInt(ConfigReceiver.KEY_BLUR_RADIUS, ConfigReceiver.DEFAULT_BLUR_RADIUS)
        return "$id|f|$blur"
    }

    private fun loadSync(id: String, reqW: Int, reqH: Int, zoomFill: Boolean): Bitmap? {
        return try {
            val raw = decodeRaw(id, reqW, reqH) ?: return null
            val prefs = ctx.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
            val blurRadius = prefs.getInt(ConfigReceiver.KEY_BLUR_RADIUS, ConfigReceiver.DEFAULT_BLUR_RADIUS).coerceIn(1, 10)
            composeFill(raw, reqW, reqH, zoomFill, blurRadius)
        } catch (e: Exception) {
            Log.e(TAG, "load failed $id", e)
            null
        }
    }

    /** Decode the source bitmap (downloading + disk-caching remote ids first). */
    @Throws(Exception::class)
    private fun decodeRaw(id: String, reqW: Int, reqH: Int): Bitmap? {
        if (id.startsWith("http")) {
            val f = File(cacheDir, md5(id) + ".img")
            if (!f.exists() || f.length() == 0L) {
                if (!download(id, f)) {
                    return null
                }
            }
            return decodeFile(f.absolutePath, reqW, reqH)
        }
        return decodeAsset(id, reqW, reqH)
    }

    /**
     * Load two portrait photos composed side-by-side into one screen-sized frame
     * ("show pairs"). Cached in memory under a combined key; falls back to null on
     * failure so the caller can skip the pair.
     */
    fun loadPair(
        id1: String, id2: String,
        reqW: Int, reqH: Int, stackVertical: Boolean, cb: Callback
    ) {
        // A paired photo only gets half the screen, so it's always center-cropped to fill its
        // half — the "zoom to fill" setting applies only to single, full-screen photos.
        val key = "$id1|$id2|${if (stackVertical) "v" else "h"}"
        val cached = mem.get(key)
        if (cached != null) {
            cb.onLoaded(cached)
            return
        }
        ioLoad.execute {
            var out: Bitmap? = null
            var a: Bitmap? = null
            var b: Bitmap? = null
            try {
                // Each photo gets half the screen: half-height when stacked top/bottom (vertical
                // screen), half-width when side-by-side (landscape screen).
                if (stackVertical) {
                    a = decodeRaw(id1, reqW, reqH / 2)
                    b = decodeRaw(id2, reqW, reqH / 2)
                } else {
                    a = decodeRaw(id1, reqW / 2, reqH)
                    b = decodeRaw(id2, reqW / 2, reqH)
                }
                if (a != null && b != null) {
                    out = composePair(a, b, reqW, reqH, stackVertical) // recycles a and b
                    a = null
                    b = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadPair failed $key", e)
            } finally {
                a?.recycle()
                b?.recycle()
            }
            val result = out
            if (result != null) {
                mem.put(key, result)
                FaceFocus.detectAndCache(result)
                PhotoEnhance.precompute(result)
                AmbientColor.precompute(result)
            }
            main.post { cb.onLoaded(result) }
        }
    }

    private fun decodeFile(path: String, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, o)
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, reqW, reqH)
        o.inPreferredConfig = Bitmap.Config.ARGB_8888
        o.inMutable = true
        val targetW = o.outWidth / o.inSampleSize
        val targetH = o.outHeight / o.inSampleSize
        val candidate = getReusableBitmap(targetW, targetH)
        if (candidate != null) {
            o.inBitmap = candidate
        }
        o.inJustDecodeBounds = false
        return try {
            BitmapFactory.decodeFile(path, o)
        } catch (_: IllegalArgumentException) {
            o.inBitmap = null
            releaseToPool(candidate)
            BitmapFactory.decodeFile(path, o)
        }
    }

    @Throws(Exception::class)
    private fun decodeAsset(assetPath: String, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        var `in` = ctx.assets.open(assetPath)
        BitmapFactory.decodeStream(`in`, null, o)
        `in`.close()
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, reqW, reqH)
        o.inPreferredConfig = Bitmap.Config.ARGB_8888
        o.inJustDecodeBounds = false
        `in` = ctx.assets.open(assetPath)
        val b = BitmapFactory.decodeStream(`in`, null, o)
        `in`.close()
        return b
    }

    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        if (reqW <= 0 || reqH <= 0 || w <= 0 || h <= 0) {
            return 1
        }
        var s = 1
        // Maintain at least 1.5x resolution headroom so Ken Burns zoom and pans stay razor-sharp
        val targetW = (reqW * 1.5f).roundToInt()
        val targetH = (reqH * 1.5f).roundToInt()
        while (w / (s * 2) >= targetW && h / (s * 2) >= targetH) {
            s *= 2
        }
        return s
    }

    private fun download(urlStr: String?, dest: File): Boolean {
        if (urlStr == null || !urlStr.startsWith("https://")) {
            Log.w(TAG, "refusing non-HTTPS image URL")
            return false
        }
        var c: HttpURLConnection? = null
        try {
            c = URL(urlStr).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code != 200) {
                Log.w(TAG, "image http $code for $urlStr")
                return false
            }
            val tmp = File(dest.absolutePath + ".tmp")
            val `in` = BufferedInputStream(c.inputStream)
            val out = FileOutputStream(tmp)
            val buf = ByteArray(8192)
            var n: Int
            var total = 0
            while (`in`.read(buf).also { n = it } != -1) {
                total += n
                if (total > MAX_IMAGE_BYTES) {
                    out.close()
                    `in`.close()
                    tmp.delete()
                    Log.w(TAG, "image exceeds $MAX_IMAGE_BYTES bytes: $urlStr")
                    return false
                }
                out.write(buf, 0, n)
            }
            out.flush()
            out.close()
            `in`.close()
            return tmp.renameTo(dest)
        } catch (e: Exception) {
            Log.e(TAG, "download failed $urlStr", e)
            return false
        } finally {
            c?.disconnect()
        }
    }

    companion object {
        private const val TAG = "PortalFrame"

        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // Cap a single image download so a hostile/oversized response can't fill the
        // cache disk or blow up decoding (a photo thumbnail is well under this).
        private const val MAX_IMAGE_BYTES = 30 * 1024 * 1024

        /**
         * Compose [src] into a screen-sized frame. [zoomFill] = center-crop to fill (zoom in,
         * cropping the overflow); otherwise the whole photo fit-centred over a blurred fill.
         */
        private fun composeFill(src: Bitmap?, screenW: Int, screenH: Int, zoomFill: Boolean, blurRadius: Int = 3): Bitmap? {
            if (src == null || screenW <= 0 || screenH <= 0) {
                return src
            }
            val out = getReusableBitmap(screenW, screenH) ?: Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
            out.eraseColor(Color.TRANSPARENT)
            val c = Canvas(out)
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
            drawComposed(c, p, src, 0, 0, screenW, screenH, zoomFill, blurRadius)
            if (src != out) {
                releaseToPool(src)
            }
            return out
        }

        /**
         * Two photos sharing the screen, each center-cropped to fill its half (a half-screen photo
         * is always zoomed to fill — the "zoom to fill" setting is only for single photos), with a
         * thin black seam between. [stackVertical] splits top/bottom (landscape photos on a vertical
         * screen); otherwise side-by-side. Recycles [a]/[b].
         */
        private fun composePair(
            a: Bitmap, b: Bitmap, screenW: Int, screenH: Int, stackVertical: Boolean
        ): Bitmap {
            val out = getReusableBitmap(screenW, screenH) ?: Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
            out.eraseColor(Color.TRANSPARENT)
            val c = Canvas(out)
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
            val seam = Paint()
            seam.color = Color.BLACK
            if (stackVertical) {
                val gap = max(2, screenH / 300)
                val half = (screenH - gap) / 2
                drawComposed(c, p, a, 0, 0, screenW, half, true)
                drawComposed(c, p, b, 0, half + gap, screenW, screenH - half - gap, true)
                c.drawRect(0f, half.toFloat(), screenW.toFloat(), (half + gap).toFloat(), seam)
            } else {
                val gap = max(2, screenW / 300)
                val half = (screenW - gap) / 2
                drawComposed(c, p, a, 0, 0, half, screenH, true)
                drawComposed(c, p, b, half + gap, 0, screenW - half - gap, screenH, true)
                c.drawRect(half.toFloat(), 0f, (half + gap).toFloat(), screenH.toFloat(), seam)
            }
            releaseToPool(a)
            releaseToPool(b)
            return out
        }

        /**
         * Draw [src] into the rect ([left],[top],[w]×[h]). [zoomFill] = center-crop to FILL the
         * rect (zoom in, crop the overflow). Otherwise the whole photo is fit-centred (never
         * cropped) over a blurred, center-cropped fill behind a gentle scrim. Does not recycle [src].
         */
        private fun drawComposed(
            c: Canvas, p: Paint, src: Bitmap?,
            left: Int, top: Int, w: Int, h: Int, zoomFill: Boolean,
            blurRadius: Int = 3
        ) {
            if (src == null || w <= 0 || h <= 0) {
                return
            }
            val dst = Rect(left, top, left + w, top + h)
            if (zoomFill) {
                drawSmoothScaled(c, p, src, centerCropRect(src.width, src.height, w, h), dst)
                return
            }
            // Blurred background: center-crop into a tiny bitmap, blur, draw upscaled.
            val bw = max(1, w / 16)
            val bh = max(1, h / 16)
            val small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            Canvas(small).drawBitmap(
                src, centerCropRect(src.width, src.height, bw, bh), Rect(0, 0, bw, bh), p
            )
            boxBlur(small, blurRadius, 2)
            c.drawBitmap(small, Rect(0, 0, bw, bh), dst, p)
            small.recycle()

            val scrim = Paint()
            scrim.color = Color.argb(70, 0, 0, 0) // gentle scrim behind the photo
            c.drawRect(dst, scrim)

            // Sharp, anti-aliased foreground: whole photo, fit-centered (no crop).
            val fc = fitCenterRect(src.width, src.height, w, h)
            fc.offset(left.toFloat(), top.toFloat())
            val targetDst = Rect(fc.left.roundToInt(), fc.top.roundToInt(), fc.right.roundToInt(), fc.bottom.roundToInt())
            drawSmoothScaled(c, p, src, Rect(0, 0, src.width, src.height), targetDst)
        }

        /**
         * Smooth progressive downscaling: when downscaling by > 1.4x, downscaling in progressive
         * 2x steps averages 4-16 source pixels together, naturally suppressing camera sensor noise
         * and preventing the harsh aliased grain that single-step Skia bilinear downsampling produces.
         */
        private fun drawSmoothScaled(
            c: Canvas, p: Paint, src: Bitmap,
            srcRect: Rect, dstRect: Rect
        ) {
            val sw = srcRect.width()
            val sh = srcRect.height()
            val dw = dstRect.width()
            val dh = dstRect.height()
            if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return

            if (sw > dw * 1.4f && sh > dh * 1.4f) {
                try {
                    var curW = sw
                    var curH = sh
                    val isEntire = srcRect.left == 0 && srcRect.top == 0 && srcRect.width() == src.width && srcRect.height() == src.height
                    var working = if (isEntire) src else Bitmap.createBitmap(src, srcRect.left, srcRect.top, srcRect.width(), srcRect.height())
                    val cropped = if (!isEntire) working else null

                    while (curW / 2 >= dw && curH / 2 >= dh) {
                        curW /= 2
                        curH /= 2
                        val next = Bitmap.createScaledBitmap(working, curW, curH, true)
                        if (working !== src && working !== cropped) {
                            working.recycle()
                        }
                        working = next
                    }

                    c.drawBitmap(working, null, dstRect, p)
                    if (working !== src && working !== cropped) {
                        working.recycle()
                    }
                    cropped?.recycle()
                    return
                } catch (_: Throwable) {
                    // Fallback to direct drawBitmap on memory pressure
                }
            }
            c.drawBitmap(src, srcRect, dstRect, p)
        }

        private fun centerCropRect(sw: Int, sh: Int, dw: Int, dh: Int): Rect {
            val srcA = sw / sh.toFloat()
            val dstA = dw / dh.toFloat()
            val cw: Int
            val ch: Int
            val cx: Int
            val cy: Int
            if (srcA > dstA) {
                ch = sh
                cw = (sh * dstA).roundToInt()
                cx = (sw - cw) / 2
                cy = 0
            } else {
                cw = sw
                ch = (sw / dstA).roundToInt()
                cx = 0
                cy = (sh - ch) / 2
            }
            return Rect(cx, cy, cx + cw, cy + ch)
        }

        private fun fitCenterRect(sw: Int, sh: Int, dw: Int, dh: Int): RectF {
            val scale = min(dw / sw.toFloat(), dh / sh.toFloat())
            val w = sw * scale
            val h = sh * scale
            val left = (dw - w) / 2f
            val top = (dh - h) / 2f
            return RectF(left, top, left + w, top + h)
        }

        private fun boxBlur(bmp: Bitmap, radius: Int, passes: Int) {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) {
                return
            }
            val a = IntArray(w * h)
            val b = IntArray(w * h)
            bmp.getPixels(a, 0, w, 0, 0, w, h)
            for (p in 0 until passes) {
                boxH(a, b, w, h, radius)
                boxV(b, a, w, h, radius)
            }
            bmp.setPixels(a, 0, w, 0, 0, w, h)
        }

        private fun boxH(`in`: IntArray, out: IntArray, w: Int, h: Int, r: Int) {
            val radius = r.coerceAtLeast(1)
            val div = radius * 2 + 1
            for (y in 0 until h) {
                val row = y * w
                val fv = `in`[row]

                var aa = ((fv ushr 24) and 0xff) * (radius + 1)
                var rr = ((fv shr 16) and 0xff) * (radius + 1)
                var gg = ((fv shr 8) and 0xff) * (radius + 1)
                var bb = (fv and 0xff) * (radius + 1)

                for (i in 0 until radius) {
                    val p = `in`[row + minOf(w - 1, i + 1)]
                    aa += (p ushr 24) and 0xff
                    rr += (p shr 16) and 0xff
                    gg += (p shr 8) and 0xff
                    bb += p and 0xff
                }

                for (x in 0 until w) {
                    out[row + x] = ((aa / div) shl 24) or ((rr / div) shl 16) or ((gg / div) shl 8) or (bb / div)

                    val p1 = `in`[row + minOf(w - 1, x + radius + 1)]
                    val p2 = if (x - radius >= 0) `in`[row + x - radius] else fv

                    aa += ((p1 ushr 24) and 0xff) - ((p2 ushr 24) and 0xff)
                    rr += ((p1 shr 16) and 0xff) - ((p2 shr 16) and 0xff)
                    gg += ((p1 shr 8) and 0xff) - ((p2 shr 8) and 0xff)
                    bb += (p1 and 0xff) - (p2 and 0xff)
                }
            }
        }

        private fun boxV(`in`: IntArray, out: IntArray, w: Int, h: Int, r: Int) {
            val radius = r.coerceAtLeast(1)
            val div = radius * 2 + 1
            for (x in 0 until w) {
                val fv = `in`[x]

                var aa = ((fv ushr 24) and 0xff) * (radius + 1)
                var rr = ((fv shr 16) and 0xff) * (radius + 1)
                var gg = ((fv shr 8) and 0xff) * (radius + 1)
                var bb = (fv and 0xff) * (radius + 1)

                for (i in 0 until radius) {
                    val p = `in`[minOf(h - 1, i + 1) * w + x]
                    aa += (p ushr 24) and 0xff
                    rr += (p shr 16) and 0xff
                    gg += (p shr 8) and 0xff
                    bb += p and 0xff
                }

                for (y in 0 until h) {
                    out[y * w + x] = ((aa / div) shl 24) or ((rr / div) shl 16) or ((gg / div) shl 8) or (bb / div)

                    val p1 = `in`[minOf(h - 1, y + radius + 1) * w + x]
                    val p2 = if (y - radius >= 0) `in`[(y - radius) * w + x] else fv

                    aa += ((p1 ushr 24) and 0xff) - ((p2 ushr 24) and 0xff)
                    rr += ((p1 shr 16) and 0xff) - ((p2 shr 16) and 0xff)
                    gg += ((p1 shr 8) and 0xff) - ((p2 shr 8) and 0xff)
                    bb += (p1 and 0xff) - (p2 and 0xff)
                }
            }
        }

        private const val MAX_POOL_SIZE = 3
        private val bitmapPool = Collections.synchronizedList(ArrayList<Bitmap>())

        fun getReusableBitmap(width: Int, height: Int): Bitmap? {
            if (width <= 0 || height <= 0) return null
            val reqBytes = width * height * 4
            synchronized(bitmapPool) {
                val it = bitmapPool.iterator()
                while (it.hasNext()) {
                    val b = it.next()
                    if (!b.isRecycled && b.isMutable && b.allocationByteCount >= reqBytes) {
                        it.remove()
                        try {
                            b.reconfigure(width, height, Bitmap.Config.ARGB_8888)
                            return b
                        } catch (_: Throwable) {
                            b.recycle()
                        }
                    } else if (b.isRecycled) {
                        it.remove()
                    }
                }
            }
            return null
        }

        fun releaseToPool(b: Bitmap?) {
            if (b == null || b.isRecycled || !b.isMutable) return
            synchronized(bitmapPool) {
                if (bitmapPool.size < MAX_POOL_SIZE && !bitmapPool.contains(b)) {
                    bitmapPool.add(b)
                } else {
                    b.recycle()
                }
            }
        }

        fun clearPool() {
            synchronized(bitmapPool) {
                bitmapPool.forEach {
                    if (!it.isRecycled) it.recycle()
                }
                bitmapPool.clear()
            }
        }

        private fun md5(s: String): String {
            return try {
                val md = MessageDigest.getInstance("MD5")
                val d = md.digest(s.toByteArray(charset("UTF-8")))
                String.format("%032x", BigInteger(1, d))
            } catch (e: Exception) {
                Integer.toHexString(s.hashCode())
            }
        }
    }
}
