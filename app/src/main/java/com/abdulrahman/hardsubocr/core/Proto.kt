package com.abdulrahman.hardsubocr.core

import java.io.ByteArrayOutputStream

/** Minimal protobuf wire-format writer (no code generation, no extra Gradle plugin). */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) {
                out.write(x.toInt()); return
            }
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    /** uint64 / int64 / enum / bool. Negative int32 values are sign-extended to 10 bytes like protobuf. */
    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, 0); rawVarint(value); return this
    }

    fun int32(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = varint(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2); rawVarint(value.size.toLong()); out.write(value); return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(build).toByteArray())

    fun float32(field: Int, value: Float): ProtoWriter {
        tag(field, 5)
        val bits = java.lang.Float.floatToIntBits(value)
        out.write(bits and 0xFF); out.write((bits ushr 8) and 0xFF)
        out.write((bits ushr 16) and 0xFF); out.write((bits ushr 24) and 0xFF)
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** One decoded field. For wire type 2 [bytes] is set, otherwise [number] holds the raw value. */
class ProtoField(val field: Int, val wire: Int, val number: Long, val bytes: ByteArray?) {
    fun asString(): String = bytes?.toString(Charsets.UTF_8) ?: ""
    fun asMessage(): List<ProtoField> = if (bytes != null) ProtoReader.parse(bytes) else emptyList()
    fun asFloat(): Float = java.lang.Float.intBitsToFloat(number.toInt())
    fun asInt(): Int = number.toInt()
}

object ProtoReader {

    class ProtoException(msg: String) : Exception(msg)

    fun parse(data: ByteArray): List<ProtoField> {
        val res = ArrayList<ProtoField>()
        var p = 0
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (p >= data.size) throw ProtoException("truncated varint")
                val b = data[p++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw ProtoException("varint too long")
            }
        }
        while (p < data.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            if (field <= 0) throw ProtoException("bad field number")
            when (wire) {
                0 -> res.add(ProtoField(field, 0, varint(), null))
                1 -> {
                    if (p + 8 > data.size) throw ProtoException("truncated fixed64")
                    var v = 0L
                    for (i in 0 until 8) v = v or ((data[p + i].toLong() and 0xFF) shl (8 * i))
                    p += 8
                    res.add(ProtoField(field, 1, v, null))
                }
                2 -> {
                    val len = varint()
                    if (len < 0 || p + len > data.size) throw ProtoException("truncated bytes")
                    val b = data.copyOfRange(p, p + len.toInt())
                    p += len.toInt()
                    res.add(ProtoField(field, 2, 0, b))
                }
                5 -> {
                    if (p + 4 > data.size) throw ProtoException("truncated fixed32")
                    var v = 0L
                    for (i in 0 until 4) v = v or ((data[p + i].toLong() and 0xFF) shl (8 * i))
                    p += 4
                    res.add(ProtoField(field, 5, v, null))
                }
                else -> throw ProtoException("unsupported wire type $wire")
            }
        }
        return res
    }
}

fun List<ProtoField>.firstOf(field: Int): ProtoField? = firstOrNull { it.field == field }
fun List<ProtoField>.allOf(field: Int): List<ProtoField> = filter { it.field == field }
