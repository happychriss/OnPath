// Port of btools.expressions.BExpressionContextNode (BRouter 1.7.11-beta, MIT)
// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions

class BExpressionContextNode : BExpressionContext {

  override fun getBuildInVariableNames(): Array<String> {
    return buildInVariables
  }

  fun getInitialcost(): Float {
    return getBuildInVariable(0)
  }

  constructor(meta: BExpressionMetaData?) : super("node", meta)

  /**
   * Create an Expression-Context for way context
   *
   * @param hashSize size of hashmap for result caching
   */
  constructor(hashSize: Int, meta: BExpressionMetaData?) : super("node", hashSize, meta)

  companion object {
    private val buildInVariables = arrayOf("initialcost")
  }
}
