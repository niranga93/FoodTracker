package com.example.nourio.energy

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import com.example.nourio.data.Profile
import com.example.nourio.health.DayActivity
import com.example.nourio.health.HealthExtras
import com.example.nourio.health.HealthPrefs
import com.example.nourio.health.HealthRepo
import java.time.LocalDate
import java.util.Calendar

object EnergyRepo {
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** BMR from Health Connect, only while reading is switched on. */
    private fun deviceBmr(): Double? {
        val ctx = appContext ?: return null
        val prefs = HealthPrefs(ctx)
        if (!prefs.enabled || !prefs.readExtras) return null
        return prefs.deviceBmr.takeIf { it > 0 }
    }

    /** Returns a Body, or null plus a message explaining what's missing. */
    fun body(profile: Profile): Pair<Body?, String?> {
        if (profile.heightCm <= 0 || profile.weightKg <= 0 || profile.birthYear <= 0) {
            return null to "Add your height, weight and year of birth in Profile to see calories burned and your balance."
        }
        val age = Calendar.getInstance().get(Calendar.YEAR) - profile.birthYear
        if (age < 18) {
            return null to "Calorie balance estimates are only available for adults (18+)."
        }
        return Body(profile.weightKg, profile.heightCm, age, profile.sex, deviceBmr()) to null
    }

    private val activityCache = HashMap<String, Pair<Long, DayActivity>>()

    /** Forgets saved Health Connect readings, so the next read fetches fresh ones. */
    fun clearCache() {
        synchronized(activityCache) { activityCache.clear() }
    }

    /** Health Connect data per day offset. Empty if not available, switched off, or not permitted. */
    suspend fun activityFor(context: Context, offsets: List<Int>): Map<Int, DayActivity> {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return emptyMap()
        if (!HealthPrefs(context).enabled) return emptyMap()
        val client = HealthConnectClient.getOrCreate(context)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        if (!granted.containsAll(HealthRepo.PERMISSIONS)) return emptyMap()
        val withDistance = HealthExtras.P_DISTANCE in granted

        val out = HashMap<Int, DayActivity>()
        val now = System.currentTimeMillis()
        for (o in offsets) {
            val key = LocalDate.now().plusDays(o.toLong()).toString()
            val ttl = if (o < 0) 600_000L else 60_000L        // finished days: 10 min, today: 1 min
            val hit = synchronized(activityCache) { activityCache[key] }
            if (hit != null && now - hit.first < ttl) {
                out[o] = hit.second
            } else {
                runCatching { HealthRepo.readDay(client, o, withDistance) }.onSuccess {
                    out[o] = it
                    synchronized(activityCache) { activityCache[key] = now to it }
                }
            }
        }
        return out
    }

    fun build(body: Body, offset: Int, eaten: Double, act: DayActivity?): DayEnergy {
        val steps = act?.steps?.takeIf { it > 0 }
        val active = act?.activeKcal?.takeIf { it > 0 }
        val total = act?.totalKcal?.takeIf { it > 0 }
        val est = EnergyModel.estimateBurn(body, steps, active, total, act?.distanceKm?.takeIf { it > 0 })
        return DayEnergy(
            offset = offset,
            eaten = eaten,
            burned = est.burned,
            source = est.source,
            steps = steps,
            logComplete = offset < 0 && eaten >= EnergyModel.MIN_LOGGED_KCAL,
            resting = est.resting
        )
    }
}
