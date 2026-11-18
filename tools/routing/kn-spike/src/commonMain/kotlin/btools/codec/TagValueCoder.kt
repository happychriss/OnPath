// Port of btools.codec.TagValueCoder (BRouter 1.7.11-beta, MIT) - decoder only
package btools.codec

import btools.util.BitCoderContext

/**
 * Decoder for way-/node-descriptions
 *
 * It detects identical descriptions and sorts them
 * into a huffman-tree according to their frequencies
 *
 * Left out (rd5 writing only): the no-arg encoder constructor, encodeTagValueSet, encodeDictionary,
 * the nested class TagValueSet (with its FrequencyComparator).
 */
class TagValueCoder(bc: BitCoderContext, buffers: DataBuffers, validator: TagValueValidator?) {
  private val tree: Any?
  private val bc: BitCoderContext

  init {
    tree = decodeTree(bc, buffers, validator)
    this.bc = bc
  }

  fun decodeTagValueSet(): TagValueWrapper? {
    var node = tree
    while (node is TreeNode) {
      val nextBit = bc.decodeBit()
      node = if (nextBit) node.child2 else node.child1
    }
    return node as TagValueWrapper?
  }

  private fun decodeTree(bc: BitCoderContext, buffers: DataBuffers, validator: TagValueValidator?): Any? {
    val isNode = bc.decodeBit()
    if (isNode) {
      val node = TreeNode()
      node.child1 = decodeTree(bc, buffers, validator)
      node.child2 = decodeTree(bc, buffers, validator)
      return node
    }

    val buffer = buffers.tagbuf1
    val ctx = buffers.bctx1
    ctx.reset(buffer)

    var inum = 0
    var lastEncodedInum = 0

    var hasdata = false
    while (true) {
      val delta = bc.decodeVarBits()
      if (!hasdata) {
        if (delta == 0) {
          return null
        }
      }
      if (delta == 0) {
        ctx.encodeVarBits(0)
        break
      }
      inum += delta

      val data = bc.decodeVarBits()

      if (validator == null || validator.isLookupIdxUsed(inum)) {
        hasdata = true
        ctx.encodeVarBits(inum - lastEncodedInum)
        ctx.encodeVarBits(data)
        lastEncodedInum = inum
      }
    }

    val res: ByteArray
    val len = ctx.closeAndGetEncodedLength()
    if (validator == null) {
      res = ByteArray(len)
      buffer.copyInto(res, 0, 0, len)
    } else {
      res = validator.unify(buffer, 0, len)
    }

    val accessType = if (validator == null) 2 else validator.accessType(res)
    if (accessType > 0) {
      val w = TagValueWrapper()
      w.data = res
      w.accessType = accessType
      return w
    }
    return null
  }

  class TreeNode {
    var child1: Any? = null
    var child2: Any? = null
  }
}
