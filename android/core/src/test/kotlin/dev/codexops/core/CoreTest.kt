package dev.codexops.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test
    fun completedSnapshotReplacesStreamAndPrependingDoesNotDuplicate() {
        val t = Timeline()
        t.event(
            "item/agentMessage/delta",
            obj("turnId" to s("t"), "itemId" to s("a"), "delta" to s("hel")),
        )
        t.event(
            "item/completed",
            obj(
                "turnId" to s("t"),
                "item" to obj("id" to s("a"), "type" to s("agentMessage"), "text" to s("hello")),
            ),
        )
        t.snapshot(
            listOf(
                obj(
                    "id" to s("t"),
                    "items" to
                        JsonArray(
                            listOf(
                                obj(
                                    "id" to s("a"),
                                    "type" to s("agentMessage"),
                                    "text" to s("hello"),
                                )
                            )
                        ),
                )
            ),
            true,
        )
        assertEquals(1, t.values().size)
        assertEquals("hello", t.values().single().text)
    }

    @Test
    fun historyHydrationDoesNotDoubleBufferedStream() {
        val t = Timeline()
        val item = obj("id" to s("a"), "type" to s("agentMessage"), "text" to s("Hello"))
        val turns = listOf(obj("id" to s("t"), "items" to JsonArray(listOf(item))))
        val events =
            listOf(
                obj(
                    "method" to s("item/agentMessage/delta"),
                    "params" to
                        obj("turnId" to s("t"), "itemId" to s("a"), "delta" to s("Hello world")),
                )
            )
        t.hydrate(turns, events)
        assertEquals("Hello world", t.values().single().text)
    }

    @Test
    fun permissionsAreTurnScopedAndDeclineGrantsNothing() {
        val d =
            Decision(
                s("r"),
                "item/permissions/requestApproval",
                obj("permissions" to obj("network" to obj("enabled" to JsonPrimitive(true)))),
                1,
            )
        assertEquals(obj(), Decisions.result(d, false).map("permissions"))
        assertEquals("turn", Decisions.result(d, true).str("scope"))
    }

    @Test
    fun serverRequestIDDoesNotConsumeClientResponse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(ws: WebSocket, text: String) {
                            val m = wire.parseToJsonElement(text).jsonObject
                            if (m.str("method") == "initialize") {
                                ws.send(
                                    obj(
                                            "id" to m["id"],
                                            "method" to s("item/tool/requestUserInput"),
                                            "params" to obj(),
                                        )
                                        .toString()
                                )
                                ws.send(
                                    obj("id" to m["id"], "result" to obj("codexHome" to s("/test")))
                                        .toString()
                                )
                            }
                        }
                    }
                )
        )
        server.start()
        val rpc = Rpc(true)
        try {
            val result =
                rpc.connect(
                    server.url("/").toString().replace("http://localhost:", "ws://127.0.0.1:"),
                    "test",
                )
            assertEquals("/test", result.str("codexHome"))
            assertEquals(
                "item/tool/requestUserInput",
                withTimeout(2000) { rpc.events.receive() }.str("method"),
            )
            val epoch = rpc.generation
            rpc.close()
            assertThrows(ConnectionLost::class.java) { rpc.respond(s("1"), obj(), epoch) }
        } finally {
            rpc.dispose()
            server.shutdown()
        }
        Unit
    }
}
