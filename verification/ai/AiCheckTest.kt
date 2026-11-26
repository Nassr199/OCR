import com.abdulrahman.hardsubocr.core.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (detail.isNotEmpty()) "  [$detail]" else "")); if (!ok) fails++ }

/**
 * 2.8.0: Google Lens as the AI engines' second reader (AiCheck). The user's faults:
 * «بخير بخير أجل أنا بخير» read by Gemini 3.5 Flash-Lite as «بخير بخير بخير أجل أنا بخير» on four frames,
 * and «أريد أمي» cut to «أر».
 */
class Ai(private val answers: Map<String, Any>) : OcrEngine {
    override val kind = EngineKind.OPENROUTER
    override val cacheId = "ai"
    var calls = 0
    override suspend fun recognize(image: PreparedImage): String {
        calls++
        return when (val a = answers[image.name]) {
            is String -> a
            is OcrError -> throw a
            else -> ""
        }
    }
}

class Lens(private val answers: Map<String, String>) : OcrEngine {
    override val kind = EngineKind.LENS
    override val cacheId = "lens"
    val seen = ArrayList<String>()
    override suspend fun recognize(image: PreparedImage): String {
        seen.add(image.name + ":" + image.mime)
        return answers[image.name] ?: throw OcrError.Retryable("Lens unreachable")
    }
}

/** The AI gets an "enhanced" picture, Lens the original: the mime tells which one each engine read. */
val preparer = ImagePreparer { bytes, name, kind -> PreparedImage(bytes, if (kind.isClassicOcr) "image/original" else "image/enhanced", 10, 10, name) }

fun run(ai: Ai, lens: Lens?, names: List<String>, cache: OcrCache? = null): Triple<OcrPipeline, Map<String, String>, List<String>> = runBlocking {
    val notes = ArrayList<String>()
    val p = OcrPipeline(
        ai, preparer, CleanMode.EXACT, false, 2, RetryPolicy(maxRetries = 0), cache = cache,
        secondReader = lens?.let { LensCheck(it, false) },
        listener = object : PipelineListener { override fun onNote(item: OcrItem, note: String) { synchronized(notes) { notes.add(item.name + ": " + note) } } }
    )
    val ch = Channel<OcrItem>(Channel.UNLIMITED)
    names.forEachIndexed { i, n -> ch.trySend(OcrItem("k$n", n, i * 1000L, i * 1000L + 900) { n.toByteArray() }) }
    ch.close()
    p.run(ch)
    val texts = p.results.associate { e -> names[(e.startMs / 1000).toInt()] to e.text }
    Triple(p, texts, notes)
}

