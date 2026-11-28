import com.abdulrahman.hardsubocr.core.*
import java.io.File

var fails = 0
fun check(name: String, ok: Boolean, d: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (d.isNotEmpty()) "  [$d]" else "")); if (!ok) fails++ }

fun main() {
    val dir = File("/tmp/diagtest").apply { deleteRecursively(); mkdirs() }
    DiagLog.init(dir, "الإصدار: 2.7.0\nالجهاز: Test")
    // masking
    val k = "AIzaSyFAKEkeyFORtestsONLYxxxxxxxxxxxxxx"
    check("Google key masked", !DiagLog.redact("key=$k").contains("FAKEkeyFOR"), DiagLog.redact("key=$k"))
    check("OpenRouter key masked", !DiagLog.redact("Authorization: Bearer sk-or-v1-abcdef0123456789abcdef").contains("abcdef0123456789"), DiagLog.redact("Authorization: Bearer sk-or-v1-abcdef0123456789abcdef"))
    check("normal Arabic text untouched", DiagLog.redact("✓ 0_01_06_266 ← أبي") == "✓ 0_01_06_266 ← أبي")
    // speed: 20k lines must not block the caller
    val t0 = System.nanoTime()
    repeat(20_000) { DiagLog.i("Job", "✓ سطر رقم $it ← نص") }
    val ms = (System.nanoTime() - t0) / 1e6
    check("20,000 lines queued without blocking", ms < 400, "%.0f ms".format(ms))
    DiagLog.e("Job", "فشل «x»", IllegalStateException("boom"))
    DiagLog.i("Job", "uses $k inside")
    DiagLog.flush()
    val text = File(dir, "diag.log").readText() + (File(dir, "diag.1.log").takeIf { it.exists() }?.readText() ?: "")
    check("header written", text.contains("الإصدار: 2.7.0"))
    check("stack trace written", text.contains("IllegalStateException") && text.contains("boom"))
    check("no key on disk", !text.contains("FAKEkeyFOR"))
    check("lines kept or counted as dropped", text.contains("سطر رقم 19999") || text.contains("حُذف"))
    // rotation: 2 files, bounded size
    repeat(60_000) { DiagLog.i("Job", "x".repeat(60) + it) ; if (it % 3000 == 0) Thread.sleep(5) }
    DiagLog.flush()
    val total = DiagLog.sizeBytes()
    check("rotated, total bounded (≤ 3.1 MB)", File(dir, "diag.1.log").exists() && total <= 3_100_000, "$total bytes")
    // crash is written synchronously
    DiagLog.crash("main", RuntimeException("crash-here"))
    check("crash written synchronously", File(dir, "diag.log").readText().contains("crash-here"))
    val exp = String(DiagLog.export("الجهاز: Test"))
    check("export has title + both files", exp.startsWith("HardSub OCR — ملف التشخيص") && exp.contains("crash-here"))
    DiagLog.clear(); DiagLog.flush()
    check("clear", DiagLog.sizeBytes() < 2000, "${DiagLog.sizeBytes()}")
    DiagLog.enabled = false
    DiagLog.i("Job", "should-not-appear"); DiagLog.flush()
    check("disabled → nothing written", !File(dir, "diag.log").readText().contains("should-not-appear"))
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
