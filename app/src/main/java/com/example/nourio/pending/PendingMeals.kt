package com.example.nourio.pending

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationManagerCompat
import com.example.nourio.data.FoodItem
import com.example.nourio.data.PortionEstimate
import com.example.nourio.nutrition.Analysis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar

enum class PendingStatus { WAITING, READY, FAILED }

/**
 * A meal waiting for analysis: a photo taken offline, or a photo or description whose analysis took too long
 * on screen and moved to the background. [autoSave] meals go straight into the log once analyzed; the others
 * wait for the user to review them.
 */
data class PendingMeal(
    val id: Long,
    val photo: File?,               // null for a typed or spoken description
    val mealTs: Long,               // when the meal is logged for
    val status: PendingStatus,
    val error: String? = null,
    val analysis: Analysis? = null,
    val text: String? = null,       // the description, when there's no photo
    val autoSave: Boolean = false,
) {
    val label: String get() = mealLabel(mealTs)
}

/** "breakfast", "lunch", "dinner" or "snack", from the time of day. */
fun mealLabel(ts: Long): String =
    when (Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.HOUR_OF_DAY)) {
        in 4..10 -> "breakfast"
        in 11..15 -> "lunch"
        in 17..21 -> "dinner"
        else -> "snack"
    }

/** Days from today to the day of [ts]: 0 = today, -1 = yesterday. */
fun dayOffsetOf(ts: Long): Int {
    val zone = ZoneId.systemDefault()
    return ChronoUnit.DAYS.between(LocalDate.now(zone), Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()).toInt()
}

fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Stored as files/pending/<id>.jpg plus <id>.json. */
object PendingMeals {
    // Bumped on every change so screens can reload
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun dir(context: Context) = File(context.filesDir, "pending").apply { mkdirs() }
    private fun jsonFile(context: Context, id: Long) = File(dir(context), "$id.json")
    private fun photoFile(context: Context, id: Long) = File(dir(context), "$id.jpg")

    /** Moves [photo] into the queue. */
    fun add(context: Context, photo: File, mealTs: Long, autoSave: Boolean = false): PendingMeal {
        val id = System.currentTimeMillis()
        val dest = photoFile(context, id)
        if (!photo.renameTo(dest)) {
            photo.copyTo(dest, overwrite = true)
            photo.delete()
        }
        val p = PendingMeal(id, dest, mealTs, PendingStatus.WAITING, autoSave = autoSave)
        write(context, p)
        return p
    }

    /** Queues a typed or spoken description. */
    fun addText(context: Context, text: String, mealTs: Long, autoSave: Boolean = true): PendingMeal {
        val p = PendingMeal(System.currentTimeMillis(), null, mealTs, PendingStatus.WAITING, text = text, autoSave = autoSave)
        write(context, p)
        return p
    }

    /** Puts a meal back in the queue to be analyzed in the background and added to the log. */
    fun requeue(context: Context, id: Long, autoSave: Boolean = true) {
        get(context, id)?.let { write(context, it.copy(status = PendingStatus.WAITING, error = null, autoSave = autoSave)) }
    }

    @Synchronized
    fun all(context: Context): List<PendingMeal> =
        dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { read(context, JSONObject(it.readText())) }.getOrNull() }
            .filter { it.photo?.exists() == true || it.text != null }
            .sortedBy { it.mealTs }

    fun get(context: Context, id: Long): PendingMeal? = all(context).firstOrNull { it.id == id }

    fun saveResult(context: Context, id: Long, analysis: Analysis) {
        get(context, id)?.let { write(context, it.copy(status = PendingStatus.READY, error = null, analysis = analysis)) }
    }

    fun saveFailure(context: Context, id: Long, error: String) {
        get(context, id)?.let { write(context, it.copy(status = PendingStatus.FAILED, error = error)) }
    }

    /** Forget the entry. The photo is kept when [keepPhoto] (e.g. it becomes the meal's thumbnail). */
    @Synchronized
    fun remove(context: Context, id: Long, keepPhoto: Boolean = false) {
        jsonFile(context, id).delete()
        if (!keepPhoto) photoFile(context, id).delete()
        NotificationManagerCompat.from(context).cancel(id.toInt())
        _changes.update { it + 1 }
    }

    @Synchronized
    private fun write(context: Context, p: PendingMeal) {
        val o = JSONObject()
            .put("id", p.id)
            .put("mealTs", p.mealTs)
            .put("status", p.status.name)
            .put("error", p.error ?: JSONObject.NULL)
            .put("text", p.text ?: JSONObject.NULL)
            .put("autoSave", p.autoSave)
        p.analysis?.let { a ->
            o.put("description", a.description)
            o.put("items", JSONArray().apply { a.items.forEach { put(itemToJson(it)) } })
        }
        val f = jsonFile(context, p.id)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
        _changes.update { it + 1 }
    }

    private fun read(context: Context, o: JSONObject): PendingMeal {
        val id = o.getLong("id")
        val analysis = o.optJSONArray("items")?.let { arr ->
            Analysis(
                o.optString("description", "Meal"),
                (0 until arr.length()).map { itemFromJson(arr.getJSONObject(it)) }
            )
        }
        val text = if (o.isNull("text")) null else o.optString("text").takeIf { it.isNotBlank() }
        return PendingMeal(
            id = id,
            photo = if (text != null) null else photoFile(context, id),
            mealTs = o.getLong("mealTs"),
            status = PendingStatus.valueOf(o.getString("status")),
            error = if (o.isNull("error")) null else o.getString("error"),
            analysis = analysis,
            text = text,
            autoSave = o.optBoolean("autoSave", false),
        )
    }

    // FoodItem.toJson leaves out the review-only estimate, so it is stored alongside here
    private fun itemToJson(f: FoodItem): JSONObject = f.toJson().apply {
        f.estimate?.let { e ->
            put(
                "estimate", JSONObject()
                    .put("low", e.lowGrams).put("high", e.highGrams)
                    .put("confidence", e.confidence).put("preparation", e.preparation)
                    .put("aiGrams", e.aiGrams)
                    .put("factor", e.learnedFactor ?: JSONObject.NULL)
            )
        }
    }

    private fun itemFromJson(o: JSONObject): FoodItem {
        val e = o.optJSONObject("estimate")?.let {
            PortionEstimate(
                lowGrams = it.optDouble("low"),
                highGrams = it.optDouble("high"),
                confidence = it.optString("confidence"),
                preparation = it.optString("preparation"),
                aiGrams = it.optDouble("aiGrams"),
                learnedFactor = if (it.isNull("factor")) null else it.optDouble("factor")
            )
        }
        return FoodItem.fromJson(o).copy(estimate = e)
    }
}
