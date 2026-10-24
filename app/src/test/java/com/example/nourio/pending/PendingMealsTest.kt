package com.example.nourio.pending

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.nourio.data.FoodItem
import com.example.nourio.nutrition.Analysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The background queue: offline photos, and meals whose analysis took too long on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingMealsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun clear() = File(ctx.filesDir, "pending").deleteRecursively().let { }

    @Test
    fun aSlowDescriptionIsQueuedToBeAddedAutomatically() {
        val p = PendingMeals.addText(ctx, "rice and chicken curry", 1_000L)
        val back = PendingMeals.get(ctx, p.id)!!
        assertEquals("rice and chicken curry", back.text)
        assertNull(back.photo)
        assertTrue(back.autoSave)
        assertEquals(PendingStatus.WAITING, back.status)
    }

    @Test
    fun aSlowPhotoKeepsItsPhotoAndIsAutoSaved() {
        val photo = File(ctx.cacheDir, "meal.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val p = PendingMeals.add(ctx, photo, 2_000L, autoSave = true)
        val back = PendingMeals.get(ctx, p.id)!!
        assertTrue(back.photo!!.exists())
        assertTrue(back.autoSave)
        assertNull(back.text)
    }

    @Test
    fun anOfflinePhotoStillWaitsForReview() {
        val photo = File(ctx.cacheDir, "meal2.jpg").apply { writeBytes(byteArrayOf(1)) }
        val p = PendingMeals.add(ctx, photo, 3_000L)
        assertFalse(PendingMeals.get(ctx, p.id)!!.autoSave)
    }

    @Test
    fun aFailedMealCanBeRequeued() {
        val p = PendingMeals.addText(ctx, "toast", 4_000L, autoSave = false)
        PendingMeals.saveFailure(ctx, p.id, "busy")
        PendingMeals.requeue(ctx, p.id)
        val back = PendingMeals.get(ctx, p.id)!!
        assertEquals(PendingStatus.WAITING, back.status)
        assertNull(back.error)
        assertTrue(back.autoSave)
    }

    @Test
    fun anAnalyzedDescriptionKeepsItsText() {
        val p = PendingMeals.addText(ctx, "an apple", 5_000L, autoSave = false)
        PendingMeals.saveResult(ctx, p.id, Analysis("Apple", listOf(FoodItem("Apple", "Apples, raw", 182.0, 52.0, 0.3, 0.2, 0.0, 14.0, 10.0, 2.4, 1.0))))
        val back = PendingMeals.get(ctx, p.id)!!
        assertEquals("an apple", back.text)
        assertEquals("Apple", back.analysis!!.description)
    }

    @Test
    fun entriesSavedBeforeThisUpdateStillLoad() {
        // 1.1.0 wrote photo entries without "text" or "autoSave"
        val dir = File(ctx.filesDir, "pending").apply { mkdirs() }
        File(dir, "42.jpg").writeBytes(byteArrayOf(1))
        File(dir, "42.json").writeText("""{"id":42,"mealTs":1,"status":"WAITING","error":null}""")
        val back = PendingMeals.get(ctx, 42)!!
        assertFalse(back.autoSave)
        assertNull(back.text)
        assertTrue(back.photo!!.exists())
    }
}
