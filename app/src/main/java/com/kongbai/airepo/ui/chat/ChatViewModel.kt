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

    /** 开=跑满步数上限后自动接着跑，适合跨仓库搬代码这类长任务 */
    private val _autoContinue = MutableStateFlow(false)
    val autoContinue: StateFlow<Boolean> = _autoContinue

    fun setAutoContinue(v: Boolean) { _autoContinue.value = v }

    private val _attachments = MutableStateFlow<List<PickedFile>>(emptyList())
    val attachments: StateFlow<List<PickedFile>> = _attachments

    private var conversationId: String = UUID.randomUUID().toString()
    private var job: Job? = null
    private var messagesJob: Job? = null
    private var pendingAnswer: CompletableDeferred<Boolean>? = null
    private var defaultRepo: String? = null

    fun setDefaultRepo(fullName: String?) { defaultRepo = fullName }
    fun setNetMode(m: NetMode) { _netMode.value = m }

    /** 任意格式都能附加。选中即复制到 App 私有目录并全程异步，任何失败都只提示不崩溃 */
    fun attach(uri: Uri) {
        viewModelScope.launch {
            _toast.value = "正在导入文件…"
            upload.import(uri)
                .onSuccess { f ->
                    _attachments.value = _attachments.value + f
                    val size = if (f.size > 0) "${f.size / 1024}KB" else "?"
                    _toast.value = "已附加 ${f.name} · $size · ${if (f.isBinary) "二进制(base64)" else "文本"}"
                }
                .onFailure { e ->
                    _toast.value = "导入失败：${e.message ?: "无法读取该文件"}"
                }
        }
    }

    fun removeAttachment(f: PickedFile) {
        _attachments.value = _attachments.value.filterNot { it.id == f.id }
        upload.delete(f)
    }

    fun newConversation() {
        job?.cancel()
        conversationId = UUID.randomUUID().toString()
        _messages.value = emptyList()
        _attachments.value = emptyList()
        _streaming.value = false
        upload.clearAll()
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
                    val (content, isB64) = upload.read(f)
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
                    append("\n\n当前上下文仓库是 $it（owner=\"${parts[0]}\"，repo=\"${parts[1]}\"）。")
                    append("\n用户没明确说别的仓库时，默认操作它。")
                } else append("\n当前上下文仓库：$it")
            }
            append("\n\n【跨仓库能力 —— 重要】")
            append("\n你可以读写当前账号有权限的【任何】仓库，不限于上面的上下文仓库。")
            append("\n需要别的仓库的资源时，按这个顺序做：")
            append("\n1) 先定位仓库：gh_list_repos 看自己/参与的全部仓库；")
            append("\n   或 gh_search_repos 搜公共仓库；或 gh_search_code 按代码内容反查仓库。")
            append("\n2) 拿到 owner 和 repo 后，就能用 gh_read_file / gh_get_tree / gh_list_dir 读它的内容。")
            append("\n3) 要搬运时：读源仓库 → 按目标仓库的目录结构与代码风格适配 → gh_write_file 写到目标仓库。")
            append("\n4) 别凭印象猜别的仓库的路径，先用 gh_get_tree 看清结构再动手。")
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

        agent.run(history, mode, _autoContinue.value) { req ->
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
