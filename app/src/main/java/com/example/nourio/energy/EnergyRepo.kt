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

    private val activityCache = HashMap<String, Pair<Long, DayActivity>>()

    /** Forgets saved Health Connect readings, so the next read fetches fresh ones. */
    fun clearCache() {
        synchronized(activityCache) { activityCache.clear() }
    }

    /**
     * Demo hook: supplies activity instead of Health Connect. Only the store-screenshot test sets it
     * (the screens render on the PC, where Health Connect doesn't exist); null in the app.
     */
    @Volatile var activityOverride: ((Int) -> DayActivity?)? = null

    /** Health Connect data per day offset. Empty if not available, switched off, or not permitted. */
    suspend fun activityFor(context: Context, offsets: List<Int>): Map<Int, DayActivity> {
        activityOverride?.let { f -> return offsets.mapNotNull { o -> f(o)?.let { o to it } }.toMap() }
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
                    if (o == 0) saveToday(context, it)
                }
            }
        }
        return out
    }

    /**
     * Active energy hour by hour since [fromMs], as (hour start, kcal), for the fasting fuel estimate.
     * Empty when Health Connect is unavailable, switched off or not permitted.
     */
    suspend fun hourlyActive(context: Context, fromMs: Long, kcalPerStep: Double): List<Pair<Long, Double>> {
        if (activityOverride != null) return emptyList()
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return emptyList()
        if (!HealthPrefs(context).enabled) return emptyList()
        val client = HealthConnectClient.getOrCreate(context)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        if (!granted.containsAll(HealthRepo.PERMISSIONS)) return emptyList()
        return runCatching {
            HealthRepo.readHourlyActive(client, java.time.Instant.ofEpochMilli(fromMs), java.time.Instant.now(), kcalPerStep)
        }.getOrDefault(emptyList())
    }

    // Health Connect can't be read in the background, so today's activity is kept from the last time
    // the app read it, for the early-evening exercise suggestion.
    private fun saveToday(context: Context, a: DayActivity) {
        context.getSharedPreferences("activity_today", Context.MODE_PRIVATE).edit()
            .putString("date", LocalDate.now().toString())
            .putLong("steps", a.steps).putFloat("active", a.activeKcal.toFloat())
            .putFloat("total", a.totalKcal.toFloat()).putFloat("km", a.distanceKm.toFloat())
            .putLong("at", System.currentTimeMillis())
            .apply()
    }

    /** Today's activity as last read in the app, and when; null if it hasn't been read today. */
    fun savedToday(context: Context): Pair<DayActivity, Long>? {
        val p = context.getSharedPreferences("activity_today", Context.MODE_PRIVATE)
        if (p.getString("date", null) != LocalDate.now().toString()) return null
        return DayActivity(p.getLong("steps", 0), p.getFloat("active", 0f).toDouble(), p.getFloat("total", 0f).toDouble(),
            p.getFloat("km", 0f).toDouble()) to p.getLong("at", 0L)
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
