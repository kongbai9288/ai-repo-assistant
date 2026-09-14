package com.kongbai.airepo.data.remote.ai

import com.kongbai.airepo.core.Constants
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.lang.reflect.ParameterizedType

sealed interface AiEvent {
    data class Text(val text: String) : AiEvent
    data class ToolCalls(val calls: List<AiToolCall>) : AiEvent
    data class Finished(val reason: String?) : AiEvent
    data class Failed(val message: String) : AiEvent
}

interface AiRest {
    suspend fun chatOnce(
        baseUrl: String, apiKey: String, model: String,
        messages: List<AiMessage>, tools: List<AiToolDef>?, temperature: Float
    ): Result<AiMessage>

    fun chatStream(
        baseUrl: String, apiKey: String, model: String,
        messages: List<AiMessage>, tools: List<AiToolDef>?, temperature: Float
    ): Flow<AiEvent>

    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>>
}

class AiClient(private val ok: OkHttpClient, private val moshi: Moshi) : AiRest {

    private val mapType: ParameterizedType =
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)

    private val msgAdapter = moshi.adapter(AiMessage::class.java)
    private val respAdapter = moshi.adapter(AiResponse::class.java)
    private val modelsAdapter = moshi.adapter(AiModelsResponse::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)

    private fun buildBody(
        model: String, messages: List<AiMessage>, tools: List<AiToolDef>?,
        temperature: Float, stream: Boolean
    ): Map<String, Any?> {
        val list = mutableListOf<Map<String, Any?>>()
        messages.forEach { m ->
            val raw = msgAdapter.toJsonValue(m)
            @Suppress("UNCHECKED_CAST")
            (raw as? Map<String, Any?>)?.let { list.add(it) }
        }
        val body = linkedMapOf<String, Any?>(
            "model" to model,
            "messages" to list,
            "temperature" to temperature,
            "stream" to stream
        )
        if (!tools.isNullOrEmpty()) {
            val toolMaps = tools.map { t ->
                mapOf(
                    "type" to "function",
                    "function" to mapOf(
                        "name" to t.function.name,
                        "description" to t.function.description,
                        "parameters" to t.function.parameters
                    )
                )
            }
            body["tools"] = toolMaps
            body["tool_choice"] = "auto"
        }
        return body
    }

    private fun request(baseUrl: String, apiKey: String, body: Map<String, Any?>, stream: Boolean): Request {
        val json = mapAdapter.toJson(body)
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val b = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", Constants.USER_AGENT)
            .addHeader("Accept", if (stream) "text/event-stream" else "application/json")
        if (apiKey.isBlank()) b.addHeader("X-Api-Key", "")
        return b.post(json.toRequestBody("application/json".toMediaType())).build()
    }

    override suspend fun chatOnce(
        baseUrl: String, apiKey: String, model: String,
        messages: List<AiMessage>, tools: List<AiToolDef>?, temperature: Float
    ): Result<AiMessage> = withContext(Dispatchers.IO) {
        runCatching {
            ok.newCall(request(baseUrl, apiKey, buildBody(model, messages, tools, temperature, false), false))
                .execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(400)}")
                    val parsed = respAdapter.fromJson(text) ?: error("解析失败")
                    parsed.error?.let { error(it.message ?: "AI 接口报错") }
                    parsed.choices.firstOrNull()?.message ?: error("无返回内容")
                }
        }
    }

    override fun chatStream(
        baseUrl: String, apiKey: String, model: String,
        messages: List<AiMessage>, tools: List<AiToolDef>?, temperature: Float
    ): Flow<AiEvent> = flow {
        val call = ok.newCall(request(baseUrl, apiKey, buildBody(model, messages, tools, temperature, true), true))
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    emit(AiEvent.Failed("HTTP ${resp.code}: ${err.take(400)}"))
                    return@flow
                }
                val stream = resp.body ?: run { emit(AiEvent.Failed("空响应")); return@flow }
                val reader = stream.charStream().buffered()
                // 增量拼接 tool_calls：index -> 片段
                val indexMap = LinkedHashMap<Int, MutableToolCall>()
                var finishedReason: String? = null
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val chunk = runCatching { respAdapter.fromJson(payload) }.getOrNull() ?: continue
                        chunk.error?.let { emit(AiEvent.Failed(it.message ?: "AI 接口报错")); break }
                        val choice = chunk.choices.firstOrNull() ?: continue
                        choice.delta?.content?.let { if (it.isNotEmpty()) emit(AiEvent.Text(it)) }
                        choice.delta?.toolCalls?.forEach { tc ->
                            val slot = indexMap.getOrPut(tc.index) { MutableToolCall() }
                            tc.id?.let { slot.id = it }
                            tc.function?.name?.let { if (it.isNotBlank()) slot.name += it }
                            tc.function?.arguments?.let { if (it.isNotEmpty()) slot.args += it }
                        }
                        if (choice.finishReason != null) {
                            finishedReason = choice.finishReason
                            break
                        }
                    }
                } finally {
                    runCatching { reader.close() }
                }
                if (indexMap.isNotEmpty()) {
                    val calls = indexMap.entries.sortedBy { it.key }.map { (_, v) ->
                        AiToolCall(
                            id = v.id.ifBlank { "call_${v.name}" },
                            function = AiFunctionCall(name = v.name, arguments = v.args)
                        )
                    }.filter { it.function.name?.isNotBlank() == true }
                    if (calls.isNotEmpty()) emit(AiEvent.ToolCalls(calls))
                }
                emit(AiEvent.Finished(finishedReason))
            }
        } catch (e: java.io.IOException) {
            emit(AiEvent.Failed(e.message ?: "网络错误"))
        } catch (e: Exception) {
            emit(AiEvent.Failed(e.message ?: "未知错误"))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("User-Agent", Constants.USER_AGENT)
                    .get().build()
                ok.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(200)}")
                    modelsAdapter.fromJson(text)?.data?.map { it.id } ?: emptyList()
                }
            }
        }

    private class MutableToolCall {
        var id: String = ""
        var name: String = ""
        var args: String = ""
    }
}
