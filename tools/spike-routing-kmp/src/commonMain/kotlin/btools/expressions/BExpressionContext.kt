// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions

import btools.platform.File
import btools.platform.readFileText
import btools.platform.tokenize
import btools.util.BitCoderContext
import btools.util.Crc32
import btools.util.IByteArrayUnifier
import btools.util.LruMap

// java.lang.Character.isWhitespace(char): differs from Kotlin's Char.isWhitespace (no-break spaces U+00A0 / U+2007 / U+202F are NOT
// whitespace in Java, the C0 separators U+001C..U+001F are)
private fun javaIsWhitespace(c: Char): Boolean {
    val code = c.code
    if (code == 0x20) return true
    if (code in 0x09..0x0D) return true
    if (code in 0x1C..0x1F) return true
    if (code < 0x80) return false
    if (code == 0x00A0 || code == 0x2007 || code == 0x202F) return false
    return code == 0x1680 || (code in 0x2000..0x200A) || code == 0x2028 || code == 0x2029 || code == 0x205F || code == 0x3000
}

// java.lang.String.split(regex) for a literal (non-regex-special) delimiter: Java drops trailing empty strings
private fun javaSplit(s: String, delim: String): List<String> {
    val parts = s.split(delim)
    if (parts.size == 1) return parts
    var end = parts.size
    while (end > 0 && parts[end - 1].isEmpty()) end--
    return parts.subList(0, end)
}

abstract class BExpressionContext protected constructor(context: String, hashSize: Int, meta: BExpressionMetaData?) : IByteArrayUnifier {
    companion object {
        private const val CONTEXT_TAG = "---context:"
        private const val MODEL_TAG = "---model:"
    }

    private var context: String = context
    private var _inOurContext: Boolean = false
    // PORT-NOTE _br: java.io.BufferedReader replaced by the whole file text + read position (char-wise read() semantics)
    private var _text: String? = null
    private var _pos: Int = 0
    private var _readerDone: Boolean = false

    var _modelClass: String? = null

    private val lookupNumbers: HashMap<String, Int> = HashMap()
    private val lookupValues: ArrayList<Array<BExpressionLookupValue?>> = ArrayList()
    private val lookupNames: ArrayList<String> = ArrayList()
    private val lookupHistograms: ArrayList<IntArray> = ArrayList()
    private var lookupIdxUsed: BooleanArray? = null

    private var lookupDataFrozen: Boolean = false

    private var lookupData: IntArray = IntArray(0)

    private var abBuf: ByteArray = ByteArray(256)
    private var ctxEndode: BitCoderContext = BitCoderContext(abBuf)
    private var ctxDecode: BitCoderContext = BitCoderContext(ByteArray(0))

    private val variableNumbers: HashMap<String, Int> = HashMap()

    var lastAssignedExpression: ArrayList<BExpression?>? = ArrayList()
    var skipConstantExpressionOptimizations: Boolean = false
    var expressionNodeCount: Int = 0

    private var variableData: FloatArray? = null


    // hash-cache for function results
    private var probeCacheNode: CacheNode = CacheNode()
    private var cache: LruMap? = null

    private var probeVarSet: VarWrapper = VarWrapper()
    private var resultVarCache: LruMap? = null

    private var expressionList: List<BExpression>? = null

    private var minWriteIdx: Int = 0

    // build-in variable indexes for fast access
    private var buildInVariableIdx: IntArray? = null
    private var nBuildInVars: Int = 0

    private var currentVars: FloatArray? = null
    private var currentVarOffset: Int = 0

    private var foreignContext: BExpressionContext? = null

    var noStartWays: IntArray = IntArray(0)

    // PORT-NOTE Boolean.getBoolean("showErrors") system property: always false here (only used by the cut unit conversion in addLookupValue)
    var showErrors: Boolean = false

    fun setInverseVars() {
        currentVarOffset = nBuildInVars
    }

    abstract fun getBuildInVariableNames(): Array<String>

    fun getBuildInVariable(idx: Int): Float {
        return currentVars!![idx + currentVarOffset]
    }

    private var linenr: Int = 0

    var meta: BExpressionMetaData? = meta
    private var lookupDataValid: Boolean = false

    protected constructor(context: String, meta: BExpressionMetaData?) : this(context, 4096, meta)

