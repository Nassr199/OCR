package com.abdulrahman.hardsubocr.video

import com.abdulrahman.hardsubocr.core.LumaGrid
import com.abdulrahman.hardsubocr.core.ScanMask
import com.abdulrahman.hardsubocr.core.ScanParams
import com.abdulrahman.hardsubocr.core.SubLang

/**
 * "Test the GPU" in the settings: opens Vulkan on the phone's GPU, shows what the driver reports,
 * runs the detector's per-pixel stage on a 1280×200 frame on the GPU and on the CPU, and compares
 * every pixel.
 */
object GpuSelfTest {
    fun run(): String {
        val why = StringBuilder()
        val vk = VulkanClassifier.create { why.append(it) } ?: return "✖ Vulkan غير متاح: $why"
        try {
            val f = vk.fields
            val sb = StringBuilder()
            sb.append("✔ Vulkan يعمل على هذا الجهاز\n")
            sb.append("المعالج الرسومي: ").append(vk.device).append('\n')
            sb.append("الشركة: ").append(f["vendor"].orEmpty()).append(" — ").append(f["type"].orEmpty()).append('\n')
            sb.append("Vulkan ").append(f["vulkan"].orEmpty()).append(" • إصدار التعريف ").append(f["driverVersion"].orEmpty()).append('\n')
            val drv = listOf(f["driverName"], f["driverInfo"]).filter { !it.isNullOrBlank() }.joinToString(" • ")
            if (drv.isNotEmpty()) sb.append("التعريف (Driver): ").append(drv).append('\n')

            val w = 1280
            val h = 200
            val g = LumaGrid(w, h, frame(w, h))
            val p = ScanParams.preset(SubLang.ARABIC)
            vk.classify(g, p) // warm-up (pipeline, buffers)
            val runs = 15
            var gpu = ByteArray(0)
            val t0 = System.nanoTime()
            repeat(runs) { gpu = vk.classify(g, p) }
            val t1 = System.nanoTime()
            var cpu = ByteArray(0)
            repeat(runs) { cpu = ScanMask.classify(g, p) }
            val t2 = System.nanoTime()
            val diff = gpu.indices.count { gpu[it] != cpu[it] }
            sb.append("إطار ${w}×${h}: Vulkan %.2f مللي ث • المعالج المركزي (نواة واحدة) %.2f مللي ث\n".format((t1 - t0) / 1e6 / runs, (t2 - t1) / 1e6 / runs))
            sb.append(if (diff == 0) "النتيجة مطابقة للمعالج المركزي بكسلًا بكسل ✓ (${w * h} بكسل)" else "✖ $diff بكسل مختلف — لن يُستخدم Vulkan في التحليل")
            if (vk.isSoftware) sb.append("\n⚠ هذا Vulkan برمجي (ليس معالجًا رسوميًا حقيقيًا) — لن يُستخدم في التحليل")
            return sb.toString()
        } finally {
            vk.close()
        }
    }

    /** Busy background with bright stroke pieces (outlined) — like a frame of hard subtitles. */
    private fun frame(w: Int, h: Int): ByteArray {
        val g = ByteArray(w * h)
        var r = 12345L
        fun next(): Int {
            r = (r * 1103515245L + 12345L) and 0xFFFFFFFFL
            return ((r shr 8) and 0xFFFFFF).toInt()
        }
        for (i in g.indices) g[i] = (40 + next() % 120).toByte()
        repeat(120) {
            val x = next() % w
            val y = next() % h
            val len = 3 + next() % 25
            val vert = next() and 1 == 1
            for (t in -1..len + 1) for (q in -2..2) {
                val xx = if (vert) x + q else x + t
                val yy = if (vert) y + t else y + q
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                g[yy * w + xx] = (if ((q == 0 || q == 1) && t in 0..len) 230 + next() % 26 else 10 + next() % 20).toByte()
            }
        }
        return g
    }
}
