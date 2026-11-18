// Port of btools.expressions.BExpressionContext (BRouter 1.7.11-beta, MIT)
// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables
//
// Not ported (map-creator / regression-test only, no caller in brouter-core or brouter-mapaccess): encode(),
// encode(int[]), generateRandomValues(), assertAllVariablesEqual(), dumpStatistics(), and in addLookupValue() the
// unit conversion for "*" lookups of an external data array (throws UnsupportedOperationException instead).

package btools.expressions

import btools.util.BitCoderContext
import btools.util.Crc32
import btools.util.IByteArrayUnifier
import btools.util.LruMap
import btools.util.readFileBytes
import btools.util.fileExists

abstract class BExpressionContext : IByteArrayUnifier {

  private var context: String
  private var _inOurContext = false
  // stand-in for the BufferedReader: the whole profile text and the read position
  private var _br: String? = null
  private var _brPos = 0
  private var _readerDone = false

  var _modelClass: String? = null

  private val lookupNumbers: MutableMap<String, Int> = HashMap()
  private val lookupValues: MutableList<Array<BExpressionLookupValue>> = ArrayList()
  private val lookupNames: MutableList<String> = ArrayList()
  private val lookupHistograms: MutableList<IntArray> = ArrayList()
  private var lookupIdxUsed: BooleanArray? = null

  private var lookupDataFrozen = false

  private var lookupData = IntArray(0)

  private val ctxDecode = BitCoderContext(ByteArray(0))

  private val variableNumbers: MutableMap<String, Int> = HashMap()

  internal var lastAssignedExpression: MutableList<BExpression?>? = ArrayList()
  internal var skipConstantExpressionOptimizations = false
  internal var expressionNodeCount = 0

  // (Java: null until the profile is parsed; here an empty array plus a flag, to keep the hot paths free of null checks)
  private var variableData: FloatArray = EMPTY_FLOATS
  private var variableDataSet = false


  // hash-cache for function results
  private val probeCacheNode = CacheNode()
  private var cache: LruMap? = null

  private val probeVarSet = VarWrapper()
  private var resultVarCache: LruMap? = null

  // (Java: List<BExpression>; an array here, filled once per parse)
  private var expressionList: Array<BExpression> = EMPTY_EXPRESSIONS

  private var minWriteIdx = 0

  // build-in variable indexes for fast access
  private var buildInVariableIdx: IntArray = IntArray(0)
  private var nBuildInVars = 0

  // (Java: null before the first evaluate)
  private var currentVars: FloatArray = EMPTY_FLOATS
  private var currentVarsSet = false
  private var currentVarOffset = 0

  private var foreignContext: BExpressionContext? = null

  var noStartWays = IntArray(0)

  internal var showErrors = false // Boolean.getBoolean("showErrors")

  protected fun setInverseVars() {
    currentVarOffset = nBuildInVars
  }

  protected abstract fun getBuildInVariableNames(): Array<String>

  fun getBuildInVariable(idx: Int): Float {
    return currentVars[idx + currentVarOffset]
  }

  private var linenr = 0

  var meta: BExpressionMetaData?
  private var lookupDataValid = false

  protected constructor(context: String, meta: BExpressionMetaData?) : this(context, 4096, meta)

