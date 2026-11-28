import com.abdulrahman.hardsubocr.core.*
import kotlinx.serialization.json.*
import java.io.File
var f26 = 0
fun chk(n: String, ok: Boolean, d: () -> String) { if (!ok) { f26++; if (f26 < 25) println("FAIL[$n] " + d()) } }
fun e(s: String?) = s?.map { c -> if (c.code < 32 || c.code in 0x64b..0x670 || c == ' ') "\\u%04x".format(c.code) else c.toString() }?.joinToString("")
fun main() {
    val v = Json.parseToJsonElement(File("/home/claude/tests/vectors26.json").readText()).jsonObject
    fun rows(k: String) = v[k]!!.jsonArray.map { it.jsonArray }
    var n = 0
    for (r in rows("cons")) { n++; val i = r[0].jsonPrimitive.content; val x = r[1].jsonPrimitive.content; val g = ArabicV26.conservativeCleanDiacritics(i); chk("cons", g == x) { "in=${e(i)}\n exp=${e(x)}\n got=${e(g)}" } }
    for (r in rows("frag")) { n++; val i = r[0].jsonPrimitive.content; val x = r[1].jsonPrimitive.content; val g = ArabicV26.fixFragmentedArabicWords(i); chk("frag", g == x) { "in=${e(i)}\n exp=${e(x)}\n got=${e(g)}" } }
    for (r in rows("norm")) { n++; val i = r[0].jsonPrimitive.content; val x = r[1].jsonPrimitive.content; val g = ArabicV26.normalizeForTemporalMatch(i); chk("norm", g == x) { "in=${e(i)}\n exp=${e(x)}\n got=${e(g)}" } }
    for (r in rows("san")) { n++; val i = r[0].jsonPrimitive.content; val x = r[1].jsonPrimitive.content; val g = ArabicV26.sanitizeWordDiacritics(i); chk("san", g == x) { "in=${e(i)} exp=${e(x)} got=${e(g)}" } }
    for (r in rows("clean26")) { n++; val i = r[0].jsonPrimitive.content; val x = r[1].jsonPrimitive.content; val g = ArabicV26.conservativeCleanDiacritics(ArabicText.cleanOcrOutput(i)); chk("clean26", g == x) { "in=${e(i)}\n exp=${e(x)}\n got=${e(g)}" } }
    for (r in rows("consensus")) { n++; val i = r[0].jsonArray.map { it.jsonPrimitive.content }; val x = r[1].jsonPrimitive.content; val g = ArabicV26.buildGroupConsensusText(i); chk("consensus", g == x) { "exp=${e(x)}\n got=${e(g)}" } }
    for (r in rows("stab")) { n++
        val ents = r[0].jsonArray.map { val a = it.jsonArray; SubEntry(a[0].jsonPrimitive.long, a[1].jsonPrimitive.long, a[2].jsonPrimitive.content) }
        val x = r[1].jsonArray.map { val a = it.jsonArray; SubEntry(a[0].jsonPrimitive.long, a[1].jsonPrimitive.long, a[2].jsonPrimitive.content) }
        val g = ArabicV26.stabilizeConsecutiveDiacritics(ents); chk("stab", g == x) { "exp=$x\n got=$g" } }
    println("v2.6 vectors checked: $n  FAILS=$f26")
}
