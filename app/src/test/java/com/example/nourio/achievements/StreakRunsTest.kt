package com.example.nourio.achievements

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StreakRunsTest {
    private val today = LocalDate.of(2026, 10, 16)
    private fun ago(vararg n: Long) = n.map { today.minusDays(it) }.toSet()

    @Test fun emptyHasNoStreak() = assertEquals(0 to 0, Achievements.runs(emptySet(), today))

    @Test fun todayCountsOnceLogged() = assertEquals(3 to 3, Achievements.runs(ago(0, 1, 2), today))

    @Test fun streakSurvivesUntilTodayIsLogged() = assertEquals(2 to 2, Achievements.runs(ago(1, 2), today))

    @Test fun gapBreaksTheStreak() = assertEquals(0 to 2, Achievements.runs(ago(2, 3), today))

    @Test fun bestIsTheLongestRun() = assertEquals(1 to 4, Achievements.runs(ago(0, 5, 6, 7, 8, 10), today))
}
