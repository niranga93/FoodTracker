package com.example.nourio.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.nourio.data.FoodItem
import com.example.nourio.data.MealDb
import com.example.nourio.data.WaterStore
import com.example.nourio.insights.InsightPrefs
import com.example.nourio.reminders.Reminder
import com.example.nourio.reminders.ReminderPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActionsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun reportIsParsed() {
        val p = Plan.parse("""{"kind":"action","action":{"type":"report_bug","title":"Scanner crashes","details":"It crashes on milk."},"answer":"Here's a draft."}""")
        assertEquals(ActionType.REPORT_BUG, p.action!!.type)
        assertEquals("It crashes on milk.", p.action!!.details)
    }

    @Test
    fun deleteWithoutScopeOrConsentWithoutNameIsDropped() {
        assertNull(Plan.parse("""{"kind":"action","action":{"type":"delete_data"},"answer":"x"}""").action)
        assertNull(Plan.parse("""{"kind":"action","action":{"type":"withdraw_consent"},"answer":"x"}""").action)
    }

    private fun meal(db: MealDb, daysAgo: Long) {
        val ts = LocalDate.now().minusDays(daysAgo).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        db.insert("Lunch", listOf(FoodItem("Rice", "Rice", 100.0, 130.0, 2.7, 0.3, 0.1, 28.0, 0.1, 0.4, 1.0)), ts)
    }

    @Test
    fun deletingADateRangeLeavesOtherDays() = runBlocking {
        val db = MealDb(ctx)
        meal(db, 0); meal(db, 1); meal(db, 10)
        val a = ProposedAction(ActionType.DELETE_DATA, scope = DeleteScope.MEALS, range = Range(RangeKind.LAST_DAYS, 3))
        assertEquals(2, Actions.preview(ctx, a).count)
        Actions.delete(ctx, a)
        assertEquals(1, MealDb(ctx).count())
    }

    @Test
    fun clearingWaterKeepsTheGoal() = runBlocking {
        val w = WaterStore(ctx)
        w.goalMl = 2500; w.add(0, 500); w.add(-1, 750)
        Actions.delete(ctx, ProposedAction(ActionType.DELETE_DATA, scope = DeleteScope.WATER))
        assertEquals(0, w.get(0)); assertEquals(0, w.get(-1)); assertEquals(2500, w.goalMl)
    }

    @Test
    fun withdrawingNotificationsTurnsOffTipsAndReminders() = runBlocking {
        InsightPrefs(ctx).notificationsOn = true
        ReminderPrefs(ctx).set(Reminder.WATER, true)
        Actions.withdraw(ctx, Consent.NOTIFICATIONS)
        assertFalse(InsightPrefs(ctx).notificationsOn)
        assertFalse(ReminderPrefs(ctx).isOn(Reminder.WATER))
    }

    @Test
    fun withdrawingTheAssistantAsksAgain() = runBlocking {
        AssistantPrefs(ctx).consented = true
        val msg = Actions.withdraw(ctx, Consent.ASSISTANT)
        assertFalse(AssistantPrefs(ctx).consented)
        assertTrue(msg.startsWith("Done."))
    }
}
