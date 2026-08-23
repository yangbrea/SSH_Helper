package com.yang136.sshhelper.ai

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun request() = AiRequest(
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "test-key",
        model = "deepseek-chat",
        systemPrompt = "你是助手",
        userMessage = "你好",
    )

    @Test
    fun streamsDeltasAndReturnsFullText() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"role":"assistant"}}]}

                    data: {"choices":[{"delta":{"content":"df "}}]}

                    data: {"choices":[{"delta":{"content":"-h"}}]}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )
        val deltas = mutableListOf<String>()
        val result = client.stream(request()) { deltas.add(it) }

        assertEquals(listOf("df ", "-h"), deltas)
        assertEquals("df -h", result)

        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertTrue("请求体应开启流式", recorded.body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun surfacesHttpErrorWithBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid key"))
        try {
            client.stream(request()) {}
            fail("应抛出 AiException")
        } catch (error: AiException) {
            assertTrue(error.message.orEmpty().contains("401"))
        }
    }

    @Test
    fun emptyStreamIsReportedAsEmptyResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("plain text, no data events"))
        try {
            client.stream(request()) {}
            fail("应抛出 AiException")
        } catch (error: AiException) {
            assertTrue(error.message.orEmpty().contains("返回为空"))
        }
    }

    @Test
    fun malformedDeltaIsReported() = runBlocking {
        server.enqueue(
            MockResponse().setBody("data: not-json\n\ndata: [DONE]\n\n"),
        )
        try {
            client.stream(request()) {}
            fail("应抛出 AiException")
        } catch (error: AiException) {
            assertTrue(error.message.orEmpty().contains("无法解析"))
        }
    }
}
