package com.example.nourio.plan

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.nourio.MainActivity
import com.example.nourio.ai.AiConsent
import com.example.nourio.ai.AiSettings
import com.example.nourio.ai.AiUse
import com.example.nourio.ai.GeminiKeys
import com.example.nourio.insights.InsightWorker
import com.example.nourio.reminders.NotificationChannels
import java.util.concurrent.TimeUnit

/**
 * Once a month, asks the AI service whether a health guideline changed ([StandardsChecker]). Runs only
 * with an AI service set up and the "Guideline updates" consent on. New changes are not applied:
 * the person gets a notification and approves or rejects each one in Goals.
 */
class StandardsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        GeminiKeys.init(ctx); AiSettings.init(ctx); AiConsent.init(ctx); Standards.init(ctx)
        if (!AiSettings.available.value || !AiConsent.isGranted(ctx, AiUse.STANDARDS)) return Result.success()
        val fresh = runCatching { StandardsChecker.check(ctx) }.getOrElse { return Result.retry() }
        if (fresh.isNotEmpty()) notifyChanges(ctx, fresh)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "standards_check"
        private const val NOTIFICATION_ID = 7302

        /** Every 30 days on any network. Keeps an existing schedule. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StandardsWorker>(30, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** "Sodium limit: 2,300 mg a day now, 2,000 mg a day recommended." Opens Goals to approve or reject. */
        fun notifyChanges(context: Context, changes: List<Proposal>) {
            if (!NotificationChannels.allowed(context)) return
            val open = PendingIntent.getActivity(
                context, 3,
                Intent(context, MainActivity::class.java).putExtra(InsightWorker.EXTRA_OPEN_PAGE, "goals")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val lines = changes.map { "${Standards.label(it.key)}: now ${Standards.currentText(it.key)}, new recommendation ${Standards.proposedText(it)}" }
            val n = NotificationChannels.builder(context, NotificationChannels.TIPS)
                .setContentTitle(if (changes.size == 1) "A health guideline has changed" else "${changes.size} health guidelines have changed")
                .setContentText(lines.first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    lines.joinToString("\n") + "\n\nNothing changes until you approve it. Tap to review."))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
        }
    }
}