  /**
   * Create an Expression-Context for the given node
   *
   * @param context  global, way or node - context of that instance
   * @param hashSize size of hashmap for result caching
   */
  protected constructor(context: String, hashSize: Int, meta: BExpressionMetaData?) {
    this.context = context
    this.meta = meta

    if (meta != null) meta.registerListener(context, this)

    // (Boolean.getBoolean("disableExpressionCache") -> always false here)

    // create the expression cache
    if (hashSize > 0) {
      cache = LruMap(4 * hashSize, hashSize)
      resultVarCache = LruMap(4096, 4096)
    }
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

      while (delta-- > 1) ld[inum++] = 0

      // see encoder for value rotation
      val dd = ctx.decodeVarBits()
      var d = if (dd == 7) 1 else (if (dd < 7) dd + 2 else dd + 1)
      if (d >= lookupValues[inum].size && d < 1000) d = 1 // map out-of-range to unknown
      ld[inum++] = d
    }
    while (inum < ld.size) ld[inum++] = 0
  }

  fun getKeyValueDescription(inverseDirection: Boolean, ab: ByteArray): String {
    val sb = StringBuilder(200)
    decode(lookupData, inverseDirection, ab)
    for (inum in 0 until lookupValues.size) { // loop over lookup names
      val va = lookupValues[inum]
      val v = lookupData[inum]
      val value = if (v >= 1000) ((v - 1000) / 100f).toString() else va[v].toString()
      if (value.length > 0) {
        if (sb.length > 0) sb.append(' ')
        sb.append(lookupNames[inum] + "=" + value)
      }
    }
    return sb.toString()
  }

  fun getKeyValueList(inverseDirection: Boolean, ab: ByteArray): MutableList<String> {
    val res: MutableList<String> = ArrayList()
    decode(lookupData, inverseDirection, ab)
    for (inum in 0 until lookupValues.size) { // loop over lookup names
      val va = lookupValues[inum]
      val v = lookupData[inum]
      // no negative values
      val value = if (v >= 1000) ((v - 1000) / 100f).toString() else va[v].toString()
      if (value.length > 0) {
        res.add(lookupNames[inum])
        res.add(value)
      }
    }
    return res
  }

  fun getLookupKey(name: String): Int {
    // (Java: unboxing a null Integer throws, caught -> -1)
    return lookupNumbers[name] ?: -1
  }

  fun getLookupValue(key: Int): Float {
    var res: Float
    val v = lookupData[key]
    if (v == 0) return Float.NaN
    if (v < 900) {
      try {
        val va = lookupValues[key]
        val sval = va[v].toString()
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
    val res: Float
    decode(lookupData, inverseDirection, ab)
    val v = lookupData[key]
    if (v == 0) return Float.NaN
    res = (v - 1000) / 100f
    return res
  }

  private var parsedLines = 0
  private var fixTagsWritten = false

  fun parseMetaLine(line: String) {
    parsedLines++
    val tk = JavaStringTokenizer(line, ' ')
    var name = tk.nextToken()
    val value = tk.nextToken()
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
    while (newValue != null && tk.hasMoreTokens()) newValue.addAlias(tk.nextToken())
  }

  fun finishMetaParsing() {
    if (parsedLines == 0 && "global" != context) {
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
    val expressionList = this.expressionList
    val n = expressionList.size
    for (expidx in 0 until n) {
      expressionList[expidx].evaluate(this)
    }
  }

  private var requests = 0L
  private var requests2 = 0L
  private var cachemisses = 0L

  fun cacheStats(): String {
    return "requests=" + requests + " requests2=" + requests2 + " cachemisses=" + cachemisses
  }

  private var lastCacheNode = CacheNode()

  // @Override
  final override fun unify(ab: ByteArray, offset: Int, len: Int): ByteArray {
    probeCacheNode.ab = null // crc based cache lookup only
    probeCacheNode.hash = Crc32.crc(ab, offset, len)

    var cn = cache!!.get(probeCacheNode) as CacheNode?
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

    val cache = this.cache
    if (cache == null) {
      decode(lookupData, inverseDirection, ab)
      if (!currentVarsSet || currentVars.size != nBuildInVars) {
        currentVars = FloatArray(nBuildInVars)
        currentVarsSet = true
      }
      evaluateInto(currentVars, 0)
      currentVarOffset = 0
      return
    }

    var cn: CacheNode?
    if (lastCacheNode.ab === ab) {
      cn = lastCacheNode
    } else {
      probeCacheNode.ab = ab
      probeCacheNode.hash = Crc32.crc(ab, 0, ab.size)
      cn = cache.get(probeCacheNode) as CacheNode?
    }

    if (cn == null) {
      cachemisses++

      cn = cache.removeLru() as CacheNode?
      if (cn == null) {
        cn = CacheNode()
      }
      cn.hash = probeCacheNode.hash
      cn.ab = ab
      cache.put(cn)

      if (probeVarSet.vars == null) {
        probeVarSet.vars = FloatArray(2 * nBuildInVars)
      }
      val probeVars = probeVarSet.vars!!

      // forward direction
      decode(lookupData, false, ab)
      evaluateInto(probeVars, 0)

      // inverse direction
      lookupData[0] = 2 // inverse shortcut: reuse decoding
      evaluateInto(probeVars, nBuildInVars)

      // (java.util.Arrays.hashCode(float[]); only the bin of the result cache depends on it)
      probeVarSet.hash = probeVars.contentHashCode()

      // unify the result variable set
      val resultVarCache = this.resultVarCache!!
      var vw = resultVarCache.get(probeVarSet) as VarWrapper?
      if (vw == null) {
        vw = resultVarCache.removeLru() as VarWrapper?
        if (vw == null) {
          vw = VarWrapper()
        }
        vw.hash = probeVarSet.hash
        vw.vars = probeVarSet.vars
        probeVarSet.vars = null
        resultVarCache.put(vw)
      }
      cn.vars = vw.vars
    } else {
      if (ab === cn.ab) requests2++

      cache.touch(cn)
    }

    currentVars = cn.vars!!
    currentVarsSet = true
    currentVarOffset = if (inverseDirection) nBuildInVars else 0
  }

  private fun evaluateInto(vars: FloatArray, offset: Int) {
    evaluate()
    val variableData = this.variableData
    for (vi in 0 until nBuildInVars) {
      val idx = buildInVariableIdx[vi]
      vars[vi + offset] = if (idx == -1) 0f else variableData[idx]
    }
  }

  /**
   * @return a new lookupData array, or null if no metadata defined
   */
  fun createNewLookupData(): IntArray? {
    if (lookupDataFrozen) {
      return IntArray(lookupValues.size)
    }
    return null
  }

  fun variableName(idx: Int): String {
    for (e in variableNumbers.entries) {
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
  internal fun addLookupValue(name: String, value: String, lookupData2: IntArray?): BExpressionLookupValue? {
    var newValue: BExpressionLookupValue? = null
    var num = lookupNumbers[name]
    if (num == null) {
      if (lookupData2 != null) {
        // do not create unknown name for external data array
        return newValue
      }

      // unknown name, create
      num = lookupValues.size
      lookupNumbers[name] = num
      lookupNames.add(name)
      lookupValues.add(arrayOf(BExpressionLookupValue(""), BExpressionLookupValue("unknown")))
      lookupHistograms.add(IntArray(2))
      val ndata = IntArray(lookupData.size + 1)
      lookupData.copyInto(ndata, 0, 0, lookupData.size)
      lookupData = ndata
    }

    // look for that value
    var values = lookupValues[num]
    var histo = lookupHistograms[num]
    var i = 0
    var bFoundAsterix = false
    while (i < values.size) {
      val v = values[i]
      if (v.value == "*") bFoundAsterix = true
      if (v.matches(value)) break
      i++
    }
    if (i == values.size) {
      if (lookupData2 != null) {
        // do not create unknown value for external data array,
        // record as 'unknown' instead
        lookupData2[num] = 1 // 1 == unknown
        if (bFoundAsterix) {
          // found value for lookup *
          // (map creator only: unit conversion of free-number tags - not ported)
          throw UnsupportedOperationException("addLookupValue: value conversion for '*' lookups is not ported")
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
      val nhisto = IntArray(values.size + 1)
      histo.copyInto(nhisto, 0, 0, histo.size)
      histo = nhisto
      newValue = BExpressionLookupValue(value)
      val created: BExpressionLookupValue = newValue
      val oldValues = values
      values = Array(oldValues.size + 1) { if (it < oldValues.size) oldValues[it] else created }
      lookupHistograms[num] = histo
      lookupValues[num] = values
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
    val num = lookupNumbers[name]
    if (num == null) {
      return
    }

    // look for that value
    val nvalues = lookupValues[num].size
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
  fun addSmallestLookupValue(name: String, valueIndex: Int) {
    var valueIndex = valueIndex
    val num = lookupNumbers[name]
    if (num == null) {
      return
    }

    // look for that value
    val nvalues = lookupValues[num].size
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
    val num = lookupNumbers[name]
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
      if (buildInVariableIdx[i] == idx) {
        return i
      }
    }
    val extended = IntArray(nBuildInVars + 1)
    buildInVariableIdx.copyInto(extended, 0, 0, nBuildInVars)
    extended[nBuildInVars] = idx
    buildInVariableIdx = extended
    return nBuildInVars++
  }

  fun setForeignContext(foreignContext: BExpressionContext?) {
    this.foreignContext = foreignContext
  }

  fun getForeignVariableValue(foreignIndex: Int): Float {
    return foreignContext!!.getBuildInVariable(foreignIndex)
  }

  fun getForeignVariableIdx(context: String, name: String): Int {
    val foreignContext = this.foreignContext
    if (foreignContext == null || context != foreignContext.context) {
      throw IllegalArgumentException("unknown foreign context: " + context)
    }
    return foreignContext.getOutputVariableIndex(name, true)
  }

  /** @param file path of the profile (Java: java.io.File) */
  fun parseFile(file: String, readOnlyContext: String?) {
    parseFile(file, readOnlyContext, null)
  }

  /** @param file path of the profile (Java: java.io.File) */
  fun parseFile(file: String, readOnlyContext: String?, keyValues: Map<String, String>?) {
    if (!fileExists(file)) {
      throw IllegalArgumentException("profile " + fileName(file) + " does not exist")
    }
    try {
      if (readOnlyContext != null) {
        linenr = 1
        val realContext = context
        context = readOnlyContext
        expressionList = _parseFile(file, keyValues)
        variableData = FloatArray(variableNumbers.size)
        variableDataSet = true
        evaluate(lookupData) // lookupData is dummy here - evaluate just to create the variables
        context = realContext
      }
      linenr = 1
      minWriteIdx = if (!variableDataSet) 0 else variableData.size
      expressionList = _parseFile(file, null)
      lastAssignedExpression = null

      // determine the build-in variable indices
      val varNames = getBuildInVariableNames()
      nBuildInVars = varNames.size
      buildInVariableIdx = IntArray(nBuildInVars)
      for (vi in 0 until varNames.size) {
        buildInVariableIdx[vi] = getVariableIdx(varNames[vi], false)
      }

      val readOnlyData = variableData
      variableData = FloatArray(variableNumbers.size)
      variableDataSet = true
      for (i in 0 until minWriteIdx) {
        variableData[i] = readOnlyData[i]
      }
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException("ParseException " + fileName(file) + " at line " + linenr + ": " + e.message)
    } catch (e: Exception) {
      throw RuntimeException(e)
    }
    if (expressionList.size == 0) {
      throw IllegalArgumentException(fileName(file)
        + " does not contain expressions for context " + context + " (old version?)")
    }
  }

  private fun _parseFile(file: String, keyValues: Map<String, String>?): Array<BExpression> {
    _br = readFileBytes(file).decodeToString()
    _brPos = 0
    _readerDone = false
    val result: MutableList<BExpression> = ArrayList()

    // if injected keyValues are present, create assign expressions for them
    // (iteration order of the map only decides the variable numbering of the injected constants, not any value)
    if (keyValues != null) {
      for (key in keyValues.keys) {
        val value = keyValues[key]!!
        result.add(BExpression.createAssignExpressionFromKeyValue(this, key, value))
      }
    }

    while (true) {
      val exp = BExpression.parse(this, 0)
      if (exp == null) break
      result.add(exp)
    }
    _br = null
    return result.toTypedArray()
  }

  fun setVariableValue(name: String, value: Float, create: Boolean) {
    var num = variableNumbers[name]
    if (num != null) {
      variableData[num] = value
    } else if (create) {
      num = getVariableIdx(name, create)
      val readOnlyData = variableData
      val minWriteIdx = readOnlyData.size
      variableData = FloatArray(variableNumbers.size)
      variableDataSet = true
      for (i in 0 until minWriteIdx) {
        variableData[i] = readOnlyData[i]
      }
      variableData[num] = value
    }
  }

  fun getVariableValue(name: String, defaultValue: Float): Float {
    val num = variableNumbers[name]
    return if (num == null) defaultValue else getVariableValue(num)
  }

  internal fun getVariableValue(variableIdx: Int): Float {
    return variableData[variableIdx]
  }

  internal fun getVariableIdx(name: String, create: Boolean): Int {
    var num = variableNumbers[name]
    if (num == null) {
      if (create) {
        num = variableNumbers.size
        variableNumbers[name] = num
        lastAssignedExpression!!.add(null)
      } else {
        return -1
      }
    }
    return num
  }

  internal fun getMinWriteIdx(): Int {
    return minWriteIdx
  }

  internal fun getLookupMatch(nameIdx: Int, valueIdxArray: IntArray): Float {
    val v = lookupData[nameIdx]
    for (i in 0 until valueIdxArray.size) {
      if (v == valueIdxArray[i]) {
        return 1.0f
      }
    }
    return 0.0f
  }

  fun getLookupNameIdx(name: String): Int {
    val num = lookupNumbers[name]
    return if (num == null) -1 else num
  }

  fun markLookupIdxUsed(idx: Int) {
    lookupIdxUsed!![idx] = true
  }

  fun isLookupIdxUsed(idx: Int): Boolean {
    val lookupIdxUsed = this.lookupIdxUsed!!
    return idx < lookupIdxUsed.size && lookupIdxUsed[idx]
  }

  fun setAllTagsUsed() {
    val lookupIdxUsed = this.lookupIdxUsed!!
    for (i in 0 until lookupIdxUsed.size) {
      lookupIdxUsed[i] = true
    }
  }

  fun usedTagList(): String {
    val sb = StringBuilder()
    for (inum in 0 until lookupValues.size) {
      if (lookupIdxUsed!![inum]) {
        if (sb.length > 0) {
          sb.append(',')
        }
        sb.append(lookupNames[inum])
      }
    }
    return sb.toString()
  }

  internal fun getLookupValueIdx(nameIdx: Int, value: String): Int {
    val values = lookupValues[nameIdx]
    for (i in 0 until values.size) {
      if (values[i].value == value) return i
    }
    return -1
  }


  internal fun parseToken(): String? {
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


  private fun _parseToken(): String? {
    val sb = StringBuilder(32)
    val sbcom = StringBuilder(32)
    var inComment = false
    while (true) {
      var ic = -1
      if (!_readerDone) {
        val br = _br!!
        if (_brPos < br.length) ic = br[_brPos++].code
      }
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
          val num = variableNumbers["check_start_way"]
          if (num != null && noStartWays.size == 0 && sbcom.toString().contains("noStartWay")) {
            var v = javaTrim(sbcom.toString())
            val savar = javaSplit(v, '|')
            if (savar.size == 4) {
              v = javaTrim(savar[3].substring(savar[3].indexOf("=") + 1))
              val sa = javaSplit(v, ';')
              for (s in sa) {
                val sa2 = javaSplit(s, ',')
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
          sbcom.setLength(0)
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

  internal fun assign(variableIdx: Int, value: Float): Float {
    variableData[variableIdx] = value
    return value
  }

  internal var ld2 = IntArray(512)

  fun checkStartWay(ab: ByteArray?): Boolean {
    if (ab == null) return true
    ld2.fill(0)
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

  companion object {
    private const val CONTEXT_TAG = "---context:"
    private const val MODEL_TAG = "---model:"

    private val EMPTY_FLOATS = FloatArray(0)
    private val EMPTY_EXPRESSIONS = arrayOf<BExpression>()

    /** File.getName() */
    private fun fileName(path: String): String {
      val idx = path.lastIndexOf('/')
      return if (idx >= 0) path.substring(idx + 1) else path
    }
  }
}
