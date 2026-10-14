package com.example.nourio.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.Vo2MaxRecord
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

/** One exercise session. minutes excludes pauses; kcal is active calories during it, when recorded. */
data class Workout(
    val name: String,
    val minutes: Long,
    val start: Instant = Instant.EPOCH,
    val source: String = "",
    val kcal: Double? = null
)

/** Readable name for the app that recorded a session. Other apps' labels are hidden from us on Android 11+. */
fun sourceAppName(pkg: String): String = when {
    pkg.startsWith("com.sec.android.app.shealth") -> "Samsung Health"
    pkg == "com.google.android.apps.fitness" -> "Google Fit"
    pkg.startsWith("com.google.android.apps.healthdata") -> "Health Connect"
    pkg.startsWith("com.fitbit") -> "Fitbit"
    pkg.startsWith("com.garmin") -> "Garmin Connect"
    pkg.startsWith("com.strava") -> "Strava"
    pkg.startsWith("com.huawei.health") -> "Huawei Health"
    pkg.startsWith("com.xiaomi") || pkg.startsWith("com.mi.health") -> "Mi Fitness"
    pkg.startsWith("com.withings") -> "Withings"
    pkg.startsWith("com.oura") -> "Oura"
    pkg.startsWith("com.whoop") -> "Whoop"
    pkg.startsWith("com.nike") -> "Nike Run Club"
    pkg.startsWith("com.samsung.android.wear") || pkg.contains("watch") -> "Watch"
    else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}

/** Minutes per sleep stage for one night. */
data class SleepStages(val deep: Long, val rem: Long, val light: Long, val awake: Long) {
    val any get() = deep + rem + light > 0
}

data class DayBody(
    val sleepMinutes: Long?,
    val hrAvg: Long?,
    val hrMin: Long?,
    val hrMax: Long?,
    val workouts: List<Workout>,
    // Optional types: null when not recorded or not allowed
    val stages: SleepStages? = null,
    val restingHr: Long? = null,
    val spo2: Double? = null,               // average %, that day
    val vo2max: Double? = null,             // latest in the 30 days up to that day, mL/kg/min
    val distanceKm: Double? = null
)

