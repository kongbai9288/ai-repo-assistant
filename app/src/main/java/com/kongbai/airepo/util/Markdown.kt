package com.kongbai.airepo.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

sealed class MdBlock {
    data class Text(val content: AnnotatedString) : MdBlock()
    data class Code(val code: String, val lang: String) : MdBlock()
    data class Quote(val content: String) : MdBlock()
}

private val CODE_FENCE = Regex("```(\\w*)\\n?([\\s\\S]*?)```", RegexOption.MULTILINE)

fun parseMarkdown(src: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    var last = 0
    CODE_FENCE.findAll(src).forEach { m ->
        if (m.range.first > last) blocks += parseText(src.substring(last, m.range.first))
        blocks += MdBlock.Code(m.groupValues[2].trimEnd(), m.groupValues[1])
        last = m.range.last + 1
    }
    if (last < src.length) blocks += parseText(src.substring(last))
    return blocks.filter { it !is MdBlock.Text || it.content.isNotBlank() }
}

private fun parseText(raw: String): MdBlock {
    val lines = raw.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<MdBlock>()
    val buf = StringBuilder()
    fun flush() {
        if (buf.isNotBlank()) out += MdBlock.Text(inlineMarkdown(buf.toString().trim()))
        buf.clear()
    }
    for (line in lines) {
        when {
            line.trimStart().startsWith("> ") -> { flush(); out += MdBlock.Quote(line.trimStart().removePrefix("> ")) }
            else -> buf.append(line).append('\n')
        }
    }
    flush()
    return if (out.size == 1) out.first()
    else MdBlock.Text(buildAnnotatedString {
        out.forEach { b ->
            when (b) {
                is MdBlock.Text -> append(b.content)
                is MdBlock.Quote -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("“${b.content}”\n") }
                is MdBlock.Code -> append(b.code)
            }
        }
    })
}

/** 支持 **粗体** *斜体* `行内代码` [文字](链接) # 标题 - 列表 */
private fun inlineMarkdown(src: String): AnnotatedString = buildAnnotatedString {
    val patterns = listOf(
        Regex("(?s)\\*\\*(.+?)\\*\\*") to "bold",
        Regex("(?s)(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)") to "italic",
        Regex("`([^`]+)`") to "code",
        Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)") to "link"
    )
    data class Hit(val start: Int, val end: Int, val kind: String, val g: MatchResult)

    val hits = mutableListOf<Hit>()
    for ((re, kind) in patterns) {
        re.findAll(src).forEach { m ->
            if (hits.none { it.start <= m.range.first && m.range.last <= it.end }) {
                hits += Hit(m.range.first, m.range.last, kind, m)
            }
        }
    }
    hits.sortBy { it.start }
    var cursor = 0
    for (h in hits) {
        if (h.start < cursor) continue
        append(src.substring(cursor, h.start))
        when (h.kind) {
            "bold" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(h.g.groupValues[1]) }
            "italic" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(h.g.groupValues[1]) }
            "code" -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color(0x22000000))
            ) { append(h.g.groupValues[1]) }
            "link" -> {
                pushStringAnnotation("URL", h.g.groupValues[2])
                withStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF1F6FEB), textDecoration = TextDecoration.Underline)) {
                    append(h.g.groupValues[1])
                }
                pop()
            }
        }
        cursor = h.end + 1
    }
    if (cursor < src.length) append(src.substring(cursor))
}
