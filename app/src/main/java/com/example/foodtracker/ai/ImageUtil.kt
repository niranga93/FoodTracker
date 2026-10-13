package com.example.foodtracker.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

object ImageUtil {
    /** Decodes the photo and applies its EXIF rotation so it is always upright. */
    fun loadUpright(file: File, sampleSize: Int): Bitmap? {
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
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

    /** Copies a picked photo into the app's cache so it can be handled like a camera photo. */
    fun copyToCache(context: Context, uri: Uri): File? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val file = File(context.cacheDir, "meal_${System.currentTimeMillis()}.jpg")
            input.use { src -> file.outputStream().use { dst -> src.copyTo(dst) } }
            file
        } catch (e: Exception) {
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
