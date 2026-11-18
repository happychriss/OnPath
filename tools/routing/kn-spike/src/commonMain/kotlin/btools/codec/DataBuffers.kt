// Port of btools.codec.DataBuffers (BRouter 1.7.11-beta, MIT)
package btools.codec

import btools.util.BitCoderContext

/**
 * Container for some re-usable databuffers for the decoder
 */
class DataBuffers(iobuffer: ByteArray) {
  var iobuffer: ByteArray = iobuffer
  var tagbuf1 = ByteArray(256)
  var bctx1 = BitCoderContext(tagbuf1)
  var bbuf1 = ByteArray(65636)
  var ibuf1 = IntArray(4096)
  var ibuf2 = IntArray(2048)
  var ibuf3 = IntArray(2048)
  var alon = IntArray(2048)
  var alat = IntArray(2048)

  constructor() : this(ByteArray(65636))
}