    /**
     * encode internal lookup data to a byte array
     */
    fun encode(): ByteArray? {
        if (!lookupDataValid)
            throw IllegalArgumentException("internal error: encoding undefined data?")
        return encode(lookupData)
    }

    fun encode(ld: IntArray): ByteArray? {
        val ctx = ctxEndode
        ctx.reset()

        var skippedTags = 0
        var nonNullTags = 0

        // (skip first bit ("reversedirection") )

        // all others are generic
        for (inum in 1 until lookupValues.size) { // loop over lookup names
            val d = ld[inum]
            if (d == 0) {
                skippedTags++
                continue
            }
            ctx.encodeVarBits(skippedTags + 1)
            nonNullTags++
            skippedTags = 0

            // 0 excluded already, 1 (=unknown) we rotate up to 8
            // to have the good code space for the popular values
            val dd = if (d < 2) 7 else (if (d < 9) d - 2 else d - 1)
            ctx.encodeVarBits(dd)
        }
        ctx.encodeVarBits(0)

        if (nonNullTags == 0) return null

        val len = ctx.closeAndGetEncodedLength()
        val ab = ByteArray(len)
        abBuf.copyInto(ab, 0, 0, len)


        // crosscheck: decode and compare
        val ld2 = IntArray(lookupValues.size)
        decode(ld2, false, ab)
        for (inum in 1 until lookupValues.size) { // loop over lookup names (except reverse dir)
            if (ld2[inum] != ld[inum])
                throw RuntimeException("assertion failed encoding inum=" + inum + " val=" + ld[inum] + " " + getKeyValueDescription(false, ab))
        }

        return ab
    }


    /**
     * decode byte array to internal lookup data
     */
    fun decode(ab: ByteArray) {
        decode(lookupData, false, ab)
        lookupDataValid = true
    }


    /**
     * decode a byte-array into a lookup data array
     */
    fun decode(ld: IntArray, inverseDirection: Boolean, ab: ByteArray) {
        val ctx = ctxDecode
        ctx.reset(ab)

        // start with first bit hardwired ("reversedirection")
        ld[0] = if (inverseDirection) 2 else 0

        // all others are generic
        var inum = 1
        while (true) {
            var delta = ctx.decodeVarBits()
            if (delta == 0) break
            if (inum + delta > ld.size) break // higher minor version is o.k.

            while (delta-- > 1) {
                ld[inum++] = 0
            }

            // see encoder for value rotation
            val dd = ctx.decodeVarBits()
            var d = if (dd == 7) 1 else (if (dd < 7) dd + 2 else dd + 1)
            if (d >= lookupValues.get(inum).size && d < 1000) d = 1 // map out-of-range to unknown
            ld[inum++] = d
        }
        while (inum < ld.size) {
            ld[inum++] = 0
        }
    }

    fun getKeyValueDescription(inverseDirection: Boolean, ab: ByteArray): String {
        val sb = StringBuilder(200)
        decode(lookupData, inverseDirection, ab)
        for (inum in 0 until lookupValues.size) { // loop over lookup names
            val va = lookupValues.get(inum)
            val v = lookupData[inum]
            val value = if (v >= 1000) ((v - 1000) / 100f).toString() else va[v]!!.toString()
            if (value != null && value.length > 0) {
                if (sb.length > 0) sb.append(' ')
                sb.append(lookupNames.get(inum) + "=" + value)
            }
        }
        return sb.toString()
    }

    fun getKeyValueList(inverseDirection: Boolean, ab: ByteArray): List<String> {
        val res = ArrayList<String>()
        decode(lookupData, inverseDirection, ab)
        for (inum in 0 until lookupValues.size) { // loop over lookup names
            val va = lookupValues.get(inum)
            val v = lookupData[inum]
            // no negative values
            val value = if (v >= 1000) ((v - 1000) / 100f).toString() else va[v]!!.toString()
            if (value != null && value.length > 0) {
                res.add(lookupNames.get(inum))
                res.add(value)
            }
        }
        return res
    }

    fun getLookupKey(name: String): Int {
        var res = -1
        try {
            res = lookupNumbers.get(name)!!
        } catch (e: Exception) {
            // expected: unknown name (Java: NPE on unboxing null), result stays -1
        }
        return res
    }

