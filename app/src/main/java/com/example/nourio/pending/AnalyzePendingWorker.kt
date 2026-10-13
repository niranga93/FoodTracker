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

/** Analyzes queued offline photos once there is a network connection, then notifies. */
class AnalyzePendingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        NutritionLookup.init(ctx)
        PortionStore.init(ctx)

        var retry = false
        for (p in PendingMeals.all(ctx).filter { it.status == PendingStatus.WAITING }) {
            MealPipeline.run(p.photo).fold(
                onSuccess = {
                    PendingMeals.saveResult(ctx, p.id, it)
                    notifyReady(ctx, p, it.description)
                },
                onFailure = { err ->
                    val msg = err.message ?: "Analysis failed"
                    Log.w("PendingWorker", "meal ${p.id}: $msg")
                    // Errors no retry can fix; anything else (network, busy servers) is tried again later
                    val permanent = PERMANENT_ERRORS.any { it in msg }
                    if (permanent || runAttemptCount >= MAX_ATTEMPTS) PendingMeals.saveFailure(ctx, p.id, msg)
                    else retry = true
                }
            )
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "analyze-pending-meals"
        private const val CHANNEL_ID = "meal_ready"
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

        private fun notifyReady(context: Context, p: PendingMeal, mealDescription: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return   // the in-app card still shows it

            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Meals ready to review", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "When a photo taken offline has been analyzed" }
            )
            val open = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_PENDING_ID, p.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(
                context, p.id.toInt(), open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Your ${p.label} is ready to review")
                .setContentText(mealDescription)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(p.id.toInt(), n)
        }
    }
}
