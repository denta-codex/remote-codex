package dev.codexops.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Async reads share Rpc's request ownership; this only owns their cancellable jobs. */
class TaskToolRequests(
    private val scope: CoroutineScope,
    private val generation: () -> Long,
    private val verifiedHostId: () -> String?,
    private val call: suspend (String, JsonObject, Long) -> JsonObject,
    private val respond: (JsonElement, JsonObject, Long) -> Unit,
    private val timeoutMillis: Long = 30000,
    private val maxMessageBytes: Int = RPC_OUTBOUND_MAX_BYTES,
) {
    private val jobs = ConcurrentHashMap<JsonElement, Job>()

    fun handle(event: JsonObject) {
        val id = event.getValue("id")
        val epoch = event.str("_epoch").toLongOrNull() ?: generation()
        if (epoch != generation()) return
        val host = verifiedHostId()
        if (host == null) {
            reply(id, failure("Task tools require a verified active connection."), epoch)
            return
        }
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            val result = try {
                withTimeout(timeoutMillis) {
                    val tools = ReadOnlyTaskTools(host) { method, params ->
                        if (epoch != generation() || verifiedHostId() != host) throw CancellationException()
                        call(method, params, epoch)
                    }
                    val data = tools.execute(event.map("params"))
                    val content = if (event.map("params").str("tool") == "list_threads")
                        listOf(obj("type" to s("inputText"), "text" to s(
                            "Bounded snapshot of recent active user tasks on the active host. Use codex-tasks for query-based finding, archives, or exhaustive inventory.")))
                    else emptyList()
                    obj("success" to JsonPrimitive(true), "contentItems" to JsonArray(content +
                        obj("type" to s("inputText"), "text" to s(data.toString()))))
                }
            } catch (_: TimeoutCancellationException) {
                failure("The task read timed out.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: TaskToolFailure) {
                failure(e.message ?: "Invalid task tool request.")
            } catch (e: RpcRejected) {
                failure("The stock task API rejected the read (RPC ${e.code}).")
            } catch (_: ConnectionLost) {
                return@launch
            } catch (_: Exception) {
                failure("The task read failed. No task was changed.")
            }
            if (epoch == generation() && verifiedHostId() == host) reply(id, result, epoch)
        }
        if (jobs.putIfAbsent(id, job) != null) job.cancel()
        else {
            job.invokeOnCompletion { jobs.remove(id, job) }
            job.start()
        }
    }

    fun resolved(id: JsonElement) { jobs.remove(id)?.cancel() }
    fun cancelAll() { jobs.values.forEach(Job::cancel); jobs.clear() }

    private fun reply(id: JsonElement, result: JsonObject, epoch: Long) {
        val message = obj("id" to id, "result" to result)
        val bounded = if (message.toString().toByteArray(Charsets.UTF_8).size > maxMessageBytes)
            failure("The task response exceeds the transport limit. Request fewer turns or omit outputs.") else result
        try { respond(id, bounded, epoch) } catch (_: ConnectionLost) { /* Never replay an uncertain reply. */ }
    }

    private fun failure(text: String) = obj("success" to JsonPrimitive(false),
        "contentItems" to JsonArray(listOf(obj("type" to s("inputText"), "text" to s(text)))))
}
