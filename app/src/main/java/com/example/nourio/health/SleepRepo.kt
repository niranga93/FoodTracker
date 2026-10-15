package com.example.nourio.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.example.nourio.insights.SleepNight
import com.example.nourio.insights.SleepSession
import com.example.nourio.insights.SleepStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** What Home knows about sleep: whether it can be read, and the nights by day offset (0 = last night). */
data class SleepData(val connected: Boolean, val nights: Map<Int, SleepNight>, val shifts: Map<Int, Double>)

object SleepRepo {
    private val READ_SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)

    /** Health Connect's sleep stage codes, in the app's own terms. Unknown stages are left out. */
    fun SleepSessionRecord.toSession() = SleepSession(
        startTime, endTime,
        stages.mapNotNull { s ->
            val type = when (s.stage) {
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepStage.Type.AWAKE
                SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStage.Type.LIGHT
                SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStage.Type.DEEP
                SleepSessionRecord.STAGE_TYPE_REM -> SleepStage.Type.REM
                SleepSessionRecord.STAGE_TYPE_SLEEPING -> SleepStage.Type.ASLEEP
                else -> null
            }
            type?.let { SleepStage(s.startTime, s.endTime, it) }
        }
    )

    /**
     * The night ending on [dayOffset]'s morning and the 14 before it, plus how far each bedtime was
     * from the usual one. Sleep counts for the day you wake up (sessions ending before 6 pm).
     */
    suspend fun load(context: Context, dayOffset: Int): SleepData = withContext(Dispatchers.IO) {
        val prefs = HealthPrefs(context)
        if (!prefs.enabled || !prefs.readExtras ||
            HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE
        ) return@withContext SleepData(false, emptyMap(), emptyMap())
        val client = HealthConnectClient.getOrCreate(context)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        if (READ_SLEEP !in granted) return@withContext SleepData(false, emptyMap(), emptyMap())

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val last = today.plusDays(dayOffset.toLong())
        val first = last.minusDays(14)
        val records = runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    SleepSessionRecord::class,
                    TimeRangeFilter.between(first.minusDays(1).atTime(18, 0).atZone(zone).toInstant(), last.atTime(18, 0).atZone(zone).toInstant())
                )
            ).records
        }.getOrDefault(emptyList())

        val byDate = records.groupBy { it.endTime.atZone(zone).toLocalDate() }
            .mapNotNull { (date, rs) -> SleepNight.of(rs.map { it.toSession() }, zone)?.let { date to it } }.toMap()
        fun offset(d: LocalDate) = (d.toEpochDay() - today.toEpochDay()).toInt()
        val shifts = SleepNight.bedtimeShifts(byDate.mapValues { it.value.bedtime })
        SleepData(true, byDate.mapKeys { offset(it.key) }, shifts.mapKeys { offset(it.key) })
    }
}
