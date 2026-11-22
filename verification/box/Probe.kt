import com.abdulrahman.hardsubocr.core.*
import java.io.File
import java.io.RandomAccessFile
fun probeMain(names: List<String>) {
    for (name in names) {
        val prefix = "/home/claude/vt/$name"
        val j = File("$prefix.json").readText()
        val gw = Regex("\"gw\": *(\\d+)").find(j)!!.groupValues[1].toInt()
        val gh = Regex("\"gh\": *(\\d+)").find(j)!!.groupValues[1].toInt()
        val n = Regex("\"frames\": *(\\d+)").find(j)!!.groupValues[1].toInt()
        var withText = 0; var withBox = 0
        val p = ScanParams.preset(if (name == "en") SubLang.ENGLISH else SubLang.ARABIC)
        RandomAccessFile("$prefix.grid", "r").use { f ->
            // 24 samples spread over the video, like the app's quick probe
            for (k in 0 until 24) {
                val fi = (n * (k + 0.5) / 24).toInt()
                val b = ByteArray(gw * gh); f.seek(fi.toLong() * gw * gh); f.readFully(b)
                val g = LumaGrid(gw, gh, b)
                val m = ScanMask.compute(g, p)
                if (m.count < gw * gh / 400) continue
                withText++
                if (SubBox.find(g, m, 16).boxes.isNotEmpty()) withBox++
            }
        }
        println("$name: samples with text $withText, with a subtitle box $withBox → ${if (withBox >= 3 && withBox >= 0.4 * withText) "BOX MODE" else "normal"}")
    }
}
