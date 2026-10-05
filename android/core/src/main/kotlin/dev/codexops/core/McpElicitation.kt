package dev.codexops.core

import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.serialization.json.*

/** The flat, typed form subset exposed by stock app-server, not a general JSON Schema engine. */
data class McpForm(val fields: List<McpField>) {
    val defaults: Map<String, JsonElement>
        get() = fields.mapNotNull { field ->
            field.schema["default"]?.takeUnless { it == JsonNull }?.let { field.name to it }
        }.toMap()

    fun validate(values: Map<String, JsonElement>): McpFormResult {
        val content = linkedMapOf<String, JsonElement>()
        val errors = linkedMapOf<String, String>()
        fields.forEach { field ->
            val value = values[field.name]
            if (value == null) {
                if (field.required) errors[field.name] = "Required"
            } else {
                val typed = field.typed(value)
                if (typed == null) errors[field.name] = "Enter a valid ${field.type} value"
                else if (!field.valid(typed)) errors[field.name] = "Check this field’s allowed values and limits"
                else content[field.name] = typed
            }
        }
        return McpFormResult(JsonObject(content), errors)
    }
}

data class McpFormResult(val content: JsonObject, val errors: Map<String, String>)
data class McpOption(val value: String, val label: String)
data class McpField(
    val name: String,
    val type: String,
    val required: Boolean,
    val schema: JsonObject,
    val options: List<McpOption>? = null,
) {
    val title: String get() = schema.str("title").ifBlank { name }

    internal fun typed(value: JsonElement): JsonElement? = when (type) {
        "string" -> (value as? JsonPrimitive)?.takeIf { it.isString }
        "boolean" -> (value as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull != null }
        "array" -> (value as? JsonArray)?.takeIf { values -> values.all { it is JsonPrimitive && it.isString } }
        "number", "integer" -> (value as? JsonPrimitive)?.contentOrNull?.let { raw ->
            raw.trim().toBigDecimalOrNull()?.takeIf {
                it.toDouble().isFinite() && (type != "integer" || it.stripTrailingZeros().scale() <= 0)
            }?.let { JsonPrimitive(if (type == "integer") it.setScale(0) else it) }
        }
        else -> null
    }

    internal fun valid(value: JsonElement): Boolean {
        fun bound(key: String): BigDecimal? = (schema[key] as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()
        fun within(size: Int, minimum: String, maximum: String): Boolean =
            (bound(minimum)?.let { size.toBigDecimal() >= it } ?: true) &&
                (bound(maximum)?.let { size.toBigDecimal() <= it } ?: true)
        return when (type) {
            "string" -> {
                val text = value.jsonPrimitive.content
                within(text.codePointCount(0, text.length), "minLength", "maxLength") &&
                    (options?.any { it.value == text } ?: true) &&
                    when (schema.str("format")) {
                        "", "null" -> true
                        "email" -> Regex("^[^\\s@]+@[^\\s@]+$").matches(text)
                        "uri" -> runCatching { URI(text).isAbsolute }.getOrDefault(false)
                        "date" -> runCatching { LocalDate.parse(text) }.isSuccess
                        "date-time" -> runCatching { OffsetDateTime.parse(text) }.isSuccess
                        else -> false
                    }
            }
            "number", "integer" -> {
                val number = value.jsonPrimitive.content.toBigDecimal()
                (bound("minimum")?.let { number >= it } ?: true) &&
                    (bound("maximum")?.let { number <= it } ?: true)
            }
            "array" -> {
                val values = value.jsonArray.map { it.jsonPrimitive.content }
                values.distinct().size == values.size && within(values.size, "minItems", "maxItems") &&
                    values.all { selected -> options.orEmpty().any { it.value == selected } }
            }
            "boolean" -> true
            else -> false
        }
    }
}

object McpElicitation {
    const val METHOD = "mcpServer/elicitation/request"

    fun response(action: String, content: JsonObject? = null): JsonObject {
        require(action in setOf("accept", "decline", "cancel"))
        require(action == "accept" || content == null)
        // URL accepts, declines and cancellations carry no submitted form content.
        return if (content == null) obj("action" to s(action))
        else obj("action" to s(action), "content" to content)
    }

    fun url(params: JsonObject): String? = params.str("url").takeIf { text ->
        params.str("mode") == "url" && params.str("elicitationId").isNotBlank() &&
            runCatching {
                val uri = URI(text)
                uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
            }.getOrDefault(false)
    }

    fun form(params: JsonObject): McpForm? = runCatching {
        require(params.str("mode") == "form")
        val schema = params.getValue("requestedSchema").jsonObject
        require(schema.str("type") == "object")
        require(schema.keys.all { it in setOf("type", "properties", "required", "\$schema") })
        val properties = schema.getValue("properties").jsonObject
        val required = (schema["required"]?.takeUnless { it == JsonNull } as? JsonArray)?.map {
            require(it is JsonPrimitive && it.isString)
            it.content
        }.orEmpty()
        require(schema["required"] == null || schema["required"] == JsonNull || schema["required"] is JsonArray)
        require(required.all { it in properties })
        McpForm(properties.map { (name, raw) -> field(name, raw.jsonObject, name in required) })
    }.getOrNull()

    private fun field(name: String, schema: JsonObject, required: Boolean): McpField {
        val type = schema.str("type")
        val common = setOf("type", "title", "description", "default")
        val allowed = common + when (type) {
            "string" -> setOf("format", "minLength", "maxLength", "enum", "enumNames", "oneOf")
            "number", "integer" -> setOf("minimum", "maximum")
            "boolean" -> emptySet()
            "array" -> setOf("items", "minItems", "maxItems")
            else -> error("Unsupported field")
        }
        require(schema.keys.all { it in allowed })
        listOf("title", "description").forEach { key ->
            require(schema[key] == null || schema[key] == JsonNull ||
                (schema[key] is JsonPrimitive && schema[key]!!.jsonPrimitive.isString))
        }
        val options = when {
            type == "array" -> options(schema.getValue("items").jsonObject, "anyOf")
            schema.containsKey("oneOf") -> {
                require(!schema.containsKey("enum"))
                options(schema, "oneOf")
            }
            schema.containsKey("enum") -> options(schema, "oneOf")
            else -> null
        }
        require(!schema.containsKey("enumNames") || schema.containsKey("enum"))
        if (type == "array") require(schema.getValue("items").jsonObject.keys.all { it in setOf("type", "enum", "anyOf") })
        if (schema["format"] != null && schema["format"] != JsonNull)
            require(schema.str("format") in setOf("email", "uri", "date", "date-time"))
        listOf("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems").forEach { key ->
            val raw = schema[key]?.takeUnless { it == JsonNull } ?: return@forEach
            require(raw is JsonPrimitive && !raw.isString)
            val number = raw.content.toBigDecimal()
            if (key !in setOf("minimum", "maximum")) require(number >= BigDecimal.ZERO && number.stripTrailingZeros().scale() <= 0)
        }
        val field = McpField(name, type, required, schema, options)
        schema["default"]?.takeUnless { it == JsonNull }?.let { require(field.typed(it) != null) }
        return field
    }

    private fun options(schema: JsonObject, titledKey: String): List<McpOption> {
        val options = if (schema.containsKey("enum")) {
            require(!schema.containsKey(titledKey))
            require(schema.str("type") == "string")
            val names = schema["enumNames"]?.takeUnless { it == JsonNull }?.jsonArray
            val values = schema.getValue("enum").jsonArray
            require(names == null || names.size == values.size)
            values.mapIndexed { index, value ->
                require(value is JsonPrimitive && value.isString)
                val label = names?.get(index)?.let { require(it is JsonPrimitive && it.isString); it.content }
                McpOption(value.content, label ?: value.content)
            }
        } else {
            schema.getValue(titledKey).jsonArray.map { value ->
                val option = value.jsonObject
                require(option.keys == setOf("const", "title"))
                require(option.getValue("const").jsonPrimitive.isString && option.getValue("title").jsonPrimitive.isString)
                McpOption(option.str("const"), option.str("title"))
            }
        }
        require(options.isNotEmpty() && options.map { it.value }.distinct().size == options.size)
        return options
    }
}
