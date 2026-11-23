package com.abdulrahman.hardsubocr.core

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device diagnostic log: what a job did, every error with its stack trace, and a crash's last
 * moments — one text file the user can save or share.
 *
 * Never slows the work: callers only put a line in a queue; one background thread formats and
 * writes it (buffered, flushed when the queue is idle). A crash is written synchronously. API keys
 * are masked before anything reaches the disk. Two files of at most [MAX_FILE] bytes are kept.
 */
object DiagLog {
    private const val MAX_FILE = 1_500_000L
    private const val QUEUE = 4000

    private class Entry(val time: Long, val level: Char, val tag: String, val msg: String)

    @Volatile
    var enabled = true

    @Volatile
    private var dir: File? = null
    private val queue = LinkedBlockingQueue<Entry>(QUEUE)
    private val dropped = AtomicInteger(0)
    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var thread: Thread? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val current get() = dir?.let { File(it, "diag.log") }
    private val previous get() = dir?.let { File(it, "diag.1.log") }

    /** Starts the writer; [header] is the first block of this session (version, device …). */
    fun init(directory: File, header: String) {
        directory.mkdirs()
        dir = directory
        if (thread == null) {
            thread = Thread({ loop() }, "diag-writer").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
        section("بدء التطبيق")
        for (line in header.lines()) if (line.isNotBlank()) i("App", line)
    }

    fun i(tag: String, msg: String) = add('I', tag, msg)
    fun w(tag: String, msg: String) = add('W', tag, msg)

    fun e(tag: String, msg: String, t: Throwable? = null) =
        add('E', tag, if (t == null) msg else msg + "\n" + stackTraceOf(t))

    fun section(title: String) = add('-', "", "==================== $title ====================")

    private fun add(level: Char, tag: String, msg: String) {
        if (!enabled || dir == null) return
        if (!queue.offer(Entry(System.currentTimeMillis(), level, tag, msg))) dropped.incrementAndGet()
    }

    /** Writes everything still queued, then the crash, and syncs the file (the process dies next). */
    fun crash(threadName: String, t: Throwable) {
        if (dir == null) return
        synchronized(lock) {
            drainLocked()
            writeLocked(Entry(System.currentTimeMillis(), 'F', "Crash", "انهيار في الخيط $threadName\n" + stackTraceOf(t)))
            runCatching { writer?.flush() }
        }
    }

    /** Both files (older first) as one text, ready to save or share. */
    fun export(extraHeader: String): ByteArray {
        flush()
        val sb = StringBuilder()
        sb.append("HardSub OCR — ملف التشخيص\n").append(extraHeader.trim()).append("\n\n")
        for (f in listOfNotNull(previous, current)) if (f.exists()) sb.append(f.readText(Charsets.UTF_8))
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun sizeBytes(): Long = listOfNotNull(previous, current).sumOf { if (it.exists()) it.length() else 0L }

    fun clear() {
        synchronized(lock) {
            runCatching { writer?.close() }
            writer = null
            previous?.delete()
            current?.delete()
        }
        section("تم مسح السجل")
    }

    fun flush() {
        synchronized(lock) {
            drainLocked()
            runCatching { writer?.flush() }
        }
    }

    // ------------------------------------------------------------------ writer thread

    private fun loop() {
        while (true) {
            val first = try {
                queue.poll(700, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return
            }
            synchronized(lock) {
                if (first != null) writeLocked(first)
                drainLocked()
                // flushed whenever the queue is idle: at most ~0.7 s of lines in memory
                if (queue.isEmpty()) runCatching { writer?.flush() }
            }
        }
    }

    private fun drainLocked() {
        while (true) writeLocked(queue.poll() ?: break)
        val d = dropped.getAndSet(0)
        if (d > 0) writeLocked(Entry(System.currentTimeMillis(), 'W', "Diag", "حُذف $d سطر من السجل (ضغط كبير)"))
    }

    private fun writeLocked(e: Entry) {
        val w = writerLocked() ?: return
        try {
            val head = if (e.level == '-') "" else fmt.format(Date(e.time)) + " " + e.level + "/" + e.tag + ": "
            w.write(head)
            w.write(redact(e.msg))
            w.newLine()
        } catch (x: Exception) {
            runCatching { w.close() }
            writer = null
        }
    }

    private fun writerLocked(): BufferedWriter? {
        val cur = current ?: return null
        writer?.let { w ->
            if (cur.length() < MAX_FILE) return w
            runCatching { w.close() }
            writer = null
            previous?.let { p -> p.delete(); cur.renameTo(p) }
        }
        return try {
            BufferedWriter(OutputStreamWriter(FileOutputStream(cur, true), Charsets.UTF_8), 16 * 1024).also { writer = it }
        } catch (e: Exception) {
            null
        }
    }

    private val keyPatterns = listOf(
        Regex("AIza[0-9A-Za-z_\\-]{20,}"),
        Regex("sk-or-[0-9A-Za-z_\\-]{8,}"),
        Regex("(?i)(x-goog-api-key|authorization|key)([\"'=: ]+)[0-9A-Za-z_\\-.]{16,}")
    )

    /** API keys never reach the file. */
    fun redact(s: String): String {
        if (!s.contains("AIza") && !s.contains("sk-or-") && !s.contains("key", ignoreCase = true) &&
            !s.contains("authorization", ignoreCase = true)
        ) return s
        var r = s
        r = keyPatterns[0].replace(r) { m -> m.value.take(6) + "…(مخفي)" }
        r = keyPatterns[1].replace(r) { m -> m.value.take(8) + "…(مخفي)" }
        r = keyPatterns[2].replace(r) { m -> m.groupValues[1] + m.groupValues[2] + "…(مخفي)" }
        return r
    }
}
