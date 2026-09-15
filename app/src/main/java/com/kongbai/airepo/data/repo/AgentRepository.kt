package com.kongbai.airepo.data.repo

import com.kongbai.airepo.core.Constants
import com.kongbai.airepo.data.prefs.NetMode
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
    fun run(
        messages: List<AiMessage>,
        netMode: NetMode = NetMode.AUTO,
        autoContinue: Boolean = false,
        confirm: suspend (ToolRequest) -> Boolean = { true }
    ): Flow<AgentEvent> = flow {
        val s = settings.current()
        if (s.apiKey.isBlank() && !s.baseUrl.contains("localhost") && !s.baseUrl.contains("127.0.0.1")) {
            emit(AgentEvent.Error("还没配置 AI 接口的 Key，去「设置 → AI 接口」填一下（本地 Ollama / vLLM 可留空）"))
            emit(AgentEvent.Done)
            return@flow
        }
        // 离线模式彻底不给搜索工具；联网模式强制给；自动模式跟随全局开关
        val useWeb = when (netMode) {
            NetMode.ON -> true
            NetMode.OFF -> false
            NetMode.AUTO -> s.webSearchEnabled
        }
        val defs = tools.definitions(includeWeb = useWeb)
        if (netMode == NetMode.ON) {
            emit(AgentEvent.Status("已启用联网：优先用 web_search 查最新资料，再动手改仓库"))
        }
        val working = messages.toMutableList()
        var round = 0

        // 外层 = 自动续跑轮次；内层 = 单轮内的工具调用步数
        while (round <= Constants.MAX_AUTO_CONTINUE) {
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
                var result = tools.execute(req)
                if (result.startsWith("工具执行异常") || result.startsWith("404") ||
                    result.startsWith("HTTP ") || result.startsWith("抓取失败") ||
                    result.startsWith("搜索失败")
                ) {
                    result += "\n（这次调用失败了：修正参数换一种方式重试；" +
                        "若是信息不足或不确定正确做法，先 web_search 查清楚再继续，不要原地打转。）"
                }
                emit(AgentEvent.ToolDone(callId, name, result))
                working.add(AiMessage(role = "tool", toolCallId = callId, content = result))
            }
            if (finished == "stop" && calls.isNotEmpty()) {
                // 少数实现会在同一轮返回 stop + tool_calls，这里继续下一轮
            }
        }
        // 跑满一轮：要么收尾，要么自动续跑
        if (autoContinue && round < Constants.MAX_AUTO_CONTINUE) {
            round++
            emit(AgentEvent.Status("第 $round 轮跑满 ${Constants.MAX_AGENT_STEPS} 步，自动继续…"))
            working.add(
                AiMessage(
                    role = "user",
                    content = "你已用完本轮步数但任务还没完成。请直接接着上一步继续做，" +
                        "不要重新开始、不要复述已完成的部分；如果已经全部完成，就简短说明结果即可。"
                )
            )
        } else {
            emit(AgentEvent.Status("已跑满 ${Constants.MAX_AGENT_STEPS} 步上限，先停下来（可在输入框上方开「自动继续」）"))
            emit(AgentEvent.Done)
            return@flow
        }
        }
        emit(AgentEvent.Done)
    }

    fun newId(): String = UUID.randomUUID().toString()
}
