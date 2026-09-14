package com.kongbai.airepo.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kongbai.airepo.data.local.ChatDao
import com.kongbai.airepo.data.local.ConversationEntity
import com.kongbai.airepo.data.local.MessageEntity
import com.kongbai.airepo.data.prefs.NetMode
import com.kongbai.airepo.data.prefs.SettingsRepository
import com.kongbai.airepo.data.remote.ai.AiMessage
import com.kongbai.airepo.data.repo.AgentEvent
import com.kongbai.airepo.data.repo.AgentRepository
import com.kongbai.airepo.data.tools.PickedFile
import com.kongbai.airepo.data.tools.ToolRequest
import com.kongbai.airepo.data.tools.UploadTools
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
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
    private val settings: SettingsRepository,
    private val upload: UploadTools
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

    private val _netMode = MutableStateFlow(NetMode.AUTO)
    val netMode: StateFlow<NetMode> = _netMode

    private val _attachments = MutableStateFlow<List<PickedFile>>(emptyList())
    val attachments: StateFlow<List<PickedFile>> = _attachments

    private var conversationId: String = UUID.randomUUID().toString()
    private var job: Job? = null
    private var messagesJob: Job? = null
    private var pendingAnswer: CompletableDeferred<Boolean>? = null
    private var defaultRepo: String? = null

    fun setDefaultRepo(fullName: String?) { defaultRepo = fullName }
    fun setNetMode(m: NetMode) { _netMode.value = m }

    /** 任意格式都能附加：文本直接读内容，其他格式给 base64 供 gh_write_file 提交 */
    fun attach(uri: Uri) {
        val f = upload.describe(uri)
        _attachments.value = _attachments.value + f
        _toast.value = "已附加 ${f.name}${if (f.isBinary) "（二进制，将按 base64 处理）" else ""}"
    }

    fun removeAttachment(f: PickedFile) {
        _attachments.value = _attachments.value.filterNot { it === f }
    }

    fun newConversation() {
        job?.cancel()
        conversationId = UUID.randomUUID().toString()
        _messages.value = emptyList()
        _attachments.value = emptyList()
        _streaming.value = false
        observe()
    }

    fun selectConversation(id: String) {
        if (id == conversationId) return
        job?.cancel()
        conversationId = id
        _streaming.value = false
        _attachments.value = emptyList()
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
        val picked = _attachments.value.toList()
        _attachments.value = emptyList()
        viewModelScope.launch {
            dao.upsertConversation(
                ConversationEntity(id = conversationId, title = input.take(30), updatedAt = System.currentTimeMillis())
            )

            // 先把附件内容落库，AI 才能读到
            val attachText = buildString {
                picked.forEach { f ->
                    val (content, isB64) = upload.read(f.uri)
                    append("\n\n<附件 file=\"${f.name}\" mime=\"${f.mime ?: "unknown"}\" size=${f.size} ${if (isB64) "encoding=base64" else "encoding=utf-8"}>\n")
                    append(content)
                    append("\n</附件>")
                    if (isB64) append("\n（这是二进制文件的 base64，若要写入仓库请调用 gh_write_file 并把 content_base64 设为 true，content 原样填这段 base64）")
                }
            }

            val userMsg = MessageEntity(
                id = UUID.randomUUID().toString(), conversationId = conversationId,
                role = "user", content = input + attachText
            )
            dao.insertMessage(userMsg)
            _messages.value = _messages.value + userMsg

            val assistantMsg = MessageEntity(
                id = UUID.randomUUID().toString(), conversationId = conversationId,
                role = "assistant", content = "", status = "running"
            )
            _messages.value = _messages.value + assistantMsg

            _streaming.value = true
            job = launch { runAgent(assistantMsg, _netMode.value) }
        }
    }

    private suspend fun runAgent(assistantMsg: MessageEntity, mode: NetMode) {
        val s = settings.current()
        val system = buildString {
            append(s.systemPrompt)
            defaultRepo?.let {
                val parts = it.split("/")
                if (parts.size >= 2) {
                    append("\n\n当前默认仓库 fullName=$it")
                    append("\n调用 gh_* 时：owner=\"${parts[0]}\"，repo=\"${parts[1]}\"（务必分开传）")
                } else append("\n当前默认仓库：$it")
            }
            if (mode == NetMode.ON) append("\n\n本条消息用户已开启联网：涉及版本、文档、报错、最新实践时必须先 web_search 再回答/动手。")
            if (mode == NetMode.OFF) append("\n\n本条消息用户要求离线：不要调用 web_search / web_fetch，只用仓库工具。")
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

        agent.run(history, mode) { req ->
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