fun main() {
    // ------------------------------------------------------------------ the arbiter alone
    check("needs Lens: «بخير بخير بخير أجل أنا بخير»", AiCheck.needsLens("بخير بخير بخير أجل أنا بخير"))
    check("needs Lens: a phrase twice «هل تسمعينني؟ هل تسمعينني؟»", AiCheck.needsLens("هل تسمعينني؟ هل تسمعينني؟"))
    check("needs Lens across a line break «- لا\n- لا أعرف»", AiCheck.needsLens("- لا\n- لا أعرف"))
    check("no repeat → Lens not needed", !AiCheck.needsLens("قمت بالضغط المباشر على الجرح") && !AiCheck.needsLens("بخير أجل أنا بخير"))
    check("user's case: Lens's count taken", AiCheck.withLensRepeats("بخير بخير بخير أجل أنا بخير", "بخير بخير أجل أنا بخير") == "بخير بخير أجل أنا بخير")
    check("the AI's spelling stays (hamza, marks, punctuation)",
        AiCheck.withLensRepeats("بِخير، بخير، بخير، أجل أنا بخير.", "بخير بخير اجل انا بخير") == "بِخير، بخير، أجل أنا بخير.",
        AiCheck.withLensRepeats("بِخير، بخير، بخير، أجل أنا بخير.", "بخير بخير اجل انا بخير") ?: "null")
    check("a phrase repeated once too often", AiCheck.withLensRepeats("هل تسمعينني؟ هل تسمعينني؟ هل تسمعينني؟ انظري إلي", "هل تسمعينني؟ هل تسمعينني؟ انظري إلي") == "هل تسمعينني؟ هل تسمعينني؟ انظري إلي")
    check("a repeat the AI dropped comes back (AI word copied)", AiCheck.withLensRepeats("لا لا أريد", "لا لا لا أريد") == "لا لا لا أريد", AiCheck.withLensRepeats("لا لا أريد", "لا لا لا أريد") ?: "null")
    check("lines and dialogue dashes kept", AiCheck.withLensRepeats("- لا لا لا\n- اذهب", "- لا لا\n- اذهب") == "- لا لا\n- اذهب",
        AiCheck.withLensRepeats("- لا لا لا\n- اذهب", "- لا لا\n- اذهب") ?: "null")
    check("same words → nothing to change", AiCheck.withLensRepeats("بخير بخير أجل أنا بخير", "بخير بخير أجل أنا بخير") == null)
    check("Lens reads other words → AI text kept (Lens never rewrites)", AiCheck.withLensRepeats("بخير بخير بخير أجل أنا بخير", "بخير بخير أجل انا بخبر") == null)
    check("Lens adds a credit line → AI text kept", AiCheck.withLensRepeats("بخير بخير بخير أجل أنا بخير", "بخير بخير أجل أنا بخير\nقرمز") == null)
    check("Lens read nothing → AI text kept", AiCheck.withLensRepeats("لا لا لا", "") == null)
    check("words differing only by hamza / ta marbuta are the same word", AiCheck.withLensRepeats("أنا أنا أنا هنا", "انا انا هنا") == "أنا أنا هنا")

    // ------------------------------------------------------------------ in the pipeline
    val lensAnswers = mapOf(
        "f6" to "بخير بخير أجل أنا بخير", "f7" to "بخير بخير أجل أنا بخير", "f8" to "بخير بخير أجل أنا بخير", "f9" to "بخير بخير أجل أنا بخير",
        "f64" to "أريد أمي", "real" to "لا لا لا", "plain" to "قمت بالضغط المباشر على الجرح"
    )
    val ai = Ai(mapOf(
        "f6" to "بخير بخير بخير أجل أنا بخير", "f7" to "بخير بخير بخير أجل أنا بخير", "f8" to "بخير بخير بخير أجل أنا بخير", "f9" to "بخير بخير بخير أجل أنا بخير",
        "f64" to OcrError.Cut("Gemini أوقف الإجابة قبل نهايتها مرتين (SAFETY)", "أر"),
        "real" to "لا لا لا", "plain" to "قمت بالضغط المباشر على الجرح"
    ))
    val lens = Lens(lensAnswers)
    val names = listOf("f6", "f7", "f8", "f9", "f64", "real", "plain")
    val (p, texts, notes) = run(ai, lens, names)
    check("4 frames of «بخير…»: Lens's count on each", (6..9).all { texts["f$it"] == "بخير بخير أجل أنا بخير" }, texts.toString())
    check("«أر»: the cut answer replaced by Lens's «أريد أمي»", texts["f64"] == "أريد أمي")
    check("a real repeat Lens agrees with stays", texts["real"] == "لا لا لا")
    check("no repeat → Lens never asked", lens.seen.none { it.startsWith("plain") } && texts["plain"] == "قمت بالضغط المباشر على الجرح")
    check("Lens reads the ORIGINAL picture, the AI the enhanced one", lens.seen.all { it.endsWith("image/original") })
    check("counted for the job summary", p.stats.lensFixed.get() == 4 && p.stats.lensForCut.get() == 1, "${p.stats.lensFixed.get()} / ${p.stats.lensForCut.get()}")
    check("said in the job log for each image", notes.size == 5 && notes.any { it.startsWith("f64") && it.contains("أريد أمي") }, notes.joinToString(" | "))
    check("no failure", p.failures.isEmpty() && p.stats.failed.get() == 0)

    // Lens off (switch): the cut answer is a failed image, the repeat stays as the AI read it
    val (p2, t2, _) = run(Ai(mapOf("f6" to "بخير بخير بخير أجل أنا بخير", "f64" to OcrError.Cut("cut", "أر"))), null, listOf("f6", "f64"))
    check("without the check: AI text as read, cut image failed (never «أر»)", t2["f6"] == "بخير بخير بخير أجل أنا بخير" && t2["f64"] == null && p2.failures.size == 1)
    // Lens unreachable: as without it
    val (p3, t3, _) = run(Ai(mapOf("x" to "لا لا لا", "y" to OcrError.Cut("cut", "أر"))), Lens(emptyMap()), listOf("x", "y"))
    check("Lens unreachable: AI text kept, cut image failed", t3["x"] == "لا لا لا" && t3["y"] == null && p3.failures.size == 1)

    // resume memory: the checked text is what is remembered, under its own key
    val mem = HashMap<String, String>()
    val cache = object : OcrCache { override fun get(key: String) = mem[key]; override fun put(key: String, text: String) { mem[key] = text } }
    run(Ai(mapOf("f6" to "بخير بخير بخير أجل أنا بخير")), Lens(lensAnswers), listOf("f6"), cache)
    check("memory holds the corrected text, key marked |lens", mem.values.single() == "بخير بخير أجل أنا بخير" && mem.keys.single().endsWith("|lens"), mem.toString())

    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
