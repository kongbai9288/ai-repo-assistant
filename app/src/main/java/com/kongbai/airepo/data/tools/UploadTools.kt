package com.kongbai.airepo.data.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 选中即复制到 App 私有目录 —— 关键修复。
 *
 * 之前只在 PickedFile 里存 content:// Uri，真正读取放到发送时做。
 * 这期间 Activity 一旦重建/被回收，系统临时授予的读权限就失效，
 * openInputStream 会抛 SecurityException 直接崩应用。
 * 现在导入时就落成本地副本，后续只读自己的文件，不再依赖 Uri 权限。
 */
data class PickedFile(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String?,
    val localPath: String,
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

private val COMPRESSED_EXT = setOf(
    "zip", "apk", "aar", "jar", "7z", "gz", "tgz", "rar",
    "png", "jpg", "jpeg", "webp", "gif", "mp4", "mp3", "pdf"
)

fun isText(name: String, mime: String?): Boolean {
    val n = name.lowercase()
    if (n in setOf("dockerfile", "makefile", "license", "readme", "changelog")) return true
    val ext = n.substringAfterLast('.', "")
    if (ext in COMPRESSED_EXT) return false
    if (ext in TEXT_EXT) return true
    return mime?.startsWith("text/") == true
}

object UploadLimits {
    /** 能落到本地的最大体积，超过就不导入，避免 OOM */
    const val MAX_IMPORT_BYTES = 20L * 1024 * 1024
    /** 文本交给模型的最大字符数 */
    const val MAX_TEXT_CHARS = 200_000
    /** 二进制转 base64 前的最大体积（base64 后约 ×1.37） */
    const val MAX_BINARY_BYTES = 1024 * 1024
}

@Singleton
class UploadTools @Inject constructor(@ApplicationContext private val ctx: Context) {

    private fun dir(): File = File(ctx.filesDir, "uploads").apply { mkdirs() }

    /**
     * 把 Uri 指向的内容复制进 App 私有目录。全程 IO 线程，失败返回 Result.failure 而不是抛异常。
     * 边复制边计数，超过上限立即中止 —— 不能先 readBytes 再判断，那样大文件会先 OOM。
     */
    suspend fun import(uri: Uri): Result<PickedFile> = withContext(Dispatchers.IO) {
        runCatching {
            val name = queryName(uri)
            val declared = querySize(uri)
            val mime = ctx.contentResolver.getType(uri)

            val id = java.util.UUID.randomUUID().toString()
            val ext = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
            val dst = File(dir(), id + ext)

            var copied = 0L
            val input = ctx.contentResolver.openInputStream(uri)
                ?: error("打不开这个文件，可能已被删除或没有读取权限")
            input.use { ins ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        copied += n
                        if (copied > UploadLimits.MAX_IMPORT_BYTES) {
                            out.close()
                            dst.delete()
                            error("文件超过 ${UploadLimits.MAX_IMPORT_BYTES / 1024 / 1024}MB 上限，已取消导入")
                        }
                        out.write(buf, 0, n)
                    }
                }
            }

            PickedFile(
                id = id,
                name = name,
                size = if (declared > 0) declared else copied,
                mime = mime,
                localPath = dst.absolutePath,
                isBinary = !isText(name, mime)
            )
        }.onFailure { }
    }

    /** 从本地副本读取：文本按 UTF-8，二进制给 base64 */
    suspend fun read(f: PickedFile): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(f.localPath)
            if (!file.exists()) return@withContext "附件已失效（本地副本丢失），请重新选择文件" to false
            val bytes = file.readBytes()
            if (!f.isBinary) {
                val text = String(bytes, Charsets.UTF_8)
                if (text.length > UploadLimits.MAX_TEXT_CHARS) {
                    text.take(UploadLimits.MAX_TEXT_CHARS) +
                        "\n…(已截断到 ${UploadLimits.MAX_TEXT_CHARS} 字符，共 ${text.length} 字符)" to false
                } else text to false
            } else {
                if (bytes.size > UploadLimits.MAX_BINARY_BYTES) {
                    val mb = bytes.size / 1024 / 1024
                    "文件是二进制且体积 ${mb}MB，转成 base64 会超出模型上下文。\n" +
                        "建议：压缩后再传，或直接告诉我文件该放到仓库哪个路径，我用链接/分片方式处理。" to true
                } else {
                    Base64.encodeToString(bytes, Base64.NO_WRAP) to true
                }
            }
        }.getOrElse { "读取附件失败：${it.message ?: "未知错误"}" to false }
    }

    fun delete(f: PickedFile) {
        runCatching { File(f.localPath).delete() }
    }

    fun clearAll() {
        runCatching { dir().deleteRecursively() }
    }

    private fun queryName(uri: Uri): String {
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun querySize(uri: Uri): Long {
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) return c.getLong(idx)
            }
        }
        return -1L
    }
}
