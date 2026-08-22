package com.yang136.sshhelper.ai

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AiException(message: String) : Exception(message)

data class AiRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val userMessage: String,
    val timeoutSeconds: Long = 90,
)

data class AiResponse(val text: String)

/** OpenAI-compatible single-turn chat completion client. */
interface AiClient {
    suspend fun ask(request: AiRequest): AiResponse
}

class OkHttpAiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build(),
) : AiClient {

    override suspend fun ask(request: AiRequest): AiResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", request.model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", request.systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", request.userMessage)
                })
            })
            put("temperature", 0.3)
            put("stream", false)
        }
        val httpRequest = Request.Builder()
            .url(request.baseUrl.trim().trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${request.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AiException("请求失败（HTTP ${response.code}）：${responseBody.take(200)}")
            }
            try {
                val json = JSONObject(responseBody)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content")
                if (content.isBlank()) throw AiException("模型返回为空")
                AiResponse(content.trim())
            } catch (error: AiException) {
                throw error
            } catch (error: Exception) {
                throw AiException("无法解析模型响应：${error.message ?: "未知错误"}")
            }
        }
    }
}
