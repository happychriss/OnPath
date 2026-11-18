package btools.expressions

import btools.platform.tokenize

class BExpression {
    companion object {
        private const val OR_EXP = 10
        private const val AND_EXP = 11
        private const val NOT_EXP = 12

        private const val ADD_EXP = 20
        private const val MULTIPLY_EXP = 21
        private const val DIVIDE_EXP = 22
        private const val MAX_EXP = 23
        private const val EQUAL_EXP = 24
        private const val GREATER_EXP = 25
        private const val MIN_EXP = 26

        private const val SUB_EXP = 27
        private const val LESSER_EXP = 28
        private const val XOR_EXP = 29

        private const val SWITCH_EXP = 30
        private const val ASSIGN_EXP = 31
        private const val LOOKUP_EXP = 32
        private const val NUMBER_EXP = 33
        private const val VARIABLE_EXP = 34
        private const val FOREIGN_VARIABLE_EXP = 35
        private const val VARIABLE_GET_EXP = 36

        // Parse the expression and all subexpression
        fun parse(ctx: BExpressionContext, level: Int): BExpression? {
            return parse(ctx, level, null)
        }

        private fun parse(ctx: BExpressionContext, level: Int, optionalToken: String?): BExpression? {
            var e: BExpression? = parseRaw(ctx, level, optionalToken)
            if (e == null) {
                return null
            }

            if (ASSIGN_EXP == e.typ) {
                // manage assined an injected values
                val assignedBefore = ctx.lastAssignedExpression!!.get(e.variableIdx)
                if (assignedBefore != null && assignedBefore.doNotChange) {
                    e.op1 = assignedBefore // was injected as key-value
                    e.op1!!.doNotChange = false // protect just once, can be changed in second assignement
                }
                ctx.lastAssignedExpression!!.set(e.variableIdx, e.op1)
            } else if (!ctx.skipConstantExpressionOptimizations) {
                // try to simplify the expression
                if (VARIABLE_EXP == e.typ) {
                    val ae = ctx.lastAssignedExpression!!.get(e.variableIdx)
                    if (ae != null && ae.typ == NUMBER_EXP) {
                        e = ae
                    }
                } else {
                    val eCollapsed = e.tryCollapse()
                    if (e !== eCollapsed) {
                        e = eCollapsed // allow breakpoint..
                    }
                    val eEvaluated = e.tryEvaluateConstant()
                    if (e !== eEvaluated) {
                        e = eEvaluated // allow breakpoint..
                    }
                }
            }
            if (level == 0) {
                // mark the used lookups after the
                // expression is collapsed to not mark
                // lookups as used that appear in the profile
                // but are de-activated by constant expressions
                val nodeCount = e!!.markLookupIdxUsed(ctx)
                ctx.expressionNodeCount += nodeCount
            }
            return e
        }

        private fun parseRaw(ctx: BExpressionContext, level: Int, optionalToken: String?): BExpression? {

            var brackets = false
            var oper: String? = ctx.parseToken()
            if (optionalToken != null && optionalToken == oper) {
                oper = ctx.parseToken()
            }
            if ("(" == oper) {
                brackets = true
                oper = ctx.parseToken()
            }

            if (oper == null) {
                if (level == 0) return null
                else throw IllegalArgumentException("unexpected end of file")
            }

            if (level == 0) {
                if ("assign" != oper) {
                    throw IllegalArgumentException("operator " + oper + " is invalid on toplevel (only 'assign' allowed)")
                }
            }

            val exp = BExpression()
            var nops = 3

            var ifThenElse = false

            if ("switch" == oper) {
                exp.typ = SWITCH_EXP
            } else if ("if" == oper) {
                exp.typ = SWITCH_EXP
                ifThenElse = true
            } else {
                nops = 2 // check binary expressions

                if ("or" == oper) {
                    exp.typ = OR_EXP
                } else if ("and" == oper) {
                    exp.typ = AND_EXP
                } else if ("multiply" == oper) {
                    exp.typ = MULTIPLY_EXP
                } else if ("divide" == oper) {
                    exp.typ = DIVIDE_EXP
                } else if ("add" == oper) {
                    exp.typ = ADD_EXP
                } else if ("max" == oper) {
                    exp.typ = MAX_EXP
                } else if ("min" == oper) {
                    exp.typ = MIN_EXP
                } else if ("equal" == oper) {
                    exp.typ = EQUAL_EXP
                } else if ("greater" == oper) {
                    exp.typ = GREATER_EXP
                } else if ("sub" == oper) {
                    exp.typ = SUB_EXP
                } else if ("lesser" == oper) {
                    exp.typ = LESSER_EXP
                } else if ("xor" == oper) {
                    exp.typ = XOR_EXP
                } else {
                    nops = 1 // check unary expressions
                    if ("assign" == oper) {
                        if (level > 0) throw IllegalArgumentException("assign operator within expression")
                        exp.typ = ASSIGN_EXP
                        val variable = ctx.parseToken()
                        if (variable == null) throw IllegalArgumentException("unexpected end of file")
                        if (variable.indexOf('=') >= 0)
                            throw IllegalArgumentException("variable name cannot contain '=': " + variable)
                        if (variable.indexOf(':') >= 0)
                            throw IllegalArgumentException("cannot assign context-prefixed variable: " + variable)
                        exp.variableIdx = ctx.getVariableIdx(variable, true)
                        if (exp.variableIdx < ctx.getMinWriteIdx())
                            throw IllegalArgumentException("cannot assign to readonly variable " + variable)
                    } else if ("not" == oper) {
                        exp.typ = NOT_EXP
                    } else {
                        nops = 0 // check elemantary expressions
                        var idx = oper.indexOf('=')
                        if (idx >= 0) {
                            exp.typ = LOOKUP_EXP
                            val name = oper.substring(0, idx)
                            val values = oper.substring(idx + 1)

                            exp.lookupNameIdx = ctx.getLookupNameIdx(name)
                            if (exp.lookupNameIdx < 0) {
                                throw IllegalArgumentException("unknown lookup name: " + name)
                            }
                            val tk = tokenize(values, "|") // StringTokenizer(values, "|")
                            val nt = tk.size
                            val nt2 = if (nt == 0) 1 else nt
                            exp.lookupValueIdxArray = IntArray(nt2)
                            for (ti in 0 until nt2) {
                                val value = if (ti < nt) tk[ti] else ""
                                exp.lookupValueIdxArray!![ti] = ctx.getLookupValueIdx(exp.lookupNameIdx, value)
                                if (exp.lookupValueIdxArray!![ti] < 0) {
                                    throw IllegalArgumentException("unknown lookup value: " + value)
                                }
                            }
                        } else {
                            idx = oper.indexOf(':')
                            if (idx >= 0) {
                                /*
                                use of variable values
                                assign no_height
                                   switch and not      maxheight=
                                            lesser v:maxheight  my_height  true
                                false
                                 */
                                if (oper.startsWith("v:")) {
                                    val name = oper.substring(2)
                                    exp.typ = VARIABLE_GET_EXP
                                    exp.lookupNameIdx = ctx.getLookupNameIdx(name)
                                } else {
                                    val context = oper.substring(0, idx)
                                    val varname = oper.substring(idx + 1)
                                    exp.typ = FOREIGN_VARIABLE_EXP
                                    exp.variableIdx = ctx.getForeignVariableIdx(context, varname)
                                }
                            } else {
                                idx = ctx.getVariableIdx(oper, false)
                                if (idx >= 0) {
                                    exp.typ = VARIABLE_EXP
                                    exp.variableIdx = idx
                                } else if ("true" == oper) {
                                    exp.numberValue = 1f
                                    exp.typ = NUMBER_EXP
                                } else if ("false" == oper) {
                                    exp.numberValue = 0f
                                    exp.typ = NUMBER_EXP
                                } else {
                                    try {
                                        // PORT-NOTE Float.parseFloat: Java accepts "1f", "1.f", "1d", "NaN", "Infinity", surrounding blanks; Kotlin/Native toFloat may differ
                                        exp.numberValue = oper.toFloat()
                                        exp.typ = NUMBER_EXP
                                    } catch (nfe: NumberFormatException) {
                                        throw IllegalArgumentException("unknown expression: " + oper)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // parse operands
            if (nops > 0) {
                exp.op1 = parse(ctx, level + 1, if (exp.typ == ASSIGN_EXP) "=" else null)
            }
            if (nops > 1) {
                if (ifThenElse) checkExpectedToken(ctx, "then")
                exp.op2 = parse(ctx, level + 1, null)
            }
            if (nops > 2) {
                if (ifThenElse) checkExpectedToken(ctx, "else")
                exp.op3 = parse(ctx, level + 1, null)
            }
            if (brackets) {
                checkExpectedToken(ctx, ")")
            }
            return exp
        }

        private fun checkExpectedToken(ctx: BExpressionContext, expected: String) {
            val token = ctx.parseToken()
            if (expected != token) {
                throw IllegalArgumentException("unexpected token: " + token + ", expected: " + expected)
            }
        }

        fun createAssignExpressionFromKeyValue(ctx: BExpressionContext, key: String, value: String): BExpression {
            val e = BExpression()
            e.typ = ASSIGN_EXP
            e.variableIdx = ctx.getVariableIdx(key, true)
            e.op1 = BExpression()
            e.op1!!.typ = NUMBER_EXP
            e.op1!!.numberValue = value.toFloat() // Float.parseFloat
            e.op1!!.doNotChange = true
            ctx.lastAssignedExpression!!.set(e.variableIdx, e.op1)
            return e
        }
    }

    private var typ: Int = 0
    private var op1: BExpression? = null
    private var op2: BExpression? = null
    private var op3: BExpression? = null
    private var numberValue: Float = 0f
    private var variableIdx: Int = 0
    private var lookupNameIdx: Int = -1
    private var lookupValueIdxArray: IntArray? = null
    private var doNotChange: Boolean = false

    private fun markLookupIdxUsed(ctx: BExpressionContext): Int {
        var nodeCount = 1
        if (lookupNameIdx >= 0) {
            ctx.markLookupIdxUsed(lookupNameIdx)
        }
        if (op1 != null) {
            nodeCount += op1!!.markLookupIdxUsed(ctx)
        }
        if (op2 != null) {
            nodeCount += op2!!.markLookupIdxUsed(ctx)
        }
        if (op3 != null) {
            nodeCount += op3!!.markLookupIdxUsed(ctx)
        }
        return nodeCount
    }

    // Evaluate the expression
    fun evaluate(ctx: BExpressionContext?): Float {
        when (typ) {
            OR_EXP ->
                return if (op1!!.evaluate(ctx) != 0f) 1f else (if (op2!!.evaluate(ctx) != 0f) 1f else 0f)
            XOR_EXP ->
                return (if ((op1!!.evaluate(ctx) != 0f) xor (op2!!.evaluate(ctx) != 0f)) 1f else 0f)
            AND_EXP ->
                return if (op1!!.evaluate(ctx) != 0f) (if (op2!!.evaluate(ctx) != 0f) 1f else 0f) else 0f
            ADD_EXP ->
                return op1!!.evaluate(ctx) + op2!!.evaluate(ctx)
            SUB_EXP ->
                return op1!!.evaluate(ctx) - op2!!.evaluate(ctx)
            MULTIPLY_EXP ->
                return op1!!.evaluate(ctx) * op2!!.evaluate(ctx)
            DIVIDE_EXP ->
                return divide(op1!!.evaluate(ctx), op2!!.evaluate(ctx))
            MAX_EXP ->
                return max(op1!!.evaluate(ctx), op2!!.evaluate(ctx))
            MIN_EXP ->
                return min(op1!!.evaluate(ctx), op2!!.evaluate(ctx))
            EQUAL_EXP ->
                return if (op1!!.evaluate(ctx) == op2!!.evaluate(ctx)) 1f else 0f
            GREATER_EXP ->
                return if (op1!!.evaluate(ctx) > op2!!.evaluate(ctx)) 1f else 0f
            LESSER_EXP ->
                return if (op1!!.evaluate(ctx) < op2!!.evaluate(ctx)) 1f else 0f
            SWITCH_EXP ->
                return if (op1!!.evaluate(ctx) != 0f) op2!!.evaluate(ctx) else op3!!.evaluate(ctx)
            ASSIGN_EXP ->
                return ctx!!.assign(variableIdx, op1!!.evaluate(ctx))
            LOOKUP_EXP ->
                return ctx!!.getLookupMatch(lookupNameIdx, lookupValueIdxArray!!)
            NUMBER_EXP ->
                return numberValue
            VARIABLE_EXP ->
                return ctx!!.getVariableValue(variableIdx)
            FOREIGN_VARIABLE_EXP ->
                return ctx!!.getForeignVariableValue(variableIdx)
            VARIABLE_GET_EXP ->
                return ctx!!.getLookupValue(lookupNameIdx)
            NOT_EXP ->
                return if (op1!!.evaluate(ctx) == 0f) 1f else 0f
            else ->
                throw IllegalArgumentException("unknown op-code: " + typ)
        }
    }

    // Try to collapse the expression
    // if logically possible
    private fun tryCollapse(): BExpression {
        when (typ) {
            OR_EXP ->
                return if (NUMBER_EXP == op1!!.typ)
                    (if (op1!!.numberValue != 0f) op1!! else op2!!)
                else (if (NUMBER_EXP == op2!!.typ)
                    (if (op2!!.numberValue != 0f) op2!! else op1!!)
                else this)
            AND_EXP ->
                return if (NUMBER_EXP == op1!!.typ)
                    (if (op1!!.numberValue == 0f) op1!! else op2!!)
                else (if (NUMBER_EXP == op2!!.typ)
                    (if (op2!!.numberValue == 0f) op2!! else op1!!)
                else this)
            ADD_EXP ->
                return if (NUMBER_EXP == op1!!.typ)
                    (if (op1!!.numberValue == 0f) op2!! else this)
                else (if (NUMBER_EXP == op2!!.typ)
                    (if (op2!!.numberValue == 0f) op1!! else this)
                else this)
            SWITCH_EXP ->
                return if (NUMBER_EXP == op1!!.typ)
                    (if (op1!!.numberValue == 0f) op3!! else op2!!) else this
            else ->
                return this
        }
    }

    // Try to evaluate the expression
    // if all operands are constant
    private fun tryEvaluateConstant(): BExpression {
        if (op1 != null && NUMBER_EXP == op1!!.typ
            && (op2 == null || NUMBER_EXP == op2!!.typ)
            && (op3 == null || NUMBER_EXP == op3!!.typ)) {
            val exp = BExpression()
            exp.typ = NUMBER_EXP
            exp.numberValue = evaluate(null)
            return exp
        }
        return this
    }

    private fun max(v1: Float, v2: Float): Float {
        return if (v1 > v2) v1 else v2
    }

    private fun min(v1: Float, v2: Float): Float {
        return if (v1 < v2) v1 else v2
    }

    private fun divide(v1: Float, v2: Float): Float {
        if (v2 == 0f) throw IllegalArgumentException("div by zero")
        return v1 / v2
    }

    override fun toString(): String {
        if (typ == NUMBER_EXP) {
            return "" + numberValue
        }
        if (typ == VARIABLE_EXP) {
            return "vidx=" + variableIdx
        }
        val sb = StringBuilder("typ=" + typ + " ops=(")
        addOp(sb, op1)
        addOp(sb, op2)
        addOp(sb, op3)
        sb.append(')')
        return sb.toString()
    }

    private fun addOp(sb: StringBuilder, e: BExpression?) {
        if (e != null) {
            sb.append('[').append(e.toString()).append(']')
        }
    }
}
