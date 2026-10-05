package dev.codexops.client

/** Transient identity of a live text update; never restored from history or persisted. */
data class LiveAssistantText(
    val thread: String,
    val turn: String,
    val item: String,
    val revision: Long,
    val textLength: Int = 0,
    val textHash: Int = 0,
) {
    val key: String get() = "$turn/$item"
}

/** Main-thread state shared across screen recreation so cancelled turns cannot restart. */
internal class StreamingHaptics(private val now: () -> Long) {
    private var turn: Pair<String, String>? = null
    private var cancelled = false
    private var pending: LiveAssistantText? = null
    private var started: Long? = null
    private var lastTick: Long? = null

    fun begin(thread: String, id: String, eligible: Boolean) {
        val next = thread to id
        if (turn == next) return
        turn = next
        cancelled = !eligible
        pending = null
        started = null
        lastTick = null
    }

    fun baseline(thread: String, id: String?) {
        if (id != null) begin(thread, id, false)
        cancel()
    }

    fun cancel() {
        cancelled = true
        pending = null
    }

    fun live(update: LiveAssistantText, eligible: Boolean) {
        begin(update.thread, update.turn, eligible)
        if (!eligible) cancel()
        if (!cancelled) pending = update
    }

    /** Consume once after layout, including suppressed updates: no backlog or timers. */
    fun rendered(update: LiveAssistantText, visible: Boolean): Float? {
        if (cancelled || pending != update) return null
        pending = null
        val instant = now()
        val start = started ?: instant.also { started = it }
        val elapsed = (instant - start).coerceAtLeast(0)
        if (elapsed >= 8_000) return null
        if (!visible || lastTick?.let { instant - it < 60 } == true) return null
        lastTick = instant
        return 0.30f * (1f - elapsed / 8_000f)
    }
}
