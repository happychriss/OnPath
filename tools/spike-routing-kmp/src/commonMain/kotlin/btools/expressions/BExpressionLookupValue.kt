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

class BExpressionLookupValue(var value: String) {
    var aliases: ArrayList<String>? = null

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

    // PORT-NOTE Java overrides equals only (identity hashCode); Kotlin wants both. The class is never used as a hash key.
    override fun hashCode(): Int = value.hashCode()

    fun matches(s: String): Boolean {
        if (value == s) return true
        if (aliases != null) {
            for (alias in aliases!!) {
                if (alias == s) return true
            }
        }
        return false
    }
}
