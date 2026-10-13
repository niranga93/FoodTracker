package com.example.foodtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.foodtracker.data.GoalStore
import com.example.foodtracker.data.MealDb
import com.example.foodtracker.data.ProfileStore
import com.example.foodtracker.data.totals
import com.example.foodtracker.energy.CalibrationStore
import com.example.foodtracker.energy.DayEnergy
import com.example.foodtracker.energy.EnergyModel
import com.example.foodtracker.energy.EnergyRepo
import java.text.NumberFormat
import kotlin.math.ceil
import kotlin.math.max

private fun signedKcal(d: Double) = "%+.0f".format(d)

@Composable
private fun ThickBar(progress: Float, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
private fun BarRow(label: String, value: String, sub: String?, progress: Float, color: Color) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        ThickBar(progress, color)
        sub?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
fun HomeScreen(
    db: MealDb,
    goalStore: GoalStore,
    dayOffset: Int,
    onDayChange: (Int) -> Unit,
    onOpenTab: (NavTab) -> Unit,
    inner: PaddingValues
) {
    val context = LocalContext.current
    val profile = remember { ProfileStore(context).load() }
    val (body, reason) = remember { EnergyRepo.body(profile) }
    val goals = remember { goalStore.load() }
    val eaten = remember(dayOffset) { loadDay(db, dayOffset).flatMap { it.items }.totals().kcal }
    var day by remember(dayOffset) { mutableStateOf<DayEnergy?>(null) }
    val nf = remember { NumberFormat.getIntegerInstance() }

    LaunchedEffect(dayOffset) {
        if (body != null) {
            val act = EnergyRepo.activityFor(context, listOf(dayOffset))[dayOffset]
            val raw = EnergyRepo.build(body, dayOffset, eaten, act)
            day = raw.copy(burned = raw.burned * CalibrationStore(context).factor)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(inner),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLogo(36.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "FoodTracker",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        item { DateNavigator(dayOffset, onDayChange) }

        // ---- intake vs burned and the balance
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Energy balance", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val d = day
                    val goalText = if (goals.kcal > 0) "Goal ${goals.kcal.f0()} kcal" else null
                    when {
                        body == null -> {
                            Muted(reason ?: "", Modifier.padding(top = 6.dp))
                            val scale = max(max(eaten, goals.kcal), 1.0)
                            BarRow(
                                "Intake", "${eaten.f0()} kcal", goalText,
                                (eaten / scale).toFloat(), MaterialTheme.colorScheme.primary
                            )
                        }
                        d == null -> Muted("Calculating…", Modifier.padding(top = 6.dp))
                        else -> {
                            if (d.eaten > 0) {
                                Text(
                                    "${signedKcal(d.balance)} kcal",
                                    fontSize = 40.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                                Muted(if (d.balance >= 0) "Surplus" else "Deficit")
                            } else {
                                Muted("Log a meal to see your balance.", Modifier.padding(top = 6.dp))
                            }
                            val scale = max(max(d.eaten, d.burned), 1.0)
                            BarRow(
                                "Intake", "${d.eaten.f0()} kcal", goalText,
                                (d.eaten / scale).toFloat(), MaterialTheme.colorScheme.primary
                            )
                            BarRow(
                                "Burned", "${d.burned.f0()} kcal", "Estimated from ${d.source.label}",
                                (d.burned / scale).toFloat(), MaterialTheme.colorScheme.secondary
                            )
                            if (dayOffset == 0) {
                                Muted(
                                    "Today's burn assumes no more activity, so it changes as you move and eat.",
                                    Modifier.padding(top = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- steps to even out
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    val d = day
                    when {
                        body == null -> {
                            Text("Steps", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Muted(reason ?: "", Modifier.padding(top = 6.dp))
                        }
                        d == null -> {
                            Text("Steps", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Muted("Calculating…", Modifier.padding(top = 6.dp))
                        }
                        dayOffset < 0 -> {
                            Text("Steps", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                d.steps?.let { nf.format(it) } ?: "No step data",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        else -> {
                            Text("Steps to even out today", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold)
                            val perStep = EnergyModel.kcalPerStep(body)
                            val surplus = d.balance
                            when {
                                d.eaten <= 0 -> Muted(
                                    "Log your meals to see how many steps would even out the day.",
                                    Modifier.padding(top = 6.dp)
                                )
                                surplus <= 0 -> {
                                    Text(
                                        "None needed",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                    Muted("You're ${(-surplus).f0()} kcal under your estimated burn so far.")
                                }
                                else -> {
                                    val steps = ceil(surplus / perStep).toLong()
                                    if (steps > 15_000) {
                                        Text(
                                            "More than 15,000",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(top = 6.dp)
                                        )
                                        Muted(
                                            "That's a lot for one day. Balance evens out over several days, " +
                                                "so there's nothing to fix today."
                                        )
                                    } else {
                                        val km = steps * EnergyModel.stepKm(body)
                                        Text(
                                            "≈ ${nf.format(steps)} steps",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 6.dp)
                                        )
                                        Muted(
                                            "to even out the remaining ${surplus.f0()} kcal  ·  " +
                                                "about ${km.f1()} km, ${(km / 5.0 * 60).toInt()} min of walking"
                                        )
                                    }
                                }
                            }
                            d.steps?.let {
                                Muted("Steps so far today: ${nf.format(it)}", Modifier.padding(top = 8.dp))
                            }
                            Muted("An estimate based on walking.", Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }

        item { ForecastSummaryCard(db, onDetails = { onOpenTab(NavTab.Burned) }) }
    }
}