    fun getLookupValue(key: Int): Float {
        var res = 0f
        val v = lookupData[key]
        if (v == 0) return Float.NaN
        if (v < 900) {
            try {
                val va = lookupValues.get(key)
                val sval = va[v]!!.toString()
                // PORT-NOTE Float.parseFloat: Java accepts "1f", "1d", "NaN", "Infinity", surrounding blanks; Kotlin/Native toFloat may differ
                res = sval.toFloat()
            } catch (e: NumberFormatException) {
                res = 0f
            }
        } else {
            res = (v - 1000) / 100f
        }
        return res
    }

    fun getLookupValue(inverseDirection: Boolean, ab: ByteArray, key: Int): Float {
        var res = 0f
        decode(lookupData, inverseDirection, ab)
        val v = lookupData[key]
        if (v == 0) return Float.NaN
        res = (v - 1000) / 100f
        return res
    }

    private var parsedLines: Int = 0
    private var fixTagsWritten: Boolean = false

    fun parseMetaLine(line: String) {
        parsedLines++
        val tk = tokenize(line, " ") // StringTokenizer(line, " ")
        var tki = 0 // PORT-NOTE nextToken() on a missing token throws NoSuchElementException in Java, IndexOutOfBoundsException here
        var name = tk[tki++]
        val value = tk[tki++]
        val idx = name.indexOf(';')
        if (idx >= 0) name = name.substring(0, idx)

        if (!fixTagsWritten) {
            fixTagsWritten = true
            if ("way" == context) addLookupValue("reversedirection", "yes", null)
            else if ("node" == context) addLookupValue("nodeaccessgranted", "yes", null)
        }
        if ("reversedirection" == name) return // this is hardcoded
        if ("nodeaccessgranted" == name) return // this is hardcoded
        val newValue = addLookupValue(name, value, null)

        // add aliases
        while (newValue != null && tki < tk.size) {
            newValue.addAlias(tk[tki++])
        }
    }

    fun finishMetaParsing() {
        if (parsedLines == 0 && !"global".equals(context)) {
            throw IllegalArgumentException("lookup table does not contain data for context " + context + " (old version?)")
        }

        // post-process metadata:
        lookupDataFrozen = true

        lookupIdxUsed = BooleanArray(lookupValues.size)
    }

    fun evaluate(lookupData2: IntArray) {
        lookupData = lookupData2
        evaluate()
    }

    private fun evaluate() {
        val n = expressionList!!.size
        for (expidx in 0 until n) {
            expressionList!!.get(expidx).evaluate(this)
        }
    }

    private var requests: Long = 0
    private var requests2: Long = 0
    private var cachemisses: Long = 0

    fun cacheStats(): String {
        return "requests=" + requests + " requests2=" + requests2 + " cachemisses=" + cachemisses
    }

    private var lastCacheNode: CacheNode = CacheNode()

    // @Override
    final override fun unify(ab: ByteArray, offset: Int, len: Int): ByteArray {
        probeCacheNode.ab = null // crc based cache lookup only
        probeCacheNode.hash = Crc32.crc(ab, offset, len)

        var cn: CacheNode? = cache!!.get(probeCacheNode) as CacheNode?
        if (cn != null) {
            val cab = cn.ab!!
            if (cab.size == len) {
                for (i in 0 until len) {
                    if (cab[i] != ab[i + offset]) {
                        cn = null
                        break
                    }
                }
                if (cn != null) {
                    lastCacheNode = cn
                    return cn.ab!!
                }
            }
        }
        val nab = ByteArray(len)
        ab.copyInto(nab, 0, offset, offset + len)
        return nab
    }


