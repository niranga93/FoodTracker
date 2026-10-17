package com.example.nourio

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.nourio.data.FoodItem
import com.example.nourio.data.MealDb
import com.example.nourio.data.Profile
import com.example.nourio.data.ProfileStore
import com.example.nourio.exercise.ExerciseNudge
import com.example.nourio.reminders.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyChannelExistsAfterStartUp() {
        NotificationChannels.ensure(ctx)
        val ids = ctx.getSystemService(NotificationManager::class.java).notificationChannels.map { it.id }.toSet()
        assertEquals(setOf("daily_tips", "reminders", "exercise", "nutrition", "meal_ready"), ids)
    }

    private fun profile() = ProfileStore(ctx).save(
        Profile(name = "Test", birthYear = Calendar.getInstance().get(Calendar.YEAR) - 35, sex = "Female", heightCm = 165.0, weightKg = 70.0)
    )

    @Test
    fun noMealsNoExerciseNudge() {
        profile()
        assertNull(ExerciseNudge.build(ctx))
    }

    @Test
    fun aBigSurplusSuggestsSomething() {
        profile()
        // 3,500 kcal of food today, far over any resting burn
        MealDb(ctx).insert("Feast", listOf(FoodItem("Pizza", "Pizza", 1300.0, 270.0, 11.0, 10.0, 4.5, 33.0, 3.6, 2.3, 600.0)))
        val n = ExerciseNudge.build(ctx)
        assertNotNull(n)
        assertTrue(n!!.text, n.text.startsWith("You've eaten about"))
    }
}
