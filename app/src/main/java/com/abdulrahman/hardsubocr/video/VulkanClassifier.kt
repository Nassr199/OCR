package com.abdulrahman.hardsubocr.video

import com.abdulrahman.hardsubocr.core.LumaGrid
import com.abdulrahman.hardsubocr.core.ScanParams
import com.abdulrahman.hardsubocr.core.SubStyle

/** JNI entry points of libhsocrvk.so (native/vk_classify.c, built with the NDK in CI). */
object VulkanNative {
    val loaded: Boolean = runCatching { System.loadLibrary("hsocrvk"); true }.getOrElse { false }

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeInfo(handle: Long): String
    @JvmStatic external fun nativeError(): String
    @JvmStatic external fun nativeClassify(handle: Long, luma: ByteArray, w: Int, h: Int, params: IntArray, out: ByteArray): Boolean
    @JvmStatic external fun nativeDestroy(handle: Long)
}

/**
 * The per-pixel stage of the text detector ([com.abdulrahman.hardsubocr.core.ScanMask.classify])
 * as a Vulkan compute shader on the phone's GPU, through its own driver (Adreno, Mali, Xclipse…).
 * Integer-only: the result is bit-identical to the CPU, which the scanner checks.
 */
class VulkanClassifier private constructor(private var handle: Long, raw: String) {

    /** key=value lines reported by the driver: device, vendor, type, vulkan, driverVersion, driverName, driverInfo. */
    val fields: Map<String, String> = raw.lines().mapNotNull { l ->
        val i = l.indexOf('=')
        if (i <= 0) null else l.substring(0, i) to l.substring(i + 1).trim()
    }.toMap()

    val device get() = fields["device"].orEmpty()
    val isSoftware get() = fields["type"].orEmpty().startsWith("CPU")

    /** One line for the log: "Adreno (TM) 740 · Qualcomm · Vulkan 1.3.128 · driver 512.744.0 (…)". */
    val summary: String
        get() {
            val drv = listOf(fields["driverName"], fields["driverInfo"]).filter { !it.isNullOrBlank() }.joinToString(" ")
            return buildString {
                append(device).append(" · ").append(fields["vendor"].orEmpty().substringBefore(" ("))
                append(" · Vulkan ").append(fields["vulkan"].orEmpty())
                append(" · driver ").append(fields["driverVersion"].orEmpty())
                if (drv.isNotEmpty()) append(" (").append(drv).append(')')
            }
        }

    private val lock = Any()

    /** Same bytes as ScanMask.classify(g, p). Thread-safe. */
    fun classify(g: LumaGrid, p: ScanParams): ByteArray {
        val n = g.width * g.height
        val out = ByteArray(n)
        val prm = intArrayOf(
            g.width, g.height, n, p.ridgeRadius,
            p.brightHigh, p.contrastHigh, p.brightLow, p.contrastLow,
            if (p.style == SubStyle.DARK) 1 else 0, p.radius.coerceAtLeast(1), 0, 0
        )
        val ok = synchronized(lock) { handle != 0L && VulkanNative.nativeClassify(handle, g.data, g.width, g.height, prm, out) }
        if (!ok) throw IllegalStateException("Vulkan: " + runCatching { VulkanNative.nativeError() }.getOrDefault("?"))
        return out
    }

    fun close() {
        synchronized(lock) {
            if (handle != 0L) runCatching { VulkanNative.nativeDestroy(handle) }
            handle = 0L
        }
    }

    companion object {
        /** Null when Vulkan cannot run here; [why] gets the reason. */
        fun create(why: (String) -> Unit = {}): VulkanClassifier? {
            if (!VulkanNative.loaded) {
                why("مكتبة Vulkan (libhsocrvk.so) غير موجودة في هذا البناء")
                return null
            }
            val h = runCatching { VulkanNative.nativeCreate() }.getOrElse {
                why(it.message ?: it.javaClass.simpleName)
                return null
            }
            if (h == 0L) {
                why(runCatching { VulkanNative.nativeError() }.getOrDefault("Vulkan غير متاح"))
                return null
            }
            return VulkanClassifier(h, runCatching { VulkanNative.nativeInfo(h) }.getOrDefault(""))
        }
    }
}
