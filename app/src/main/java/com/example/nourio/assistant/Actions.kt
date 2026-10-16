package com.example.nourio.assistant

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import com.example.nourio.SUPPORT_EMAIL                // reports go to the About page's support address
import com.example.nourio.backup.BackupPrefs
import com.example.nourio.data.MealDb
import com.example.nourio.data.WeightDb
import com.example.nourio.health.HealthPrefs
import com.example.nourio.insights.InsightPrefs
import com.example.nourio.reminders.Reminder
import com.example.nourio.reminders.ReminderPrefs
import com.example.nourio.reminders.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ActionType(val key: String) {
    REPORT_BUG("report_bug"), FEATURE_REQUEST("feature_request"), DATA_ISSUE("data_issue"),
    DELETE_DATA("delete_data"), WITHDRAW_CONSENT("withdraw_consent");

    val isReport get() = this == REPORT_BUG || this == FEATURE_REQUEST || this == DATA_ISSUE

    companion object {
        fun of(k: String?) = entries.firstOrNull { it.key == k }
    }
}

enum class DeleteScope(val key: String, val label: String) {
    MEALS("meals", "meals"),
    WATER("water", "water entries"),
    WEIGHTS("weights", "weigh-ins"),
    FAVORITES("favorites", "favorite foods and meals"),
    CHAT("chat", "this chat"),
    EVERYTHING("everything", "everything");

    companion object {
        fun of(k: String?) = entries.firstOrNull { it.key == k }
    }
}

enum class Consent(val key: String, val label: String, val effect: String) {
    AI_PHOTOS("ai_photos", "Meal photo analysis", "Meal photos are no longer sent to your AI service. You'll be asked again before the next one."),
    AI_TEXT("ai_text", "Meal descriptions", "Typed, spoken and shared meal descriptions are no longer sent to your AI service until you allow it again."),
    AI_COACH("ai_coach", "AI coach", "The coach note will ask for your permission again before it sends anything."),
    ASSISTANT("assistant", "Nourio Assistant", "I'll ask for your permission again before your next question is sent."),
    HEALTH_CONNECT("health_connect", "Health Connect", "Nourio stops reading and writing Health Connect, and its access is removed there. Steps, sleep and heart data disappear from the app."),
    NOTIFICATIONS("notifications", "Tips and reminders", "The daily tip and all reminders are switched off."),
    GEMINI_KEYS("ai_keys", "AI keys", "Your AI keys (Gemini, Claude and any others) are removed from this phone. Photo analysis, the coach and I stop working until you add a key again."),
    DRIVE_BACKUP("drive_backup", "Google Drive backup", "Automatic backup to your Drive stops. The backup file already in your Drive stays there until you delete it."),
    ALL("all", "every consent", "All of the above: every AI feature, Health Connect, tips and reminders, AI keys and Drive backup.");

    companion object {
        fun of(k: String?) = entries.firstOrNull { it.key == k }
    }
}

/** What Gemini proposed. Nothing happens until the user confirms it on the card. */
data class ProposedAction(
    val type: ActionType,
    val title: String = "",
    val details: String = "",
    val scope: DeleteScope? = null,
    val range: Range? = null,          // meals, water and weigh-ins only; null = all of them
    val consent: Consent? = null,
)

/** What a delete would remove, counted on the phone so the card can say exactly. */
data class DeletePreview(val count: Int, val from: LocalDate?, val to: LocalDate?, val text: String)

object Actions {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun span(r: Range?, today: LocalDate): Pair<LocalDate, LocalDate>? =
        r?.let { Executor.resolve(it, today, null) }

    private fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun between(from: LocalDate?, to: LocalDate?) =
        if (from == null || to == null) "" else if (from == to) " on ${Executor.date(from)}" else " between ${Executor.date(from)} and ${Executor.date(to)}"

    suspend fun preview(context: Context, a: ProposedAction): DeletePreview = withContext(Dispatchers.IO) {
        val today = LocalDate.now(zone)
        val s = span(a.range, today)
        when (a.scope) {
            DeleteScope.MEALS -> {
                val db = MealDb(context)
                val n = try {
                    if (s == null) db.count() else db.between(ms(s.first), ms(s.second.plusDays(1))).size
                } finally { db.close() }
                DeletePreview(n, s?.first, s?.second,
                    "This deletes $n ${if (n == 1) "meal" else "meals"}${between(s?.first, s?.second)}, with their photos.")
            }
            DeleteScope.WATER -> {
                val days = waterKeys(context, s).size
                DeletePreview(days, s?.first, s?.second,
                    "This clears the water you logged on $days ${if (days == 1) "day" else "days"}${between(s?.first, s?.second)}. Your water goal stays.")
            }
            DeleteScope.WEIGHTS -> {
                val n = weights(context, s).size
                DeletePreview(n, s?.first, s?.second, "This deletes $n ${if (n == 1) "weigh-in" else "weigh-ins"}${between(s?.first, s?.second)}.")
            }
            DeleteScope.FAVORITES -> DeletePreview(1, null, null, "This removes all your favorite foods and saved meals.")
            DeleteScope.CHAT -> DeletePreview(1, null, null, "This clears this conversation from your phone.")
            DeleteScope.EVERYTHING, null -> DeletePreview(1, null, null,
                "This erases everything Nourio keeps on this phone: meals and photos, weigh-ins, water, goals, profile, " +
                    "favorites, settings, AI keys and consents. Nourio will close and start fresh.")
        }
    }

