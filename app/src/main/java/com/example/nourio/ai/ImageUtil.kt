package com.example.nourio.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max

object ImageUtil {
    /**
     * Decodes the photo and applies its EXIF rotation so it is always upright.
     * Subsamples while decoding so the longer side stays at least [minLongSide] px,
     * which avoids decoding a full 12-50 MP image only to shrink it.
     */
    fun loadUpright(file: File, minLongSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longSide = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longSide / (sample * 2) >= minLongSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val orientation = runCatching {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private const val MAX_PHOTO_BYTES = 40L * 1024 * 1024

    /**
     * Copies a picked or shared photo into the app's cache so it can be handled like a camera photo.
     * Only content:// URIs from other apps are accepted: a shared file:// URI or one pointing at this
     * app's own provider could otherwise make the app read (and send to Gemini) its private files.
     */
    fun copyToCache(context: Context, uri: Uri): File? {
        if (uri.scheme != "content") return null
        if (uri.authority.orEmpty().startsWith(context.packageName)) return null
        val file = File(context.cacheDir, "meal_${System.currentTimeMillis()}.jpg")
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { src ->
                file.outputStream().use { dst ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_PHOTO_BYTES) error("Photo is too large")
                        dst.write(buf, 0, n)
                    }
                }
            }
            // Reject anything that isn't a decodable image
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Not an image")
            file
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /** How many days ago the photo was taken (0 = today), from its EXIF date. Null if unknown or too old. */
    fun photoDayOffset(file: File): Int? {
        return try {
            val raw = ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: return null
            val taken = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw) ?: return null
            val zone = ZoneId.systemDefault()
            val days = ChronoUnit.DAYS.between(
                LocalDate.now(zone),
                Instant.ofEpochMilli(taken.time).atZone(zone).toLocalDate()
            ).toInt()
            if (days in -365..0) days else null
        } catch (e: Exception) {
            null
        }
    }
}
