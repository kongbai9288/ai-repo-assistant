package com.kongbai.airepo.data.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class PickedFile(
    val name: String,
    val size: Long,
    val mime: String?,
    val uri: Uri,
    val isBinary: Boolean
)

/** 常见的纯文本扩展名，这些直接按 UTF-8 文本交给 AI；其余一律 base64 */
private val TEXT_EXT = setOf(
    "md", "markdown", "txt", "text", "log", "kt", "kts", "java", "gradle", "json", "xml",
    "yml", "yaml", "toml", "properties", "pro", "sh", "bash", "bat", "ps1", "py", "js", "ts",
    "tsx", "jsx", "html", "htm", "css", "scss", "less", "c", "h", "cc", "cpp", "hpp",
    "go", "rs", "rb", "php", "swift", "sql", "ini", "cfg", "conf", "env", "gitignore",
    "gitattributes", "editorconfig", "csv", "tsv", "dockerfile", "mk", "make", "cmake", "r", "m", "mm"
)

private val COMPRESSED_EXT = setOf("zip", "apk", "aar", "jar", "7z", "gz", "tgz", "rar", "png", "jpg", "jpeg", "webp", "gif", "mp4", "mp3", "pdf")

fun isText(name: String, mime: String?): Boolean {
    val n = name.lowercase()
    if (n in setOf("dockerfile", "makefile", "license", "readme", "changelog")) return true
    val ext = n.substringAfterLast('.', "")
    if (ext in COMPRESSED_EXT) return false
    if (ext in TEXT_EXT) return true
    return mime?.startsWith("text/") == true
}

@Singleton
class UploadTools @Inject constructor(@ApplicationContext private val ctx: Context) {

    fun describe(uri: Uri): PickedFile {
        val name = queryName(uri)
        val size = querySize(uri)
        val mime = ctx.contentResolver.getType(uri)
        val isBinary = !isText(name, mime)
        return PickedFile(name, size, mime, uri, isBinary)
    }

    /** 文本直接读，二进制给 base64（供 gh_write_file 用 base64 模式提交） */
    suspend fun read(uri: Uri, maxChars: Int = 400_000): Pair<String, Boolean> =
        withContext(Dispatchers.IO) {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext "读取失败：无法打开文件" to false
            val f = describe(uri)
            if (!f.isBinary) {
                val text = String(bytes, Charsets.UTF_8)
                return@withContext if (text.length > maxChars)
                    text.take(maxChars) + "\n…(已截断到 ${maxChars} 字符)" to false
                else text to false
            }
            if (bytes.size > 10 * 1024 * 1024) {
                return@withContext "文件过大（${bytes.size / 1024 / 1024}MB base64 会超出模型上下文，建议压缩或分批上传）" to true
            }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return@withContext b64 to true
        }

    private fun queryName(uri: Uri): String {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun querySize(uri: Uri): Long {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) return c.getLong(idx)
        }
        return -1L
    }
}
