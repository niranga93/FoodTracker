package com.example.nourio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nourio.data.GoalStore
import com.example.nourio.data.MealDb
import com.example.nourio.data.Totals
import com.example.nourio.plan.Budget
import com.example.nourio.plan.computeBudget
import kotlin.math.abs

/** Today's goals after the target-weight plan and carry-over. Null while calculating. */
@Composable
fun rememberBudget(db: MealDb, goalStore: GoalStore, dayOffset: Int): State<Budget?> {
    val context = LocalContext.current
    val state = remember(dayOffset) { mutableStateOf<Budget?>(null) }
    LaunchedEffect(dayOffset) { state.value = computeBudget(context, db, goalStore, dayOffset) }
    return state
}
