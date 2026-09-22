package com.freedomfighter.readersbooks.books

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.util.zip.ZipFile

/**
 * Images of one book, read from its EPUB on demand. Sizes are cached for pagination, decoded
 * bitmaps in a small memory cache; both are keyed by the zip entry path.
 */
class ImageStore(private val file: File) {
    class Size(val width: Int, val height: Int)

    private val sizes = HashMap<String, Size?>()
    private val bitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun bytes(source: String): ByteArray? = runCatching {
        ZipFile(file).use { zip -> zip.getEntry(source)?.let { zip.getInputStream(it).use { s -> s.readBytes() } } }
    }.getOrNull()

    /** Pixel size, or null when the entry is missing or not an image. Blocking but quick. */
    @Synchronized
    fun size(source: String): Size? = sizes.getOrPut(source) {
        val b = bytes(source) ?: return@getOrPut null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth > 0 && o.outHeight > 0) Size(o.outWidth, o.outHeight) else null
    }

    /** Decoded at about the wanted width (power-of-two subsampling). Blocking: call off the main thread. */
    fun bitmap(source: String, wantedWidth: Int): Bitmap? {
        val key = "$source@$wantedWidth"
        bitmaps.get(key)?.let { return it }
        val b = bytes(source) ?: return null
        val size = size(source) ?: return null
        var sample = 1
        while (size.width / (sample * 2) >= wantedWidth && sample < 16) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        val bmp = runCatching { BitmapFactory.decodeByteArray(b, 0, b.size, o) }.getOrNull() ?: return null
        bitmaps.put(key, bmp)
        return bmp
    }
}
