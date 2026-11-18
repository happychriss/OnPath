// Port of btools.expressions.CacheNode (BRouter 1.7.11-beta, MIT)
package btools.expressions

import btools.util.LruMapNode

class CacheNode : LruMapNode() {
  internal var ab: ByteArray? = null
  internal var vars: FloatArray? = null

  override fun hashCode(): Int {
    return hash
  }

  override fun equals(other: Any?): Boolean {
    val n = other as CacheNode
    if (hash != n.hash) {
      return false
    }
    if (ab == null) {
      return true // hack: null = crc match only
    }
    return ab.contentEquals(n.ab)
  }
}
