package com.abdulrahman.hardsubocr.data

import android.content.Context
import com.abdulrahman.hardsubocr.core.DiagLog
import com.abdulrahman.hardsubocr.core.OcrCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Resume cache: every finished image is written immediately, so a stopped, killed or crashed job
 * continues where it left off instead of paying for the same OCR again.
 *
 * - One append-only file per source (folder / video + settings), read once into memory.
 * - A file full of replaced entries is compacted on open (written to a temporary file, then renamed:
 *   a crash in the middle never loses the cache).
 * - Housekeeping on open: caches unused for [MAX_AGE_DAYS] days are removed, and the oldest ones go
 *   first if all of them pass [MAX_TOTAL_BYTES].
 */
class FileOcrCache private constructor(private val file: File) : OcrCache, Closeable {
    private val map = ConcurrentHashMap<String, String>()
    private val stream: FileOutputStream
    private val writer: BufferedWriter

    init {
        var lines = 0
        if (file.exists()) {
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                lines++
                try {
                    val o = Json.parseToJsonElement(line).jsonObject
                    val k = o["k"]?.jsonPrimitive?.content
                    val t = o["t"]?.jsonPrimitive?.content
                    if (k != null && t != null) map[k] = t
                } catch (e: Exception) {
                    // a half-written last line after a crash is simply ignored
                }
            }
        }
        if (lines > 200 && lines > map.size * 2) compact()
        file.setLastModified(System.currentTimeMillis())
        stream = FileOutputStream(file, true)
        writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
        DiagLog.i("Cache", "ذاكرة الاستئناف: ${map.size} صورة محفوظة (${file.length() / 1024} KB)")
    }

    private fun line(k: String, t: String) = buildJsonObject { put("k", k); put("t", t) }.toString()

    private fun compact() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for ((k, t) in map) {
                    w.write(line(k, t)); w.newLine()
                }
            }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    val size: Int get() = map.size

    override fun get(key: String): String? = map[key]

    @Synchronized
    override fun put(key: String, text: String) {
        if (map.put(key, text) == text) return
        try {
            writer.write(line(key, text))
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            // cache is an optimisation only
        }
    }

    @Synchronized
    override fun close() {
        runCatching { writer.flush() }
        runCatching { stream.fd.sync() }
        runCatching { writer.close() }
    }

    companion object {
        private const val MAX_AGE_DAYS = 30
        private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

        private fun dir(context: Context) = File(context.filesDir, "ocr-cache").apply { mkdirs() }

        fun open(context: Context, sourceId: String): FileOcrCache {
            housekeeping(context)
            val md = MessageDigest.getInstance("SHA-1").digest(sourceId.toByteArray(Charsets.UTF_8))
            val name = md.joinToString("") { b -> Integer.toHexString((b.toInt() and 0xFF) or 0x100).substring(1) } + ".jsonl"
            return FileOcrCache(File(dir(context), name))
        }

        /** Old and oversized caches go; the most recently used are kept. */
        fun housekeeping(context: Context) {
            val files = dir(context).listFiles()?.filter { it.isFile } ?: return
            val now = System.currentTimeMillis()
            var removed = 0
            val alive = ArrayList<File>()
            for (f in files) {
                if (f.name.endsWith(".tmp") || now - f.lastModified() > MAX_AGE_DAYS * 86_400_000L) {
                    if (f.delete()) removed++
                } else alive.add(f)
            }
            var total = alive.sumOf { it.length() }
            if (total > MAX_TOTAL_BYTES) {
                for (f in alive.sortedBy { it.lastModified() }) {
                    if (total <= MAX_TOTAL_BYTES) break
                    val len = f.length()
                    if (f.delete()) {
                        total -= len; removed++
                    }
                }
            }
            if (removed > 0) DiagLog.i("Cache", "تنظيف تلقائي: حُذف $removed ملف قديم من ذاكرة الاستئناف")
        }

        fun clearAll(context: Context): Int {
            val files = dir(context).listFiles() ?: return 0
            var n = 0
            for (f in files) if (f.delete()) n++
            return n
        }

        fun sizeBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

        fun fileCount(context: Context): Int = dir(context).listFiles()?.count { it.name.endsWith(".jsonl") } ?: 0

        /** Temporary video frames left by a job that was killed (normally deleted when a job ends). */
        fun cleanTemp(context: Context) {
            val v = File(context.cacheDir, "video-frames")
            val n = v.listFiles()?.count { it.deleteRecursively() } ?: 0
            File(context.cacheDir, "export").listFiles()?.forEach { it.delete() }
            if (n > 0) DiagLog.i("Cache", "حُذفت $n مجلدات إطارات مؤقتة من مهمة سابقة لم تكتمل")
        }
    }
}
