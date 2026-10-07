package dev.codexops.client

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.widget.*
import androidx.activity.ComponentActivity
import dev.codexops.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Approval state stays in op-bridge; this activity never creates a chat model. */
class CredentialRequestsActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connection = ApprovalConnection(BuildConfig.DEBUG)
    private val local by lazy { LocalStore(applicationContext) }
    private val blockedIds = mutableSetOf<String>()
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var progress: TextView
    private lateinit var requests: LinearLayout
    private lateinit var singlePanel: LinearLayout
    private lateinit var batchPanel: LinearLayout
    internal lateinit var secret: EditText
        private set
    private val batchInputs = linkedMapOf<String, EditText>()
    internal val batchSecrets: Map<String, EditText> get() = batchInputs
    private val explicitEmpty = mutableSetOf<String>()
    private var batch: ApprovalBatch? = null
    private var validSelection = false
    private lateinit var release: Button
    private lateinit var deny: Button
    private lateinit var refreshButton: Button
    private lateinit var ui: CredentialRequestLayout
    private var pendingCount = 0
    private var selected: JsonObject? = null
    private var submitted = false
    private var busy = false
    private var refreshJob: Job? = null
    private var updateJob: Job? = null
    private var visible = false
    private var lockedRequestId: String? = null
    private var initialRequestId: String? = null
    private val autofill get() = getSystemService(AutofillManager::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lockedRequestId = savedInstanceState?.getString("submitted_request")
        initialRequestId = if (savedInstanceState == null) intent.getStringExtra("request_id") else null
        blockedIds.addAll(savedInstanceState?.getStringArrayList("submitted_requests").orEmpty())
        ui = CredentialRequestLayout(this,
            close = { finish() }, refresh = { refresh() },
            back = { clearSelection(); refresh() }, choose = { choose(secret) },
            release = { submit("release") }, deny = { submit("deny") })
        status = ui.status; details = ui.details; progress = ui.progress
        requests = ui.requests; singlePanel = ui.singlePanel; batchPanel = ui.batchPanel
        release = ui.releaseButton; deny = ui.denyButton; refreshButton = ui.refreshButton
        secret = secretField("Requested secret", singleLine = true)
        ui.addSingleField(secret)
        watch(secret) { buttons() }
        setContentView(ui.scroll)
        buttons()
    }

    private fun addButton(parent: LinearLayout, text: String, action: () -> Unit) =
        ui.button(text, action = action).also { ui.addControl(parent, it) }
    private fun secretField(hint: String, singleLine: Boolean) = EditText(this).apply {
        id = View.generateViewId()
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
            if (singleLine) 0 else InputType.TYPE_TEXT_FLAG_MULTI_LINE
        isSingleLine = singleLine
        // Keep focus available to Autofill without opening the typing keyboard.
        showSoftInputOnFocus = false
        setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        isSaveEnabled = false
        isSaveFromParentEnabled = false
    }
    private fun watch(input: EditText, changed: () -> Unit) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = changed()
        })
    }
    private fun choose(input: EditText) {
        if (canEdit()) { input.requestFocus(); autofill?.requestAutofill(input) }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        refresh()
        updateJob = scope.launch {
            while (isActive) {
                delay(1000)
                val request = selected
                if (request != null && !submitted) {
                    if (!connection.connected) { clearSecret(); status.text = "Approval connection unavailable. Refresh checks status only." }
                    else if (!live(request)) { clearSecret(); details.text = "Request expired. Start a new op-bridge request." }
                    else if (validSelection) showDetails(request)
                }
                buttons()
                if (connection.connected && !busy && refreshJob?.isActive != true) refresh()
            }
        }
    }

    private fun live(request: JsonObject) = request.str("state") == "pending" && (request.str("deadline").toLongOrNull() ?: 0) > System.currentTimeMillis()
    private fun canEdit() = validSelection && selected?.let(::live) == true && !submitted && !busy && connection.connected
    private fun selectedField(id: String) = !batchInputs[id]?.text.isNullOrEmpty() || id in explicitEmpty
    private fun complete() = batch?.fields?.all { selectedField(it.id) } ?: !secret.text.isNullOrEmpty()
    private fun releaseValues() = JsonArray(batch!!.fields.map { field ->
        obj("id" to s(field.id), "value" to s(batchInputs.getValue(field.id).text.toString()))
    })
    private fun buttons() {
        if (!::release.isInitialized) return
        val editable = canEdit()
        secret.isEnabled = editable
        batchInputs.values.forEach { it.isEnabled = editable }
        release.text = if (selected?.str("kind") == "inject") "Release all once" else "Release once"
        release.isEnabled = editable && complete() && connection.releaseFits(selected!!.str("id"),
            value = if (batch == null) secret.text.toString() else null,
            values = if (batch != null) releaseValues() else null)
        deny.isEnabled = selected?.let(::live) == true && !submitted && !busy && connection.connected
        refreshButton.isEnabled = !busy
        ui.chooseButton.isEnabled = editable
        ui.connection.text = if (connection.connected) "Connected to Grace" else "Not connected"
        ui.heading.text = if (selected != null || submitted) "Credential request" else "Credential requests"
        ui.card.visibility = if (selected != null || submitted) View.VISIBLE else View.GONE
        requests.visibility = if (selected == null && !submitted) View.VISIBLE else View.GONE
        release.visibility = if (selected != null || submitted) View.VISIBLE else View.GONE
        deny.visibility = release.visibility
        ui.backButton.visibility = if (pendingCount > 1 || submitted) View.VISIBLE else View.GONE
        if (submitted || selected?.let(::live) != true) ui.expires.text = "—"
        details.visibility = if (details.text.isEmpty()) View.GONE else View.VISIBLE
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        progress.visibility = if (batch == null) View.GONE else View.VISIBLE
        progress.text = batch?.let { "${it.fields.count { field -> selectedField(field.id) }} of ${it.fields.size} selected" } ?: ""
        singlePanel.visibility = if (selected?.str("kind") == "inject") View.GONE else View.VISIBLE
        batchPanel.visibility = if (selected?.str("kind") == "inject") View.VISIBLE else View.GONE
    }
    private fun clearSecret() {
        autofill?.cancel()
        secret.text?.clear()
        batchInputs.values.forEach { it.text?.clear() }
        explicitEmpty.clear()
        buttons()
    }
    private fun clearSelection() {
        refreshJob?.cancel(); refreshJob = null
        clearSecret(); selected = null; batch = null; validSelection = false
        batchPanel.removeAllViews(); batchInputs.clear()
        submitted = false; lockedRequestId = null
        details.text = ""; ui.title.text = ""; ui.subtitle.text = ""; buttons()
    }
    internal fun selectRequest(request: JsonObject) {
        if (busy) return
        refreshJob?.cancel(); refreshJob = null
        clearSecret(); batchPanel.removeAllViews(); batchInputs.clear(); batch = null
        selected = request; submitted = request.str("id") in blockedIds
        applyMetadata(request)
        buttons()
        if (request.str("kind") == "inject" && request["fields"] == null) refresh()
    }
    private fun applyMetadata(request: JsonObject) {
        validSelection = false
        showDetails(request)
        val kind = request.str("kind")
        if (kind == "inject") {
            if (request["fields"] == null) { details.text = "Loading credential group…"; return }
            val parsed = try { ApprovalBatch.parse(request) } catch (_: ApprovalFailure) {
                clearSecret(); batchPanel.removeAllViews(); batchInputs.clear(); batch = null
                details.text = "Unsupported credential group. Cancel the caller and start a fresh request."
                return
            }
            if (batch != parsed) {
                clearSecret(); batchPanel.removeAllViews(); batchInputs.clear(); batch = parsed
                parsed.fields.groupBy { it.vault to it.item }.forEach { (group, fields) ->
                    val groupPanel = ui.group(group.first, group.second)
                    fields.forEach { field ->
                        ui.fieldLabel(groupPanel, field.field, field.occurrences)
                        val input = secretField(field.field, singleLine = false)
                        batchInputs[field.id] = input
                        ui.styleSecret(input)
                        ui.addControl(groupPanel, input)
                        watch(input) {
                            if (!input.text.isNullOrEmpty()) explicitEmpty.remove(field.id)
                            buttons()
                        }
                        addButton(groupPanel, "Choose in 1Password") { choose(input) }
                        addButton(groupPanel, "Use empty value") {
                            if (canEdit()) { input.text?.clear(); explicitEmpty.add(field.id); buttons() }
                        }
                    }
                }
            }
        } else if (kind.isNotEmpty() && kind != "read") {
            clearSecret(); details.text = "Unsupported credential request."; return
        }
        validSelection = request.str("id").isNotEmpty() && request.str("account").isNotEmpty() &&
            (kind == "inject" || request.str("item").isNotEmpty() && request.str("field").isNotEmpty())
    }
    private fun showDetails(request: JsonObject) {
        val seconds = (((request.str("deadline").toLongOrNull() ?: 0) - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        val grouped = request.str("kind") == "inject"
        ui.title.text = if (grouped) "Credential group" else request.str("item")
        ui.subtitle.text = if (grouped) "${request.str("unique_count")} fields · ${request.str("occurrence_count")} uses"
            else request.str("vault").ifEmpty { "Vault not specified" }
        ui.field.text = if (grouped) "${request.str("unique_count")} requested fields" else request.str("field")
        ui.requester.text = "${request.str("caller")} on ${request.str("host") }"
        ui.account.text = request.str("account")
        ui.expires.text = "%d:%02d".format(seconds / 60, seconds % 60)
        details.text = ""
    }
    private fun metadata(request: JsonObject) = JsonObject(request.filterKeys { it in setOf("id", "host", "caller", "account", "vault", "item", "field", "kind", "deadline", "fields", "unique_count", "occurrence_count") })

    private fun refresh() {
        if (!visible || busy || refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            try {
                if (!connection.connected) {
                    status.text = "Connecting…"
                    val fixture = if (BuildConfig.DEBUG) intent.getStringExtra("fixture_endpoint") else null
                    val token = if (fixture != null) "fixture-approval-token" else local.token()
                    if (token.isEmpty()) { status.text = "Set your connection credential in Settings first."; return@launch }
                    connection.connect(fixture ?: GraceHost.endpoint, token)
                }
                val id = selected?.str("id") ?: lockedRequestId ?: initialRequestId
                initialRequestId = null
                if (id != null) {
                    val response = connection.call("get", id)
                    val current = response.list("requests").firstOrNull()
                    if (current != null && current.str("id") != id) throw ApprovalFailure("invalid_response")
                    if (current == null || current.str("state") != "pending") {
                        clearSecret(); submitted = true; validSelection = false
                        selected = current
                        details.text = terminalText(current?.str("state"))
                    } else {
                        if (selected?.let { metadata(it) != metadata(current) } == true) clearSecret()
                        selected = current
                        if (id in blockedIds) {
                            submitted = true; validSelection = false
                            details.text = "Submission was interrupted. Its values will not be resent. Cancel the caller and start a fresh request if needed."
                        } else applyMetadata(current)
                    }
                }
                val pending = connection.call("list").list("requests")
                pendingCount = pending.size
                if (selected == null && !submitted && pending.size == 1) {
                    val only = pending.single()
                    val full = connection.call("get", only.str("id")).list("requests").singleOrNull()
                    if (full != null && full.str("id") != only.str("id")) throw ApprovalFailure("invalid_response")
                    if (full != null && full.str("id") == only.str("id") && live(full)) {
                        selected = full
                        submitted = full.str("id") in blockedIds
                        if (submitted) {
                            validSelection = false
                            details.text = "Submission was interrupted. Its values will not be resent."
                        } else applyMetadata(full)
                    }
                }
                requests.removeAllViews()
                pending.forEach { request ->
                    val title = if (request.str("kind") == "inject") "Credential group · ${request.str("unique_count")} fields"
                        else "${request.str("item")} / ${request.str("field") }"
                    addButton(requests, title) { selectRequest(request) }
                }
                status.text = if (selected != null && !submitted && validSelection) ""
                    else if (pending.isEmpty()) "No pending credential requests." else "${pending.size} requests waiting for approval."
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                clearSecret(); connection.close()
                status.text = if (error is ApprovalFailure && error.kind == "no_session") "No active approval session. Start an op-bridge request, then refresh." else "Approval connection unavailable. Refresh to check again; submitted values are never resent."
            } finally { buttons() }
        }
    }

    private fun terminalText(state: String?) = when (state) {
        "completed" -> "Received by the waiting op-bridge caller."
        "releasing" -> "Release accepted; waiting for caller receipt."
        "denied" -> "Request denied."
        "expired" -> "Request expired."
        "cancelled" -> "Caller cancelled or disconnected."
        "failed" -> "Batch failed. No result was released."
        else -> "Delivery cannot be confirmed. Start a fresh caller request if needed; no value will be resent."
    }

    private fun submit(method: String) {
        val request = selected ?: return
        if (request.let(::live).not() || submitted || busy || !connection.connected) return
        if (method == "release" && (!canEdit() || !complete())) return
        val id = request.str("id")
        val submission = try {
            if (method == "release" && batch != null) {
                connection.prepare("release_batch", id, values = releaseValues())
            } else connection.prepare(method, id, if (method == "release") secret.text.toString() else null)
        } catch (error: ApprovalFailure) {
            status.text = if (error.kind == "invalid_value") "Each value must be valid text within the 64 KiB limit." else "Credential group exceeds the 512 KiB transport limit."
            return
        }
        submitted = true; lockedRequestId = id; blockedIds.add(id); busy = true; buttons()
        clearSecret()
        scope.launch {
            try {
                refreshJob?.cancelAndJoin()
                val response = connection.send(submission)
                details.text = if (response.str("error").isNotEmpty()) "Request could not be approved. Refresh for its current status." else terminalText(response.list("requests").firstOrNull()?.str("state"))
            } catch (error: Exception) {
                connection.close()
                details.text = "Submission outcome unknown. Refresh checks status only; it never resends values."
                if (error is CancellationException) throw error
            } finally { submission.discard(); busy = false; buttons() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        lockedRequestId?.let { outState.putString("submitted_request", it) }
        outState.putStringArrayList("submitted_requests", ArrayList(blockedIds))
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        visible = false; updateJob?.cancel(); refreshJob?.cancel(); connection.close()
        // A password-manager picker may temporarily cover us: keep only live fields.
        super.onStop()
    }
    override fun finish() { clearSecret(); super.finish() }
    override fun onDestroy() { clearSecret(); scope.cancel(); connection.close(); super.onDestroy() }
}
