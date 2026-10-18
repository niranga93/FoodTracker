package com.example.nourio.reminders

import android.annotation.SuppressLint
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.nourio.MainActivity
import com.example.nourio.R
import com.example.nourio.data.FeaturePrefs
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.data.Totals
import com.example.nourio.data.WaterStore
import com.example.nourio.data.WeightDb
import com.example.nourio.data.totals
import com.example.nourio.fasting.Fasting
import com.example.nourio.fasting.FastingPrefs
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * The reminders the user can switch on and off in Settings. Water and dinner are on by default;
 * fasting is off until the user turns it on, since not everyone fasts.
 */
enum class Reminder(val key: String, val title: String, val hint: String, val requestCode: Int, val default: Boolean) {
    FAST_START("rem_fast", "Fasting window", "When your eating window closes and when your fast reaches its goal", 8101, false),
    FAST_DONE("rem_fast", "", "", 8102, false),     // shares the FAST_START switch
    WATER("rem_water", "Hydration", "At 3 pm if you've had less than half your water goal", 8103, true),
    DINNER("rem_dinner", "Evening meal", "At 8 pm if nothing has been logged since 4 pm", 8104, true),
    EXERCISE("rem_exercise", "Exercise suggestion", "At 6 pm, an activity that would balance today when you've eaten more than you've burned", 8105, true),
    DINNER_PLAN("rem_plan", "Dinner planner", "At 5 pm, the one change for dinner that would round off today: protein, fibre, salt, sugar or calories", 8106, true),
    WEEKLY("rem_week", "Weekly review", "Sunday at 6 pm, your week against your goals and one thing to focus on next", 8107, true),
    WEIGH_IN("rem_weigh", "Weigh-in", "At 8 am, when it's been a week since your last weigh-in", 8108, true),
    // Same switch as "Daily tips" on the Insights page
    DAILY_TIP("notify", "Daily smart tip", "At 8 am, one tip from your past week: a pattern found in your data, or advice from how you ate", 8109, true);

    companion object {
        /** One switch per row in Settings. */
        val SWITCHES = listOf(DAILY_TIP, DINNER_PLAN, WEEKLY, WATER, DINNER, WEIGH_IN, EXERCISE, FAST_START)
    }
}

class ReminderPrefs(context: Context) {
    private val p = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    // What was already sent, kept apart so the backed-up settings file doesn't change every day
    private val sent = context.getSharedPreferences("reminders_sent", Context.MODE_PRIVATE)

    // The daily tip keeps its switch where it always was, in the Insights settings
    private val insights = context.getSharedPreferences("insights", Context.MODE_PRIVATE)
    private fun store(r: Reminder) = if (r == Reminder.DAILY_TIP) insights else p

    fun isOn(r: Reminder): Boolean = store(r).getBoolean(r.key, r.default)
    fun set(r: Reminder, on: Boolean) = store(r).edit { putBoolean(r.key, on) }

    fun wasSent(r: Reminder, instance: Long) = sent.getLong(r.name, -1L) == instance
    fun markSent(r: Reminder, instance: Long) = sent.edit { putLong(r.name, instance) }
}

/**
 * Gentle local reminders. Inexact alarms (no exact-alarm permission, batched by the system so they cost
 * almost no battery) wake [ReminderReceiver], which checks the condition again before notifying: the
 * meal log may have changed since the alarm was set. Nothing leaves the phone.
 */
object Reminders {
    const val EXTRA_KIND = "reminder"
    const val EXTRA_OPEN_TAB = "open_tab"

    private const val NOTIFICATION_ID = 7401
    private const val WATER_HOUR = 15
    private const val DINNER_HOUR = 20
    private const val DINNER_SINCE_HOUR = 16
    private const val DAY_START_HOUR = 4             // an eating day runs from 4 am to 4 am
    private const val PLAN_HOUR = 17                 // early enough to shop or change what's cooked
    private const val WEEK_HOUR = 18                 // Sunday evening, before the new week
    private const val WEIGH_HOUR = 8                 // a morning reading, before breakfast
    private const val TIP_HOUR = 8                   // the daily tip, to plan the day by
    private const val HOUR_MS = 3_600_000L
    private const val LATE_MS = 90 * 60_000L       // a delayed alarm still counts this long after it was due

    /** Sets (or clears) the next alarm for every reminder. Safe to call often. */
    fun schedule(context: Context) {
        val ctx = context.applicationContext
        thread(name = "reminders") { runCatching { scheduleNow(ctx) } }
    }

