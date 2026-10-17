package com.example.nourio.insights

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.nourio.reminders.NotificationChannels
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.nourio.MainActivity
import com.example.nourio.R
import com.example.nourio.data.MealDb
import java.time.LocalDate

/** Once a day: analyse the history and send the top new tip as a notification with Accept / Not now. */
class InsightWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = InsightPrefs(ctx)
        if (!prefs.notificationsOn) return Result.success()
        // Prefer the tips worked out while the app was open: they include Health Connect data,
        // which a background worker can't read. Fall back to a fresh (food-only) analysis.
        val tips = prefs.savedTips(2 * 86_400_000L) ?: run {
            val db = MealDb(ctx)
            try {
                InsightEngine.analyse(HistoryLoader.load(ctx, db), InsightGoals.current(ctx), LocalDate.now()).tips
            } finally {
                db.close()
            }
        }
        // Don't repeat the same tip within 3 days
        val tip = prefs.open(tips)
            .firstOrNull { System.currentTimeMillis() - prefs.lastNotified(it.id) > 3 * 86_400_000L }
            ?: return Result.success()
        notify(ctx, tip)
        prefs.markNotified(tip.id)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "daily_insight"
        const val NOTIFICATION_ID = 7301
        const val EXTRA_OPEN_PAGE = "open_page"

        /**
         * The daily tip is now an 8 am alarm with the other reminders (Reminder.DAILY_TIP), which also
         * sends advice from the past week when there's no pattern. This removes the old 9 am job, whose
         * timing drifted, from phones that still have it.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** While the app is open: re-analyse at most every 6 hours and save the tips for the daily worker. */
        suspend fun refreshTips(context: Context) {
            val prefs = InsightPrefs(context)
            if (prefs.tipsAge < 6 * 3_600_000L) return
            val db = MealDb(context)
            try {
                prefs.saveTips(InsightEngine.analyse(HistoryLoader.load(context, db), InsightGoals.current(context), LocalDate.now()).tips)
            } finally {
                db.close()
            }
        }

        private fun tipIntent(context: Context, action: String, tip: Tip) =
            Intent(context, TipActionReceiver::class.java).setAction(action)
                .putExtra("id", tip.id).putExtra("title", tip.title).putExtra("body", tip.body)
                .putExtra("field", tip.change?.field?.name).putExtra("from", tip.change?.from ?: 0.0)
                .putExtra("to", tip.change?.to ?: 0.0)

        fun notify(context: Context, tip: Tip) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_PAGE, "insights")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags
            )
            val accept = PendingIntent.getBroadcast(context, 1, tipIntent(context, TipActionReceiver.ACCEPT, tip), flags)
            val later = PendingIntent.getBroadcast(context, 2, tipIntent(context, TipActionReceiver.DISMISS, tip), flags)
            val text = tip.body + (tip.change?.let { "\n\n${it.text}" } ?: "")
            val n = NotificationChannels.builder(context, NotificationChannels.TIPS)
                .setContentTitle(tip.title)
                .setContentText(tip.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                // Tips can mention health details, so the lock screen only shows that one arrived
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationChannels.builder(context, NotificationChannels.TIPS)
                        .setContentTitle("New health tip from Nourio")
                        .build()
                )
                .addAction(0, if (tip.change != null) "Accept and update goal" else "Got it", accept)
                .addAction(0, "Not now", later)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        }
    }
}

/** Handles the notification buttons. Not exported: only this app's own PendingIntents reach it. */
class TipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val change = intent.getStringExtra("field")?.let { f ->
            runCatching { GoalChange(GoalField.valueOf(f), intent.getDoubleExtra("from", 0.0), intent.getDoubleExtra("to", 0.0)) }.getOrNull()
        }
        val tip = Tip(id, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("body").orEmpty(), change)
        when (intent.action) {
            ACCEPT -> {
                InsightGoals.accept(context, tip)
                Toast.makeText(context, change?.let { "Updated. ${it.text}" } ?: "Saved to your insights", Toast.LENGTH_LONG).show()
            }
            DISMISS -> InsightPrefs(context).dismiss(id)
        }
        NotificationManagerCompat.from(context).cancel(InsightWorker.NOTIFICATION_ID)
    }

    companion object {
        const val ACCEPT = "com.nnrzone.nourio.TIP_ACCEPT"
        const val DISMISS = "com.nnrzone.nourio.TIP_DISMISS"
    }
}
