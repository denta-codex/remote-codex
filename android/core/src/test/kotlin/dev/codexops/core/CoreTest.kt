package dev.codexops.core

import java.util.Base64
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
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
        assertEquals(
            "2-my-photo.png",
            safeAttachmentName(
                2,
                "my photo.HEIC",
                AttachmentKind.IMAGE,
                ImageFormat("image/png", "png"),
            ),
        )
        val image = TurnAttachment(AttachmentKind.IMAGE, "a.png", "/host/a.png")
        val file = TurnAttachment(AttachmentKind.FILE, "notes.txt", "/host/notes.txt")
        val imageOnly = turnInput("", listOf(image))
        assertEquals(listOf("text", "localImage"), imageOnly.map { it.jsonObject.str("type") })
        val mixed = turnInput("hello", listOf(image, file))
        assertEquals(
            listOf("text", "localImage"),
            mixed.map { it.jsonObject.str("type") },
        )
        val context = mixed.first().jsonObject.str("text")
        assertEquals("hello", parseAttachmentContext(context)?.request)
        assertEquals(
            listOf("a.png" to "/host/a.png", "notes.txt" to "/host/notes.txt"),
            parseAttachmentContext(context)?.files,
        )
        AttachmentPolicy.validateCombined(
            listOf(AttachmentKind.IMAGE to MAX_IMAGE_BYTES, AttachmentKind.FILE to 0L)
        )
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentPolicy.validateCombined(
                listOf(
                    AttachmentKind.FILE to MAX_IMAGE_BYTES,
                    AttachmentKind.FILE to MAX_IMAGE_BYTES,
                    AttachmentKind.FILE to MAX_IMAGE_BYTES,
                )
            )
        }
    }

    @Test
    fun attachmentContextIsHiddenAndGenericFilesRemainStructured() {
        val context =
            attachmentContext(
                "review these",
                listOf(
                    TurnAttachment(AttachmentKind.IMAGE, "shot.png", "/host/shot.png"),
                    TurnAttachment(AttachmentKind.FILE, "notes.txt", "/host/notes.txt"),
                ),
            )
        val entry =
            Entry(
                "turn",
                obj(
                    "id" to s("user"),
                    "type" to s("userMessage"),
                    "content" to
                        JsonArray(
                            listOf(
                                obj("type" to s("text"), "text" to s(context)),
                                obj(
                                    "type" to s("localImage"),
                                    "path" to s("/host/shot.png"),
                                ),
                            )
                        ),
                ),
            )
        assertEquals("review these", entry.text)
        assertEquals(listOf("notes.txt"), entry.files.map(FileRef::displayName))
        assertEquals("/host/notes.txt", entry.files.single().path)
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
                                            s(
                                                Base64.getEncoder()
                                                    .encodeToString("image".toByteArray())
                                            )
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
    fun rpcRejectionsSurfaceWithoutWaitingForTheRequestTimeout() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(ws: WebSocket, text: String) {
                        val message = wire.parseToJsonElement(text).jsonObject
                        val method = message.str("method")
                        if (method.isEmpty() || method == "initialized") return
                        if (method == "initialize") {
                            ws.send(
                                obj(
                                        "id" to message["id"],
                                        "result" to obj("codexHome" to s("/test")),
                                    )
                                    .toString()
                            )
                        } else {
                            ws.send(
                                obj(
                                        "id" to message["id"],
                                        "error" to
                                            obj(
                                                "code" to JsonPrimitive(-32000),
                                                "message" to s("Fixture rejection"),
                                            ),
                                    )
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
            rpc.connect(
                server.url("/").toString().replace("http://localhost:", "ws://127.0.0.1:"),
                "test",
            )
            val rejection =
                assertThrows(RpcRejected::class.java) {
                    runBlocking { rpc.call("fs/getMetadata", obj("path" to s("/test")), 2000) }
                }
            assertEquals(-32000, rejection.code)
            assertEquals("Fixture rejection", rejection.message)
        } finally {
            rpc.dispose()
            server.shutdown()
        }
        Unit
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
            assertTrue(failure?.javaClass?.name, failure is ConnectionFailure)
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
    fun completedPlanReplacesItsNonAuthoritativeStream() {
        val t = Timeline()
        t.event(
            "item/plan/delta",
            obj("turnId" to s("t"), "itemId" to s("p"), "delta" to s("Draft plan")),
        )
        assertFalse(t.values().single().completed)
        t.event(
            "item/completed",
            obj(
                "turnId" to s("t"),
                "item" to
                    obj(
                        "id" to s("p"),
                        "type" to s("plan"),
                        "text" to s("1. Inspect\n2. Implement"),
                    ),
            ),
        )
        assertEquals("1. Inspect\n2. Implement", t.values().single().text)
        assertTrue(t.values().single().completed)
        t.event(
            "item/plan/delta",
            obj("turnId" to s("t"), "itemId" to s("p"), "delta" to s(" stale")),
        )
        assertEquals("1. Inspect\n2. Implement", t.values().single().text)
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

    @Test
    fun fragmentedTransportCarriesImageBoundaryAndLargeResponse() = runBlocking {
        val server = MockWebServer()
        val uploaded = CompletableDeferred<ByteArray>()
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val message = wire.parseToJsonElement(text).jsonObject
                            val method = message.str("method")
                            if (method.isEmpty() || method == "initialized") return
                            val result =
                                when (method) {
                                    "initialize" -> obj("codexHome" to s("/test"))
                                    "fs/writeFile" -> {
                                        uploaded.complete(
                                            Base64.getDecoder()
                                                .decode(message.map("params").str("dataBase64"))
                                        )
                                        obj()
                                    }
                                    "unicode/boundary" ->
                                        obj("value" to message.map("params")["value"])
                                    else -> obj()
                                }
                            webSocket.send(
                                obj("id" to message["id"], "result" to result).toString()
                            )
                        }
                    }
                )
        )
        server.start()
        val rpc = Rpc(true)
        try {
            rpc.connect(
                server.url("/codex/rpc").toString().replace("http://localhost:", "ws://127.0.0.1:"),
                "test",
            )
            assertNull(server.takeRequest().getHeader("Sec-WebSocket-Extensions"))
            val boundary = ByteArray(20 * 1024 * 1024)
            "remote-codex-image-boundary".toByteArray().copyInto(boundary)
            rpc.call(
                "fs/writeFile",
                obj(
                    "path" to s("/test/20-mib.png"),
                    "dataBase64" to s(Base64.getEncoder().encodeToString(boundary)),
                ),
            )
            assertArrayEquals(boundary, withTimeout(2000) { uploaded.await() })
            val unicode = "a".repeat(256 * 1024 - 1) + "🙂 after boundary"
            assertEquals(
                unicode,
                rpc.call("unicode/boundary", obj("value" to s(unicode))).str("value"),
            )
        } finally {
            rpc.dispose()
            server.shutdown()
        }
        Unit
    }

    @Test
    fun acceptsStockSizedSingleFrameResponse() = runBlocking {
        val started = CountDownLatch(1)
        val inboundSize = 28 * 1024 * 1024
        val server =
            object :
                WebSocketServer(
                    InetSocketAddress("127.0.0.1", 0),
                    listOf(Draft_6455(emptyList(), 100 * 1024 * 1024)),
                ) {
                override fun onOpen(webSocket: org.java_websocket.WebSocket, request: ClientHandshake) {}

                override fun onClose(
                    webSocket: org.java_websocket.WebSocket,
                    code: Int,
                    reason: String,
                    remote: Boolean,
                ) {}

                override fun onMessage(webSocket: org.java_websocket.WebSocket, text: String) {
                    val message = wire.parseToJsonElement(text).jsonObject
                    val method = message.str("method")
                    if (method.isEmpty() || method == "initialized") return
                    val result =
                        if (method == "initialize") obj("codexHome" to s("/test"))
                        else obj("blob" to s("z".repeat(inboundSize)))
                    webSocket.send(obj("id" to message["id"], "result" to result).toString())
                }

                override fun onError(
                    webSocket: org.java_websocket.WebSocket?,
                    error: Exception,
                ) {}

                override fun onStart() {
                    started.countDown()
                }
            }
        server.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val rpc = Rpc(true)
        try {
            rpc.connect("ws://127.0.0.1:${server.port}/codex/rpc", "test")
            assertEquals(inboundSize, rpc.call("fs/readLarge").str("blob").length)
        } finally {
            rpc.dispose()
            server.stop(1000)
        }
        Unit
    }
}
