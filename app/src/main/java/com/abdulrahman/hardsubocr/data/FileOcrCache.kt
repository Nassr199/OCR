package com.abdulrahman.hardsubocr.data

import android.content.Context
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
 * Append-only resume cache: every finished image is written immediately, so a stopped,
 * killed or crashed job continues where it left off instead of paying for the same OCR again.
 */
class FileOcrCache private constructor(file: File) : OcrCache, Closeable {
    private val map = ConcurrentHashMap<String, String>()
    private val writer: BufferedWriter

    init {
        if (file.exists()) {
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
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
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
    }

    val size: Int get() = map.size

    override fun get(key: String): String? = map[key]

    @Synchronized
    override fun put(key: String, text: String) {
        map[key] = text
        try {
            writer.write(buildJsonObject { put("k", key); put("t", text) }.toString())
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            // cache is an optimisation only
        }
    }

    @Synchronized
    override fun close() {
        runCatching { writer.close() }
    }

    companion object {
        private fun dir(context: Context) = File(context.filesDir, "ocr-cache").apply { mkdirs() }

        fun open(context: Context, sourceId: String): FileOcrCache {
            val md = MessageDigest.getInstance("SHA-1").digest(sourceId.toByteArray(Charsets.UTF_8))
            val name = md.joinToString("") { b -> Integer.toHexString((b.toInt() and 0xFF) or 0x100).substring(1) } + ".jsonl"
            return FileOcrCache(File(dir(context), name))
        }

        fun clearAll(context: Context): Int {
            val files = dir(context).listFiles() ?: return 0
            var n = 0
            for (f in files) if (f.delete()) n++
            return n
        }

        fun sizeBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L
    }
}
