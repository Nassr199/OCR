import com.abdulrahman.hardsubocr.core.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.abs

var fails = 0
fun check(name: String, ok: Boolean, detail: () -> String) {
    if (!ok) { fails++; if (fails < 40) println("FAIL[$name] " + detail()) }
}
fun esc(s: String?) = s?.map { c -> if (c.code < 32 || c.code in 0x200e..0x200f || c == ' ') "\\u%04x".format(c.code) else c.toString() }?.joinToString("")

fun main() {
    val v = Json.parseToJsonElement(File("/home/claude/tests/vectors.json").readText()).jsonObject
    fun pairs(k: String) = v[k]!!.jsonArray.map { it.jsonArray }
    var n = 0
    for (p in pairs("norm")) { n++; val i = p[0].jsonPrimitive.content; val e = p[1].jsonPrimitive.content
        val g = ArabicText.normText(i); check("norm", g == e) { "in=${esc(i)}\n exp=${esc(e)}\n got=${esc(g)}" } }
    for (p in pairs("sus")) { n++; val i = p[0].jsonPrimitive.content; val e = p[1].jsonPrimitive.content
        val g = ArabicText.cleanSuspiciousNumbers(i); check("sus", g == e) { "in=${esc(i)}\n exp=${esc(e)}\n got=${esc(g)}" } }
    for (p in pairs("arabic")) { n++; val i = p[0].jsonPrimitive.content; val e = p[1].jsonPrimitive.content
        val g = ArabicText.arabicFilter(i); check("arabic", g == e) { "in=${esc(i)}\n exp=${esc(e)}\n got=${esc(g)}" } }
    for (p in pairs("clean")) { n++; val i = p[0].jsonPrimitive.content
        val e = if (p[1] is JsonNull) "" else p[1].jsonPrimitive.content
        val g = ArabicText.cleanOcrOutput(i); check("clean", g == e) { "in=${esc(i)}\n exp=${esc(e)}\n got=${esc(g)}" } }
    for (p in pairs("ratio")) { n++; val a = p[0].jsonPrimitive.content; val b = p[1].jsonPrimitive.content; val e = p[2].jsonPrimitive.double
        val g = SequenceMatcher.ratio(a, b); check("ratio", abs(g - e) < 1e-12) { "a=${esc(a)} b=${esc(b)} exp=$e got=$g" } }
    for (p in pairs("simpro")) { n++; val a = p[0].jsonPrimitive.content; val b = p[1].jsonPrimitive.content; val e = p[2].jsonPrimitive.double
        val g = ArabicText.similarityPro(a, b); check("simpro", abs(g - e) < 1e-12) { "exp=$e got=$g" } }
    for (p in pairs("simstrict")) { n++; val a = p[0].jsonPrimitive.content; val b = p[1].jsonPrimitive.content; val e = p[2].jsonPrimitive.double
        val g = ArabicText.similarityStrict(a, b); check("simstrict", abs(g - e) < 1e-12) { "a=${esc(a)} b=${esc(b)} exp=$e got=$g" } }
    fun entries(a: JsonArray) = a.map { val x = it.jsonArray; SubEntry(x[0].jsonPrimitive.long, x[1].jsonPrimitive.long, x[2].jsonPrimitive.content) }
    var mpDiff = 0; var msDiff = 0
    for (p in pairs("mergepro")) { n++; val g = Merge.mergePro(entries(p[0].jsonArray), 350, true); val e = entries(p[1].jsonArray)
        if (g != e) mpDiff++ ; check("mergepro", g == e) { "exp=$e\n got=$g" } }
    for (p in pairs("mergestrict")) { n++; val g = Merge.mergeStrict(entries(p[0].jsonArray)); val e = entries(p[1].jsonArray)
        if (g != e) msDiff++ ; check("mergestrict", g == e) { "exp=${e.size} got=${g.size}\n exp=$e\n got=$g" } }
    // timestamps
    check("ts1", Timestamps.parseVsfName("0_00_10_000__0_00_12_500_abc.jpeg") == (10000L to 12500L)) { "parse" }
    check("ts2", Timestamps.parseVsfName("12_01_02_003__12_01_04_005.png") == (43262003L to 43264005L)) { "parse10h" }
    check("ts3", Timestamps.srt(3599999) == "00:59:59,999") { Timestamps.srt(3599999) }
    check("ts4", Timestamps.vsfName(61001, 62500) == "0_01_01_001__0_01_02_500.png") { Timestamps.vsfName(61001, 62500) }
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
    check("ts5-locale", Timestamps.srt(1234) == "00:00:01,234") { "arabic locale digits: " + Timestamps.srt(1234) }
    check("srt", Srt.build(listOf(SubEntry(0, 1500, "مرحبا")), bom = true) == "﻿1\n00:00:00,000 --> 00:00:01,500\nمرحبا\n\n") { "srt" }
    println("checked $n vectors, mergePro diffs=$mpDiff, mergeStrict diffs=$msDiff, FAILS=$fails")
}
