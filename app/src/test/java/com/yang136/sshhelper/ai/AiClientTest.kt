package com.yang136.sshhelper.ai

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpAiClient

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        client = OkHttpAiClient(
            OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build(),
        )
    }

    @After
    fun stop() = server.shutdown()

    private fun request(enableTools: Boolean = true) = AiChatRequest(
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "test-key",
        model = "test-model",
        messages = listOf(
            AiChatMessage(AiMessageRole.SYSTEM, "你是助手"),
            AiChatMessage(AiMessageRole.USER, "你好"),
        ),
        enableTools = enableTools,
    )

    @Test
    fun streamsTextAndToolArgumentFragments() = runBlocking {
        server.enqueue(sse(
            """{"choices":[{"delta":{"content":"先检查。"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"propose_terminal_command","arguments":"{\\\"command\\\":\\\"df "}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"-h\\\",\\\"summary\\\":\\\"磁盘\\\",\\\"expectedOutcome\\\":\\\"列表\\\"}"}}]},"finish_reason":"tool_calls"}]}""",
        ))

        val events = client.stream(request()).toList()

        assertTrue(events.any { it == AiStreamEvent.TextDelta("先检查。") })
        val fragments = events.filterIsInstance<AiStreamEvent.ToolCallDelta>()
        assertEquals(2, fragments.size)
        assertEquals("call_1", fragments.first().id)
        assertEquals("tool_calls", events.filterIsInstance<AiStreamEvent.Completed>().first().finishReason)
        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("propose_terminal_command"))
        assertTrue(body.contains("\"stream\":true"))
    }

    @Test
    fun retriesWithoutToolsOnExplicitUnsupportedResponseAndCachesCapability() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("unknown field tools"))
        server.enqueue(sse("""{"choices":[{"delta":{"content":"fallback"},"finish_reason":"stop"}]}"""))
        server.enqueue(sse("""{"choices":[{"delta":{"content":"cached"},"finish_reason":"stop"}]}"""))

        assertTrue(client.stream(request()).toList().contains(AiStreamEvent.TextDelta("fallback")))
        assertTrue(client.stream(request()).toList().contains(AiStreamEvent.TextDelta("cached")))

        val first = server.takeRequest().body.readUtf8()
        val fallback = server.takeRequest().body.readUtf8()
        val cached = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("\"tools\""))
        assertFalse(fallback.contains("\"tools\""))
        assertFalse(cached.contains("\"tools\""))
    }

    @Test
    fun doesNotFallbackAfterVisibleOutput() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"visible\"}}]}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        try {
            client.stream(request()).toList()
            fail("流中断应失败")
        } catch (_: AiException) {
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun surfacesHttpErrorBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid key"))
        try {
            client.stream(request(enableTools = false)).toList()
            fail("应抛出 AiException")
        } catch (error: AiException) {
            assertTrue(error.message.orEmpty().contains("401"))
        }
    }

    @Test
    fun malformedEventIsReported() = runBlocking {
        server.enqueue(sse("not-json"))
        try {
            client.stream(request(enableTools = false)).toList()
            fail("应抛出 AiException")
        } catch (error: AiException) {
            assertTrue(error.message.orEmpty().contains("无法解析"))
        }
    }

    private fun sse(vararg data: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(data.joinToString("\n\n", postfix = "\n\ndata: [DONE]\n\n") { "data: $it" })
}
