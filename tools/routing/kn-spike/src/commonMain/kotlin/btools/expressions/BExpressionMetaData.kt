// Port of btools.expressions.BExpressionMetaData (BRouter 1.7.11-beta, MIT)
// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions

import btools.util.LineReader

class BExpressionMetaData {
  var lookupVersion: Short = -1
  var lookupMinorVersion: Short = -1
  var minAppVersion: Short = -1

  private val listeners: MutableMap<String, BExpressionContext> = HashMap()

  fun registerListener(context: String, ctx: BExpressionContext) {
    listeners[context] = ctx
  }

  /** @param lookupsFile path of lookups.dat (Java: java.io.File) */
  fun readMetaData(lookupsFile: String) {
    try {
      val br = LineReader.ofFile(lookupsFile)

      var ctx: BExpressionContext? = null

      while (true) {
        var line = br.readLine()
        if (line == null) break
        line = javaTrim(line)
        if (line.length == 0 || line.startsWith("#")) continue
        if (line.startsWith(CONTEXT_TAG)) {
          ctx = listeners[line.substring(CONTEXT_TAG.length)]
          continue
        }
        if (line.startsWith(VERSION_TAG)) {
          lookupVersion = line.substring(VERSION_TAG.length).toShort()
          continue
        }
        if (line.startsWith(MINOR_VERSION_TAG)) {
          lookupMinorVersion = line.substring(MINOR_VERSION_TAG.length).toShort()
          continue
        }
        if (line.startsWith(MIN_APP_VERSION_TAG)) {
          minAppVersion = line.substring(MIN_APP_VERSION_TAG.length).toShort()
          continue
        }
        if (line.startsWith(VARLENGTH_TAG)) { // tag removed...
          continue
        }
        if (ctx != null) ctx.parseMetaLine(line)
      }

      // (order of the listeners does not matter: finishMetaParsing only touches its own context)
      for (c in listeners.values) {
        c.finishMetaParsing()
      }
    } catch (e: Exception) {
      throw RuntimeException(e)
    }
  }

  companion object {
    private const val CONTEXT_TAG = "---context:"
    private const val VERSION_TAG = "---lookupversion:"
    private const val MINOR_VERSION_TAG = "---minorversion:"
    private const val VARLENGTH_TAG = "---readvarlength"
    private const val MIN_APP_VERSION_TAG = "---minappversion:"
  }
}