    fun evaluate(inverseDirection: Boolean, ab: ByteArray) {
        requests++
        lookupDataValid = false // this is an assertion for a nasty pifall

        if (cache == null) {
            decode(lookupData, inverseDirection, ab)
            if (currentVars == null || currentVars!!.size != nBuildInVars) {
                currentVars = FloatArray(nBuildInVars)
            }
            evaluateInto(currentVars!!, 0)
            currentVarOffset = 0
            return
        }

        var cn: CacheNode?
        if (lastCacheNode.ab === ab) {
            cn = lastCacheNode
        } else {
            probeCacheNode.ab = ab
            probeCacheNode.hash = Crc32.crc(ab, 0, ab.size)
            cn = cache!!.get(probeCacheNode) as CacheNode?
        }

        if (cn == null) {
            cachemisses++

            cn = cache!!.removeLru() as CacheNode?
            if (cn == null) {
                cn = CacheNode()
            }
            cn.hash = probeCacheNode.hash
            cn.ab = ab
            cache!!.put(cn)

            if (probeVarSet.vars == null) {
                probeVarSet.vars = FloatArray(2 * nBuildInVars)
            }

            // forward direction
            decode(lookupData, false, ab)
            evaluateInto(probeVarSet.vars!!, 0)

            // inverse direction
            lookupData[0] = 2 // inverse shortcut: reuse decoding
            evaluateInto(probeVarSet.vars!!, nBuildInVars)

            probeVarSet.hash = floatArrayHashCode(probeVarSet.vars) // Arrays.hashCode(float[])

            // unify the result variable set
            var vw: VarWrapper? = resultVarCache!!.get(probeVarSet) as VarWrapper?
            if (vw == null) {
                vw = resultVarCache!!.removeLru() as VarWrapper?
                if (vw == null) {
                    vw = VarWrapper()
                }
                vw.hash = probeVarSet.hash
                vw.vars = probeVarSet.vars
                probeVarSet.vars = null
                resultVarCache!!.put(vw)
            }
            cn.vars = vw.vars
        } else {
            if (ab === cn.ab) requests2++

            cache!!.touch(cn)
        }

        currentVars = cn!!.vars
        currentVarOffset = if (inverseDirection) nBuildInVars else 0
    }

    private fun evaluateInto(vars: FloatArray, offset: Int) {
        evaluate()
        for (vi in 0 until nBuildInVars) {
            val idx = buildInVariableIdx!![vi]
            vars[vi + offset] = if (idx == -1) 0f else variableData!![idx]
        }
    }


    // PORT-CUT dumpStatistics (lookup dump, TreeMap + System.out, debug only)

    /**
     * @return a new lookupData array, or null if no metadata defined
     */
    fun createNewLookupData(): IntArray? {
        if (lookupDataFrozen) {
            return IntArray(lookupValues.size)
        }
        return null
    }

    // PORT-CUT generateRandomValues (regression test helper)

    fun assertAllVariablesEqual(other: BExpressionContext) {
        val nv = variableData!!.size
        val nv2 = other.variableData!!.size
        if (nv != nv2) throw RuntimeException("mismatch in variable-count: " + nv + "<->" + nv2)
        for (i in 0 until nv) {
            if (variableData!![i] != other.variableData!![i]) {
                throw RuntimeException("mismatch in variable " + variableName(i) + " " + variableData!![i] + "<->" + other.variableData!![i]
                    + "\ntags = " + getKeyValueDescription(false, encode()!!))
            }
        }
    }

    fun variableName(idx: Int): String {
        for (e in variableNumbers.entries) { // PORT-NOTE hash order (values are unique, so the result does not depend on the order)
            if (e.value == idx) {
                return e.key
            }
        }
        throw RuntimeException("no variable for index" + idx)
    }

