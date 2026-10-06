package dev.codexops.client

import dev.codexops.core.obj
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

data class WeeklyUsageState(
    val remainingPercent: Int? = null,
    val resetsAt: Long? = null,
    val loading: Boolean = false,
    val message: String? = null,
)

/** Select the actual seven-day Codex quota; never label a short window as weekly. */
internal fun parseWeeklyUsage(response: JsonObject): WeeklyUsageState {
    val buckets = response["rateLimitsByLimitId"] as? JsonObject
    val snapshot = (buckets?.get("codex") ?: response["rateLimits"]) as? JsonObject
    val weekly = listOf("primary", "secondary").mapNotNull { snapshot?.get(it) as? JsonObject }
        .firstOrNull { (it["windowDurationMins"] as? JsonPrimitive)?.longOrNull == 7 * 24 * 60L }
    val used = (weekly?.get("usedPercent") as? JsonPrimitive)?.intOrNull
        ?: return WeeklyUsageState(message = "Weekly usage is unavailable for this account.")
    return WeeklyUsageState(
        remainingPercent = 100 - used.coerceIn(0, 100),
        resetsAt = (weekly?.get("resetsAt") as? JsonPrimitive)?.longOrNull,
    )
}

internal class WeeklyUsageController(
    private val scope: CoroutineScope,
    private val rpc: RemoteSession,
    private val state: () -> ScreenState,
    private val publish: (WeeklyUsageState) -> Unit,
) {
    private var job: Job? = null
    private var revision = 0L

    fun disconnected() {
        revision++
        job?.cancel()
        job = null
        publish(WeeklyUsageState(message = "Connect to see your weekly usage."))
    }

    fun refresh() {
        if (!state().ready) { disconnected(); return }
        // A sparse update can arrive during a read. Read again before publishing that result.
        revision++
        if (job?.isActive == true) return
        publish(WeeklyUsageState(loading = true))
        val epoch = rpc.generation
        job = scope.launch {
            try {
                do {
                    val requested = revision
                    val result = rpc.callForGeneration("account/rateLimits/read", obj(), epoch)
                    if (epoch != rpc.generation || !state().ready) return@launch
                    if (requested == revision) { publish(parseWeeklyUsage(result)); break }
                } while (true)
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (epoch == rpc.generation && state().ready)
                    publish(WeeklyUsageState(message = "Couldn't load weekly usage. Try refreshing."))
            }
        }
    }
}
