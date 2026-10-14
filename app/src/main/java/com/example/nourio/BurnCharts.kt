package com.example.nourio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.nourio.energy.DayEnergy
import com.example.nourio.energy.EnergyModel
import java.text.NumberFormat

private val nf: NumberFormat get() = NumberFormat.getIntegerInstance()
private fun signed(d: Double) = (if (d >= 0) "+" else "−") + nf.format(kotlin.math.abs(d).toLong())

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Eaten vs burned, last 7 days (newest first in [days]). Each day is a pale "burned" bar with the
 * eaten amount inside it, so a gap at the top is a deficit; the day's net is printed underneath.
 */
@Composable
fun EatenBurnedCard(days: List<DayEnergy>) {
    val cs = MaterialTheme.colorScheme
    val eatenColor = cs.primary
    val burnedColor = cs.secondary
    val ordered = days.reversed()
    val logged = days.filter { it.eaten >= EnergyModel.MIN_LOGGED_KCAL }
    val maxV = (days.maxOfOrNull { maxOf(it.eaten, it.burned) } ?: 1.0).coerceAtLeast(1.0)

    WidgetCard(
        "Eaten vs burned",
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(eatenColor, "Eaten")
                LegendDot(burnedColor.copy(alpha = 0.45f), "Burned")
            }
        }
    ) {
        if (logged.isNotEmpty()) {
            val avgE = logged.map { it.eaten }.average()
            val avgB = logged.map { it.burned }.average()
            Row(Modifier.fillMaxWidth()) {
                Stat("avg eaten", nf.format(avgE.toLong()), eatenColor, Modifier.weight(1f))
                Stat("avg burned", nf.format(avgB.toLong()), burnedColor, Modifier.weight(1f))
                Stat("avg net / day", signed(avgE - avgB), if (avgE <= avgB) eatenColor else cs.error, Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
        }
        Row(
            Modifier.fillMaxWidth().height(130.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            ordered.forEach { d ->
                // Burned is the outer bar; eaten sits inside it, centred and narrower
                Box(
                    Modifier.weight(1f).fillMaxHeight((d.burned / maxV).toFloat().coerceIn(0.02f, 1f))
                        .clip(RoundedCornerShape(6.dp)).background(burnedColor.copy(alpha = 0.28f)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (d.eaten > 0) {
                        val share = (d.eaten / d.burned.coerceAtLeast(1.0)).toFloat()
                        Box(
                            Modifier.fillMaxWidth(0.55f).fillMaxHeight(share.coerceIn(0.02f, 1f))
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (d.eaten > d.burned) cs.error else eatenColor)
                        )
                    }
                }
            }
        }
        // Over-burn days push the eaten bar to full height, so mark them in the net row instead
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ordered.forEach { d ->
                val net = d.eaten - d.burned
                Text(
                    if (d.eaten < EnergyModel.MIN_LOGGED_KCAL) "—" else signed(net),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        d.eaten < EnergyModel.MIN_LOGGED_KCAL -> cs.onSurfaceVariant
                        net <= 0 -> eatenColor
                        else -> cs.error
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ordered.forEach { d ->
                Text(
                    dayLetter(d.offset),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            "Net is eaten minus burned. Below zero (green) means you burned more than you ate. " +
                "\"—\" marks days with too little logged to count.",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}



/** Steps per day as a line with the step goal as a guide. Hidden when there is no step data. */
@Composable
fun StepsCard(days: List<DayEnergy>, stepGoal: Long) {
    val ordered = days.reversed()
    val steps = ordered.map { it.steps ?: 0L }
    if (steps.all { it == 0L }) return
    val cs = MaterialTheme.colorScheme
    val line = cs.tertiary
    val guide = cs.onSurfaceVariant.copy(alpha = 0.5f)
    val withData = steps.filter { it > 0 }
    val maxV = maxOf(steps.max(), stepGoal).toFloat() * 1.1f

    WidgetCard("Daily steps", trailing = { Pill("goal ${nf.format(stepGoal)}") }) {
        Row(Modifier.fillMaxWidth()) {
            Stat(if (ordered.last().offset == 0) "today" else "that day", nf.format(steps.last()), line, Modifier.weight(1f))
            Stat("avg", nf.format(withData.average().toLong()), line, Modifier.weight(1f))
            Stat("best", nf.format(withData.max()), line, Modifier.weight(1f))
            Stat("goal days", "${withData.count { it >= stepGoal }}/${steps.size}", line, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val w = size.width
            val h = size.height
            val n = steps.size
            fun x(i: Int) = if (n == 1) w / 2 else w * (i + 0.5f) / n
            fun y(v: Long) = h - (v / maxV) * h

            // Dashed goal line
            val gy = y(stepGoal)
            drawLine(guide, Offset(0f, gy), Offset(w, gy), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))

            // Filled area under the line
            val path = Path()
            steps.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(i), y(s)) else path.lineTo(x(i), y(s)) }
            val area = Path().apply {
                addPath(path)
                lineTo(x(n - 1), h); lineTo(x(0), h); close()
            }
            drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0f))))
            drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            steps.forEachIndexed { i, s ->
                drawCircle(if (s >= stepGoal) line else cs.surface, 5.dp.toPx(), Offset(x(i), y(s)))
                drawCircle(line, 5.dp.toPx(), Offset(x(i), y(s)), style = Stroke(2.dp.toPx()))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            ordered.forEach { d ->
                Text(
                    dayLetter(d.offset),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            "Filled dots are days you reached ${nf.format(stepGoal)} steps.",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