    /**
     * add a new lookup-value for the given name to the given lookupData array.
     * If no array is given (null value passed), the value is added to
     * the context-binded array. In that case, unknown names and values are
     * created dynamically.
     *
     * @return a newly created value element, if any, to optionally add aliases
     */
    fun addLookupValue(name: String, value: String, lookupData2: IntArray?): BExpressionLookupValue? {
        var newValue: BExpressionLookupValue? = null
        var num: Int? = lookupNumbers.get(name)
        if (num == null) {
            if (lookupData2 != null) {
                // do not create unknown name for external data array
                return newValue
            }

            // unknown name, create
            num = lookupValues.size
            lookupNumbers.put(name, num)
            lookupNames.add(name)
            lookupValues.add(arrayOf<BExpressionLookupValue?>(BExpressionLookupValue(""), BExpressionLookupValue("unknown")))
            lookupHistograms.add(IntArray(2))
            val ndata = IntArray(lookupData.size + 1)
            lookupData.copyInto(ndata, 0, 0, lookupData.size)
            lookupData = ndata
        }

        // look for that value
        var values = lookupValues.get(num)
        var histo = lookupHistograms.get(num)
        var i = 0
        var bFoundAsterix = false
        while (i < values.size) {
            val v = values[i]!!
            if (v.equals("*")) bFoundAsterix = true
            if (v.matches(value)) break
            i++
        }
        if (i == values.size) {
            if (lookupData2 != null) {
                // do not create unknown value for external data array,
                // record as 'unknown' instead
                lookupData2[num] = 1 // 1 == unknown
                if (bFoundAsterix) {
                    // PORT-CUT value conversion for lookups with '*' (ft / in / mph / kg ... -> number): only used when OSM tags are
                    // converted to rd5 data (map creator), never when routing on rd5 files. Needs Java's String.format("%3.1f").
                    throw UnsupportedOperationException("PORT-CUT addLookupValue unit conversion for " + name + "=" + value)
                }
                return newValue
            }

            if (i == 499) {
                // System.out.println( "value limit reached for: " + name );
            }
            if (i == 500) {
                return newValue
            }
            // unknown value, create
            val nvalues = arrayOfNulls<BExpressionLookupValue>(values.size + 1)
            val nhisto = IntArray(values.size + 1)
            values.copyInto(nvalues, 0, 0, values.size)
            histo.copyInto(nhisto, 0, 0, histo.size)
            values = nvalues
            histo = nhisto
            newValue = BExpressionLookupValue(value)
            values[i] = newValue
            lookupHistograms.set(num, histo)
            lookupValues.set(num, values)
        }

        histo[i]++

        // finally remember the actual data
        if (lookupData2 != null) lookupData2[num] = i
        else lookupData[num] = i
        return newValue
    }

    /**
     * add a value-index to to internal array
     * value-index means 0=unknown, 1=other, 2=value-x, ...
     */
    fun addLookupValue(name: String, valueIndex: Int) {
        val num = lookupNumbers.get(name)
        if (num == null) {
            return
        }

        // look for that value
        val nvalues = lookupValues.get(num).size
        if (valueIndex < 0 || valueIndex >= nvalues)
            throw IllegalArgumentException("value index out of range for name " + name + ": " + valueIndex)
        lookupData[num] = valueIndex
    }


    /**
     * special hack for yes/proposed relations:
     * add a lookup value if not yet a smaller, &gt; 1 value was added
     * add a 2=yes if the provided value is out of range
     * value-index means here 0=unknown, 1=other, 2=yes, 3=proposed
     */
    fun addSmallestLookupValue(name: String, valueIndex0: Int) {
        var valueIndex = valueIndex0
        val num = lookupNumbers.get(name)
        if (num == null) {
            return
        }

        // look for that value
        val nvalues = lookupValues.get(num).size
        val oldValueIndex = lookupData[num]
        if (oldValueIndex > 1 && oldValueIndex < valueIndex) {
            return
        }
        if (valueIndex >= nvalues) {
            valueIndex = nvalues - 1
        }
        if (valueIndex < 0)
            throw IllegalArgumentException("value index out of range for name " + name + ": " + valueIndex)
        lookupData[num] = valueIndex
    }

    fun getBooleanLookupValue(name: String): Boolean {
        val num = lookupNumbers.get(name)
        return num != null && lookupData[num] == 2
    }

    fun getOutputVariableIndex(name: String, mustExist: Boolean): Int {
        val idx = getVariableIdx(name, false)
        if (idx < 0) {
            if (mustExist) {
                throw IllegalArgumentException("unknown variable: " + name)
            }
        } else if (idx < minWriteIdx) {
            throw IllegalArgumentException("bad access to global variable: " + name)
        }
        for (i in 0 until nBuildInVars) {
            if (buildInVariableIdx!![i] == idx) {
                return i
            }
        }
        val extended = IntArray(nBuildInVars + 1)
        buildInVariableIdx!!.copyInto(extended, 0, 0, nBuildInVars)
        extended[nBuildInVars] = idx
        buildInVariableIdx = extended
        return nBuildInVars++
    }

    fun setForeignContext(foreignContext: BExpressionContext) {
        this.foreignContext = foreignContext
    }

    fun getForeignVariableValue(foreignIndex: Int): Float {
        return foreignContext!!.getBuildInVariable(foreignIndex)
    }

    fun getForeignVariableIdx(context: String, name: String): Int {
        if (foreignContext == null || context != foreignContext!!.context) {
            throw IllegalArgumentException("unknown foreign context: " + context)
        }
        return foreignContext!!.getOutputVariableIndex(name, true)
    }

