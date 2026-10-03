package dev.codexops.client

import dev.codexops.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class VisualizationsTest {
    private val marker = "visualize{\"path\":\"/work/chart.html\",\"mode\":\"wide\",\"title\":\"Chart\"}"

    @Test fun parsesMultipleReferencesWithoutEatingSurroundingMarkdown() {
        val parts = visualizationParts("Before\n\n$marker\n\nBetween\n$marker\nAfter")
        assertEquals(5, parts.size)
        assertEquals("Before", (parts[0] as VisualizationPart.Markdown).text)
        assertEquals(VisualizationRef("/work/chart.html", "Chart", true), (parts[1] as VisualizationPart.Visual).reference)
        assertEquals("After", (parts[4] as VisualizationPart.Markdown).text)
    }

    @Test fun codeMalformedAndPartialMarkersRemainInert() {
        val samples = listOf("```text\n$marker\n```", "~~~\n$marker\n~~~", "    $marker", "\t$marker", " \t$marker",
            "`$marker`", "prefix $marker", marker.dropLast(1),
            "visualize{\"path\":\"https://example.org/a.html\"}", "visualize{bad json}",
            "visualize{\"path\":\"/etc/passwd\"}")
        samples.forEach { sample ->
            assertEquals(sample, (visualizationParts(sample).single() as VisualizationPart.Markdown).text)
        }
    }

    @Test fun boundsFileReadsAndRejectsInvalidUtf8() = runBlocking {
        var reads = 0
        val ref = VisualizationRef("/work/chart.html")
        assertTrue(runCatching {
            readVisualization(ref, { obj("size" to JsonPrimitive(MAX_VISUALIZATION_BYTES + 1)) }, { reads++; byteArrayOf() })
        }.isFailure)
        assertEquals(0, reads)
        assertTrue(runCatching {
            readVisualization(ref, { obj() }, { ByteArray(MAX_VISUALIZATION_BYTES + 1) })
        }.isFailure)
        assertTrue(runCatching {
            readVisualization(ref, { obj() }, { byteArrayOf(0xc3.toByte(), 0x28) })
        }.isFailure)
        assertEquals("<div>✓</div>", readVisualization(ref, { obj("metadata" to obj("size" to JsonPrimitive(14))) }, { "<div>✓</div>".toByteArray() }))
    }

    @Test fun resourcePolicyRejectsNonCdnAndCredentialedUrls() {
        assertTrue(VisualizationPolicy.allowsResource("https://cdn.jsdelivr.net/npm/d3@7.9.0/dist/d3.min.js"))
        listOf("https://cdn.jsdelivr.net.evil.example/a.js", "http://cdn.jsdelivr.net/a.js",
            "https://user@cdn.jsdelivr.net/a.js", "https://cdn.jsdelivr.net:444/a.js",
            "file:///etc/passwd", "content://dev.codexops.client.files/a", "https://127.0.0.1/", GraceHost.endpoint,
            "https://visualization.invalid/").forEach { assertFalse(it, VisualizationPolicy.allowsResource(it)) }
    }

    @Test fun stateMustBeBoundedJsonObject() {
        assertTrue(validWidgetState("{\"modelContent\":{\"n\":2},\"privateContent\":null}"))
        assertFalse(validWidgetState("[]"))
        assertFalse(validWidgetState("null"))
        assertFalse(validWidgetState("{\"x\":\"${"é".repeat(9000)}\"}"))
    }
}
