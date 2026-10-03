package dev.codexops.client

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatStatusIndicator(indicator: ChatIndicator) {
    if (indicator == ChatIndicator.None) return
    val blue = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF75B6FF) else Color(0xFF0969DA)
    val animated = ValueAnimator.areAnimatorsEnabled()
    val modifier = Modifier.padding(start = 12.dp).size(18.dp).semantics {
        contentDescription = indicator.label
    }
    when (indicator) {
        ChatIndicator.Working -> if (animated) CircularProgressIndicator(modifier, color = blue, strokeWidth = 2.dp)
            else CircularProgressIndicator(progress = { 0.7f }, modifier = modifier, color = blue, strokeWidth = 2.dp)
        ChatIndicator.Error -> Icon(painterResource(R.drawable.ic_warning), null, modifier,
            tint = MaterialTheme.colorScheme.error)
        else -> {
            val waiting = indicator == ChatIndicator.Approval || indicator == ChatIndicator.Input
            val opacity = if (waiting && animated) {
                val transition = rememberInfiniteTransition(label = "Waiting for you")
                val value by transition.animateFloat(0.4f, 1f,
                    infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Blue pulse")
                value
            } else 1f
            Canvas(modifier.alpha(opacity)) { drawCircle(blue, radius = (if (waiting) 4 else 3).dp.toPx()) }
        }
    }
}
