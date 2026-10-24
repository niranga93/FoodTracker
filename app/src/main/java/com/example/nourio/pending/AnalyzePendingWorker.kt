package com.example.nourio.pending

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nourio.reminders.NotificationChannels
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.nourio.MainActivity
import com.example.nourio.R
import com.example.nourio.data.PortionStore
import com.example.nourio.nutrition.MealPipeline
import com.example.nourio.nutrition.NutritionLookup
import java.util.concurrent.TimeUnit

/**
 * Analyzes queued meals once there is a network connection: photos taken offline (then the user reviews
 * them) and photos or descriptions whose analysis took too long on screen (saved to the log automatically).
 */
class AnalyzePendingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        NutritionLookup.init(ctx)
        PortionStore.init(ctx)
        var retry = false
        for (p in PendingMeals.all(ctx).filter { it.status == PendingStatus.WAITING }) {
            // Each meal is sent only with consent for its kind; without it, it waits untouched
            val use = if (p.photo != null) com.example.nourio.ai.AiUse.PHOTO else com.example.nourio.ai.AiUse.TEXT
            if (!com.example.nourio.ai.AiConsent.isGranted(ctx, use)) continue
            val result = if (p.photo != null) MealPipeline.run(p.photo) else MealPipeline.runText(p.text.orEmpty())
            result.fold(
                onSuccess = {
                    if (p.autoSave && it.items.isNotEmpty()) saveToLog(ctx, p, it)
                    else {
                        PendingMeals.saveResult(ctx, p.id, it)
                        notifyReady(ctx, p, it.description)
                    }
                },
                onFailure = { err ->
                    val msg = err.message ?: "Analysis failed"
                    Log.w("PendingWorker", "meal ${p.id}: $msg")
                    // Errors no retry can fix; anything else (network, busy servers) is tried again later
                    val permanent = PERMANENT_ERRORS.any { it in msg }
                    if (permanent || runAttemptCount >= MAX_ATTEMPTS) {
                        PendingMeals.saveFailure(ctx, p.id, msg)
                        if (p.autoSave) notifyFailed(ctx, p)
                    } else retry = true
                }
            )
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "analyze-pending-meals"
        private const val MAX_ATTEMPTS = 6
        const val EXTRA_PENDING_ID = "pending_id"
        private val PERMANENT_ERRORS = listOf("No food detected", "rejected the request", "Could not read the photo")

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AnalyzePendingWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            // Append so a photo queued while a run is in progress is still picked up
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        const val EXTRA_MEAL_ID = "saved_meal_id"

        /** Adds the analyzed meal to the log at the time it was logged for, as the Save button would. */
        private fun saveToLog(context: Context, p: PendingMeal, a: com.example.nourio.nutrition.Analysis) {
            val mealId = com.example.nourio.data.MealDb(context).insert(a.description, a.items, p.mealTs)
            PendingMeals.remove(context, p.id, keepPhoto = true)
            p.photo?.let { photo -> if (mealId > 0) com.example.nourio.data.MealPhotos.save(context, mealId, photo) }
            com.example.nourio.backup.BackupService.autoBackup(context)
            val kcal = a.items.sumOf { it.kcal }.toInt()
            notify(
                context, p.id.toInt(),
                "Added your ${p.label} to your log",
                "${a.description} · ${"%,d".format(kcal)} kcal. Tap to check it.",
                Intent(context, MainActivity::class.java).putExtra(EXTRA_MEAL_ID, mealId),
            )
        }

        private fun notifyFailed(context: Context, p: PendingMeal) = notify(
            context, p.id.toInt(),
            "Couldn't analyze your ${p.label}",
            "Tap to try again or add it by hand.",
            Intent(context, MainActivity::class.java).putExtra(EXTRA_PENDING_ID, p.id),
        )

        private fun notify(context: Context, id: Int, title: String, text: String, open: Intent) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationChannels.builder(context, NotificationChannels.MEAL_READY)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(id, n)
        }

        private fun notifyReady(context: Context, p: PendingMeal, mealDescription: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return   // the in-app card still shows it

            val open = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_PENDING_ID, p.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(
                context, p.id.toInt(), open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationChannels.builder(context, NotificationChannels.MEAL_READY)
                .setContentTitle("Your ${p.label} is ready to review")
                .setContentText(mealDescription)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(p.id.toInt(), n)
        }
    }
}
