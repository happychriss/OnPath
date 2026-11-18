// Port of btools.expressions.BExpressionLookupValue (BRouter 1.7.11-beta, MIT)
/**
 * A lookup value with optional aliases
 *
 * toString just gives the primary value,
 * equals just compares against primary value
 * matches() also compares aliases
 *
 * @author ab
 */
package btools.expressions

internal class BExpressionLookupValue(var value: String) {
  var aliases: MutableList<String>? = null

  override fun toString(): String {
    return value
  }

  fun addAlias(alias: String) {
    if (aliases == null) aliases = ArrayList()
    aliases!!.add(alias)
  }

  override fun equals(other: Any?): Boolean {
    if (other is String) {
      return value == other
    }
    if (other is BExpressionLookupValue) {
      return value == other.value
    }
    return false
  }

  // (Java does not override hashCode either; identity hash is never used for these values)
  override fun hashCode(): Int {
    return super.hashCode()
  }

  fun matches(s: String?): Boolean {
    if (value == s) return true
    val aliases = this.aliases
    if (aliases != null) {
      for (i in 0 until aliases.size) {
        if (aliases[i] == s) return true
      }
    }
    return false
  }
}
