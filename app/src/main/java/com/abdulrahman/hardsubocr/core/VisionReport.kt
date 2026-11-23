package com.abdulrahman.hardsubocr.core

/**
 * The Vision report written next to the SRT: one entry per image Vision read in this run, with
 * Vision's own text exactly as Google returned it, the text after ordering, the confidence, and
 * the focused second look when there was one. It shows that every image was read, and whether a
 * wrong line comes from Vision itself or from the app's processing.
 */
object VisionReport {

    fun build(title: String, document: Boolean, language: String, traces: List<VisionEngine.Trace>, fromMemory: Int): String {
        val sb = StringBuilder()
        sb.append("تقرير Google Vision — ").append(title).append('\n')
        sb.append("نوع القراءة: ").append(if (document) "مستند (DOCUMENT_TEXT_DETECTION)" else "نص (TEXT_DETECTION)")
            .append(" • لغة القراءة: ").append(language).append('\n')
        sb.append("صور قرأها Vision في هذه المرة: ").append(traces.size)
        if (fromMemory > 0) sb.append(" • من ذاكرة الاستئناف (لم تُرسل): ").append(fromMemory)
        val second = traces.count { it.second != null }
        if (second > 0) sb.append(" • قراءة ثانية للترجمة وحدها: ").append(second).append(" (اختيرت في ").append(traces.count { it.secondChosen }).append(')')
        sb.append("\n«Vision» = نصه كما أرسلته Google حرفيًا. «النص» = بعد ترتيب الكلمات، قبل التنظيف والدمج.\n\n")
        val sorted = traces.sortedWith(compareBy<VisionEngine.Trace> { Timestamps.parseVsfName(it.name)?.first ?: Long.MAX_VALUE }.thenBy { it.name })
        for (t in sorted) {
            val ts = Timestamps.parseVsfName(t.name)
            if (ts != null) sb.append(Timestamps.srt(ts.first)).append(" → ").append(Timestamps.srt(ts.second)).append("  ")
            sb.append('[').append(t.name).append("]\n")
            sb.append("  Vision: «").append(one(t.vision)).append("»\n")
            sb.append("  النص:   «").append(one(t.text)).append('»')
            if (t.confidence > 0) sb.append("  ثقة ").append((t.confidence * 100).toInt()).append('%')
            sb.append('\n')
            t.second?.let {
                sb.append("  الترجمة وحدها: «").append(one(it)).append("» ← ").append(if (t.secondChosen) "اختيرت" else "بقيت الأولى").append('\n')
            }
        }
        return sb.toString()
    }

    private fun one(s: String) = if (s.isEmpty()) "—" else s.replace("\n", " ⏎ ")
}
