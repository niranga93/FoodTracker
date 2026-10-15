package com.example.nourio.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseCoachTest {
    private fun input(
        eaten: Double = 2400.0, burned: Double = 2000.0, hour: Int = 10, age: Int = 35,
        workouts: List<PastWorkout> = emptyList(), sleep: Double? = 7.5, steps: Long? = 4000,
        week: List<Double> = emptyList(),
    ) = CoachInput(75.0, age, eaten, burned, week, steps, 8000, workouts, sleep, hour)

    @Test
    fun netKcalUsesMetAboveResting() {
        // Brisk walk: (4.3 - 1) x 75 kg / 60 = 4.125 kcal a minute
        assertEquals(4.125, Activity.BRISK_WALK.kcalPerMinute(75.0), 1e-9)
    }

    @Test
    fun surplusSetsTheTargetAndMinutesCoverIt() {
        val r = ExerciseCoach.recommend(input())
        assertEquals(400, r.targetKcal)
        assertEquals(3, r.options.size)
        r.options.filter { it.minutes < 75 }.forEach { assertTrue("${it.activity} burns ${it.kcal}", it.kcal >= 400) }
    }

    @Test
    fun hugeSurplusIsCapped() {
        val r = ExerciseCoach.recommend(input(eaten = 4000.0))
        assertEquals(ExerciseCoach.MAX_TARGET_KCAL, r.targetKcal)
        assertTrue(r.reason.contains("tomorrow"))
    }

    @Test
    fun deficitNeedsNoBurnTarget() {
        val r = ExerciseCoach.recommend(input(eaten = 1500.0))
        assertEquals(0, r.targetKcal)
        assertTrue(r.options.isNotEmpty())
    }

    @Test
    fun weekSurplusGivesATargetOnABalancedDay() {
        val r = ExerciseCoach.recommend(input(eaten = 1900.0, week = listOf(400.0, 300.0, 500.0)))
        assertEquals(300, r.targetKcal)
    }

    @Test
    fun nothingVigorousLateInTheEvening() {
        val r = ExerciseCoach.recommend(input(hour = 21, eaten = 2700.0))
        assertTrue(r.options.none { it.activity.intensity == Intensity.VIGOROUS })
        assertTrue(r.options.any { it.activity == Activity.EASY_WALK })
    }

    @Test
    fun recoveryAfterAHardRunYesterday() {
        val r = ExerciseCoach.recommend(input(eaten = 2700.0, workouts = listOf(PastWorkout(1, "Running", 50))))
        assertTrue(r.options.none { it.activity.intensity == Intensity.VIGOROUS })
    }

    @Test
    fun shortSleepKeepsItModerate() {
        val r = ExerciseCoach.recommend(input(eaten = 2700.0, sleep = 5.0))
        assertTrue(r.options.none { it.activity.intensity == Intensity.VIGOROUS })
    }

    @Test
    fun weekWithoutStrengthSuggestsStrength() {
        val r = ExerciseCoach.recommend(input(eaten = 2100.0, workouts = listOf(PastWorkout(2, "Walking", 40))))
        assertTrue(r.options.any { it.activity == Activity.STRENGTH })
    }

    @Test
    fun noJoggingOrHiitFrom65() {
        val r = ExerciseCoach.recommend(input(eaten = 2600.0, age = 70))
        assertTrue(r.options.none { it.activity == Activity.JOG || it.activity == Activity.HIIT })
    }

    @Test
    fun workoutNamesAreClassified() {
        assertEquals(Kind.STRENGTH, PastWorkout(0, "Gym: upper body weights", 45).kind)
        assertTrue(PastWorkout(0, "Morning run", 40).hard)
        assertEquals(Kind.MOBILITY, PastWorkout(0, "Pilates", 30).kind)
    }
}
