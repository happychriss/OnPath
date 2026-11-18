// Port of btools.expressions.VarWrapper (BRouter 1.7.11-beta, MIT)
package btools.expressions

import btools.util.LruMapNode

class VarWrapper : LruMapNode() {
  internal var vars: FloatArray? = null

  override fun hashCode(): Int {
    return hash
  }

  override fun equals(other: Any?): Boolean {
    val n = other as VarWrapper
    if (hash != n.hash) {
      return false
    }
    // like java.util.Arrays.equals(float[], float[]): element-wise by bit pattern (NaN equals NaN, 0.0 != -0.0)
    return vars.contentEquals(n.vars)
  }
}
