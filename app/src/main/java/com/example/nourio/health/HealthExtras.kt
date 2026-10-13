package com.example.nourio.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import com.example.nourio.data.MealDb
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.WaterStore
import com.example.nourio.data.WeightDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class Workout(val name: String, val minutes: Long)

data class DayBody(
    val sleepMinutes: Long?,
    val hrAvg: Long?,
    val hrMin: Long?,
    val hrMax: Long?,
    val workouts: List<Workout>
)

object HealthExtras {
    val READ_PERMS = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class)
    )

    val WRITE_PERMS = setOf(
        HealthPermission.getWritePermission(NutritionRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class)
    )

    // Records we write carry a stable id, so a later sync updates them instead of adding duplicates.
    // Health Connect only replaces a record when the version is higher, so the sync time is used.
    private fun meta(id: String, version: Long) =
        Metadata.manualEntry(clientRecordId = id, clientRecordVersion = version)

    // ------------------------------------------------------------ reading

    private fun exerciseName(r: ExerciseSessionRecord): String =
        r.title?.takeIf { it.isNotBlank() } ?: when (r.exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Running"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Cycling"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Strength training"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Swimming"
            else -> "Workout"
        }

    /** Sleep counts for the day you wake up: sessions between 6 pm the evening before and 6 pm that day. */
    suspend fun readDayBody(client: HealthConnectClient, offset: Int): DayBody {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone).plusDays(offset.toLong())
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val sleepFrom = day.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        val sleepTo = day.atTime(18, 0).atZone(zone).toInstant()

        val sleep = runCatching {
            client.aggregate(
                AggregateRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL), TimeRangeFilter.between(sleepFrom, sleepTo))
            )[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()
        }.getOrNull()?.takeIf { it > 0 }

        val hr = runCatching {
            client.aggregate(
                AggregateRequest(
                    setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX),
                    TimeRangeFilter.between(start, end)
                )
            )
        }.getOrNull()

        val workouts = runCatching {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(start, end)))
                .records
                .map { Workout(exerciseName(it), Duration.between(it.startTime, it.endTime).toMinutes()) }
                .filter { it.minutes > 0 }
        }.getOrDefault(emptyList())

        return DayBody(
            sleepMinutes = sleep,
            hrAvg = hr?.get(HeartRateRecord.BPM_AVG),
            hrMin = hr?.get(HeartRateRecord.BPM_MIN),
            hrMax = hr?.get(HeartRateRecord.BPM_MAX),
            workouts = workouts
        )
    }

    /** Smart-scale and other-app weights become weigh-ins. Each Health Connect record is imported once. */
    suspend fun importWeights(context: Context, client: HealthConnectClient): Int {
        val prefs = HealthPrefs(context)
        val now = Instant.now()
        val records = client.readRecords(
            ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(now.minus(180, ChronoUnit.DAYS), now))
        ).records

        val db = WeightDb(context)
        val existing = db.all()
        val seenBefore = prefs.importedWeightIds
        val seenNow = mutableSetOf<String>()      // only ids still in the window, so the set stays small
        val importedTimes = prefs.importedWeightTimes.toMutableSet()
        var added = 0
        for (r in records) {
            if (r.metadata.dataOrigin.packageName == context.packageName) continue   // our own write
            val id = r.metadata.id
            seenNow += id
            if (id in seenBefore) continue
            val ts = r.time.toEpochMilli()
            val kg = r.weight.inKilograms
            if (kg !in 20.0..400.0) continue
            if (existing.any { abs(it.ts - ts) < 60_000 && abs(it.kg - kg) < 0.05 }) continue
            db.add(ts, kg)
            importedTimes += ts.toString()
            added++
        }
        prefs.importedWeightIds = seenNow
        prefs.importedWeightTimes = importedTimes

        if (added > 0) {
            db.all().lastOrNull()?.let { latest ->
                val store = ProfileStore(context)
                store.save(store.load().copy(weightKg = latest.kg))      // keep calorie estimates current
            }
        }
        return added
    }

    // ------------------------------------------------------------ writing

    /** "id:fingerprint" for a synced item. Only items whose fingerprint changed are written again. */
    private fun sig(id: Any, vararg parts: Any) = "$id:${parts.contentHashCode()}"
    private fun idOf(sig: String) = sig.substringBefore(':')

    suspend fun pushNutrition(context: Context, client: HealthConnectClient, db: MealDb) {
        val prefs = HealthPrefs(context)
        val now = System.currentTimeMillis()
        val meals = db.between(now - 14L * 86_400_000L, now + 86_400_000L)
        val zone = ZoneId.systemDefault()
        val pushed = prefs.pushedMealIds

        val sigs = meals.associateWith { m ->
            val t = m.totals
            sig(m.id, m.timestamp, m.description, t.kcal, t.protein, t.carbs, t.fat, t.satFat, t.sugar, t.fiber, t.sodium)
        }
        val records = meals.filter { sigs.getValue(it) !in pushed }.map { m ->
            val t = m.totals
            val start = Instant.ofEpochMilli(minOf(m.timestamp, now - 120_000L))     // never in the future
            val offset = zone.rules.getOffset(start)
            NutritionRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = start.plusSeconds(60),
                endZoneOffset = offset,
                metadata = meta("ft-meal-${m.id}", now),
                name = m.description.take(100),
                energy = Energy.kilocalories(t.kcal.coerceAtLeast(0.0)),
                protein = Mass.grams(t.protein.coerceAtLeast(0.0)),
                totalCarbohydrate = Mass.grams(t.carbs.coerceAtLeast(0.0)),
                totalFat = Mass.grams(t.fat.coerceAtLeast(0.0)),
                saturatedFat = Mass.grams(t.satFat.coerceAtLeast(0.0)),
                sugar = Mass.grams(t.sugar.coerceAtLeast(0.0)),
                dietaryFiber = Mass.grams(t.fiber.coerceAtLeast(0.0)),
                sodium = Mass.milligrams(t.sodium.coerceAtLeast(0.0)),
                mealType = MealType.MEAL_TYPE_UNKNOWN
            )
        }
        if (records.isNotEmpty()) client.insertRecords(records)

        // Remove records for meals that were deleted since the last sync
        val currentIds = meals.map { it.id.toString() }.toSet()
        val gone = pushed.map(::idOf).filter { it !in currentIds && it.toLongOrNull()?.let(db::getById) == null }
        if (gone.isNotEmpty()) {
            client.deleteRecords(NutritionRecord::class, emptyList(), gone.map { "ft-meal-$it" })
        }
        prefs.pushedMealIds = sigs.values.toSet()
    }

    suspend fun pushHydration(context: Context, client: HealthConnectClient) {
        val prefs = HealthPrefs(context)
        val water = WaterStore(context)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val pushed = prefs.pushedWaterDays
        val pushedDays = pushed.map(::idOf).toSet()
        val records = mutableListOf<HydrationRecord>()
        val empty = mutableListOf<String>()
        val current = mutableSetOf<String>()
        for (off in 0 downTo -6) {
            val day = LocalDate.now(zone).plusDays(off.toLong())
            val ml = water.get(off)
            val clientId = "ft-water-$day"
            if (ml > 0) {
                val s = sig(day, ml)
                current += s
                if (s in pushed) continue
                val noon = day.atTime(12, 0).atZone(zone).toInstant()
                val start = if (noon.toEpochMilli() > now - 120_000L) Instant.ofEpochMilli(now - 120_000L) else noon
                val offset = zone.rules.getOffset(start)
                records += HydrationRecord(
                    startTime = start,
                    startZoneOffset = offset,
                    endTime = start.plusSeconds(60),
                    endZoneOffset = offset,
                    volume = Volume.milliliters(ml.toDouble()),
                    metadata = meta(clientId, now)
                )
            } else if (day.toString() in pushedDays) {
                empty += clientId           // water was reset to zero after it was sent
            }
        }
        if (records.isNotEmpty()) client.insertRecords(records)
        if (empty.isNotEmpty()) client.deleteRecords(HydrationRecord::class, emptyList(), empty)
        prefs.pushedWaterDays = current
    }

    suspend fun pushWeights(context: Context, client: HealthConnectClient) {
        val prefs = HealthPrefs(context)
        val skip = prefs.importedWeightTimes          // came from Health Connect: don't send back
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val pushed = prefs.pushedWeightTimes
        val entries = WeightDb(context).all().takeLast(60)
            .filter { it.ts.toString() !in skip && it.ts < now }
        val sigs = entries.associateWith { sig(it.ts, it.kg) }
        val records = entries.filter { sigs.getValue(it) !in pushed }.map { e ->
            val t = Instant.ofEpochMilli(e.ts)
            WeightRecord(
                time = t,
                zoneOffset = zone.rules.getOffset(t),
                weight = Mass.kilograms(e.kg),
                metadata = meta("ft-weight-${e.ts}", now)
            )
        }
        if (records.isNotEmpty()) client.insertRecords(records)

        // Weigh-ins deleted in the app are removed from Health Connect too.
        // Older ones that only fell out of the 60-entry window are left alone.
        val oldest = entries.firstOrNull()?.ts ?: Long.MIN_VALUE
        val current = entries.map { it.ts.toString() }.toSet()
        val gone = pushed.map(::idOf).filter { it !in current && (it.toLongOrNull() ?: 0L) >= oldest }
        if (gone.isNotEmpty()) {
            client.deleteRecords(WeightRecord::class, emptyList(), gone.map { "ft-weight-$it" })
        }
        prefs.pushedWeightTimes = sigs.values.toSet()
    }
}

/** Runs the two-way sync in the background. Silent on failure. */
object HealthSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun runAsync(context: Context) {
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                runCatching { run(app) }.onFailure { Log.w("HealthSync", "sync failed", it) }
            }
        }
    }

    /** Same as runAsync, but waits for the sync to finish. Used by pull-to-refresh. */
    suspend fun syncNow(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        mutex.withLock {
            runCatching { run(app) }.onFailure { Log.w("HealthSync", "sync failed", it) }
        }
        Unit
    }

    private suspend fun run(context: Context) {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return
        val prefs = HealthPrefs(context)
        if (!prefs.enabled || (!prefs.readExtras && !prefs.writeSync)) return

        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()

        // Import first, so imported weights are known before anything is written
        if (prefs.readExtras && granted.containsAll(HealthExtras.READ_PERMS)) {
            HealthExtras.importWeights(context, client)
        }
        if (prefs.writeSync && granted.containsAll(HealthExtras.WRITE_PERMS)) {
            val db = MealDb(context)
            try {
                HealthExtras.pushNutrition(context, client, db)
            } finally {
                db.close()
            }
            HealthExtras.pushHydration(context, client)
            HealthExtras.pushWeights(context, client)
        }
    }
}
