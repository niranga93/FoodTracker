package com.example.nourio.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.nourio.R

/**
 * Every notification channel the app uses, created at start-up. Android only lists an app's
 * notification categories once its channels exist, so creating them lazily (just before the first
 * notification) left Nourio's notification settings empty until something happened to fire.
 */
object NotificationChannels {
    const val TIPS = "daily_tips"
    const val REMINDERS = "reminders"
    const val MEAL_READY = "meal_ready"
    const val EXERCISE = "exercise"

    private const val GREEN = 0xFF0A7A50.toInt()

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(TIPS, "Daily health tips", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "One tip a day, based on patterns in your food, sleep, activity and heart data" },
                NotificationChannel(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Fasting window, water and evening meal reminders. Switch each one off in Settings." },
                NotificationChannel(EXERCISE, "Exercise suggestions", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "In the early evening, an activity that would balance what you've eaten today" },
                NotificationChannel(MEAL_READY, "Meals ready to review", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "When a photo taken offline has been analyzed" },
            )
        )
    }

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** The app's look for every notification: the monochrome leaf icon, tinted Nourio green. */
    fun builder(context: Context, channel: String): NotificationCompat.Builder {
        ensure(context)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(GREEN)
    }

    /** From Settings: proves the whole path works on this phone. */
    fun sendTest(context: Context): Boolean {
        if (!allowed(context)) return false
        val n = builder(context, REMINDERS)
            .setContentTitle("Notifications are working")
            .setContentText("This is how Nourio's tips and reminders will appear.")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(7499, n)
        return true
    }
}
