package dev.codexops.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ApprovalBatchTest {
    private fun field(id: String, count: Int = 1) = obj("id" to s(id), "vault" to s("Fixture"), "item" to s("Item"), "field" to s("password"), "occurrences" to JsonPrimitive(count))
    private fun request(fields: List<JsonObject> = listOf(field("f1", 2), field("f2")), occurrences: Int = 3) = obj(
        "kind" to s("inject"), "unique_count" to JsonPrimitive(fields.size), "occurrence_count" to JsonPrimitive(occurrences), "fields" to JsonArray(fields))
    @Test fun metadataParsesUniqueSelectionsWithRepeatedOccurrences() {
        val batch = ApprovalBatch.parse(request())
        assertEquals(2, batch.fields.size)
        assertEquals(3, batch.occurrences)
        assertEquals(2, batch.fields[0].occurrences)
    }
    @Test fun malformedMetadataCannotBecomeAnEditableBatch() {
        val malformed = listOf(request(listOf(field("same"), field("same")), 2), request(occurrences = 4), request(emptyList(), 0),
            request(listOf(field("f1", -1)), 1), JsonObject(request().filterKeys { it != "fields" }),
            JsonObject(request() + ("unique_count" to s("2"))),
            request(listOf(JsonObject(field("f1") + ("field" to JsonPrimitive(123)))), 1))
        malformed.forEach {
            try { ApprovalBatch.parse(it); fail("invalid metadata accepted") }
            catch (error: ApprovalFailure) { assertEquals("invalid_response", error.kind); assertFalse(error.message.orEmpty().contains("Fixture")) }
        }
    }
    @Test fun valuesUseUtf8ByteLimitsAndExplicitEmptyIsValid() {
        assertTrue(approvalValueFits(""))
        assertTrue(approvalValueFits("x".repeat(APPROVAL_VALUE_LIMIT)))
        assertFalse(approvalValueFits("x".repeat(APPROVAL_VALUE_LIMIT + 1)))
        assertTrue(approvalValueFits("😀".repeat(APPROVAL_VALUE_LIMIT / 4)))
        assertFalse(approvalValueFits("😀".repeat(APPROVAL_VALUE_LIMIT / 4 + 1)))
        assertFalse(approvalValueFits("\ud800"))
        assertFalse(approvalValueFits("\udfff"))
    }
    @Test fun batchTransportBoundIncludesJsonEscaping() {
        val connection = ApprovalConnection()
        val fitting = JsonArray(listOf(obj("id" to s("f1"), "value" to s("\u0001".repeat(APPROVAL_VALUE_LIMIT)))))
        assertTrue(connection.releaseFits("fixture", values = fitting))
        val tooLarge = JsonArray(listOf("f1", "f2").map { obj("id" to s(it), "value" to s("\u0001".repeat(APPROVAL_VALUE_LIMIT))) })
        assertFalse(connection.releaseFits("fixture", values = tooLarge))
        try { connection.prepare("release_batch", "fixture", values = tooLarge); fail("large escaped batch accepted") }
        catch (error: ApprovalFailure) { assertEquals("request_limit", error.kind) }
        val invalid = JsonArray(listOf(obj("id" to s("f1"), "value" to JsonNull)))
        try { connection.prepare("release_batch", "fixture", values = invalid); fail("missing value accepted") }
        catch (error: ApprovalFailure) { assertEquals("invalid_value", error.kind) }
    }
    @Test fun submissionDiagnosticsAndConsumptionDoNotExposeValues() {
        val submission = ApprovalConnection().prepare("release_batch", "fixture", values = JsonArray(listOf(obj("id" to s("f1"), "value" to s("PRIVATE_FIXTURE")))))
        assertFalse(submission.toString().contains("PRIVATE_FIXTURE"))
        assertTrue(submission.take().contains("PRIVATE_FIXTURE"))
        try { submission.take(); fail("payload consumed twice") }
        catch (error: ApprovalFailure) { assertEquals("already_submitted", error.kind) }
    }
}