    private fun scheduleNow(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        for (r in Reminder.entries) {
            val pi = alarmIntent(context, r)
            val at = if (enabled(context, r)) nextDue(context, r, now) else null
            if (at == null) am.cancel(pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    // Each reminder has its own switch; hiding a Home line doesn't silence it
    private fun enabled(context: Context, r: Reminder): Boolean = ReminderPrefs(context).isOn(r)

    private fun alarmIntent(context: Context, r: Reminder): PendingIntent =
        PendingIntent.getBroadcast(
            context, r.requestCode,
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_KIND, r.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun atHour(date: LocalDate, hour: Int, zone: ZoneId) =
        date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    /** The next daily time at [hour], today if it's still ahead. */
    private fun daily(hour: Int, now: Long): Long {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val t = atHour(today, hour, zone)
        return if (t > now) t else atHour(today.plusDays(1), hour, zone)
    }

    /** The next Sunday at [hour], today if it's Sunday and still ahead. */
    private fun sunday(hour: Int, now: Long): Long {
        val zone = ZoneId.systemDefault()
        var d = LocalDate.now(zone)
        while (d.dayOfWeek != java.time.DayOfWeek.SUNDAY || atHour(d, hour, zone) <= now) d = d.plusDays(1)
        return atHour(d, hour, zone)
    }

    /** Daily totals for each day from [from] (inclusive) to [to] (exclusive), as day starts. */
    private fun totalsBetween(context: Context, from: Long, to: Long): Totals =
        MealDb(context).between(from, to).flatMap { it.items }.totals()

    /** Within a few hours after [at]: a delayed alarm still counts, a very late one doesn't. */
    private fun around(now: Long, at: Long) = now in at - 5 * 60_000L..at + 3 * HOUR_MS

    /** Times of fast-breaking meals in the last two days, oldest first. */
    private fun recentEating(context: Context, now: Long): List<Long> {
        val db = MealDb(context)
        return try {
            Fasting.eatingTimes(db.between(now - 48 * HOUR_MS, now + HOUR_MS))
        } finally {
            db.close()
        }
    }

    /**
     * When today's eating window opened: the first meal from 4 am on. Meals between midnight and 4 am
     * belong to the evening before; counting a 1 am snack as the day's first meal closed the
     * "window" at 9 am.
     */
    internal fun windowStart(times: List<Long>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val t = java.time.Instant.ofEpochMilli(now).atZone(zone)
        val day = if (t.hour < DAY_START_HOUR) t.toLocalDate().minusDays(1) else t.toLocalDate()
        val start = atHour(day, DAY_START_HOUR, zone)
        return times.firstOrNull { it >= start }
    }

    /**
     * When [r] is next due. For the fasting reminders that comes from the meal log:
     * the window closes (24 - goal) hours after the day's first meal, and the fast is done goal hours
     * after the last meal. Null when there's nothing to remind about.
     */
    private fun nextDue(context: Context, r: Reminder, now: Long): Long? = when (r) {
        Reminder.WATER -> daily(WATER_HOUR, now)
        Reminder.DINNER -> daily(DINNER_HOUR, now)
        Reminder.EXERCISE -> daily(com.example.nourio.exercise.ExerciseNudge.HOUR, now)
        Reminder.DINNER_PLAN -> daily(PLAN_HOUR, now)
        Reminder.WEIGH_IN -> daily(WEIGH_HOUR, now)
        Reminder.WEEKLY -> sunday(WEEK_HOUR, now)
        Reminder.DAILY_TIP -> daily(TIP_HOUR, now)
        Reminder.FAST_START, Reminder.FAST_DONE -> fastDue(context, r, now)?.takeIf { it > now }
    }

    private fun fastDue(context: Context, r: Reminder, now: Long): Long? {
        val goalH = FastingPrefs(context).goalH
        val times = recentEating(context, now)
        return when (r) {
            Reminder.FAST_START -> windowStart(times, now)?.let { it + ((24 - goalH) * HOUR_MS).toLong() }
            Reminder.FAST_DONE -> times.lastOrNull()?.let { it + (goalH * HOUR_MS).toLong() }
            else -> null
        }
    }

    /** Called by the alarm: checks the condition again, notifies once per occasion, then sets the next alarm. */
    internal fun fire(context: Context, r: Reminder) {
        try {
            if (!enabled(context, r)) return
            val now = System.currentTimeMillis()
            if (r == Reminder.DAILY_TIP) { dailyTip(context, now); return }
            val message = check(context, r, now) ?: return
            val prefs = ReminderPrefs(context)
            if (prefs.wasSent(r, message.instance)) return
            // Counted as sent only once it's really shown; while notifications are blocked it stays due
            if (notify(context, message)) prefs.markSent(r, message.instance)
        } finally {
            scheduleNow(context)
        }
    }

    /**
     * 8 am: a pattern found by Insights when there is a new one (with Accept / Not now), otherwise
     * advice from the past week's log ([DailyTip]). Once a day.
     */
    private fun dailyTip(context: Context, now: Long) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        if (!around(now, atHour(today, TIP_HOUR, zone))) return
        val prefs = ReminderPrefs(context)
        if (prefs.wasSent(Reminder.DAILY_TIP, today.toEpochDay())) return
        if (!NotificationChannels.allowed(context)) return

        val insight = com.example.nourio.insights.InsightPrefs(context)
        val db = MealDb(context)
        // Patterns first: worked out while the app was open (they include Health Connect data), or fresh from the log
        // A background thread of the alarm's receiver, so waiting for the history here is fine
        val tips = insight.savedTips(2 * 86_400_000L) ?: runCatching {
            com.example.nourio.insights.InsightEngine.analyse(
                kotlinx.coroutines.runBlocking { com.example.nourio.insights.HistoryLoader.load(context, db) },
                com.example.nourio.insights.InsightGoals.current(context), today
            ).tips
        }.getOrDefault(emptyList())
        val pattern = insight.open(tips).firstOrNull { now - insight.lastNotified(it.id) > 3 * 86_400_000L }
        if (pattern != null) {
            com.example.nourio.insights.InsightWorker.notify(context, pattern)
            insight.markNotified(pattern.id)
        } else {
            // No pattern to report: advice from the 7 days before today
            val water = WaterStore(context)
            val days = (7 downTo 1).map { back ->
                val d = today.minusDays(back.toLong())
                val meals = db.between(atHour(d, 0, zone), atHour(d.plusDays(1), 0, zone))
                val late = atHour(d, 20, zone)
                TipDay(
                    totals = meals.flatMap { it.items }.totals(),
                    lateKcal = meals.filter { it.timestamp >= late }.flatMap { it.items }.totals().kcal,
                    waterMl = water.get(-back),
                )
            }
            val lastId = context.getSharedPreferences("reminders_sent", Context.MODE_PRIVATE).getString("tip_last", null)
            val tip = DailyTip.pick(days, GoalStore(context).load(), water.goalMl, today.dayOfYear, lastId)
            notify(context, Message(today.toEpochDay(), tip.title, tip.body, "home", NotificationChannels.TIPS, private = true))
            context.getSharedPreferences("reminders_sent", Context.MODE_PRIVATE).edit { putString("tip_last", tip.id) }
        }
        prefs.markSent(Reminder.DAILY_TIP, today.toEpochDay())
    }

    private data class Message(
        val instance: Long, val title: String, val text: String, val tab: String,
        val channel: String = NotificationChannels.REMINDERS,
        val private: Boolean = false,       // mentions health details: the lock screen only says one arrived
    )

    private fun check(context: Context, r: Reminder, now: Long): Message? {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayKey = today.toEpochDay()
        when (r) {
            Reminder.WATER -> {
                val water = WaterStore(context)
                val had = water.get(0)
                val goal = water.goalMl
                if (goal <= 0 || had * 2 >= goal) return null
                // Only for people who track water: some logged in the last week
                val recent = (0..7).map { today.minusDays(it.toLong()).toString() }
                if (water.allDays().keys.none { it in recent }) return null
                if (now !in atHour(today, WATER_HOUR, zone) - 5 * 60_000L..atHour(today, WATER_HOUR, zone) + 3 * HOUR_MS) return null
                return Message(
                    todayKey, "Time for some water",
                    if (had == 0) "No water logged yet today. A glass now keeps you on track for ${goal} ml."
                    else "You're at $had of $goal ml. A glass or two now keeps you on track.",
                    "intake"
                )
            }
            Reminder.EXERCISE -> {
                val at = atHour(today, com.example.nourio.exercise.ExerciseNudge.HOUR, zone)
                if (now !in at - 5 * 60_000L..at + 3 * HOUR_MS) return null
                val n = com.example.nourio.exercise.ExerciseNudge.build(context) ?: return null
                return Message(todayKey, n.title, n.text, "burned", NotificationChannels.EXERCISE)
            }
            Reminder.DINNER -> {
                val dinnerAt = atHour(today, DINNER_HOUR, zone)
                if (now !in dinnerAt - 5 * 60_000L..dinnerAt + 3 * HOUR_MS) return null
                val db = MealDb(context)
                val meals = try {
                    db.between(now - 3 * 24 * HOUR_MS, now + HOUR_MS)
                } finally {
                    db.close()
                }
                if (meals.isEmpty()) return null              // not using the app lately: don't nag
                val since = atHour(today, DINNER_SINCE_HOUR, zone)
                if (meals.any { it.timestamp >= since }) return null
                return Message(
                    todayKey, "Had dinner yet?",
                    "Nothing logged since 4 pm. Snap a photo or describe it in a few words to keep your day complete.",
                    "home"
                )
            }
            Reminder.DAILY_TIP -> return null             // sent by dailyTip(), which has its own buttons
            Reminder.DINNER_PLAN -> {
                if (!around(now, atHour(today, PLAN_HOUR, zone))) return null
                val t = totalsBetween(context, atHour(today, 0, zone), now + 60_000)
                val m = NutritionNudges.dinnerPlan(t, GoalStore(context).load()) ?: return null
                return Message(todayKey, m.title, m.body, "home", NotificationChannels.NUTRITION)
            }
            Reminder.WEEKLY -> {
                if (today.dayOfWeek != java.time.DayOfWeek.SUNDAY || !around(now, atHour(today, WEEK_HOUR, zone))) return null
                // The 7 days before today: today is still under way
                val days = (7 downTo 1).map { back ->
                    val d = today.minusDays(back.toLong())
                    totalsBetween(context, atHour(d, 0, zone), atHour(d.plusDays(1), 0, zone))
                }
                val m = NutritionNudges.weeklyReview(days, GoalStore(context).load()) ?: return null
                return Message(todayKey, m.title, m.body, "home", NotificationChannels.NUTRITION)
            }
            Reminder.WEIGH_IN -> {
                if (!around(now, atHour(today, WEIGH_HOUR, zone))) return null
                val m = NutritionNudges.weighIn(WeightDb(context).all().map { it.ts }.sorted(), now) ?: return null
                return Message(todayKey, m.title, m.body, "weight")
            }
            Reminder.FAST_START, Reminder.FAST_DONE -> {
                val due = fastDue(context, r, now) ?: return null
                if (now < due - 5 * 60_000L || now > due + LATE_MS) return null
                val goal = FastingPrefs(context).goalH
                val goalText = Fasting.duration(goal).removeSuffix(" 0 m")
                return if (r == Reminder.FAST_START) Message(
                    due, "Eating window closing",
                    "Your ${Fasting.duration(24 - goal).removeSuffix(" 0 m")} eating window ends now. Your $goalText fast starts after your last bite.",
                    "fasting"
                ) else Message(
                    due, "Fast complete",
                    "You've reached your $goalText fast. Your eating window is open.",
                    "fasting"
                )
            }
        }
    }

    @SuppressLint("MissingPermission")   // allowed() checks POST_NOTIFICATIONS
    private fun notify(context: Context, m: Message): Boolean {
        if (!NotificationChannels.allowed(context)) return false
        val open = PendingIntent.getActivity(
            context, slot(m) + 3,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_TAB, m.tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationChannels.builder(context, m.channel)
            .setContentTitle(m.title)
            .setContentText(m.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .apply {
                if (m.private) setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
                    NotificationChannels.builder(context, m.channel).setContentTitle("New health tip from Nourio").build()
                )
            }
            .build()
        // Each kind has its own slot, so a check-in doesn't replace a reminder still on screen
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + slot(m), n)
        return true
    }

    private fun slot(m: Message) = when (m.channel) {
        NotificationChannels.EXERCISE -> 1
        NotificationChannels.NUTRITION -> 2
        NotificationChannels.TIPS -> 3
        else -> 0
    }
}

/** The reminder alarms. Not exported: only this app's own PendingIntents reach it. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val r = intent.getStringExtra(Reminders.EXTRA_KIND)
            ?.let { runCatching { Reminder.valueOf(it) }.getOrNull() } ?: return
        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "reminder") {
            try {
                runCatching { Reminders.fire(app, r) }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms don't survive a restart, an app update or a clock change, so set them again. */
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Reminders.schedule(context)
        }
    }
}
