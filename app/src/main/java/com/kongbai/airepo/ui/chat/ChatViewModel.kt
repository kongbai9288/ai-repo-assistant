package com.kongbai.airepo.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kongbai.airepo.data.local.ChatDao
import com.kongbai.airepo.data.local.ConversationEntity
import com.kongbai.airepo.data.local.MessageEntity
import com.kongbai.airepo.data.prefs.SettingsRepository
import com.kongbai.airepo.data.remote.ai.AiMessage
import com.kongbai.airepo.data.repo.AgentEvent
import com.kongbai.airepo.data.repo.AgentRepository
import com.kongbai.airepo.data.tools.ToolRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val dao: ChatDao,
    private val agent: AgentRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    val conversations: StateFlow<List<ConversationEntity>> =
        dao.conversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming

    private val _confirmRequest = MutableStateFlow<ToolRequest?>(null)
    val confirmRequest: StateFlow<ToolRequest?> = _confirmRequest

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private var conversationId: String = UUID.randomUUID().toString()
    private var job: Job? = null
    private var pendingAnswer: CompletableDeferred<Boolean>? = null
    private var messagesJob: Job? = null
    private var defaultRepo: String? = null

    fun setDefaultRepo(fullName: String?) { defaultRepo = fullName }

    fun newConversation() {
        job?.cancel()
        conversationId = UUID.randomUUID().toString()
        _messages.value = emptyList()
        _streaming.value = false
    }

    fun selectConversation(id: String) {
        if (id == conversationId) return
        job?.cancel()
        conversationId = id
        _streaming.value = false
        observe()
    }

    private fun observe() {
        messagesJob?.cancel()
        val id = conversationId
        messagesJob = viewModelScope.launch {
            dao.messages(id).collect { _messages.value = it }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            dao.deleteConversation(id)
            dao.clearMessages(id)
            if (id == conversationId) newConversation()
        }
    }

    fun answerConfirm(allow: Boolean) {
        pendingAnswer?.complete(allow)
        pendingAnswer = null
    }

    fun stop() {
        job?.cancel()
        _streaming.value = false
        persistAll()
        observe()
    }

    fun send(text: String) {
        val input = text.trim()
        if (input.isBlank() || _streaming.value) return
        messagesJob?.cancel()
        viewModelScope.launch {
            val conv = ConversationEntity(
                id = conversationId,
                title = input.take(30),
                updatedAt = System.currentTimeMillis()
            )
            dao.upsertConversation(conv)

            val userMsg = MessageEntity(
                id = UUID.randomUUID().toString(), conversationId = conversationId,
                role = "user", content = input
            )
            dao.insertMessage(userMsg)
            _messages.value = _messages.value + userMsg

            val assistantId = UUID.randomUUID().toString()
            val assistantMsg = MessageEntity(
                id = assistantId, conversationId = conversationId,
                role = "assistant", content = "", status = "running"
            )
            _messages.value = _messages.value + assistantMsg

            _streaming.value = true
            job = launch { runAgent(assistantMsg) }
        }
    }

    private suspend fun runAgent(assistantMsg: MessageEntity) {
        val s = settings.current()
        val system = buildString {
            append(s.systemPrompt)
            defaultRepo?.let { append("\n当前默认仓库：$it（用户没指定仓库时用它）") }
        }
        val history = mutableListOf<AiMessage>()
        if (system.isNotBlank()) history += AiMessage(role = "system", content = system)
        _messages.value.dropLast(1).forEach { m ->
            when (m.role) {
                "user" -> history += AiMessage(role = "user", content = m.content)
                "assistant" -> if (m.content.isNotBlank()) history += AiMessage(role = "assistant", content = m.content)
                "tool" -> history += AiMessage(
                    role = "user",
                    content = "工具 ${m.toolName} 的返回：\n${m.toolResult ?: m.content}"
                )
            }
        }

        var assistant = assistantMsg
        fun update(msg: MessageEntity) {
            assistant = msg
            _messages.value = _messages.value.map { if (it.id == msg.id) msg else it }
        }

        agent.run(history) { req ->
            _confirmRequest.value = req
            val d = CompletableDeferred<Boolean>()
            pendingAnswer = d
            val ok = d.await()
            _confirmRequest.value = null
            ok
        }.collect { ev ->
            when (ev) {
                is AgentEvent.Text -> update(assistant.copy(content = assistant.content + ev.text, status = "running"))
                is AgentEvent.ToolStart -> {
                    update(assistant.copy(status = "done"))
                    _messages.value = _messages.value + MessageEntity(
                        id = ev.callId, conversationId = conversationId, role = "tool",
                        toolName = ev.name, content = ev.summary, status = "running"
                    )
                }
                is AgentEvent.ToolDone -> {
                    _messages.value = _messages.value.map {
                        if (it.id == ev.callId) it.copy(status = "done", toolResult = ev.result) else it
                    }
                }
                is AgentEvent.ToolDenied -> {
                    _messages.value = _messages.value.map {
                        if (it.id == ev.callId) it.copy(status = "denied", toolResult = "用户拒绝了这一操作") else it
                    }
                }
                is AgentEvent.Status -> _toast.value = ev.text
                is AgentEvent.Error -> update(assistant.copy(content = assistant.content + "\n\n⚠️ ${ev.message}", status = "error"))
                AgentEvent.Done -> update(assistant.copy(status = "done"))
            }
        }
        _streaming.value = false
        persistAll()
    }

    private fun persistAll() {
        messagesJob?.cancel()
        val list = _messages.value
        viewModelScope.launch {
            list.forEach { dao.insertMessage(it) }
            val first = list.firstOrNull { it.role == "user" }?.content ?: "新对话"
            dao.upsertConversation(
                ConversationEntity(
                    id = conversationId, title = first.take(30),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun consumeToast() { _toast.value = null }
}
