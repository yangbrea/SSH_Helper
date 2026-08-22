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
    fun parsesChatCompletionResponse() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"df -h"}}]}"""),
        )
        val result = client.ask(request())
        assertEquals("df -h", result.text)

        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertTrue("请求体应包含模型与消息", recorded.body.readUtf8().contains("\"model\":\"deepseek-chat\""))
    }

    @Test
    fun surfacesHttpErrorWithBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid key"))
        try {
            client.ask(request())
            fail("应抛出 AiException")
        } catch (error: AiException) {
            assertTrue(error.message.orEmpty().contains("401"))
        }
    }

    @Test
    fun surfacesMalformedResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("not json"))
        try {
            client.ask(request())
            fail("应抛出 AiException")
        } catch (error: AiException) {
            assertTrue(error.message.orEmpty().contains("无法解析"))
        }
    }
}
