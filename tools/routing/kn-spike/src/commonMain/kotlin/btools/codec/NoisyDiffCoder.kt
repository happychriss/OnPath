// Port of btools.codec.NoisyDiffCoder (BRouter 1.7.11-beta, MIT) - decoder only
package btools.codec

/**
 * Decoder for signed integers that automatically detects the typical
 * range of these numbers to determine a noisy-bit count as a very simple
 * dictionary
 *
 * Left out (rd5 writing only): the no-arg encoder constructor, encodeSignedValue, encodeDictionary, count.
 */
class NoisyDiffCoder(bc: StatCoderContext) {
  /** Create a decoder and read the noisy-bit count from the given context */
  private val noisybits: Int = bc.decodeVarBits()
  private val bc: StatCoderContext = bc

  /**
   * decodes a signed int
   */
  fun decodeSignedValue(): Int {
    return bc.decodeNoisyDiff(noisybits)
  }
}
