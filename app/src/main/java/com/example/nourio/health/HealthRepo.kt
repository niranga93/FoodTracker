package com.example.nourio.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.ZoneId

data class DayActivity(
    val steps: Long,
    val activeKcal: Double,
    val totalKcal: Double,
    val distanceKm: Double = 0.0        // 0 when not recorded or not allowed
)

object HealthRepo {
    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
    )

    suspend fun hasPermissions(client: HealthConnectClient): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    /** offset 0 = today, -1 = yesterday, ... */
    suspend fun readDay(client: HealthConnectClient, offset: Int, withDistance: Boolean = false): DayActivity {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone).plusDays(offset.toLong())
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()

        val r = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL
                ),
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        // Distance is an optional permission, so it is asked for separately: a missing one would fail the request above
        val km = if (!withDistance) 0.0 else runCatching {
            client.aggregate(
                AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), TimeRangeFilter.between(start, end))
            )[DistanceRecord.DISTANCE_TOTAL]?.inKilometers
        }.getOrNull() ?: 0.0
        return DayActivity(
            steps = r[StepsRecord.COUNT_TOTAL] ?: 0L,
            activeKcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0,
            totalKcal = r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0,
            distanceKm = km
        )
    }
}