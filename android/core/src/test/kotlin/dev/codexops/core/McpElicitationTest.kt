package dev.codexops.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpElicitationTest {
    private fun form(properties: String, required: String = "[]") = McpElicitation.form(wire.parseToJsonElement(
        """{"mode":"form","serverName":"fixture","threadId":"thread","turnId":null,
        "requestedSchema":{"type":"object","properties":$properties,"required":$required}}"""
    ).jsonObject)

    @Test fun acceptedFormsKeepTypesAndOmitUnsetOptionalValues() {
        val form = form("""{
            "name":{"type":"string","minLength":1},
            "count":{"type":"integer","minimum":1,"maximum":5,"default":3},
            "price":{"type":"number"},"confirmed":{"type":"boolean"},"optional":{"type":"boolean"}
        }""", """["name","count","confirmed"]""")!!
        assertEquals(mapOf("count" to JsonPrimitive(3)), form.defaults)
        assertEquals(setOf("name", "confirmed"), form.validate(form.defaults).errors.keys)
        val result = form.validate(form.defaults + mapOf("name" to s("Example"), "price" to s("1.25"), "confirmed" to JsonPrimitive(false)))
        assertTrue(result.errors.isEmpty())
        assertEquals(obj("name" to s("Example"), "count" to JsonPrimitive(3), "price" to JsonPrimitive(1.25), "confirmed" to JsonPrimitive(false)), result.content)
        assertFalse(result.content.containsKey("optional"))
        assertEquals(obj("action" to s("accept"), "content" to result.content), McpElicitation.response("accept", result.content))
        assertEquals(obj("action" to s("decline")), McpElicitation.response("decline"))
        assertEquals(obj("action" to s("cancel")), McpElicitation.response("cancel"))
        assertEquals(obj("action" to s("accept")), McpElicitation.response("accept"))
        assertThrows(IllegalArgumentException::class.java) { McpElicitation.response("decline", result.content) }
    }

    @Test fun numericLengthFormatAndSelectionConstraintsBlockInvalidSubmissions() {
        val form = form("""{
            "integer":{"type":"integer","minimum":0,"maximum":4},
            "number":{"type":"number","minimum":-2},
            "text":{"type":"string","minLength":2,"maxLength":3},
            "email":{"type":"string","format":"email"},"uri":{"type":"string","format":"uri"},
            "date":{"type":"string","format":"date"},"time":{"type":"string","format":"date-time"},
            "select":{"type":"string","enum":["a","b"]},
            "multi":{"type":"array","items":{"type":"string","enum":["a","b"]},"minItems":1,"maxItems":2}
        }""")!!
        val invalid = mapOf("integer" to s("1.2"), "number" to s("NaN"), "text" to s("a"),
            "email" to s("bad"), "uri" to s("relative"), "date" to s("2026-02-30"), "time" to s("2026-10-05"),
            "select" to s("c"), "multi" to JsonArray(listOf(s("a"), s("a"))))
        assertEquals(invalid.keys, form.validate(invalid).errors.keys)
        val valid = mapOf("integer" to s("4"), "number" to s("-1.5"), "text" to s("🙂a"),
            "email" to s("user@example.com"), "uri" to s("https://example.com"), "date" to s("2026-10-05"),
            "time" to s("2026-10-05T10:00:00-04:00"), "select" to s("b"), "multi" to JsonArray(listOf(s("a"))))
        assertTrue(form.validate(valid).errors.isEmpty())
        assertEquals(setOf("integer", "number", "text", "multi"), form.validate(valid + mapOf(
            "integer" to s("5"), "number" to s("-3"), "text" to s("long"), "multi" to JsonArray(emptyList())
        )).errors.keys)
        assertEquals(setOf("number"), form.validate(valid + ("number" to s("1e999"))).errors.keys)
        assertEquals(JsonPrimitive(4), form.validate(valid + ("integer" to s("4.0"))).content["integer"])
    }

    @Test fun enumLabelsNeverReplaceWireValuesAndAllStockVariantsParse() {
        val form = form("""{
            "legacy":{"type":"string","enum":["old"],"enumNames":["Old label"]},
            "single":{"type":"string","oneOf":[{"const":"one","title":"One label"}]},
            "multi":{"type":"array","items":{"anyOf":[{"const":"many","title":"Many label"}]},"default":["many"]}
        }""")!!
        assertEquals(listOf("Old label", "One label", "Many label"), form.fields.map { it.options!!.single().label })
        val result = form.validate(form.defaults + mapOf("legacy" to s("old"), "single" to s("one")))
        assertTrue(result.errors.isEmpty())
        assertEquals("one", result.content.str("single"))
        assertEquals(JsonArray(listOf(s("many"))), result.content["multi"])
    }

    @Test fun unsupportedOrMalformedSchemasCannotBePartiallySubmitted() {
        assertNull(form("""{"nested":{"type":"object","properties":{}}}"""))
        assertNull(form("""{"text":{"type":"string","pattern":".*"}}"""))
        assertNull(form("""{"choice":{"type":"string","enum":["a"],"enumNames":[]}}"""))
        assertNull(form("""{"count":{"type":"integer","minimum":"1"}}"""))
        assertNull(form("{}", """["missing"]"""))
        assertNull(McpElicitation.form(obj("mode" to s("openai/form"), "requestedSchema" to obj())))
        assertNull(McpElicitation.form(obj("mode" to s("openai/userVerification"))))
        assertTrue(form("{}")!!.validate(emptyMap()).errors.isEmpty())
    }

    @Test fun urlRequiresExplicitHttpsDestinationAndAnElicitationId() {
        fun request(url: String) = obj("mode" to s("url"), "url" to s(url), "elicitationId" to s("auth"))
        assertEquals("https://example.com/auth", McpElicitation.url(request("https://example.com/auth")))
        listOf("http://example.com", "javascript:alert(1)", "intent://example.com", "https://user:password@example.com", "https:///missing", "bad").forEach {
            assertNull(McpElicitation.url(request(it)))
        }
        assertNull(McpElicitation.url(obj("mode" to s("url"), "url" to s("https://example.com"))))
    }
}
