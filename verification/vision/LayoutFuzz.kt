import com.abdulrahman.hardsubocr.core.*
import kotlin.random.Random

fun main() {
    val vocab = listOf("بخير", "أجل", "أنا", "لا", "هل", "أنت", "حبيبي،", "ماذا", "حدث؟", "و", "في", "البيت", "كنت", "أبحث", "عنك", "يا", "إلهي!", "نعم", "من", "هنا", "الآن", "أبي")
    val rnd = Random(7)
    var clean = 0; var cleanBad = 0; var dup = 0; var dupBad = 0
    repeat(4000) { iter ->
        val nLines = 1 + rnd.nextInt(2)
        val h = 30 + rnd.nextInt(40)
        val lines = List(nLines) { List(1 + rnd.nextInt(7)) { vocab[rnd.nextInt(vocab.size)] } }
        val words = ArrayList<W>()
        lines.forEachIndexed { li, ws ->
            val y0 = 20 + li * (h + 8 + rnd.nextInt(20))
            var x = 1400 - rnd.nextInt(200)
            for (t in ws) {
                val wpx = (t.length * h * (0.35 + rnd.nextDouble() * 0.25)).toInt().coerceAtLeast(8)
                val dy = rnd.nextInt(5) - 2
                val tall = if (rnd.nextBoolean()) rnd.nextInt(h / 4 + 1) else 0
                words.add(W(t, x - wpx, y0 + dy - tall, x, y0 + h + dy, 0.6 + rnd.nextDouble() * 0.39))
                x -= wpx + (h * (0.12 + rnd.nextDouble() * 0.5)).toInt()
            }
        }
        val want = lines.joinToString("\n") { it.joinToString(" ") }
        // genuine reading, cut into random paragraphs, in random order
        fun cut(ws: List<W>): List<List<W>> {
            val out = ArrayList<List<W>>(); var i = 0
            while (i < ws.size) { val n = 1 + rnd.nextInt(ws.size - i); out.add(ws.subList(i, i + n)); i += n }
            return out.shuffled(rnd)
        }
        val g = VisionEngine.parseText(answer(*cut(words).toTypedArray()))
        clean++; if (g != want) { cleanBad++; if (cleanBad <= 5) println("clean ✖ #$iter\n  got  «$g»\n  want «$want»") }
        // plus a second reading of a random part (outline copy: shifted 1–6 px, lower or higher confidence)
        val a = rnd.nextInt(words.size); val b = a + 1 + rnd.nextInt(words.size - a)
        val dx = 1 + rnd.nextInt(6); val dy = rnd.nextInt(5)
        val copy = words.subList(a, b).map { W(it.t, it.x0 + dx, it.y0 + dy, it.x1 + dx, it.y1 + dy, 0.4 + rnd.nextDouble() * 0.5) }
        val d = VisionEngine.parseText(answer(*(cut(words) + cut(copy)).shuffled(rnd).toTypedArray()))
        dup++; if (d != want) { dupBad++; if (dupBad <= 5) println("dup ✖ #$iter\n  got  «$d»\n  want «$want»") }
    }
    // misread copy (lower confidence) and joined copy (two words read as one)
    var mis = 0; var misBad = 0; var jn = 0; var jnBad = 0
    val rnd2 = Random(11)
    repeat(3000) { iter ->
        val h = 30 + rnd2.nextInt(40)
        val ws = List(2 + rnd2.nextInt(6)) { vocab[rnd2.nextInt(vocab.size)] }
        val words = ArrayList<W>(); var x = 1400
        for (t in ws) { val wpx = (t.length * h * (0.35 + rnd2.nextDouble() * 0.25)).toInt().coerceAtLeast(8); words.add(W(t, x - wpx, 50, x, 50 + h, 0.85 + rnd2.nextDouble() * 0.14)); x -= wpx + (h * (0.15 + rnd2.nextDouble() * 0.4)).toInt() }
        val want = ws.joinToString(" ")
        val k = rnd2.nextInt(words.size)
        val bad = words.mapIndexed { i, w -> W(if (i == k && w.t.length > 2) w.t.drop(1) else w.t, w.x0 + 2, w.y0 + 1, w.x1 + 2, w.y1 + 1, 0.3 + rnd2.nextDouble() * 0.5) }
        val g = VisionEngine.parseText(answer(*listOf(bad, words).shuffled(rnd2).toTypedArray()))
        mis++; if (g != want) { misBad++; if (misBad <= 4) println("misread ✖\n  got  «$g»\n  want «$want»") }
        val m = rnd2.nextInt(words.size - 1)
        val joined = words.take(m) + W(words[m].t + words[m + 1].t, words[m + 1].x0 + 1, 51, words[m].x1 + 1, 51 + h, rnd2.nextDouble()) + words.drop(m + 2)
        val gj = VisionEngine.parseText(answer(*listOf(joined, words).shuffled(rnd2).toTypedArray()))
        jn++; if (gj != want) { jnBad++; if (jnBad <= 4) println("joined ✖\n  got  «$gj»\n  want «$want»") }
    }
    println("misread copy: ${mis - misBad}/$mis exact · joined copy: ${jn - jnBad}/$jn exact")
    println("clean readings: ${clean - cleanBad}/$clean exact · with an outline copy: ${dup - dupBad}/$dup exact")
}
