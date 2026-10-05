package dev.codexops.core

import kotlinx.serialization.json.*

const val APPROVAL_BATCH_CAPABILITY = "inject_batch_v1"
const val APPROVAL_VALUE_LIMIT = 64 * 1024
const val APPROVAL_WIRE_LIMIT = 512 * 1024

data class ApprovalField(val id: String, val vault: String, val item: String, val field: String, val occurrences: Int)

/** Metadata only. Neither templates nor selected values belong in this model. */
data class ApprovalBatch(val fields: List<ApprovalField>, val occurrences: Int) {
    companion object {
        fun parse(request: JsonObject): ApprovalBatch {
            fun invalid(): Nothing = throw ApprovalFailure("invalid_response")
            fun text(value: JsonObject, key: String): String =
                (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: invalid()
            fun count(value: JsonObject, key: String): Int =
                (value[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 } ?: invalid()
            if (request.str("kind") != "inject") invalid()
            val unique = count(request, "unique_count")
            val occurrences = count(request, "occurrence_count")
            val raw = request["fields"] as? JsonArray ?: invalid()
            if (raw.size != unique || unique > 4096 || occurrences > 4096) invalid()
            val fields = raw.map { element ->
                val value = element as? JsonObject ?: invalid()
                ApprovalField(text(value, "id"), text(value, "vault"), text(value, "item"), text(value, "field"), count(value, "occurrences"))
            }
            if (fields.map { it.id }.toSet().size != unique || fields.sumOf { it.occurrences.toLong() } != occurrences.toLong()) invalid()
            return ApprovalBatch(fields, occurrences)
        }
    }
}

/** String encoding must not silently replace unpaired UTF-16 surrogates. */
fun approvalValueFits(value: String): Boolean {
    var i = 0
    while (i < value.length) {
        val c = value[i++]
        if (c.isHighSurrogate()) {
            if (i >= value.length || !value[i++].isLowSurrogate()) return false
        } else if (c.isLowSurrogate()) return false
    }
    return value.toByteArray(Charsets.UTF_8).size <= APPROVAL_VALUE_LIMIT
}
