import com.abdulrahman.hardsubocr.core.*
fun e(name: String, text: String): SubEntry { val (a, b) = Timestamps.parseVsfName(name)!!; return SubEntry(a, b, text) }
fun show(t: String, l: List<SubEntry>) { println("  $t: " + l.joinToString("  |  ") { "${Timestamps.srt(it.startMs)} → ${Timestamps.srt(it.endMs)} «${it.text}»" }) }
fun main() {
    val user = listOf(e("0_01_55_280__0_01_56_879_0000003120852016808520480.jpeg", "الآن"), e("0_01_56_880__0_01_57_239_0000003120852016808520480.jpeg", "الآن"))
    println("Your example (gap 1 ms, same text):")
    show("split-only merge", Merge.mergeSplits(user)); show("no merge", Merge.noMerge(user))
    val look = listOf(e("0_00_10_000__0_00_11_599_x.jpeg", "سأذهب إلى البيت الآن"), e("0_00_11_600__0_00_13_000_x.jpeg", "سأذهب إلى البيت غدًا"))
    println("Different sentences back to back (similarity %.2f):".format(ArabicText.similarityStrict(look[0].text, look[1].text)))
    show("OLD v2.1 rules", Merge.mergeStrict(look)); show("split-only merge", Merge.mergeSplits(look))
    val tash = listOf(e("0_00_20_000__0_00_20_239_x.jpeg", "الآنَ يا أحمد"), e("0_00_20_240__0_00_22_000_x.jpeg", "الآن يا أحمدُ"))
    println("Same line, OCR differs only in tashkeel:"); show("split-only merge", Merge.mergeSplits(tash))
    val gapped = listOf(e("0_00_30_000__0_00_31_000_x.jpeg", "نعم"), e("0_00_31_200__0_00_32_000_x.jpeg", "نعم"))
    println("Same text, 200 ms apart (two separate lines):"); show("split-only merge", Merge.mergeSplits(gapped)); show("smart merge (PRO)", Merge.mergePro(gapped, 350, false))
    check(Merge.mergeSplits(user).size == 1 && Merge.noMerge(user).size == 2 && Merge.mergeSplits(look).size == 2 && Merge.mergeStrict(look).size == 1 && Merge.mergeSplits(tash).size == 1 && Merge.mergeSplits(gapped).size == 2)
    // 3.0.3: «أخي» while the character runs — 9 pieces of one line with holes of 17–50 ms (the user's file names)
    val blink = listOf("0_04_06_850__0_04_06_915", "0_04_06_933__0_04_06_999", "0_04_07_050__0_04_07_115", "0_04_07_133__0_04_07_199",
        "0_04_07_250__0_04_07_315", "0_04_07_333__0_04_07_399", "0_04_07_450__0_04_07_515", "0_04_07_533__0_04_07_599", "0_04_07_650__0_04_07_715")
        .map { e(it + "_0000006701875041019201080.jpeg", "أخي") }
    println("One line blinking (9 pieces, holes of 17–50 ms):"); show("split-only merge", Merge.mergeSplits(blink)); show("no merge", Merge.noMerge(blink))
    val far = listOf(e("0_00_40_000__0_00_40_999_x.jpeg", "لا"), e("0_00_41_170__0_00_42_000_x.jpeg", "لا"), e("0_00_42_090__0_00_43_000_x.jpeg", "لا لا"))
    println("Same text 171 ms apart, then another text 90 ms after:"); show("split-only merge", Merge.mergeSplits(far))
    check(Merge.mergeSplits(blink).let { it.size == 1 && it[0].startMs == 246_850L && it[0].endMs == 247_715L } && Merge.noMerge(blink).size == 9 &&
        Merge.mergeSplits(far).size == 3)
    val en = listOf(
        "```\nText: \"| don't know what happened.\"\n```" to "I don't know what happened.",
        "- Where were you?\n- At home.\n- At home." to "- Where were you?\n- At home.",
        "NO_TEXT" to "", "♪ Follow me ♪" to "♪ Follow me ♪", "...\nAre you sure?" to "Are you sure?")
    for ((i, o) in en) { val r = EnglishText.clean(i); println("  EN «${i.replace("\n", "⏎")}» → «${r.replace("\n", "⏎")}»"); check(r == o) }
    check(Cleaner.clean("Are you okay?", CleanMode.ENGLISH) == "Are you okay?" && Cleaner.clean("Are you okay?", CleanMode.PRO) == "")
    println("ALL MERGE + ENGLISH CHECKS PASSED")
}
