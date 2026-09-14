package com.kongbai.airepo.data.remote.ai

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AiFunctionCall(val name: String? = null, val arguments: String? = null)

@JsonClass(generateAdapter = true)
data class AiToolCall(
    val id: String? = null,
    val index: Int = 0,
    val type: String = "function",
    val function: AiFunctionCall = AiFunctionCall()
)

@JsonClass(generateAdapter = true)
data class AiMessage(
    val role: String = "user",
    val content: String? = null,
    val name: String? = null,
    @Json(name = "tool_calls") val toolCalls: List<AiToolCall>? = null,
    @Json(name = "tool_call_id") val toolCallId: String? = null
)

@JsonClass(generateAdapter = true)
data class AiFunctionDef(
    val name: String = "",
    val description: String = "",
    val parameters: Map<String, @JvmSuppressWildcards Any?> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class AiToolDef(
    val type: String = "function",
    val function: AiFunctionDef = AiFunctionDef()
)

@JsonClass(generateAdapter = true)
data class AiUsage(
    @Json(name = "prompt_tokens") val promptTokens: Int = 0,
    @Json(name = "completion_tokens") val completionTokens: Int = 0
)

@JsonClass(generateAdapter = true)
data class AiChoice(
    val index: Int = 0,
    val message: AiMessage? = null,
    val delta: AiMessage? = null,
    @Json(name = "finish_reason") val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class AiResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<AiChoice> = emptyList(),
    val usage: AiUsage? = null,
    val error: AiError? = null
)

@JsonClass(generateAdapter = true)
data class AiError(val message: String? = null, val code: String? = null, val type: String? = null)

@JsonClass(generateAdapter = true)
data class AiModelItem(val id: String = "", val object_: String? = null)

@JsonClass(generateAdapter = true)
data class AiModelsResponse(val data: List<AiModelItem> = emptyList())
