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

/** OpenAI-compatible streaming chat completion client (SSE, single turn). */
interface AiClient {
    /**
     * Streams the completion, invoking [onDelta] with each text increment (called on the IO
     * dispatcher). Returns the full accumulated text.
     */
    suspend fun stream(request: AiRequest, onDelta: (String) -> Unit): String
}

class OkHttpAiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build(),
) : AiClient {

    override suspend fun stream(request: AiRequest, onDelta: (String) -> Unit): String = withContext(Dispatchers.IO) {
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
            put("stream", true)
        }
        val httpRequest = Request.Builder()
            .url(request.baseUrl.trim().trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${request.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful) {
                val detail = body?.string().orEmpty()
                throw AiException("请求失败（HTTP ${response.code}）：${detail.take(200)}")
            }
            body ?: throw AiException("请求失败：响应为空")
            val text = body.charStream().useLines { lines ->
                val builder = StringBuilder()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = parseDelta(data)
                    if (delta.isNotEmpty()) {
                        builder.append(delta)
                        onDelta(delta)
                    }
                }
                builder
            }
            if (text.isEmpty()) throw AiException("模型返回为空")
            return@withContext text.toString()
        }
    }

    private fun parseDelta(data: String): String {
        return try {
            val json = JSONObject(data)
            json.getJSONArray("choices")
                .getJSONObject(0)
                .optJSONObject("delta")
                ?.optString("content")
                .orEmpty()
        } catch (error: Exception) {
            throw AiException("无法解析模型响应：${error.message ?: "未知错误"}")
        }
    }
}
