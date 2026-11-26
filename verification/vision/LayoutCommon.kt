import kotlinx.serialization.json.*
class W(val t: String, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val c: Double = 0.9)

/** Vision answer: blocks → one paragraph each → words with symbols and boxes; full text = blocks in the given order. */
fun answer(vararg blocks: List<W>, dropZero: Boolean = true): String {
    fun v(x: Int, y: Int) = buildJsonObject { if (!dropZero || x != 0) put("x", x); if (!dropZero || y != 0) put("y", y) }
    fun box(w: W) = buildJsonObject { putJsonArray("vertices") { add(v(w.x0, w.y0)); add(v(w.x1, w.y0)); add(v(w.x1, w.y1)); add(v(w.x0, w.y1)) } }
    val text = blocks.joinToString("") { b -> b.joinToString(" ") { it.t } + "\n" }
    return buildJsonObject {
        putJsonArray("responses") {
            addJsonObject {
                putJsonArray("textAnnotations") { addJsonObject { put("locale", "ar"); put("description", text) } }
                putJsonObject("fullTextAnnotation") {
                    put("text", text)
                    putJsonArray("pages") {
                        addJsonObject {
                            putJsonArray("blocks") {
                                for (b in blocks) addJsonObject {
                                    putJsonArray("paragraphs") {
                                        addJsonObject {
                                            putJsonArray("words") {
                                                for (w in b) addJsonObject {
                                                    put("boundingBox", box(w)); put("confidence", w.c)
                                                    putJsonArray("symbols") { for (ch in w.t) addJsonObject { put("text", ch.toString()) } }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }.toString()
}

