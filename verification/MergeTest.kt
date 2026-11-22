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
    val en = listOf(
        "```\nText: \"| don't know what happened.\"\n```" to "I don't know what happened.",
        "- Where were you?\n- At home.\n- At home." to "- Where were you?\n- At home.",
        "NO_TEXT" to "", "♪ Follow me ♪" to "♪ Follow me ♪", "...\nAre you sure?" to "Are you sure?")
    for ((i, o) in en) { val r = EnglishText.clean(i); println("  EN «${i.replace("\n", "⏎")}» → «${r.replace("\n", "⏎")}»"); check(r == o) }
    check(Cleaner.clean("Are you okay?", CleanMode.ENGLISH) == "Are you okay?" && Cleaner.clean("Are you okay?", CleanMode.PRO) == "")
    println("ALL MERGE + ENGLISH CHECKS PASSED")
}