object HealthExtras {
    // The set the read switch first asked for. Kept unchanged so existing users keep their data.
    val READ_PERMS = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class)
    )

    // Added later and optional: each one is read only when it was allowed
    val P_BMR = HealthPermission.getReadPermission(BasalMetabolicRateRecord::class)
    val P_HEIGHT = HealthPermission.getReadPermission(HeightRecord::class)
    val P_RESTING_HR = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    val P_SPO2 = HealthPermission.getReadPermission(OxygenSaturationRecord::class)
    val P_VO2 = HealthPermission.getReadPermission(Vo2MaxRecord::class)
    val P_DISTANCE = HealthPermission.getReadPermission(DistanceRecord::class)
    val P_BLOOD_PRESSURE = HealthPermission.getReadPermission(BloodPressureRecord::class)
    val MORE_READ_PERMS = setOf(P_BMR, P_HEIGHT, P_RESTING_HR, P_SPO2, P_VO2, P_DISTANCE, P_BLOOD_PRESSURE)

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

    /** Session length without paused segments. */
    private fun activeMinutes(r: ExerciseSessionRecord): Long {
        val paused = r.segments
            .filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }
            .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
        return (Duration.between(r.startTime, r.endTime).toMinutes() - paused).coerceAtLeast(0)
    }

    /**
     * The same walk is often written by two apps (for example a watch app and Samsung Health).
     * Sessions that overlap by more than half of the shorter one count once; the longer one is kept.
     */
    private fun dedupe(sessions: List<ExerciseSessionRecord>): List<ExerciseSessionRecord> {
        val kept = mutableListOf<ExerciseSessionRecord>()
        for (s in sessions.sortedByDescending { Duration.between(it.startTime, it.endTime) }) {
            val dup = kept.any { k ->
                val overlap = Duration.between(maxOf(k.startTime, s.startTime), minOf(k.endTime, s.endTime))
                val shorter = minOf(Duration.between(k.startTime, k.endTime), Duration.between(s.startTime, s.endTime))
                !overlap.isNegative && overlap.toMillis() * 2 > shorter.toMillis()
            }
            if (!dup) kept += s
        }
        return kept
    }

    private fun stagesOf(sessions: List<SleepSessionRecord>): SleepStages {
        var deep = 0L; var rem = 0L; var light = 0L; var awake = 0L
        for (s in sessions) for (st in s.stages) {
            val m = Duration.between(st.startTime, st.endTime).toMinutes()
            when (st.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> deep += m
                SleepSessionRecord.STAGE_TYPE_REM -> rem += m
                SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> light += m
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> awake += m
            }
        }
        return SleepStages(deep, rem, light, awake)
    }

    /**
     * Sleep counts for the day you wake up: sessions between 6 pm the evening before and 6 pm that day.
     * [granted] decides which of the optional types are read.
     */
    suspend fun readDayBody(client: HealthConnectClient, offset: Int, granted: Set<String>): DayBody {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone).plusDays(offset.toLong())
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val dayRange = TimeRangeFilter.between(start, end)
        val sleepRange = TimeRangeFilter.between(
            day.minusDays(1).atTime(18, 0).atZone(zone).toInstant(),
            day.atTime(18, 0).atZone(zone).toInstant()
        )

        val sleep = runCatching {
            client.aggregate(
                AggregateRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL), sleepRange)
            )[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()
        }.getOrNull()?.takeIf { it > 0 }

        // Stages come with the same sleep permission, when the watch records them
        val stages = if (sleep == null) null else runCatching {
            stagesOf(client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, sleepRange)).records)
        }.getOrNull()?.takeIf { it.any }

        val hr = runCatching {
            client.aggregate(
                AggregateRequest(setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX), dayRange)
            )
        }.getOrNull()

        val workouts = runCatching {
            dedupe(client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, dayRange)).records)
                .map { r ->
                    // Active calories inside the session window; Health Connect already merges apps for totals
                    val kcal = runCatching {
                        client.aggregate(
                            AggregateRequest(
                                setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                                TimeRangeFilter.between(r.startTime, r.endTime)
                            )
                        )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                    }.getOrNull()?.takeIf { it >= 1 }
                    Workout(exerciseName(r), activeMinutes(r), r.startTime, sourceAppName(r.metadata.dataOrigin.packageName), kcal)
                }
                .filter { it.minutes > 0 }
                .sortedBy { it.start }
        }.getOrDefault(emptyList())

        val restingHr = if (P_RESTING_HR !in granted) null else runCatching {
            client.aggregate(AggregateRequest(setOf(RestingHeartRateRecord.BPM_AVG), dayRange))[RestingHeartRateRecord.BPM_AVG]
        }.getOrNull()

        val spo2 = if (P_SPO2 !in granted) null else runCatching {
            client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, dayRange)).records
                .map { it.percentage.value }.filter { it in 50.0..100.0 }.takeIf { it.isNotEmpty() }?.average()
        }.getOrNull()

        val vo2 = if (P_VO2 !in granted) null else runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    Vo2MaxRecord::class,
                    TimeRangeFilter.between(start.minus(30, ChronoUnit.DAYS), end),
                    ascendingOrder = false, pageSize = 1
                )
            ).records.firstOrNull()?.vo2MillilitersPerMinuteKilogram
        }.getOrNull()

        val distance = if (P_DISTANCE !in granted) null else runCatching {
            client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), dayRange))[DistanceRecord.DISTANCE_TOTAL]
                ?.inKilometers
        }.getOrNull()?.takeIf { it > 0.01 }

        return DayBody(
            sleepMinutes = sleep,
            hrAvg = hr?.get(HeartRateRecord.BPM_AVG),
            hrMin = hr?.get(HeartRateRecord.BPM_MIN),
            hrMax = hr?.get(HeartRateRecord.BPM_MAX),
            workouts = workouts,
            stages = stages,
            restingHr = restingHr,
            spo2 = spo2,
            vo2max = vo2,
            distanceKm = distance
        )
    }

    /**
     * Basal metabolic rate and height from a watch, scale or health app.
     * BMR feeds the burn estimate; height only fills an empty profile field, never overwrites one.
     */
    suspend fun importBodyMeasurements(context: Context, client: HealthConnectClient, granted: Set<String>) {
        val prefs = HealthPrefs(context)
        val now = Instant.now()
        if (P_BMR in granted) {
            val latest = client.readRecords(
                ReadRecordsRequest(
                    BasalMetabolicRateRecord::class,
                    TimeRangeFilter.between(now.minus(30, ChronoUnit.DAYS), now),
                    ascendingOrder = false, pageSize = 1
                )
            ).records.firstOrNull()
            prefs.deviceBmr = latest?.basalMetabolicRate?.inKilocaloriesPerDay?.takeIf { it in 800.0..4000.0 } ?: 0.0
        }
        if (P_HEIGHT in granted) {
            val store = ProfileStore(context)
            val profile = store.load()
            if (profile.heightCm <= 0) {
                val cm = client.readRecords(
                    ReadRecordsRequest(
                        HeightRecord::class,
                        TimeRangeFilter.between(now.minus(3650, ChronoUnit.DAYS), now),
                        ascendingOrder = false, pageSize = 1
                    )
                ).records.firstOrNull()?.height?.inMeters?.times(100)
                if (cm != null && cm in 100.0..250.0) store.save(profile.copy(heightCm = cm))
            }
        }
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
            runCatching { HealthExtras.importBodyMeasurements(context, client, granted) }
                .onFailure { Log.w("HealthSync", "body measurements failed", it) }
        } else {
            prefs.deviceBmr = 0.0          // reading switched off: stop using the device's BMR
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
