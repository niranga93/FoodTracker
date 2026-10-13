package com.example.nourio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.nourio.data.MealPhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The meal's photo thumbnail, or nothing for meals logged without a photo. */
@Composable
fun MealThumb(mealId: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, mealId) {
        value = withContext(Dispatchers.IO) { MealPhotos.load(context, mealId) }
    }
    bmp?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Meal photo",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(12.dp))
        )
    }
}
