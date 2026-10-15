package com.example.nourio.insights

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A sleep session as Health Connect reports it, reduced to what the analysis needs. */
data class SleepSession(val start: Instant, val end: Instant, val stages: List<SleepStage> = emptyList()) {
    val minutes get() = Duration.between(start, end).toMinutes()
}

data class SleepStage(val start: Instant, val end: Instant, val type: Type) {
    enum class Type { AWAKE, LIGHT, DEEP, REM, ASLEEP }
    val minutes get() = Duration.between(start, end).toMinutes()
}

/**
 * One night, from every session that ended on the same day (the day you wake up), naps included.
 * Stage figures are null when the watch or app didn't record stages.
 */
data class SleepNight(
    val asleepH: Double,
    val bedtime: Double,          // hour the main sleep began; after midnight counts on past 24 (00:30 = 24.5)
    val wakeTime: Double,         // hour the main sleep ended
    val awakeMin: Double?,
    val efficiency: Double?,      // % of time in bed spent asleep
    val deepMin: Double?,
    val remMin: Double?,
    val inBedH: Double = asleepH,
    val timeline: List<Segment> = emptyList(),    // the main sleep, in minutes from bedtime, for the stage strip
) {
    /** A stretch of the main sleep. [type] ASLEEP when the device only says "asleep". */
    data class Segment(val fromMin: Double, val toMin: Double, val type: SleepStage.Type)

    val lengthMin: Double get() = timeline.maxOfOrNull { it.toMin } ?: (inBedH * 60)

    fun into(v: MutableMap<Metric, Double>) {
        v[Metric.SLEEP] = asleepH
        v[Metric.BEDTIME] = bedtime
        v[Metric.WAKE_TIME] = wakeTime
        awakeMin?.let { v[Metric.AWAKE] = it }
        efficiency?.let { v[Metric.SLEEP_EFFICIENCY] = it }
        deepMin?.let { v[Metric.DEEP_SLEEP] = it }
        remMin?.let { v[Metric.REM_SLEEP] = it }
    }

    companion object {
        /** Null for nothing that looks like a night: under 1 h or over 16 h in bed. */
        fun of(sessions: List<SleepSession>, zone: ZoneId): SleepNight? {
            if (sessions.isEmpty()) return null
            val inBed = sessions.sumOf { it.minutes }.toDouble()
            if (inBed < 60 || inBed > 16 * 60) return null

            val main = sessions.maxBy { it.minutes }
            val startAt = main.start.atZone(zone)
            var bedtime = startAt.hour + startAt.minute / 60.0
            if (bedtime < 15) bedtime += 24                  // went to bed after midnight
            val endAt = main.end.atZone(zone)

            fun fromBed(t: java.time.Instant) = Duration.between(main.start, t).toMillis() / 60_000.0
            val timeline = if (main.stages.isEmpty()) listOf(Segment(0.0, main.minutes.toDouble(), SleepStage.Type.ASLEEP))
                else main.stages.sortedBy { it.start }.map { Segment(fromBed(it.start), fromBed(it.end), it.type) }

            val stages = sessions.flatMap { it.stages }
            if (stages.isEmpty()) {
                return SleepNight(inBed / 60, bedtime, endAt.hour + endAt.minute / 60.0, null, null, null, null, inBed / 60, timeline)
            }
            fun sum(t: SleepStage.Type) = stages.filter { it.type == t }.sumOf { it.minutes }.toDouble()
            val awake = sum(SleepStage.Type.AWAKE).coerceAtMost(inBed)
            val asleep = inBed - awake
            // Some devices only record "asleep" and "awake": no deep or REM figures then
            val hasDepth = stages.any { it.type == SleepStage.Type.DEEP || it.type == SleepStage.Type.REM }
            return SleepNight(
                asleepH = asleep / 60,
                bedtime = bedtime,
                wakeTime = endAt.hour + endAt.minute / 60.0,
                awakeMin = awake,
                efficiency = asleep / inBed * 100,
                deepMin = if (hasDepth) sum(SleepStage.Type.DEEP) else null,
                remMin = if (hasDepth) sum(SleepStage.Type.REM) else null,
                inBedH = inBed / 60,
                timeline = timeline,
            )
        }

        /**
         * How far each bedtime was from your usual one: the median of the 7 nights before,
         * when at least 3 of them are known.
         */
        fun bedtimeShifts(bedtimes: Map<LocalDate, Double>): Map<LocalDate, Double> =
            bedtimes.mapNotNull { (date, bt) ->
                val before = (1..7L).mapNotNull { bedtimes[date.minusDays(it)] }.sorted()
                if (before.size < 3) return@mapNotNull null
                val median = if (before.size % 2 == 1) before[before.size / 2]
                    else (before[before.size / 2 - 1] + before[before.size / 2]) / 2
                date to kotlin.math.abs(bt - median)
            }.toMap()
    }
}
