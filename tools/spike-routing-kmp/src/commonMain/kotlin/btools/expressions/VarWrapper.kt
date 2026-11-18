package btools.expressions

import btools.util.LruMapNode

class VarWrapper : LruMapNode() {
    var vars: FloatArray? = null

    override fun hashCode(): Int {
        return hash
    }

    override fun equals(other: Any?): Boolean {
        val n = other as VarWrapper
        if (hash != n.hash) {
            return false
        }
        return floatArrayEquals(vars, n.vars)
    }
}

// java.util.Arrays.equals(float[], float[]): elements compared by Float.floatToIntBits
fun floatArrayEquals(a: FloatArray?, b: FloatArray?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    if (a.size != b.size) return false
    for (i in 0 until a.size) {
        if (a[i].toBits() != b[i].toBits()) return false
    }
    return true
}

// java.util.Arrays.hashCode(float[])
fun floatArrayHashCode(a: FloatArray?): Int {
    if (a == null) return 0
    var result = 1
    for (element in a) {
        result = 31 * result + element.toBits()
    }
    return result
}
