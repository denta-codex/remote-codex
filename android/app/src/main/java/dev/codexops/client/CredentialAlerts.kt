package dev.codexops.client

import dev.codexops.core.list
import dev.codexops.core.str
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

internal const val CREDENTIAL_REQUESTS_CHANGED = "remoteCodex/credentialRequestsChanged"

data class CredentialNotice(val id: Long, val requestIds: List<String>, val expiresAt: Long) {
    val message: String get() = if (requestIds.size == 1) "Credential approval needed" else "${requestIds.size} credential requests need approval"
}

/** Read-only discovery. No selected values or mutations ever enter this controller. */
internal class CredentialAlertController(
    private val scope: CoroutineScope,
    private val generation: () -> Long,
    private val active: () -> Boolean,
    private val read: suspend () -> JsonObject,
    private val changed: (CredentialNotice?) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var job: Job? = null
    private var jobEpoch = -1L
    private var expiry: Job? = null
    private var dirty = false
    private var sequence = 0L
    private val seen = mutableMapOf<String, Long>()
    private var notice: CredentialNotice? = null

    fun refresh() {
        if (!active()) return
        dirty = true
        val epoch = generation()
        if (job?.isActive == true) {
            if (jobEpoch == epoch) return
            job?.cancel()
        }
        jobEpoch = epoch
        job = scope.launch {
            while (dirty && active() && generation() == epoch) {
                dirty = false
                val response = try { read() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (active() && generation() == epoch) setNotice(null)
                    continue
                }
                if (!active() || generation() != epoch) return@launch
                val pending = response.list("requests").filter {
                    it.str("state") == "pending" && it.str("id").isNotBlank() &&
                        (it.str("deadline").toLongOrNull() ?: 0) > now()
                }.associate { it.str("id") to it.str("deadline").toLong() }
                seen.entries.removeAll { it.value <= now() }
                val fresh = pending.keys.filter { it !in seen }
                seen.putAll(pending)
                val remaining = notice?.requestIds.orEmpty().filter { it in pending }
                val ids = (remaining + fresh).distinct()
                if (ids.isEmpty()) setNotice(null)
                else {
                    val deadline = ids.minOf { pending.getValue(it) }
                    if (notice?.requestIds != ids || notice?.expiresAt != deadline)
                        setNotice(CredentialNotice(++sequence, ids, deadline))
                }
            }
        }
    }
    fun dismiss(id: Long) { if (notice?.id == id) setNotice(null) }
    fun stopped() {
        dirty = false
        job?.cancel(); job = null
        setNotice(null)
    }
    private fun setNotice(value: CredentialNotice?) {
        expiry?.cancel(); expiry = null
        notice = value
        changed(value)
        if (value != null) expiry = scope.launch {
            delay((value.expiresAt - now()).coerceAtLeast(1))
            // One deadline-driven read retires an expired request; this is not polling.
            if (notice?.id == value.id) refresh()
        }
    }
}
