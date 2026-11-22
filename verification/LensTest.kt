import com.abdulrahman.hardsubocr.core.*
import java.io.File
fun main() {
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(5000) { (it % 251).toByte() }
    File("/home/claude/tests/req.bin").writeBytes(LensProtocol.buildRequest(png, 1500, 156))
    val r = LensProtocol.parseResponse(File("/home/claude/tests/resp.bin").readBytes())
    println("lang=" + r.language + " paragraphs=" + r.paragraphs.size + " lines=" + r.lines.size)
    println("full=" + r.fullText.replace("\n", "|"))
    println("box=" + r.lines[1].box)
    println("filtered=" + ArabicText.arabicFilter(r.fullText))
    // malformed input must throw, not crash the VM
    try { LensProtocol.parseResponse(byteArrayOf(0x0A, 0x7F)); println("BAD: no exception") } catch (e: Exception) { println("malformed ok: " + e.message) }
}
