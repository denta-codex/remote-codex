package dev.codexops.client

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** The hardware boundary is replaceable by a recorder in fixture-backed UI tests. */
internal interface HapticDriver {
    fun tick()
    fun longPress() = tick()
    fun stream(scale: Float)
    fun cancel()
}

internal val LocalHapticDriver = staticCompositionLocalOf<HapticDriver?> { null }
internal val LocalAppHaptics = staticCompositionLocalOf { AppHaptics(null, false) }

internal class AppHaptics(private val driver: HapticDriver?, private val enabled: Boolean) {
    fun tick() { if (enabled) driver?.tick() }
    fun longPress() { if (enabled) driver?.longPress() }
    fun stream(scale: Float) { if (enabled) driver?.stream(scale) }
    fun cancel() { driver?.cancel() }
}

@Composable
internal fun HapticProvider(enabled: Boolean, onResumed: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val supplied = LocalHapticDriver.current
    val driver = supplied ?: remember(context, view) { AndroidHapticDriver(context, view) }
    val haptics = remember(driver, enabled) { AppHaptics(driver, enabled) }
    val owner = LocalLifecycleOwner.current
    val resumed by rememberUpdatedState(onResumed)
    DisposableEffect(owner, driver) {
        resumed(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed(true)
            if (event == Lifecycle.Event.ON_PAUSE) { resumed(false); driver.cancel() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); resumed(false); driver.cancel() }
    }
    DisposableEffect(driver, enabled) {
        if (!enabled) driver.cancel()
        onDispose { driver.cancel() }
    }
    CompositionLocalProvider(LocalAppHaptics provides haptics, content = content)
}

private class AndroidHapticDriver(private val context: Context, private val view: View) : HapticDriver {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val streamingSupported = Build.VERSION.SDK_INT >= 30 && runCatching {
        vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK) == true
    }.getOrDefault(false)
    private val primitiveDuration = if (streamingSupported && Build.VERSION.SDK_INT >= 31) runCatching {
        vibrator?.getPrimitiveDurations(VibrationEffect.Composition.PRIMITIVE_TICK)?.firstOrNull()?.toLong() ?: 60L
    }.getOrDefault(60L).coerceAtLeast(60L) else 60L
    private var lastStream: Long? = null

    // Direct vibrator effects do not automatically honor the touch-feedback preference.
    private fun allowed(): Boolean = view.isAttachedToWindow && view.hasWindowFocus() && runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    }.getOrDefault(false)

    override fun tick() {
        if (allowed()) view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
    }

    override fun longPress() {
        if (allowed()) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun stream(scale: Float) {
        if (!streamingSupported || !allowed() || scale <= 0f || Build.VERSION.SDK_INT < 30) return
        val instant = SystemClock.uptimeMillis()
        if (lastStream?.let { instant - it < primitiveDuration } == true) return
        // No waveform fallback: unsupported phones keep the standard send/swipe feedback.
        runCatching {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale.coerceIn(0f, 1f))
                .compose()
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator?.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build())
            } else {
                vibrator?.vibrate(effect, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
            lastStream = instant
        }
    }

    override fun cancel() { lastStream = null; runCatching { vibrator?.cancel() } }
}