    fun parseFile(file: File, readOnlyContext: String?) {
        parseFile(file, readOnlyContext, null)
    }

    fun parseFile(file: File, readOnlyContext: String?, keyValues: Map<String, String>?) {
        if (!file.exists()) {
            throw IllegalArgumentException("profile " + file.getName() + " does not exist")
        }
        try {
            if (readOnlyContext != null) {
                linenr = 1
                val realContext = context
                context = readOnlyContext
                expressionList = _parseFile(file, keyValues)
                variableData = FloatArray(variableNumbers.size)
                evaluate(lookupData) // lookupData is dummy here - evaluate just to create the variables
                context = realContext
            }
            linenr = 1
            minWriteIdx = if (variableData == null) 0 else variableData!!.size
            expressionList = _parseFile(file, null)
            lastAssignedExpression = null

            // determine the build-in variable indices
            val varNames = getBuildInVariableNames()
            nBuildInVars = varNames.size
            buildInVariableIdx = IntArray(nBuildInVars)
            for (vi in 0 until varNames.size) {
                buildInVariableIdx!![vi] = getVariableIdx(varNames[vi], false)
            }

            val readOnlyData = variableData
            variableData = FloatArray(variableNumbers.size)
            for (i in 0 until minWriteIdx) {
                variableData!![i] = readOnlyData!![i]
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("ParseException " + file.getName() + " at line " + linenr + ": " + e.message)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
        if (expressionList!!.size == 0) {
            throw IllegalArgumentException(file.getName()
                + " does not contain expressions for context " + context + " (old version?)")
        }
    }

    private fun _parseFile(file: File, keyValues: Map<String, String>?): List<BExpression> {
        _text = readFileText(file.path) // new BufferedReader(new FileReader(file))
        _pos = 0
        _readerDone = false
        val result = ArrayList<BExpression>()

        // if injected keyValues are present, create assign expressions for them
        if (keyValues != null) {
            for (key in keyValues.keys) { // PORT-NOTE hash order (the order of the injected assignments creates the variable indices; caller should pass an insertion-ordered map)
                val value = keyValues.get(key)!!
                result.add(BExpression.createAssignExpressionFromKeyValue(this, key, value))
            }
        }

        while (true) {
            val exp = BExpression.parse(this, 0)
            if (exp == null) break
            result.add(exp)
        }
        _text = null // _br.close(); _br = null
        return result
    }

    fun setVariableValue(name: String, value: Float, create: Boolean) {
        var num: Int? = variableNumbers.get(name)
        if (num != null) {
            variableData!![num] = value
        } else if (create) {
            num = getVariableIdx(name, create)
            val readOnlyData = variableData
            val minWriteIdx = readOnlyData!!.size
            variableData = FloatArray(variableNumbers.size)
            for (i in 0 until minWriteIdx) {
                variableData!![i] = readOnlyData[i]
            }
            variableData!![num] = value
        }
    }

    fun getVariableValue(name: String, defaultValue: Float): Float {
        val num = variableNumbers.get(name)
        return if (num == null) defaultValue else getVariableValue(num)
    }

    fun getVariableValue(variableIdx: Int): Float {
        return variableData!![variableIdx]
    }

    fun getVariableIdx(name: String, create: Boolean): Int {
        var num: Int? = variableNumbers.get(name)
        if (num == null) {
            if (create) {
                num = variableNumbers.size
                variableNumbers.put(name, num)
                lastAssignedExpression!!.add(null)
            } else {
                return -1
            }
        }
        return num
    }

    fun getMinWriteIdx(): Int {
        return minWriteIdx
    }

    fun getLookupMatch(nameIdx: Int, valueIdxArray: IntArray): Float {
        for (i in 0 until valueIdxArray.size) {
            if (lookupData[nameIdx] == valueIdxArray[i]) {
                return 1.0f
            }
        }
        return 0.0f
    }

    fun getLookupNameIdx(name: String): Int {
        val num = lookupNumbers.get(name)
        return if (num == null) -1 else num
    }

    fun markLookupIdxUsed(idx: Int) {
        lookupIdxUsed!![idx] = true
    }

    fun isLookupIdxUsed(idx: Int): Boolean {
        return idx < lookupIdxUsed!!.size && lookupIdxUsed!![idx]
    }

    fun setAllTagsUsed() {
        for (i in 0 until lookupIdxUsed!!.size) {
            lookupIdxUsed!![i] = true
        }
    }

    fun usedTagList(): String {
        val sb = StringBuilder()
        for (inum in 0 until lookupValues.size) {
            if (lookupIdxUsed!![inum]) {
                if (sb.length > 0) {
                    sb.append(',')
                }
                sb.append(lookupNames.get(inum))
            }
        }
        return sb.toString()
    }

    fun getLookupValueIdx(nameIdx: Int, value: String): Int {
        val values = lookupValues.get(nameIdx)
        for (i in 0 until values.size) {
            if (values[i]!!.equals(value)) return i
        }
        return -1
    }


    fun parseToken(): String? {
        while (true) {
            val token = _parseToken()
            if (token == null) return null
            if (token.startsWith(CONTEXT_TAG)) {
                _inOurContext = token.substring(CONTEXT_TAG.length) == context
            } else if (token.startsWith(MODEL_TAG)) {
                _modelClass = javaTrim(token.substring(MODEL_TAG.length))
            } else if (_inOurContext) {
                return token
            }
        }
    }


    private fun readChar(): Int { // BufferedReader.read()
        val t = _text!!
        return if (_pos < t.length) t[_pos++].code else -1
    }

    private fun _parseToken(): String? {
        val sb = StringBuilder(32)
        val sbcom = StringBuilder(32)
        var inComment = false
        while (true) {
            val ic = if (_readerDone) -1 else readChar()
            if (ic < 0) {
                if (sb.length == 0) return null
                _readerDone = true
                return sb.toString()
            }
            val c = ic.toChar()
            if (c == '\n') linenr++

            if (inComment) {
                sbcom.append(c)
                if (c == '\r' || c == '\n') inComment = false
                if (!inComment) {
                    val num = variableNumbers.get("check_start_way")
                    if (num != null && noStartWays.size == 0 && sbcom.toString().contains("noStartWay")) {
                        var vr = javaTrim(sbcom.toString())
                        val savar = javaSplit(vr, "|")
                        if (savar.size == 4) {
                            vr = javaTrim(savar[3].substring(savar[3].indexOf("=") + 1))
                            val sa = javaSplit(vr, ";")
                            for (s in sa) {
                                val sa2 = javaSplit(s, ",")
                                val name = sa2[0]
                                val value = sa2[1]
                                val nidx = getLookupNameIdx(name)
                                if (nidx == -1) break
                                val vidx = getLookupValueIdx(nidx, value)
                                val tmp = IntArray(noStartWays.size + 2)
                                if (noStartWays.size > 0) noStartWays.copyInto(tmp, 0, 0, noStartWays.size)
                                noStartWays = tmp
                                noStartWays[noStartWays.size - 2] = nidx
                                noStartWays[noStartWays.size - 1] = vidx
                            }
                        }
                    }
                    sbcom.clear() // setLength(0)
                }
                continue
            }
            if (javaIsWhitespace(c)) {
                if (sb.length > 0) return sb.toString()
                else continue
            }
            if (c == '#' && sb.length == 0) inComment = true
            else sb.append(c)
        }
    }

    fun assign(variableIdx: Int, value: Float): Float {
        variableData!![variableIdx] = value
        return value
    }

    var ld2: IntArray = IntArray(512)
    fun checkStartWay(ab: ByteArray?): Boolean {
        if (ab == null) return true
        ld2.fill(0) // Arrays.fill(ld2, 0)
        decode(ld2, false, ab)
        var i = 0
        while (i < noStartWays.size) {
            val key = noStartWays[i]
            val value = noStartWays[i + 1]
            if (ld2[key] == value) return false
            i += 2
        }
        return true
    }

    fun freeNoWays() {
        noStartWays = IntArray(0)
    }

    // The Java constructor body. It runs after all field initializers, so it sits after the last property in Kotlin, too.
    init {
        if (meta != null) meta.registerListener(context, this)

        // PORT-NOTE Boolean.getBoolean("disableExpressionCache") system property: always false here
        var hs = hashSize

        // create the expression cache
        if (hs > 0) {
            cache = LruMap(4 * hs, hs)
            resultVarCache = LruMap(4096, 4096)
        }
    }
}
