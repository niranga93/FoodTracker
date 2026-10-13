package com.example.foodtracker.energy

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import com.example.foodtracker.data.Profile
import com.example.foodtracker.health.DayActivity
import com.example.foodtracker.health.HealthPrefs
import com.example.foodtracker.health.HealthRepo
import java.util.Calendar

object EnergyRepo {

    /** Returns a Body, or null plus a message explaining what's missing. */
    fun body(profile: Profile): Pair<Body?, String?> {
        if (profile.heightCm <= 0 || profile.weightKg <= 0 || profile.birthYear <= 0) {
            return null to "Add your height, weight and year of birth in Profile to see calories burned and your balance."
        }
        val age = Calendar.getInstance().get(Calendar.YEAR) - profile.birthYear
        if (age < 18) {
            return null to "Calorie balance estimates are only available for adults (18+)."
        }
        return Body(profile.weightKg, profile.heightCm, age, profile.sex) to null
    }

    /** Health Connect data per day offset. Empty if not available, switched off, or not permitted. */
    suspend fun activityFor(context: Context, offsets: List<Int>): Map<Int, DayActivity> {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return emptyMap()
        if (!HealthPrefs(context).enabled) return emptyMap()
        val client = HealthConnectClient.getOrCreate(context)
        if (!runCatching { HealthRepo.hasPermissions(client) }.getOrDefault(false)) return emptyMap()
        val out = HashMap<Int, DayActivity>()
        for (o in offsets) {
            runCatching { HealthRepo.readDay(client, o) }.onSuccess { out[o] = it }
        }
        return out
    }

    fun build(body: Body, offset: Int, eaten: Double, act: DayActivity?): DayEnergy {
        val steps = act?.steps?.takeIf { it > 0 }
        val active = act?.activeKcal?.takeIf { it > 0 }
        val total = act?.totalKcal?.takeIf { it > 0 }
        val (burn, source) = EnergyModel.estimateBurn(body, steps, active, total, dayComplete = offset < 0)
        return DayEnergy(
            offset = offset,
            eaten = eaten,
            burned = burn,
            source = source,
            steps = steps,
            logComplete = offset < 0 && eaten >= EnergyModel.MIN_LOGGED_KCAL
        )
    }
}
