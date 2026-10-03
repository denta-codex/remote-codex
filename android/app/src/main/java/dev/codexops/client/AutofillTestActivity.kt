package dev.codexops.client

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.*

/** Local experiment: no client model, persistence, or network dependencies. */
class AutofillTestActivity : Activity() {
    internal enum class Form(val label: String) { SECRET("Secret only"), LOGIN("Login"), CONTROL("Control") }
    internal val fields = linkedMapOf<String, ObservedField>()
    private val events = ArrayDeque<String>()
    internal var form = Form.SECRET
        private set
    private lateinit var fieldContainer: LinearLayout
    private lateinit var status: TextView
    private lateinit var observations: Spinner
    private lateinit var itemType: Spinner
    private lateinit var associated: CheckBox
    private val manager get() = getSystemService(AutofillManager::class.java)
    private val callback = object : AutofillManager.AutofillCallback() {
        override fun onAutofillEvent(view: View, event: Int) {
            val field = fields.entries.firstOrNull { it.value === view }?.key ?: return
            val kind = when (event) {
                EVENT_INPUT_SHOWN -> "suggestions shown"
                EVENT_INPUT_HIDDEN -> "suggestions hidden"
                EVENT_INPUT_UNAVAILABLE -> "suggestions unavailable"
                else -> return
            }
            record("$field: $kind")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        // Respect system bars on the edge-to-edge target SDK, including the keyboard.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemWindowInsetTop
            view.setPadding(24, 24 + bars, 24, 24 + insets.systemWindowInsetBottom)
            insets
        }
        fun label(text: String) = TextView(this).apply { this.text = text; root.addView(this) }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { action() }; root.addView(this)
        }
        label("Autofill test · experimental").textSize = 24f
        label("Use harmless test items. Filled values stay on this screen and are never included in diagnostics. Suggestion events do not identify what 1Password displayed.")
        val forms = Spinner(this).apply {
            adapter = ArrayAdapter(this@AutofillTestActivity, android.R.layout.simple_spinner_dropdown_item, Form.entries.map { it.label })
            isSaveEnabled = false
        }
        root.addView(forms)
        fieldContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(fieldContainer)
        button("Request Autofill") { requestFill() }
        button("Reset") { reset() }
        label("Test each: normal Login, token in a Login password, API Credential, custom field. Repeat after Always Allow, then close and reopen this screen.")
        fun choice(items: List<String>): Spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@AutofillTestActivity, android.R.layout.simple_spinner_dropdown_item, items)
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            root.addView(this)
        }
        itemType = choice(listOf("Not selected", "Normal Login", "Token as Login password", "API Credential", "Custom field"))
        observations = choice(listOf("Not recorded", "Item appeared", "Item unavailable", "Filled expected fields", "Filled unexpected fields", "Picker dismissed"))
        associated = CheckBox(this).apply { text = "Repeating after Always Allow"; isSaveEnabled = false; root.addView(this) }
        status = label("")
        button("Copy diagnostic report") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Autofill diagnostics", report()))
            Toast.makeText(this, "Value-free report copied", Toast.LENGTH_SHORT).show()
        }
        button("Close") { finish() }
        setContentView(ScrollView(this).apply { isSaveEnabled = false; addView(root) })
        forms.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selectForm(Form.entries[position]) }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        manager?.registerCallback(callback)
        selectForm(Form.SECRET)
    }

    internal fun selectForm(selected: Form) {
        manager?.cancel()
        fields.values.forEach { it.text?.clear() }
        fields.clear()
        fieldContainer.removeAllViews()
        events.clear()
        form = selected
        observations.setSelection(0)
        fun field(key: String, label: String, secret: Boolean, hint: String?) {
            fieldContainer.addView(TextView(this).apply { text = label })
            val input = ObservedField(key).apply {
                id = View.generateViewId()
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
                isSingleLine = true
                isSaveEnabled = false
                isSaveFromParentEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                if (hint != null) setAutofillHints(hint)
            }
            fields[key] = input
            fieldContainer.addView(input)
        }
        if (selected == Form.LOGIN) field("username", "Username", false, View.AUTOFILL_HINT_USERNAME)
        field("secret", if (selected == Form.LOGIN) "Password" else "API token", true,
            if (selected == Form.CONTROL) null else View.AUTOFILL_HINT_PASSWORD)
        record("form ready")
    }

    internal fun requestFill() {
        val target = fields.values.firstOrNull { it.hasFocus() } ?: fields.values.first()
        target.requestFocus()
        manager?.requestAutofill(target)
        record("${target.fieldKey}: request issued")
    }

    internal fun reset() { selectForm(form) }

    private fun record(event: String) {
        if (events.size == 100) events.removeFirst()
        events.addLast(event)
        status.text = diagnostics()
    }

    private fun diagnostics(): String = buildString {
        appendLine("Autofill supported: ${manager?.isAutofillSupported == true}; enabled: ${manager?.isEnabled == true}")
        fields.forEach { (key, field) ->
            appendLine("$key: ${if (field.text.isNullOrEmpty()) "empty" else "nonempty"}")
            val hints = field.autofillHints.orEmpty()
            appendLine("$key hints: username=${View.AUTOFILL_HINT_USERNAME in hints}, password=${View.AUTOFILL_HINT_PASSWORD in hints}, automatic-password=${"passwordAuto" in hints}")
        }
        events.forEach { appendLine(it) }
    }

    internal fun report(): String = buildString {
        appendLine("Remote Codex Autofill test ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android API ${Build.VERSION.SDK_INT}")
        appendLine("Form: ${form.label}")
        appendLine("Test category: ${itemType.selectedItem}")
        appendLine("Observation: ${observations.selectedItem}")
        appendLine("After Always Allow: ${associated.isChecked}")
        append(diagnostics())
    }

    internal inner class ObservedField(val fieldKey: String) : EditText(this@AutofillTestActivity) {
        private var delivering = false
        init {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    // Input-type configuration can trigger watchers before the form is ready.
                    if (!delivering && fields[fieldKey] === this@ObservedField) record("$fieldKey: text edited")
                }
            })
        }
        override fun autofill(value: AutofillValue) {
            if (!value.isText) return
            delivering = true
            try { super.autofill(value) } finally { delivering = false }
            record("$fieldKey: autofill delivered")
        }
    }

    override fun finish() {
        manager?.cancel()
        fields.values.forEach { it.text?.clear() }
        super.finish()
    }

    override fun onDestroy() {
        manager?.unregisterCallback(callback)
        manager?.cancel()
        fields.values.forEach { it.text?.clear() }
        events.clear()
        super.onDestroy()
    }
}
