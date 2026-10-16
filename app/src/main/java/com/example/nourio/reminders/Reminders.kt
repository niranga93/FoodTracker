package com.example.nourio.reminders

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
import com.example.nourio.data.MealDb
import com.example.nourio.data.WaterStore
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
    EXERCISE("rem_exercise", "Exercise suggestion", "At 6 pm, an activity that would balance today when you've eaten more than you've burned", 8105, true);

    companion object {
        /** One switch per row in Settings. */
        val SWITCHES = listOf(FAST_START, WATER, DINNER, EXERCISE)
    }
}

class ReminderPrefs(context: Context) {
    private val p = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    // What was already sent, kept apart so the backed-up settings file doesn't change every day
    private val sent = context.getSharedPreferences("reminders_sent", Context.MODE_PRIVATE)

    fun isOn(r: Reminder): Boolean = p.getBoolean(r.key, r.default)
    fun set(r: Reminder, on: Boolean) = p.edit { putBoolean(r.key, on) }

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

    private fun enabled(context: Context, r: Reminder): Boolean {
        if (!ReminderPrefs(context).isOn(r)) return false
        // The fasting reminders follow the fasting card being shown at all
        return r !in setOf(Reminder.FAST_START, Reminder.FAST_DONE) || FeaturePrefs(context).isOn("home_fast")
    }

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

    /** Times of fast-breaking meals in the last two days, oldest first. */
    private fun recentEating(context: Context, now: Long): List<Long> {
        val db = MealDb(context)
        return try {
            Fasting.eatingTimes(db.between(now - 48 * HOUR_MS, now + HOUR_MS))
        } finally {
            db.close()
        }
    }

    /** First fast-breaking meal today, if any. */
    private fun firstToday(times: List<Long>): Long? {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
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
        Reminder.FAST_START, Reminder.FAST_DONE -> fastDue(context, r, now)?.takeIf { it > now }
    }

    private fun fastDue(context: Context, r: Reminder, now: Long): Long? {
        val goalH = FastingPrefs(context).goalH
        val times = recentEating(context, now)
        return when (r) {
            Reminder.FAST_START -> firstToday(times)?.let { it + ((24 - goalH) * HOUR_MS).toLong() }
            Reminder.FAST_DONE -> times.lastOrNull()?.let { it + (goalH * HOUR_MS).toLong() }
            else -> null
        }
    }

    /** Called by the alarm: checks the condition again, notifies once per occasion, then sets the next alarm. */
    internal fun fire(context: Context, r: Reminder) {
        try {
            if (!enabled(context, r)) return
            val now = System.currentTimeMillis()
            val message = check(context, r, now) ?: return
            val prefs = ReminderPrefs(context)
            if (prefs.wasSent(r, message.instance)) return
            notify(context, message)
            prefs.markSent(r, message.instance)
        } finally {
            scheduleNow(context)
        }
    }

    private data class Message(
        val instance: Long, val title: String, val text: String, val tab: String,
        val channel: String = NotificationChannels.REMINDERS,
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

    private fun notify(context: Context, m: Message) {
        if (!NotificationChannels.allowed(context)) return
        val open = PendingIntent.getActivity(
            context, if (m.channel == NotificationChannels.EXERCISE) 4 else 3,
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
            .build()
        // The exercise suggestion has its own slot so it doesn't replace a reminder still on screen
        NotificationManagerCompat.from(context).notify(if (m.channel == NotificationChannels.EXERCISE) NOTIFICATION_ID + 1 else NOTIFICATION_ID, n)
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
