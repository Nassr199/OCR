package com.abdulrahman.hardsubocr.core

/**
 * Exact port of Python's difflib.SequenceMatcher(None, a, b).ratio()
 * (isjunk = None, autojunk = True). Works on Unicode code points like Python.
 * Both Windows versions rely on this ratio for merging, so it must be identical.
 */
object SequenceMatcher {

    fun ratio(a: String, b: String): Double {
        val ca = a.codePoints().toArray()
        val cb = b.codePoints().toArray()
        val length = ca.size + cb.size
        if (length == 0) return 1.0
        val matches = matchingCount(ca, cb)
        return 2.0 * matches / length
    }

    private fun matchingCount(a: IntArray, b: IntArray): Int {
        // __chain_b
        val b2j = HashMap<Int, IntArrayList>()
        for (i in b.indices) b2j.getOrPut(b[i]) { IntArrayList() }.add(i)
        val n = b.size
        if (n >= 200) {
            val ntest = n / 100 + 1
            val popular = b2j.filterValues { it.size > ntest }.keys.toList()
            for (p in popular) b2j.remove(p)
        }
        // bjunk is always empty (isjunk = None)

        var total = 0
        val queue = ArrayDeque<IntArray>()
        queue.addLast(intArrayOf(0, a.size, 0, b.size))
        while (queue.isNotEmpty()) {
            val q = queue.removeLast() // Python list.pop() -> LIFO
            val alo = q[0]; val ahi = q[1]; val blo = q[2]; val bhi = q[3]
            val m = findLongestMatch(a, b, b2j, alo, ahi, blo, bhi)
            val i = m[0]; val j = m[1]; val k = m[2]
            if (k > 0) {
                total += k
                if (alo < i && blo < j) queue.addLast(intArrayOf(alo, i, blo, j))
                if (i + k < ahi && j + k < bhi) queue.addLast(intArrayOf(i + k, ahi, j + k, bhi))
            }
        }
        return total
    }

    private fun findLongestMatch(
        a: IntArray, b: IntArray, b2j: Map<Int, IntArrayList>,
        alo: Int, ahi: Int, blo: Int, bhi: Int
    ): IntArray {
        var besti = alo
        var bestj = blo
        var bestsize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val newj2len = HashMap<Int, Int>()
            val idx = b2j[a[i]]
            if (idx != null) {
                for (t in 0 until idx.size) {
                    val j = idx[t]
                    if (j < blo) continue
                    if (j >= bhi) break
                    val k = (j2len[j - 1] ?: 0) + 1
                    newj2len[j] = k
                    if (k > bestsize) {
                        besti = i - k + 1
                        bestj = j - k + 1
                        bestsize = k
                    }
                }
            }
            j2len = newj2len
        }
        // Extend with non-junk elements on each end (bjunk is empty, so isbjunk() is always false).
        while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
            besti--; bestj--; bestsize++
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi && a[besti + bestsize] == b[bestj + bestsize]) {
            bestsize++
        }
        return intArrayOf(besti, bestj, bestsize)
    }

    private class IntArrayList {
        private var data = IntArray(4)
        var size = 0
            private set

        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        operator fun get(i: Int): Int = data[i]
    }
}
