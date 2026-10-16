package com.example.nourio.exercise

import android.content.Context
import com.example.nourio.data.MealDb
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.totals
import com.example.nourio.energy.CalibrationStore
import com.example.nourio.energy.EnergyRepo
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * The early-evening exercise suggestion, worked out on the phone from today's meals and the activity
 * the app last read. Only sent when there's a real surplus to balance; otherwise there's no message.
 */
object ExerciseNudge {
    const val HOUR = 18                 // late enough to know the day, early enough to act on it
    private const val MIN_TARGET = 150

    data class Nudge(val title: String, val text: String)

    fun build(context: Context): Nudge? {
        EnergyRepo.init(context)
        val body = EnergyRepo.body(ProfileStore(context).load()).first ?: return null
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val db = MealDb(context)
        val eaten = try {
            db.between(start, System.currentTimeMillis() + 60_000).flatMap { it.items }.totals().kcal
        } finally { db.close() }
        if (eaten <= 0) return null                               // nothing logged: nothing to balance

        val saved = EnergyRepo.savedToday(context)
        val day = EnergyRepo.build(body, 0, eaten, saved?.first)
        val burned = day.burned * CalibrationStore(context).factor
        val rec = ExerciseCoach.recommend(
            CoachInput(
                weightKg = body.weightKg, age = body.age, eaten = eaten, burned = burned,
                weekBalances = emptyList(), steps = day.steps, stepGoal = com.example.nourio.data.GoalStore(context).load().stepGoal,
                workouts = emptyList(), sleepLastNightH = null, hour = HOUR,
            )
        )
        if (rec.targetKcal < MIN_TARGET || rec.options.isEmpty()) return null

        val first = rec.options[0]
        val second = rec.options.getOrNull(1)
        val asOf = saved?.second?.let { " Activity counted up to ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}." }
            ?: " Open Nourio during the day so your steps count too."
        return Nudge(
            "${first.activity.emoji} ${first.activity.label}: ${first.minutes} min would balance today",
            "You've eaten about ${rec.targetKcal} kcal more than you've burned so far." +
                (second?.let { " Or ${it.activity.label.lowercase()} for ${it.minutes} min." } ?: "") + asOf
        )
    }
}
