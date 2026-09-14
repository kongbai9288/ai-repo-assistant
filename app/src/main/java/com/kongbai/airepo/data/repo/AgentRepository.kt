package com.kongbai.airepo.data.repo

import com.kongbai.airepo.core.Constants
import com.kongbai.airepo.data.prefs.SettingsRepository
import com.kongbai.airepo.data.remote.ai.AiEvent
import com.kongbai.airepo.data.remote.ai.AiMessage
import com.kongbai.airepo.data.remote.ai.AiRest
import com.kongbai.airepo.data.remote.ai.AiToolCall
import com.kongbai.airepo.data.tools.ToolExecutor
import com.kongbai.airepo.data.tools.ToolRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AgentEvent {
    data class Status(val text: String) : AgentEvent
    data class ToolStart(val callId: String, val name: String, val summary: String) : AgentEvent
    data class ToolDone(val callId: String, val name: String, val result: String) : AgentEvent
    data class ToolDenied(val callId: String, val name: String) : AgentEvent
    data class Text(val text: String) : AgentEvent
    data class Error(val message: String) : AgentEvent
    data object Done : AgentEvent
}

@Singleton
class AgentRepository @Inject constructor(
    private val ai: AiRest,
    private val tools: ToolExecutor,
    private val settings: SettingsRepository
) {

    /**
     * 一个最小的 ReAct 循环：模型输出 -> 工具调用 -> 结果回喂 -> 再输出，直到不再需要工具。
     * @param confirm 高危工具的二次确认，返回 false 表示拒绝执行
     */
    fun run(messages: List<AiMessage>, confirm: suspend (ToolRequest) -> Boolean): Flow<AgentEvent> = flow {
        val s = settings.current()
        if (s.apiKey.isBlank() && !s.baseUrl.contains("localhost") && !s.baseUrl.contains("127.0.0.1")) {
            emit(AgentEvent.Error("还没配置 AI 接口的 Key，去「设置 → AI 接口」填一下（本地 Ollama / vLLM 可留空）"))
            emit(AgentEvent.Done)
            return@flow
        }
        val defs = tools.definitions(includeWeb = s.webSearchEnabled)
        val working = messages.toMutableList()
        var step = 0

        while (step < Constants.MAX_AGENT_STEPS) {
            step++
            val collected = StringBuilder()
            val calls = mutableListOf<AiToolCall>()
            var failed: String? = null
            var finished: String? = null

            ai.chatStream(s.baseUrl, s.apiKey, s.model, working, defs, s.temperature)
                .onCompletion { }
                .collect { ev ->
                    when (ev) {
                        is AiEvent.Text -> {
                            collected.append(ev.text)
                            emit(AgentEvent.Text(ev.text))
                        }
                        is AiEvent.ToolCalls -> calls.addAll(ev.calls)
                        is AiEvent.Finished -> finished = ev.reason
                        is AiEvent.Failed -> failed = ev.message
                    }
                }

            if (failed != null && step == 1) {
                // 某些兼容接口不支持 SSE，退化成一次性请求
                val once = ai.chatOnce(s.baseUrl, s.apiKey, s.model, working, defs, s.temperature)
                once.onSuccess { m ->
                    m.content?.let { emit(AgentEvent.Text(it)); collected.append(it) }
                    calls.addAll(m.toolCalls.orEmpty())
                    failed = null
                }.onFailure { e ->
                    emit(AgentEvent.Error(e.message ?: "请求失败")); emit(AgentEvent.Done); return@flow
                }
            } else if (failed != null) {
                emit(AgentEvent.Error(failed!!)); emit(AgentEvent.Done); return@flow
            }

            if (calls.isEmpty()) {
                emit(AgentEvent.Done)
                return@flow
            }

            working.add(
                AiMessage(
                    role = "assistant",
                    content = collected.toString().ifBlank { null },
                    toolCalls = calls.toList()
                )
            )

            for (c in calls) {
                val name = c.function.name ?: continue
                val callId = c.id ?: "call_$name"
                val args = tools.parseArgs(c.function.arguments)
                val req = ToolRequest(name, args)
                val needsConfirm = name in Constants.DANGEROUS_TOOLS
                if (needsConfirm) {
                    emit(AgentEvent.Status("等待确认：$name"))
                    if (!confirm(req)) {
                        emit(AgentEvent.ToolDenied(callId, name))
                        working.add(
                            AiMessage(role = "tool", toolCallId = callId, content = "用户拒绝了该操作，请改用它法或向用户确认。")
                        )
                        continue
                    }
                }
                emit(AgentEvent.ToolStart(callId, name, tools.summarize(req)))
                val result = tools.execute(req)
                emit(AgentEvent.ToolDone(callId, name, result))
                working.add(AiMessage(role = "tool", toolCallId = callId, content = result))
            }
            if (finished == "stop" && calls.isNotEmpty()) {
                // 少数实现会在同一轮返回 stop + tool_calls，这里继续下一轮
            }
        }
        emit(AgentEvent.Status("已达到最大步数 ${Constants.MAX_AGENT_STEPS}，先停下来"))
        emit(AgentEvent.Done)
    }

    fun newId(): String = UUID.randomUUID().toString()
}
