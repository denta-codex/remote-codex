package dev.codexops.client

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.codexops.core.*
import kotlinx.serialization.json.*

@Composable
internal fun McpElicitationInput(
    decision: Decision,
    ready: Boolean,
    openUrl: ((String) -> Boolean)? = null,
    answer: (JsonObject) -> Unit,
) {
    val params = decision.params
    val form = remember(decision.key, decision.epoch) { McpElicitation.form(params) }
    val values = remember(decision.key, decision.epoch) {
        mutableStateMapOf<String, JsonElement>().apply { form?.defaults?.let(::putAll) }
    }
    var submitting by remember(decision.key, decision.epoch) { mutableStateOf(false) }
    var launchFailed by remember(decision.key, decision.epoch) { mutableStateOf(false) }
    val active = ready && !submitting
    val context = LocalContext.current
    val submit: (String, JsonObject?) -> Unit = { action, content ->
        if (active && !submitting) {
            submitting = true
            answer(McpElicitation.response(action, content))
        }
    }
    Text(params.str("serverName"))
    val cover = LocalAppWindowClass.current.coverScreen
    Column(
        Modifier.fillMaxWidth().heightIn(max = if (cover) 240.dp else 400.dp)
            .verticalScroll(rememberScrollState()).testTag("mcp-fields"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(params.str("message"))
        when (params.str("mode")) {
            "form" -> {
                if (form == null) Text("This form requires the desktop client.")
                else {
                    val validation = form.validate(values)
                    form.fields.forEach { field ->
                        McpFormField(field, values[field.name], active, validation.errors[field.name]) { value ->
                            if (value == null) values.remove(field.name) else values[field.name] = value
                        }
                    }
                }
            }
            "url" -> {
                val url = McpElicitation.url(params)
                SelectionContainer { Text(params.str("url"), modifier = Modifier.testTag("mcp-url")) }
                if (url == null) Text("This link cannot be opened here. Use the desktop client.")
                else {
                    Text("Destination: ${Uri.parse(url).host}")
                    Text("Continue in your browser. The server determines when the operation is complete.")
                }
                if (launchFailed) Text("Could not open the browser. Try again or cancel.", color = MaterialTheme.colorScheme.error)
            }
            else -> Text("This request requires the desktop client.")
        }
    }
    // Keep all actions reachable without scrolling through a long form, including on the cover display.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton({ submit("cancel", null) }, enabled = active, modifier = Modifier.testTag("mcp-cancel")) { Text("Cancel") }
        TextButton({ submit("decline", null) }, enabled = active, modifier = Modifier.testTag("mcp-decline")) { Text("Decline") }
    }
    if (params.str("mode") == "form" && form != null) {
        val validation = form.validate(values)
        Button(
            { submit("accept", validation.content) },
            enabled = active && validation.errors.isEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("mcp-submit"),
        ) { Text("Submit form") }
    } else if (params.str("mode") == "url") {
        val url = McpElicitation.url(params)
        Button(
            {
                if (active && !submitting && url != null) {
                    // Reserve the tap before launching an external activity. No automatic launch or retry.
                    submitting = true
                    val opened = try {
                        openUrl?.invoke(url) ?: run {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                            true
                        }
                    } catch (_: ActivityNotFoundException) { false }
                    catch (_: SecurityException) { false }
                    if (opened) answer(McpElicitation.response("accept"))
                    else { submitting = false; launchFailed = true }
                }
            },
            enabled = active && url != null,
            modifier = Modifier.fillMaxWidth().testTag("mcp-open"),
        ) { Text("Open and continue") }
    }
}

@Composable
private fun McpFormField(
    field: McpField,
    value: JsonElement?,
    enabled: Boolean,
    error: String?,
    change: (JsonElement?) -> Unit,
) {
    val label = field.title + if (field.required) " *" else " (optional)"
    val options = field.options
    Text(label)
    if (field.schema.str("description").isNotBlank()) Text(field.schema.str("description"), style = MaterialTheme.typography.bodySmall)
    val limits = listOf(
        "minimum" to "Minimum", "maximum" to "Maximum",
        "minLength" to "Minimum characters", "maxLength" to "Maximum characters",
        "minItems" to "Minimum selections", "maxItems" to "Maximum selections",
        "format" to "Format",
    ).mapNotNull { (key, title) -> field.schema[key]?.takeUnless { it == JsonNull }?.let { "$title: ${it.jsonPrimitive.content}" } }
    if (limits.isNotEmpty()) Text(limits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    when {
        options != null -> {
            val selected = if (field.type == "array") (value as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                else listOfNotNull((value as? JsonPrimitive)?.content)
            options.forEach { option ->
                val checked = option.value in selected
                val optionModifier = Modifier.fillMaxWidth().testTag("mcp-field-${field.name}-${option.value}")
                val interaction = if (field.type == "array") optionModifier.toggleable(
                    checked, enabled = enabled, role = Role.Checkbox,
                    onValueChange = { change(JsonArray((if (it) selected + option.value else selected - option.value).map(::s))) },
                ) else optionModifier.selectable(
                    checked, enabled = enabled, role = Role.RadioButton, onClick = { change(s(option.value)) },
                )
                Row(interaction, verticalAlignment = Alignment.CenterVertically) {
                    if (field.type == "array") Checkbox(checked, null, enabled = enabled)
                    else RadioButton(checked, null, enabled = enabled)
                    Text(option.label)
                }
            }
        }
        field.type == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(true to "Yes", false to "No").forEach { (choice, title) ->
                Row(
                    Modifier.selectable(value == JsonPrimitive(choice), enabled = enabled, role = Role.RadioButton,
                        onClick = { change(JsonPrimitive(choice)) }).testTag("mcp-field-${field.name}-$choice"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(value == JsonPrimitive(choice), null, enabled = enabled)
                    Text(title)
                }
            }
        }
        else -> OutlinedTextField(
            (value as? JsonPrimitive)?.content.orEmpty(), { change(s(it)) },
            modifier = Modifier.fillMaxWidth().testTag("mcp-field-${field.name}"),
            enabled = enabled, isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = when (field.type) {
                "number", "integer" -> KeyboardType.Decimal
                else -> when (field.schema.str("format")) {
                    "email" -> KeyboardType.Email
                    "uri" -> KeyboardType.Uri
                    else -> KeyboardType.Text
                }
            }),
            singleLine = true,
        )
    }
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (!field.required && value != null) TextButton(
        { change(null) }, enabled = enabled, modifier = Modifier.testTag("mcp-clear-${field.name}"),
    ) { Text("Leave unset") }
}
