package com.example.nourio.insights

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.example.nourio.data.MealDb
import com.example.nourio.data.WaterStore
import com.example.nourio.data.WeightDb
import com.example.nourio.data.totals
import com.example.nourio.energy.EnergyRepo
import com.example.nourio.health.HealthExtras
import com.example.nourio.health.HealthPrefs
import com.example.nourio.health.HealthRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Builds [DayMetrics] for the last [days] days from the food log, water, weigh-ins and Health Connect. */
object HistoryLoader {
    private const val MIN_LOGGED_KCAL = 800.0

    suspend fun load(context: Context, db: MealDb, days: Int = 60): List<DayMetrics> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val first = today.minusDays(days.toLong() - 1)
        val values = (0 until days).associate { first.plusDays(it.toLong()) to HashMap<Metric, Double>() }

        // Food: intake measures only count on days with a full log
        val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        db.between(start, end).groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .forEach { (date, meals) ->
                val t = meals.flatMap { it.items }.totals()
                val v = values[date] ?: return@forEach
                if (t.kcal < MIN_LOGGED_KCAL && date != today) return@forEach
                if (date == today) return@forEach       // today isn't finished, so it would skew every link
                v[Metric.KCAL] = t.kcal
                v[Metric.PROTEIN] = t.protein
                v[Metric.FIBER] = t.fiber
                v[Metric.SUGAR] = t.addedSugar
                v[Metric.SAT_FAT] = t.satFat
                v[Metric.SODIUM] = t.sodium
                val last = Instant.ofEpochMilli(meals.maxOf { it.timestamp }).atZone(zone)
                v[Metric.LAST_MEAL] = last.hour + last.minute / 60.0
            }

        // Water: finished days only
        val water = WaterStore(context).allDays()
        values.forEach { (date, v) -> if (date != today) water[date.toString()]?.let { v[Metric.WATER] = it.toDouble() } }

        // Weight: the first weigh-in of each day, and the change to the next day's
        val weights = WeightDb(context).all().groupBy { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> list.minBy { it.ts }.kg }
        weights.forEach { (date, kg) ->
            values[date]?.set(Metric.WEIGHT, kg)
            weights[date.plusDays(1)]?.let { next -> values[date]?.set(Metric.WEIGHT_CHANGE, next - kg) }
        }

        // Store screenshots render on the PC, where Health Connect doesn't exist: use the test's activity
        val demo = EnergyRepo.activityOverride
        if (demo != null) values.forEach { (date, v) ->
            if (date != today) demo(ChronoUnit.DAYS.between(today, date).toInt())?.let { if (it.steps > 0) v[Metric.STEPS] = it.steps.toDouble() }
        } else readHealthConnect(context, first, today, values)
        values.entries.sortedBy { it.key }.map { DayMetrics(it.key, it.value) }
    }

    private suspend fun readHealthConnect(
        context: Context, first: LocalDate, today: LocalDate, values: Map<LocalDate, HashMap<Metric, Double>>
    ) {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return
        val prefs = HealthPrefs(context)
        if (!prefs.enabled) return
        val client = HealthConnectClient.getOrCreate(context)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        val zone = ZoneId.systemDefault()
        val range = TimeRangeFilter.between(first.atStartOfDay(), today.atStartOfDay())   // finished days

        // One grouped request per data type: missing permissions would fail a combined request
        suspend fun <T : Any> daily(metric: AggregateMetric<T>, put: (HashMap<Metric, Double>, T) -> Unit) = runCatching {
            client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(metric), range, Period.ofDays(1)))
                .forEach { g -> g.result[metric]?.let { r -> values[g.startTime.toLocalDate()]?.let { put(it, r) } } }
        }

        if (granted.containsAll(HealthRepo.PERMISSIONS)) {
            daily(StepsRecord.COUNT_TOTAL) { v, r -> if (r > 0) v[Metric.STEPS] = r.toDouble() }
        }
        if (prefs.readExtras && granted.containsAll(HealthExtras.READ_PERMS)) {
            daily(HeartRateRecord.BPM_AVG) { v, r -> v[Metric.AVG_HR] = r.toDouble() }
            // Sleep belongs to the day you wake up
            runCatching {
                client.readRecords(
                    ReadRecordsRequest(
                        SleepSessionRecord::class,
                        TimeRangeFilter.between(first.minusDays(1).atStartOfDay(zone).toInstant(), today.atTime(18, 0).atZone(zone).toInstant())
                    )
                ).records.groupBy { it.endTime.atZone(zone).toLocalDate() }.forEach { (date, sessions) ->
                    val h = sessions.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() } / 60.0
                    if (h in 1.0..16.0) values[date]?.set(Metric.SLEEP, h)
                }
            }
        }
        if (HealthExtras.P_RESTING_HR in granted) {
            daily(RestingHeartRateRecord.BPM_AVG) { v, r -> v[Metric.RESTING_HR] = r.toDouble() }
        }
        if (HealthExtras.P_BLOOD_PRESSURE in granted) {
            daily(BloodPressureRecord.SYSTOLIC_AVG) { v, r -> v[Metric.SYSTOLIC] = r.inMillimetersOfMercury }
            daily(BloodPressureRecord.DIASTOLIC_AVG) { v, r -> v[Metric.DIASTOLIC] = r.inMillimetersOfMercury }
        }
    }
}
