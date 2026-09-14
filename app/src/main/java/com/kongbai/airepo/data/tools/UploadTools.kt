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

private val TEXT_EXT = setOf(
    "md", "txt", "kt", "java", "kts", "gradle", "json", "xml", "yml", "yaml", "toml",
    "properties", "pro", "sh", "py", "js", "ts", "html", "css", "scss", "c", "h", "cpp",
    "go", "rs", "rb", "php", "swift", "sql", "ini", "cfg", "conf", "gitignore", "csv"
)

@Singleton
class UploadTools @Inject constructor(@ApplicationContext private val ctx: Context) {

    fun describe(uri: Uri): PickedFile {
        val name = queryName(uri)
        val size = querySize(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = ctx.contentResolver.getType(uri)
        val isBinary = ext !in TEXT_EXT && !(mime?.startsWith("text/") == true)
        return PickedFile(name, size, mime, uri, isBinary)
    }

    /** 文本直接读，二进制给 base64（供 gh_write_file 用 base64 模式提交） */
    suspend fun read(uri: Uri, maxChars: Int = 100_000): Pair<String, Boolean> =
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
