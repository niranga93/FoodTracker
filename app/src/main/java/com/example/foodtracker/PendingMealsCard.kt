package com.example.foodtracker

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.foodtracker.ai.ImageUtil
import com.example.foodtracker.pending.PendingMeal
import com.example.foodtracker.pending.PendingMeals
import com.example.foodtracker.pending.PendingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Photos taken offline: waiting for a connection, ready to review, or failed. Hidden when empty. */
@Composable
fun PendingMealsCard(onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val changes by PendingMeals.changes.collectAsState()
    val pending by produceState(emptyList<PendingMeal>(), changes) {
        value = withContext(Dispatchers.IO) { PendingMeals.all(context) }
    }
    if (pending.isEmpty()) return
    val timeFmt = remember { SimpleDateFormat("EEE h:mm a", Locale.getDefault()) }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                "Photos to review",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            pending.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PhotoThumb(p)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            "${p.label.replaceFirstChar { it.uppercase() }} · ${timeFmt.format(Date(p.mealTs))}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            when (p.status) {
                                PendingStatus.WAITING -> "Waiting for a connection to analyze"
                                PendingStatus.READY -> "Ready to review: ${p.analysis?.description.orEmpty()}"
                                PendingStatus.FAILED -> "Couldn't analyze. Tap to try again."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (p.status) {
                                PendingStatus.READY -> MaterialTheme.colorScheme.primary
                                PendingStatus.FAILED -> MaterialTheme.colorScheme.error
                                PendingStatus.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2
                        )
                    }
                    IconButton(onClick = { PendingMeals.remove(context, p.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Discard")
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoThumb(p: PendingMeal) {
    val bmp by produceState<Bitmap?>(null, p.id) {
        value = withContext(Dispatchers.IO) { ImageUtil.loadUpright(p.photo, 160) }
    }
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        bmp?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
