package com.portalhacks.frame

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.Collections
import java.util.LinkedHashMap

class FaceFocusResult(
    val fx: Float,
    val fy: Float,
    val zoomFactor: Float
)

internal object FaceFocus {

    private const val TAG = "PortalFrame"
    private const val MAX_DIM = 480 // scale down target for detection accuracy and speed

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .build()

    private val detector = FaceDetection.getClient(options)

    // Identity-based/Instance-based cache for Bitmaps to their face detection results.
    // LruCache sized to 20 is sufficient for holds / transitions and prefetch.
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<Bitmap, FaceFocusResult>(20, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Bitmap, FaceFocusResult>?): Boolean {
                return size > 20
            }
        }
    )

    /**
     * Runs face detection on a background thread and caches the result.
     */
    @JvmStatic
    fun detectAndCache(src: Bitmap?) {
        if (src == null || src.width < 2 || src.height < 2) {
            return
        }
        if (cache.containsKey(src)) {
            return
        }

        var scaled: Bitmap? = null
        try {
            // Keep detection lightweight by downscaling moderately
            val scale = minOf(1f, MAX_DIM / maxOf(src.width, src.height).toFloat())
            val w = maxOf(2, Math.round(src.width * scale))
            val h = maxOf(2, Math.round(src.height * scale))
            scaled = Bitmap.createScaledBitmap(src, w, h, true)

            val inputImage = InputImage.fromBitmap(scaled, 0)
            val task = detector.process(inputImage)

            // Synchronously block the background thread to perform detection
            val faces = Tasks.await(task)

            val result: FaceFocusResult?
            val n = faces.size
            if (n <= 0) {
                result = null
            } else {
                var totalX = 0f
                var totalY = 0f
                for (face in faces) {
                    totalX += face.boundingBox.exactCenterX()
                    totalY += face.boundingBox.exactCenterY()
                }
                val avgX = totalX / n
                val avgY = totalY / n

                val fx = clamp01(avgX / w)
                val fy = clamp01(avgY / h)

                // Dynamic zoom-out factor based on number of faces:
                // 1 face -> 1.0f (full default zoom)
                // 2 faces -> 0.8f (slightly zoomed out)
                // 3 faces -> 0.6f (zoomed out more)
                // 4 faces -> 0.4f
                // 5+ faces -> 0.2f (minimum zoom, showing full group)
                val zoomFactor = maxOf(0.2f, 1.0f - 0.2f * (n - 1))

                result = FaceFocusResult(fx, fy, zoomFactor)
                Log.i(TAG, "FaceFocus (ML Kit): $n face(s) found, centroid=($fx, $fy), zoomFactor=$zoomFactor")
            }

            if (result != null) {
                cache[src] = result
            }
        } catch (t: Throwable) {
            Log.w(TAG, "FaceFocus ML Kit detection failed: $t")
        } finally {
            if (scaled != null && scaled !== src) {
                scaled.recycle()
            }
        }
    }

    /**
     * Retrieve the pre-computed face focus result from the cache.
     */
    @JvmStatic
    fun find(src: Bitmap?): FaceFocusResult? {
        if (src == null) return null
        return cache[src]
    }

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v
}
