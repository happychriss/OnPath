// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions

import btools.platform.File
import btools.platform.readFileLines

// java.lang.String.trim(): strips every char <= ' ' (Kotlin's trim() strips isWhitespace chars, which differs)
fun javaTrim(s: String): String = s.trim { it <= ' ' }

class BExpressionMetaData {
    companion object {
        private const val CONTEXT_TAG = "---context:"
        private const val VERSION_TAG = "---lookupversion:"
        private const val MINOR_VERSION_TAG = "---minorversion:"
        private const val VARLENGTH_TAG = "---readvarlength"
        private const val MIN_APP_VERSION_TAG = "---minappversion:"
    }

    var lookupVersion: Short = -1
    var lookupMinorVersion: Short = -1
    var minAppVersion: Short = -1

    private val listeners: HashMap<String, BExpressionContext> = HashMap()

    fun registerListener(context: String, ctx: BExpressionContext) {
        listeners.put(context, ctx)
    }

    fun readMetaData(lookupsFile: File) {
        try {
            var ctx: BExpressionContext? = null

            // BufferedReader.readLine loop -> readFileLines
            for (rawLine in readFileLines(lookupsFile.path)) {
                var line = rawLine
                line = javaTrim(line)
                if (line.length == 0 || line.startsWith("#")) continue
                if (line.startsWith(CONTEXT_TAG)) {
                    ctx = listeners.get(line.substring(CONTEXT_TAG.length))
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

            for (c in listeners.values) { // PORT-NOTE hash order (finishMetaParsing of each context is independent of the order)
                c.finishMetaParsing()
            }

        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
