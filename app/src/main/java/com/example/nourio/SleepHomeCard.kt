package com.example.nourio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nourio.health.SleepData
import com.example.nourio.insights.Metric
import com.example.nourio.insights.SleepNight
import com.example.nourio.insights.SleepStage

// Night-sky stage colours, readable on both the light and dark card
private val DeepC = Color(0xFF3F51D8)
private val RemC = Color(0xFF9C8CFF)
private val LightC = Color(0xFF6CB6FF)
private val AwakeC = Color(0xFFFFB547)

private fun stageColor(t: SleepStage.Type) = when (t) {
    SleepStage.Type.DEEP -> DeepC
    SleepStage.Type.REM -> RemC
    SleepStage.Type.LIGHT, SleepStage.Type.ASLEEP -> LightC
    SleepStage.Type.AWAKE -> AwakeC
}

/** Hypnogram lanes, top to bottom: awake, REM, light, deep. "Asleep" without detail sits with light. */
private fun lane(t: SleepStage.Type) = when (t) {
    SleepStage.Type.AWAKE -> 0
    SleepStage.Type.REM -> 1
    SleepStage.Type.LIGHT, SleepStage.Type.ASLEEP -> 2
    SleepStage.Type.DEEP -> 3
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier
)

/**
 * Home: the night before the day shown. Leads with time asleep and its grade, then the shape of the
 * night (stage strip with bedtime and wake time), the stage numbers, a week of nights against the
 * goal, and one line on what it means for today's eating and exercise.
 */
@Composable
fun LastNightCard(data: SleepData?, dayOffset: Int, goalH: Double, subject: Subject?) {
    val openPage = LocalOpenPage.current
    val title = if (dayOffset == 0) "Last night" else "Sleep before ${dayTitle(dayOffset).lowercase()}"
    val night = data?.nights?.get(dayOffset)
    WidgetCard(
        title,
        modifier = Modifier.clickable(enabled = night != null) { openPage("body:$dayOffset") },
        trailing = subject?.let { s -> { GradeChip(s.grade) } }
    ) {
        when {
            data == null -> Muted("Reading your sleep…")
            !data.connected -> {
                Muted("See how long and how well you slept, and how it shapes your appetite. Sleep comes from your watch or sleep app through Health Connect.")
                TextButton(onClick = { openPage("connections") }, contentPadding = PaddingValues(0.dp)) {
                    Text("Connect Health Connect")
                }
            }
            night == null -> Muted(
                "No sleep recorded for this night. Wear your watch to bed, or check that your sleep app shares with Health Connect."
            )
            else -> NightBody(night, data, dayOffset, goalH, subject)
        }
    }
}

@Composable
private fun GradeChip(grade: String) {
    val c = gradeColor(grade)
    Text(
        grade, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = c,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 3.dp)
    )
}

@Composable
private fun NightBody(night: SleepNight, data: SleepData, dayOffset: Int, goalH: Double, subject: Subject?) {
    val cs = MaterialTheme.colorScheme

    // 1. The headline number
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
        Text(Grading.hm(night.asleepH * 60), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Muted("  asleep  ·  goal ${goalH.toInt()} h", Modifier.padding(bottom = 4.dp))
    }
    night.efficiency?.let {
        Muted("${Grading.hm(night.inBedH * 60)} in bed  ·  ${it.toLong()}% of it asleep")
    }

    // 2. The shape of the night
    Spacer(Modifier.height(14.dp))
    Hypnogram(night, Modifier.fillMaxWidth().height(64.dp))
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("🌙 ${Metric.clock(night.bedtime % 24)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text("☀️ ${Metric.clock(night.wakeTime)}", style = MaterialTheme.typography.labelMedium)
    }

    // 3. Stages, when the device records them
    if (night.deepMin != null || night.awakeMin != null) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            night.deepMin?.let { StageStat(DeepC, Grading.hm(it), "deep", Modifier.weight(1f)) }
            night.remMin?.let { StageStat(RemC, Grading.hm(it), "REM", Modifier.weight(1f)) }
            night.awakeMin?.let { StageStat(AwakeC, Grading.hm(it), "awake", Modifier.weight(1f)) }
        }
    }

    // 4. A week of nights: is this one a pattern or a blip?
    Spacer(Modifier.height(16.dp))
    Text("Last 7 nights", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    WeekNights(data, dayOffset, goalH)

    // 5. What it means today
    val shift = data.shifts[dayOffset]
    val note = when {
        night.asleepH < 6 && dayOffset == 0 -> "Short night. Appetite and sugar cravings run higher after one, so plan protein at breakfast and keep exercise moderate today."
        night.asleepH < 6 -> "A short night. Days after one are usually bigger eating days."
        shift != null && shift > 1.0 -> "Bedtime was ${Grading.hm(shift * 60)} off your usual. A steady bedtime is the easiest sleep win."
        (night.efficiency ?: 100.0) < 85 -> subject?.comment ?: "A restless night."
        night.asleepH >= goalH && dayOffset == 0 -> "Well rested. A good day for a harder workout."
        else -> subject?.comment ?: "A solid night."
    }
    Text(
        note, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.6f)).padding(12.dp)
    )
}

@Composable
private fun StageStat(color: Color, value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
            Spacer(Modifier.width(6.dp))
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Muted(label, Modifier.padding(start = 14.dp))
    }
}

/** Four lanes, awake at the top and deep at the bottom, so the night reads like a sleep lab chart. */
@Composable
private fun Hypnogram(night: SleepNight, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val total = night.lengthMin.coerceAtLeast(1.0)
        val laneH = size.height / 4
        for (i in 0 until 4) {
            drawRoundRect(track.copy(alpha = 0.45f), Offset(0f, i * laneH + laneH * 0.2f), Size(size.width, laneH * 0.6f), CornerRadius(4f))
        }
        night.timeline.forEach { s ->
            val x = (s.fromMin / total * size.width).toFloat()
            val w = ((s.toMin - s.fromMin) / total * size.width).toFloat().coerceAtLeast(2f)
            val y = lane(s.type) * laneH + laneH * 0.1f
            drawRoundRect(stageColor(s.type), Offset(x, y), Size(w, laneH * 0.8f), CornerRadius(4f))
        }
    }
}

/** Seven bars of time asleep against a dashed goal line; the night on the card is solid. */
@Composable
private fun WeekNights(data: SleepData, dayOffset: Int, goalH: Double) {
    val cs = MaterialTheme.colorScheme
    val offsets = (6 downTo 0).map { dayOffset - it }
    val hours = offsets.map { data.nights[it]?.asleepH }
    val top = maxOf(goalH + 1.5, hours.filterNotNull().maxOrNull() ?: 0.0)
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val slot = size.width / 7
        val barW = slot * 0.5f
        hours.forEachIndexed { i, h ->
            val x = i * slot + (slot - barW) / 2
            if (h == null) {
                drawRoundRect(cs.surfaceVariant, Offset(x, size.height - 4f), Size(barW, 4f), CornerRadius(2f))
            } else {
                val bh = (h / top * size.height).toFloat()
                val c = if (h >= goalH) LightC else AwakeC
                drawRoundRect(c.copy(alpha = if (i == 6) 1f else 0.55f), Offset(x, size.height - bh), Size(barW, bh), CornerRadius(6f))
            }
        }
        val gy = (size.height - goalH / top * size.height).toFloat()
        drawLine(cs.onSurfaceVariant, Offset(0f, gy), Offset(size.width, gy), 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
    }
    Row(Modifier.fillMaxWidth()) {
        offsets.forEach { o ->
            Text(dayLetter(o), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
