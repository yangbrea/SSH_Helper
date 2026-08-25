package com.yang136.sshhelper.ai

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject

open class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AiHttpException(
    val statusCode: Int,
    val responseDetail: String,
    val streamStarted: Boolean,
) : AiException("请求失败（HTTP $statusCode）：${responseDetail.take(200)}")

interface AiClient {
    fun stream(request: AiChatRequest): Flow<AiStreamEvent>
}

class OkHttpAiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : AiClient {
    private enum class ToolCapability { SUPPORTED, UNSUPPORTED }

    private val toolCapabilities = ConcurrentHashMap<String, ToolCapability>()

    override fun stream(request: AiChatRequest): Flow<AiStreamEvent> = flow {
        require(request.timeoutSeconds > 0) { "timeoutSeconds 必须大于 0" }
        val capabilityKey = "${request.baseUrl.trim().trimEnd('/')}\n${request.model}"
        val useTools = request.enableTools && toolCapabilities[capabilityKey] != ToolCapability.UNSUPPORTED
        try {
            collectAttempt(request, useTools) { emit(it) }
            if (useTools) toolCapabilities[capabilityKey] = ToolCapability.SUPPORTED
        } catch (error: AiHttpException) {
            val canFallback = useTools && !error.streamStarted && error.statusCode in setOf(400, 422)
            if (!canFallback) throw error
            toolCapabilities[capabilityKey] = ToolCapability.UNSUPPORTED
            collectAttempt(request, useTools = false) { emit(it) }
        }
    }

    private suspend fun collectAttempt(
        request: AiChatRequest,
        useTools: Boolean,
        emitEvent: suspend (AiStreamEvent) -> Unit,
    ) {
        var meaningfulOutput = false
        withTimeout(request.timeoutSeconds * 1_000L) {
            eventSourceFlow(request, useTools).collect { event ->
                if (event is AiStreamEvent.TextDelta || event is AiStreamEvent.ToolCallDelta) meaningfulOutput = true
                emitEvent(event)
            }
        }
        if (!meaningfulOutput) throw AiException("模型返回为空")
    }

    private fun eventSourceFlow(request: AiChatRequest, useTools: Boolean): Flow<AiStreamEvent> = callbackFlow {
        val emitted = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val httpRequest = Request.Builder()
            .url(request.baseUrl.trim().trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${request.apiKey}")
            .header("Content-Type", "application/json")
            .post(buildPayload(request, useTools).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    if (completed.compareAndSet(false, true)) trySend(AiStreamEvent.Completed())
                    close()
                    return
                }
                try {
                    parseChunk(data).forEach {
                        if (it is AiStreamEvent.TextDelta || it is AiStreamEvent.ToolCallDelta) emitted.set(true)
                        if (it is AiStreamEvent.Completed) completed.set(true)
                        trySend(it).getOrThrow()
                    }
                } catch (failure: Exception) {
                    eventSource.cancel()
                    close(AiException("无法解析模型响应：${failure.message ?: "未知错误"}", failure))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (completed.compareAndSet(false, true)) trySend(AiStreamEvent.Completed())
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val failure = if (response != null) {
                    val detail = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                    AiHttpException(response.code, detail, emitted.get())
                } else {
                    AiException("请求失败：${t?.message ?: "连接已关闭"}", t)
                }
                close(failure)
            }
        }
        val timedClient = client.newBuilder()
            .callTimeout(request.timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val eventSource = EventSources.createFactory(timedClient).newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun buildPayload(request: AiChatRequest, useTools: Boolean): JSONObject = JSONObject().apply {
        put("model", request.model)
        put("messages", JSONArray().apply { request.messages.forEach { put(it.toJson()) } })
        put("temperature", 0.3)
        put("stream", true)
        if (useTools) {
            put("tools", JSONArray().put(PROPOSE_COMMAND_TOOL))
            put("tool_choice", "auto")
        }
    }

    private fun AiChatMessage.toJson(): JSONObject = JSONObject().apply {
        put("role", role.wireName)
        if (content != null) put("content", content) else put("content", JSONObject.NULL)
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", JSONArray().apply {
                toolCalls.forEach { call ->
                    put(JSONObject().apply {
                        put("id", call.id)
                        put("type", "function")
                        put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
                    })
                }
            })
        }
        toolCallId?.let { put("tool_call_id", it) }
    }

    private fun parseChunk(data: String): List<AiStreamEvent> {
        val choices = JSONObject(data).optJSONArray("choices") ?: return emptyList()
        val events = mutableListOf<AiStreamEvent>()
        for (choiceIndex in 0 until choices.length()) {
            val choice = choices.getJSONObject(choiceIndex)
            val delta = choice.optJSONObject("delta")
            val content = delta?.opt("content")
            if (content is String && content.isNotEmpty()) events += AiStreamEvent.TextDelta(content)
            val toolCalls = delta?.optJSONArray("tool_calls")
            if (toolCalls != null) {
                for (index in 0 until toolCalls.length()) {
                    val call = toolCalls.getJSONObject(index)
                    val function = call.optJSONObject("function")
                    events += AiStreamEvent.ToolCallDelta(
                        index = call.optInt("index", index),
                        id = call.optString("id").takeIf(String::isNotEmpty),
                        name = function?.optString("name")?.takeIf(String::isNotEmpty),
                        argumentsDelta = function?.optString("arguments").orEmpty(),
                    )
                }
            }
            if (choice.has("finish_reason") && !choice.isNull("finish_reason")) {
                events += AiStreamEvent.Completed(choice.optString("finish_reason").takeIf(String::isNotEmpty))
            }
        }
        return events
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val PROPOSE_COMMAND_TOOL = JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "propose_terminal_command")
                put("description", "Propose exactly one next terminal command for the user to review and confirm.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("additionalProperties", false)
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().put("type", "string"))
                        put("summary", JSONObject().put("type", "string"))
                        put("expectedOutcome", JSONObject().put("type", "string"))
                    })
                    put("required", JSONArray().put("command").put("summary").put("expectedOutcome"))
                })
            })
        }
    }
}