    private fun waterKeys(context: Context, s: Pair<LocalDate, LocalDate>?): List<String> =
        context.getSharedPreferences("water", Context.MODE_PRIVATE).all.keys.filter { k ->
            k.startsWith("d_") && (s == null || runCatching { LocalDate.parse(k.removePrefix("d_")) }.getOrNull()?.let { it in s.first..s.second } == true)
        }

    private fun weights(context: Context, s: Pair<LocalDate, LocalDate>?) =
        WeightDb(context).all().filter { w -> s == null || Instant.ofEpochMilli(w.ts).atZone(zone).toLocalDate() in s.first..s.second }

    /** Runs a confirmed delete and says what happened. */
    suspend fun delete(context: Context, a: ProposedAction): String = withContext(Dispatchers.IO) {
        val today = LocalDate.now(zone)
        val s = span(a.range, today)
        val drive = BackupPrefs(context).email != null
        val driveNote = if (drive) " Your Google Drive backup will match the next time Nourio backs up." else ""
        when (a.scope) {
            DeleteScope.MEALS -> {
                val db = MealDb(context)
                val n = try {
                    val meals = if (s == null) db.all() else db.between(ms(s.first), ms(s.second.plusDays(1)))
                    meals.forEach { db.delete(it.id) }      // also removes each meal's photo
                    meals.size
                } finally { db.close() }
                "Deleted $n ${if (n == 1) "meal" else "meals"}.$driveNote"
            }
            DeleteScope.WATER -> {
                val keys = waterKeys(context, s)
                context.getSharedPreferences("water", Context.MODE_PRIVATE).edit { keys.forEach { remove(it) } }
                "Cleared water on ${keys.size} ${if (keys.size == 1) "day" else "days"}.$driveNote"
            }
            DeleteScope.WEIGHTS -> {
                val list = weights(context, s)
                val db = WeightDb(context)
                list.forEach { db.delete(it.id) }
                "Deleted ${list.size} ${if (list.size == 1) "weigh-in" else "weigh-ins"}.$driveNote"
            }
            DeleteScope.FAVORITES -> {
                listOf("favorites", "favorite_meals").forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit { clear() } }
                "Removed your favorites.$driveNote"
            }
            DeleteScope.CHAT -> { withContext(Dispatchers.Main) { AssistantSession.clear() }; "Chat cleared." }
            DeleteScope.EVERYTHING, null -> {
                // Health Connect access isn't part of the app's data, so remove it first
                revokeHealthConnect(context)
                context.getSystemService(ActivityManager::class.java).clearApplicationUserData()   // closes the app
                "Erasing everything…"
            }
        }
    }

    private suspend fun revokeHealthConnect(context: Context) {
        HealthPrefs(context).apply { enabled = false; readExtras = false; writeSync = false; deviceBmr = 0.0 }
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            runCatching { HealthConnectClient.getOrCreate(context).permissionController.revokeAllPermissions() }
        }
    }

    /** Withdraws a confirmed consent and says what changed. */
    suspend fun withdraw(context: Context, c: Consent): String = withContext(Dispatchers.IO) {
        val all = c == Consent.ALL
        if (all || c == Consent.AI_PHOTOS) com.example.nourio.ai.AiConsent.set(context, com.example.nourio.ai.AiUse.PHOTO, false)
        if (all || c == Consent.AI_TEXT) com.example.nourio.ai.AiConsent.set(context, com.example.nourio.ai.AiUse.TEXT, false)
        if (all || c == Consent.AI_COACH) com.example.nourio.withdrawCoachConsent(context)
        if (all || c == Consent.ASSISTANT) AssistantPrefs(context).consented = false
        if (all || c == Consent.HEALTH_CONNECT) revokeHealthConnect(context)
        if (all || c == Consent.NOTIFICATIONS) {
            InsightPrefs(context).notificationsOn = false
            val r = ReminderPrefs(context)
            Reminder.SWITCHES.forEach { r.set(it, false) }
            Reminders.schedule(context)
        }
        if (all || c == Consent.DRIVE_BACKUP) BackupPrefs(context).clear()
        if (all || c == Consent.GEMINI_KEYS) {
            val keys = com.example.nourio.ai.GeminiKeys
            withContext(Dispatchers.Main) { (0 until keys.SLOTS).forEach { keys.remove(it) } }
            com.example.nourio.ai.AiSettings.removeAllKeys(context)          // Claude, ChatGPT and the rest too
        }
        if (all) "Done. Every consent is withdrawn. You can turn things back on any time in Profile > Connections and Settings."
        else "Done. ${c.effect}"
    }

    /** Opens the user's email app with the report filled in. Nothing is sent until they press send. */
    fun emailReport(context: Context, a: ProposedAction, body: String, withDevice: Boolean) {
        val kind = when (a.type) {
            ActionType.REPORT_BUG -> "Bug"
            ActionType.FEATURE_REQUEST -> "Feature request"
            else -> "Data issue"
        }
        val device = if (!withDevice) "" else {
            val v = com.example.nourio.appVersion(context)
            "\n\n---\nNourio ${v.name} (${v.code}) · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}"
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, "[Nourio] $kind: ${a.title.ifBlank { kind }}")
            .putExtra(Intent.EXTRA_TEXT, body + device)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, "Send with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
