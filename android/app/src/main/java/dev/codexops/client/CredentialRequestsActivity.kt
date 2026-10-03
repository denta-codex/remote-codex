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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.codexops.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

/** Approval state stays in op-bridge; this activity never creates a chat model. */
class CredentialRequestsActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connection = ApprovalConnection(BuildConfig.DEBUG)
    private val local by lazy { LocalStore(applicationContext) }
    private val blockedIds = mutableSetOf<String>()
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var requests: LinearLayout
    internal lateinit var secret: EditText
        private set
    private lateinit var release: Button
    private lateinit var deny: Button
    private lateinit var refreshButton: Button
    private var selected: JsonObject? = null
    private var submitted = false
    private var busy = false
    private var refreshJob: Job? = null
    private var updateJob: Job? = null
    private var visible = false
    private var lockedRequestId: String? = null
    private val autofill get() = getSystemService(AutofillManager::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lockedRequestId = savedInstanceState?.getString("submitted_request")
        blockedIds.addAll(savedInstanceState?.getStringArrayList("submitted_requests").orEmpty())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        fun label(text: String) = TextView(this).apply { this.text = text; root.addView(this) }
        fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { action() }; root.addView(this) }
        label("Credential requests").textSize = 24f
        label("Select the requested item in 1Password, then release it once. Autofill cannot verify the selected account or item. Values go only to the waiting caller.")
        status = label("Open this screen when an op-bridge caller is waiting.")
        refreshButton = button("Refresh") { refresh() }
        requests = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; root.addView(this) }
        details = label("Select a pending request.")
        secret = EditText(this).apply {
            id = View.generateViewId()
            hint = "Requested secret"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            isSaveEnabled = false
            isSaveFromParentEnabled = false
        }
        root.addView(secret)
        button("Choose in 1Password") {
            if (canEdit()) { secret.requestFocus(); autofill?.requestAutofill(secret) }
        }
        release = button("Release once") { submit("release") }
        deny = button("Deny") { submit("deny") }
        secret.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { buttons() }
        })
        button("Back to requests") { clearSelection(); refresh() }
        button("Close") { finish() }
        setContentView(ScrollView(this).apply { isSaveEnabled = false; addView(root) })
        buttons()
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
                    if (!live(request)) { clearSecret(); details.text = "Request expired. Start a new op-bridge request." }
                    else showDetails(request)
                }
                buttons()
                if (connection.connected && !busy && refreshJob?.isActive != true) refresh()
            }
        }
    }

    private fun live(request: JsonObject) = request.str("state") == "pending" && (request.str("deadline").toLongOrNull() ?: 0) > System.currentTimeMillis()
    private fun canEdit() = selected?.let(::live) == true && !submitted && !busy && connection.connected
    private fun buttons() {
        secret.isEnabled = canEdit()
        release.isEnabled = canEdit() && !secret.text.isNullOrEmpty()
        deny.isEnabled = canEdit()
        refreshButton.isEnabled = !busy
    }
    private fun clearSecret() { autofill?.cancel(); secret.text?.clear() }
    private fun clearSelection() {
        refreshJob?.cancel(); refreshJob = null
        clearSecret(); selected = null; submitted = false; lockedRequestId = null
        details.text = "Select a pending request."; buttons()
    }
    internal fun selectRequest(request: JsonObject) {
        if (busy) return
        refreshJob?.cancel(); refreshJob = null
        clearSecret(); selected = request; submitted = request.str("id") in blockedIds
        showDetails(request); buttons()
    }
    private fun showDetails(request: JsonObject) {
        val seconds = (((request.str("deadline").toLongOrNull() ?: 0) - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        details.text = "Host: ${request.str("host")} · Caller: ${request.str("caller")}\nAccount: ${request.str("account")}\nVault: ${request.str("vault").ifEmpty { "Not specified" }}\nItem: ${request.str("item")}\nField: ${request.str("field")}\nTime remaining: ${seconds}s"
    }

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
                val id = selected?.str("id") ?: lockedRequestId
                if (id != null) {
                    val response = connection.call("get", id)
                    val current = response.list("requests").firstOrNull()
                    if (current == null || current.str("state") != "pending") {
                        clearSecret()
                        submitted = true
                        selected = current
                        details.text = terminalText(current?.str("state"))
                    } else {
                        selected = current
                        if (id in blockedIds) { submitted = true; details.text = "Submission was interrupted. Its value will not be resent. Cancel the caller and start a fresh request if needed." }
                        else showDetails(current)
                    }
                }
                val pending = connection.call("list").list("requests")
                requests.removeAllViews()
                pending.forEach { request ->
                    requests.addView(Button(this@CredentialRequestsActivity).apply {
                        text = "${request.str("item")} / ${request.str("field")}"
                        setOnClickListener { selectRequest(request) }
                    })
                }
                status.text = if (pending.isEmpty()) "No pending credential requests." else "${pending.size} pending request(s)."
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                connection.close()
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
        else -> "Delivery cannot be confirmed. Start a fresh caller request if needed; no value will be resent."
    }

    private fun submit(method: String) {
        val request = selected ?: return
        if (!canEdit() || method == "release" && secret.text.isNullOrEmpty()) return
        if (method == "release" && secret.text.toString().toByteArray().size > 65536) { status.text = "Value exceeds the 64 KiB limit."; return }
        val id = request.str("id")
        var value: String? = if (method == "release") secret.text.toString() else null
        submitted = true; lockedRequestId = id; blockedIds.add(id); busy = true; buttons()
        clearSecret()
        scope.launch {
            try {
                refreshJob?.cancelAndJoin()
                val response = connection.call(method, id, value)
                value = null
                details.text = if (response.str("error").isNotEmpty()) "Request could not be approved. Refresh for its current status." else terminalText(response.list("requests").firstOrNull()?.str("state"))
            } catch (error: Exception) {
                connection.close()
                details.text = "Submission outcome unknown. Refresh checks status only; it never resends a value."
                if (error is CancellationException) throw error
            } finally { value = null; busy = false; buttons() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        lockedRequestId?.let { outState.putString("submitted_request", it) }
        outState.putStringArrayList("submitted_requests", ArrayList(blockedIds))
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        visible = false; updateJob?.cancel(); refreshJob?.cancel(); connection.close()
        // A password-manager picker may temporarily cover us: keep only the live field.
        super.onStop()
    }
    override fun finish() { clearSecret(); super.finish() }
    override fun onDestroy() { clearSecret(); scope.cancel(); connection.close(); super.onDestroy() }
}
