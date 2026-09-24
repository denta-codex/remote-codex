package dev.codexops.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test
    fun imageItemsRemainStructuredAndDoNotBecomePlaceholderText() {
        val entry =
            Entry(
                "turn",
                obj(
                    "id" to s("user"),
                    "type" to s("userMessage"),
                    "content" to
                        JsonArray(
                            listOf(
                                obj("type" to s("text"), "text" to s("look")),
                                obj("type" to s("localImage"), "path" to s("/tmp/a.png")),
                                obj(
                                    "type" to s("image"),
                                    "url" to s("data:image/png;base64,AA=="),
                                ),
                            )
                        ),
                ),
            )
        assertEquals("look", entry.text)
        assertEquals(2, entry.media.size)
        assertEquals(MediaLocation.HOST_PATH, entry.media[0].location)
        assertEquals(MediaLocation.DATA_URL, entry.media[1].location)

        val view =
            Entry(
                "turn",
                obj("id" to s("view"), "type" to s("imageView"), "path" to s("/tmp/b.png")),
            )
        val generation =
            Entry(
                "turn",
                obj(
                    "id" to s("generation"),
                    "type" to s("imageGeneration"),
                    "status" to s("completed"),
                    "result" to s("AA=="),
                ),
            )
        assertEquals(MediaLocation.HOST_PATH, view.media.single().location)
        assertEquals(MediaLocation.BASE64, generation.media.single().location)
    }

    @Test
    fun failedImageGenerationKeepsItsStatus() {
        val entry =
            Entry(
                "turn",
                obj(
                    "id" to s("generation"),
                    "type" to s("imageGeneration"),
                    "status" to s("failed"),
                ),
            )
        assertTrue(entry.media.isEmpty())
        assertEquals("failed", entry.text)
    }

    @Test
    fun imageLimitsNamesAndImageOnlyInputMatchPolicy() {
        ImagePolicy.validateSize(MAX_IMAGE_BYTES)
        ImagePolicy.validateCombined(listOf(MAX_IMAGE_BYTES, MAX_IMAGE_BYTES, 10L * 1024 * 1024))
        assertThrows(IllegalArgumentException::class.java) {
            ImagePolicy.validateSize(MAX_IMAGE_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImagePolicy.validateCombined(listOf(MAX_IMAGE_BYTES, MAX_IMAGE_BYTES, MAX_IMAGE_BYTES))
        }
        assertEquals("2-my-photo.png", safeAttachmentName(2, "my photo.HEIC", ImageFormat("image/png", "png")))
        val imageOnly = turnInput("", listOf("/host/a.png"))
        assertEquals("localImage", imageOnly.single().jsonObject.str("type"))
        val mixed = turnInput("hello", listOf("/host/a.png", "/host/b.jpg"))
        assertEquals(listOf("text", "localImage", "localImage"), mixed.map { it.jsonObject.str("type") })
    }

    @Test
    fun filesystemHelpersUseStockWireShapes() = runBlocking {
        val methods = mutableListOf<String>()
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(ws: WebSocket, text: String) {
                        val message = wire.parseToJsonElement(text).jsonObject
                        val method = message.str("method")
                        if (method.isEmpty() || method == "initialized") return
                        methods += method
                        val result =
                            when (method) {
                                "initialize" -> obj("codexHome" to s("/test"))
                                "fs/readFile" ->
                                    obj(
                                        "dataBase64" to
                                            s(Base64.getEncoder().encodeToString("image".toByteArray()))
                                    )
                                else -> obj()
                            }
                        if (method == "fs/writeFile") {
                            assertEquals("/test/a.png", message.map("params").str("path"))
                            assertEquals(
                                "image",
                                String(
                                    Base64.getDecoder()
                                        .decode(message.map("params").str("dataBase64"))
                                ),
                            )
                        }
                        ws.send(obj("id" to message["id"], "result" to result).toString())
                    }
                }
            )
        )
        server.start()
        val rpc = Rpc(true)
        try {
            rpc.connect(
                server.url("/").toString().replace("http://localhost:", "ws://127.0.0.1:"),
                "test",
            )
            rpc.createDirectory("/test/images")
            rpc.writeFile("/test/a.png", "image".toByteArray())
            assertEquals("image", String(rpc.readFile("/test/a.png")))
            assertTrue(methods.containsAll(listOf("fs/createDirectory", "fs/writeFile", "fs/readFile")))
        } finally {
            rpc.dispose()
            server.shutdown()
        }
    }

    @Test
    fun rejectedHandshakePreservesStatusWithoutResponseBody() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("private response body"))
        server.start()
        val rpc = Rpc(true)
        try {
            val failure = runCatching {
                rpc.connect("ws://127.0.0.1:${server.port}/codex/rpc", "private token")
            }.exceptionOrNull()
            assertTrue(failure is ConnectionFailure)
            assertEquals(401, (failure as ConnectionFailure).httpStatus)
            assertFalse(failure.toString().contains("private"))
        } finally {
            rpc.dispose()
            server.shutdown()
        }
        Unit
    }

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
