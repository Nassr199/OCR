import com.abdulrahman.hardsubocr.core.*

// The three lines from the user's report, at 30 fps, in µs.
const val F = 33_367L
fun ms(m: Long) = m * 1000
fun P(a: Long, b: Long, t: String?) = Arbiter.Piece(ms(a), ms(b), t)
var fails = 0
fun check(name: String, ok: Boolean, got: Any) { println((if (ok) "PASS " else "FAIL ") + name + "  →  " + got); if (!ok) fails++ }
fun show(l: List<Arbiter.Line>) = l.joinToString(" | ") { "${Timestamps.srt(it.startUs / 1000)}→${Timestamps.srt(it.endUs / 1000)} ${it.text} [${it.note}]" }

fun main() {
    val minF = 5
    // 1) «ماذا حدث ؟» missed by 2.1, read by 2.0 in the gap
    run {
        val base = listOf(P(44_000, 48_500, "قبلها"), P(52_300, 55_632, "حبيبي، هل أنت بخير؟"))
        val raw = listOf(listOf(), listOf(P(44_000, 48_500, null), P(48_816, 51_916, null), P(52_300, 55_632, null)), listOf())
        val need = Arbiter.needed(base, raw, F, minF)
        check("gap piece is read", need[1] == listOf(1), need)
        val alts = listOf(listOf(), listOf(P(44_000, 48_500, null), P(48_816, 51_916, "ماذا حدث ؟"), P(52_300, 55_632, null)), listOf<Arbiter.Piece>())
        val r = Arbiter.resolve(base, alts, F, minF)
        check("«ماذا حدث ؟» added at 2.0's time", r.any { it.text == "ماذا حدث ؟" && it.startUs == ms(48_816) && it.endUs == ms(51_916) }, show(r))
        check("2.1 lines unchanged", r.count { it.note == "2.1" } == 2, show(r))
    }
    // 2) «حبيبي» too long in 2.1 (credits before/after); 2.0 cut it and read nothing around it
    run {
        val base = listOf(P(52_300, 55_632, "حبيبي، هل أنت بخير؟"))
        val raw = listOf(listOf(), listOf(P(52_300, 52_966, null), P(52_966, 54_233, null), P(54_233, 55_632, null)), listOf())
        val need = Arbiter.needed(base, raw, F, minF)
        check("specific pieces are read", need[1] == listOf(0, 1, 2), need)
        val alts = listOf(listOf(), listOf(P(52_300, 52_966, ""), P(52_966, 54_233, "حبيبي ، هل أنت بخير ؟"), P(54_233, 55_632, "")), listOf<Arbiter.Piece>())
        val r = Arbiter.resolve(base, alts, F, minF)
        check("«حبيبي» trimmed to 52.966–54.233", r.size == 1 && r[0].startUs == ms(52_966) && r[0].endUs == ms(54_233) && r[0].text == "حبيبي، هل أنت بخير؟", show(r))
    }
    // 3) «أبي»: 2.1's composite read only "2." (→ "" after the Arabic filter); 2.0's frame shows أبي
    run {
        val base = listOf(P(66_716, 70_032, ""))
        val raw = listOf(listOf(P(66_716, 70_032, null)), listOf(P(66_750, 70_000, null)), listOf(P(66_716, 70_032, null)))
        val need = Arbiter.needed(base, raw, F, minF)
        check("copies over a blank 2.1 image are read", need.all { it == listOf(0) }, need)
        val alts = listOf(listOf(P(66_716, 70_032, "")), listOf(P(66_750, 70_000, "أبي")), listOf(P(66_716, 70_032, "أبي")))
        val r = Arbiter.resolve(base, alts, F, minF)
        check("«أبي» recovered", r.size == 1 && r[0].text == "أبي", show(r))
    }
    // 4) everything agrees → exactly 2.1
    run {
        val base = listOf(P(1_000, 3_000, "أ"), P(3_000, 5_000, "ب ب ب"), P(6_000, 9_000, "ج ج ج"))
        val raw = listOf(listOf(P(1_000, 3_000, null), P(3_000, 5_000, null), P(6_010, 9_000, null)), listOf(P(1_020, 2_990, null), P(3_000, 5_000, null), P(6_000, 9_000, null)), base.map { P(it.startUs / 1000, it.endUs / 1000, null) })
        val need = Arbiter.needed(base, raw, F, minF)
        check("nothing to read when engines agree", need.all { it.isEmpty() }, need)
        val r = Arbiter.resolve(base, raw, F, minF)
        check("output == 2.1", r.size == 3 && r.indices.all { r[it].startUs == base[it].startUs && r[it].endUs == base[it].endUs && r[it].text == base[it].text }, show(r))
    }
    // 5) 2.1 joined two lines; 2.0 split them and read both
    run {
        val base = listOf(P(10_000, 16_000, "سأعود قريبًا"))
        val alts = listOf(listOf(), listOf(P(10_000, 13_000, "سأعود قريبًا"), P(13_000, 16_000, "لا تتركني وحدي")), listOf<Arbiter.Piece>())
        val r = Arbiter.resolve(base, alts, F, minF)
        check("over-merged 2.1 line split", r.size == 2 && r[0].text == "سأعود قريبًا" && r[1].text == "لا تتركني وحدي" && r[1].startUs == ms(13_000), show(r))
    }
    // 6) a misread (≈70 % alike) must NOT split a 2.1 line
    run {
        val base = listOf(P(10_000, 16_000, "لا تتركني وحدي"))
        val alts = listOf(listOf(), listOf(P(10_000, 13_000, "لا تتركني وحدي"), P(13_000, 16_000, "لا تبركنى وحدى")), listOf<Arbiter.Piece>())
        val r = Arbiter.resolve(base, alts, F, minF)
        check("misread keeps the line whole", r.size == 1 && r[0].startUs == ms(10_000) && r[0].endUs == ms(16_000), show(r))
    }
    // 7) same text in two consecutive scenes («الآن» + «الآن»): two 2.1 lines stay two
    run {
        val base = listOf(P(115_280, 116_879, "الآن"), P(116_880, 117_239, "الآن"))
        val r = Arbiter.resolve(base, listOf(listOf(), listOf(), listOf()), F, 3)
        check("«الآن» twice stays twice", r.size == 2, show(r))
    }
    // 8) 2.1 split one line by a flash; 2.2 saw one subtitle across the gap and read it → rejoined
    run {
        val base = listOf(P(20_000, 21_500, "أين أمي؟"), P(21_800, 23_000, "أين أمي؟"))
        val alts = listOf(listOf(P(20_000, 23_000, "أين أمي ؟")), listOf(), listOf<Arbiter.Piece>())
        val r = Arbiter.resolve(base, alts, F, minF)
        check("flash split rejoined", r.size == 1 && r[0].startUs == ms(20_000) && r[0].endUs == ms(23_000), show(r))
    }
    println(if (fails == 0) "ARBITER: ALL PASSED" else "ARBITER: $fails FAILED")
}
