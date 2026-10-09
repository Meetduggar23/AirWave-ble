package com.airwave.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * v3.2.7 (A3): sampled image decoding shared by the chat screens and the
 * message adapter. Decoding full-size gallery photos on the main thread was
 * causing OOM kills on low-RAM devices and jank while scrolling.
 */
object ImageUtils {

    /** Longest side allowed for images sent over BLE (keeps payloads small). */
    const val MAX_SEND_DIM = 1280

    /** Longest side allowed for message-list thumbnails. */
    const val MAX_THUMB_DIM = 1024

    private fun sampleFor(longSide: Int, maxDim: Int): Int {
        var sample = 1
        while (longSide / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    /**
     * Decode a gallery image so its longest side is at most ~2x [maxDim]
     * (power-of-two sampling). Returns null when the image cannot be read.
     */
    fun decodeSampledUri(
        resolver: ContentResolver,
        uri: Uri,
        maxDim: Int = MAX_SEND_DIM
    ): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(maxOf(bounds.outWidth, bounds.outHeight), maxDim)
            }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Decode a cached image file for display in the message list with the
     * same power-of-two sampling rule.
     */
    fun decodeSampledFile(path: String, maxDim: Int = MAX_THUMB_DIM): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(maxOf(bounds.outWidth, bounds.outHeight), maxDim)
            }
            BitmapFactory.decodeFile(path, opts)
        }
    } catch (_: Exception) {
        null
    }
}
