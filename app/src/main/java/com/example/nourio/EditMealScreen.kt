package com.example.nourio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.nourio.backup.BackupService
import com.example.nourio.data.MealDb
import com.example.nourio.data.totals
import com.example.nourio.nutrition.NutritionLookup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar

private val TIME_REGEX = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

@Composable
fun EditMealScreen(mealId: Long, db: MealDb, onBack: () -> Unit, onDone: (Int) -> Unit) {
    val context = LocalContext.current
    val meal = remember(mealId) { db.getById(mealId) }
    if (meal == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val zone = remember { ZoneId.systemDefault() }
    val mealDateTime = remember(meal) { Instant.ofEpochMilli(meal.timestamp).atZone(zone) }
    val startOffset = remember(meal) {
        ChronoUnit.DAYS.between(LocalDate.now(zone), mealDateTime.toLocalDate()).toInt()
    }

    var offset by rememberSaveable { mutableIntStateOf(startOffset) }
    var timeText by rememberSaveable {
        mutableStateOf("%02d:%02d".format(mealDateTime.hour, mealDateTime.minute))
    }
    var description by rememberSaveable { mutableStateOf(meal.description) }
    var items by remember { mutableStateOf(meal.items) }
    var pickIndex by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val m = TIME_REGEX.matchEntire(timeText.trim())
        if (m == null) {
            error = "Enter the time as HH:mm, for example 13:30."
            return
        }
        if (items.isEmpty()) {
            error = "Keep at least one item, or delete the meal."
            return
        }
        val c = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, offset)
            set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
            set(Calendar.MINUTE, m.groupValues[2].toInt())
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        db.update(mealId, description.trim().ifBlank { meal.description }, items, c.timeInMillis)
        BackupService.autoBackup(context)
        onDone(offset)
    }

    pickIndex?.let { idx ->
        items.getOrNull(idx)?.let { food ->
            MatchPickerDialog(
                foodName = food.name,
                onPick = { p, remember ->
                    items = items.toMutableList().also { it[idx] = it[idx].withMatch(p) }
                    if (remember) NutritionLookup.saveCorrection(food.name, p)
                    pickIndex = null
                },
                onDismiss = { pickIndex = null }
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this meal?") },
            text = { Text("It will be removed from your log.") },
            confirmButton = {
                TextButton(onClick = {
                    db.delete(mealId)
                    BackupService.autoBackup(context)
                    confirmDelete = false
                    onDone(offset)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        ScreenTopBar("Edit meal", onBack)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { MealThumb(mealId, Modifier.fillMaxWidth().height(170.dp)) }
            item { DietBanner(items) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = timeText,
                            onValueChange = { timeText = it; error = null },
                            label = { Text("Time (24h)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item { TotalsCard(items.totals()) }
            itemsIndexed(items) { index, food ->
                ItemRow(
                    index = index,
                    food = food,
                    onGrams = { g -> items = items.toMutableList().also { it[index] = food.copy(grams = g) } },
                    onRemove = { items = items.toMutableList().also { it.removeAt(index) } },
                    onChangeMatch = if (food.matchedName == "Quick add" || food.matchedName == "Open Food Facts") null
                    else ({ pickIndex = index })
                )
            }
        }
        ActionBar(
            logOffset = offset,
            onOffset = { offset = it },
            secondaryLabel = "Delete",
            onSecondary = { confirmDelete = true },
            saveEnabled = items.isNotEmpty(),
            onSave = { save() }
        )
    }
}
