package dev.codexops.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class QuestionAnswersTest {
    @Test fun secretAnswersPreserveExactInput() {
        val question = obj("id" to s("secret"), "isSecret" to JsonPrimitive(true))
        val response = Decisions.questionAnswers(listOf(question), emptyMap(), mapOf("secret" to " value "))
        assertEquals(JsonArray(listOf(s(" value "))), response.map("answers").map("secret")["answers"])
    }

    @Test fun answersPreserveLabelsNotesAndExplicitlyUnansweredQuestions() {
        val questions = listOf("choice", "text", "empty").map { obj("id" to s(it)) }
        assertEquals(
            obj("answers" to obj(
                "choice" to obj("answers" to JsonArray(listOf(s(Decisions.OTHER_ANSWER), s("details")))),
                "text" to obj("answers" to JsonArray(listOf(s("typed answer")))),
                "empty" to obj("answers" to JsonArray(emptyList())),
            )),
            Decisions.questionAnswers(questions, mapOf("choice" to Decisions.OTHER_ANSWER),
                mapOf("choice" to " details ", "text" to "typed answer", "empty" to "  ", "stale" to "discard")),
        )
    }

    @Test fun onlyExplicitNonblockingQuestionsAvoidWaitingPresentation() {
        fun decision(method: String, params: kotlinx.serialization.json.JsonObject) =
            Decision(JsonPrimitive(1), method, params, 1)
        assertFalse(decision("item/tool/requestUserInput", obj("isBlocking" to JsonPrimitive(false))).blocksUser)
        assertTrue(decision("item/tool/requestUserInput", obj()).blocksUser)
        assertTrue(decision("item/tool/requestUserInput", obj("isBlocking" to JsonPrimitive(true))).blocksUser)
        assertTrue(decision("item/fileChange/requestApproval", obj("isBlocking" to JsonPrimitive(false))).blocksUser)
    }
}
