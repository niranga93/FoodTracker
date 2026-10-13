package com.example.foodtracker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.foodtracker.ai.ImageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

/** Small JPEG thumbnails of meal photos, stored as files/thumbs/<mealId>.jpg. */
object MealPhotos {
    private const val SIZE = 480

    // Outlives the review screen, which closes right after saving
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dir(context: Context) = File(context.filesDir, "thumbs").apply { mkdirs() }
    private fun fileFor(context: Context, mealId: Long) = File(dir(context), "$mealId.jpg")

    /** Saves a thumbnail of [photo] for the meal in the background, then deletes the full photo. */
    fun saveAsync(context: Context, mealId: Long, photo: File) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val bmp = ImageUtil.loadUpright(photo, SIZE) ?: return@runCatching
                val scale = SIZE.toFloat() / max(bmp.width, bmp.height)
                val small = if (scale < 1f)
                    Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                else bmp
                fileFor(app, mealId).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            }
            photo.delete()
        }
    }

    /** Blocking: call from Dispatchers.IO. */
    fun load(context: Context, mealId: Long): Bitmap? {
        val f = fileFor(context, mealId)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    fun delete(context: Context, mealId: Long) {
        fileFor(context, mealId).delete()
    }
}
