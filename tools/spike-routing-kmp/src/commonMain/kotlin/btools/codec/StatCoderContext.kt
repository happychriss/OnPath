package btools.codec

import btools.util.BitCoderContext

class StatCoderContext(ab: ByteArray) : BitCoderContext(ab) {
    private var lastbitpos: Long = 0

    companion object {
        private val noisy_bits = IntArray(1024)

        init {
            // noisybits lookup
            for (i in 0 until 1024) {
                var p = i
                var noisybits = 0
                while (p > 2) {
                    noisybits++
                    p = p shr 1
                }
                noisy_bits[i] = noisybits
            }
        }

        /**
         * Get a textual report on the bit-statistics
         */
        fun getBitReport(): String {
            // PORT-CUT bit statistics (statsPerName TreeMap): encoding statistics only
            return "<empty bit report>"
        }
    }

    /**
     * assign the de-/encoded bits since the last call assignBits to the given
     * name. Used for encoding statistics
     */
    fun assignBits(name: String) {
        // PORT-CUT bit statistics (statsPerName TreeMap): encoding statistics only
    }

    /**
     * encode an unsigned integer with some of of least significant bits
     * considered noisy
     */
    fun encodeNoisyNumber(value0: Int, noisybits: Int) {
        var value = value0
        if (value < 0) {
            throw IllegalArgumentException("encodeVarBits expects positive value")
        }
        if (noisybits > 0) {
            val mask = -1 ushr (32 - noisybits) // 0xffffffff >>> (32 - noisybits)
            encodeBounded(mask, value and mask)
            value = value shr noisybits
        }
        encodeVarBits(value)
    }

    /**
     * decode an unsigned integer with some of of least significant bits
     * considered noisy
     */
    fun decodeNoisyNumber(noisybits: Int): Int {
        val value = decodeBits(noisybits)
        return value or (decodeVarBits() shl noisybits)
    }

    /**
     * encode a signed integer with some of of least significant bits considered
     * noisy
     */
    fun encodeNoisyDiff(value0: Int, noisybits: Int) {
        var value = value0
        if (noisybits > 0) {
            value += 1 shl (noisybits - 1)
            val mask = -1 ushr (32 - noisybits) // 0xffffffff >>> (32 - noisybits)
            encodeBounded(mask, value and mask)
            value = value shr noisybits
        }
        encodeVarBits(if (value < 0) -value else value)
        if (value != 0) {
            encodeBit(value < 0)
        }
    }

    /**
     * decode a signed integer with some of of least significant bits considered
     * noisy
     */
    fun decodeNoisyDiff(noisybits: Int): Int {
        var value = 0
        if (noisybits > 0) {
            value = decodeBits(noisybits) - (1 shl (noisybits - 1))
        }
        var val2 = decodeVarBits() shl noisybits
        if (val2 != 0) {
            if (decodeBit()) {
                val2 = -val2
            }
        }
        return value + val2
    }

    /**
     * encode a signed integer with the typical range and median taken from the
     * predicted value
     */
    fun encodePredictedValue(value: Int, predictor: Int) {
        var p = if (predictor < 0) -predictor else predictor
        var noisybits = 0

        while (p > 2) {
            noisybits++
            p = p shr 1
        }
        encodeNoisyDiff(value - predictor, noisybits)
    }

    /**
     * decode a signed integer with the typical range and median taken from the
     * predicted value
     */
    fun decodePredictedValue(predictor: Int): Int {
        var p = if (predictor < 0) -predictor else predictor
        var noisybits = 0
        while (p > 1023) {
            noisybits++
            p = p shr 1
        }
        return predictor + decodeNoisyDiff(noisybits + noisy_bits[p])
    }

    /**
     * encode an integer-array making use of the fact that it is sorted. This is
     * done, starting with the most significant bit, by recursively encoding the
     * number of values with the current bit being 0. This yields an number of
     * bits per value that only depends on the typical distance between subsequent
     * values and also benefits
     *
     * @param values  the array to encode
     * @param offset  position in this array where to start
     * @param subsize number of values to encode
     * @param nextbit bitmask with the most significant bit set to 1
     * @param mask    should be 0
     */
    fun encodeSortedArray(values: IntArray, offset: Int, subsize: Int, nextbit0: Int, mask0: Int) {
        var nextbit = nextbit0
        var mask = mask0
        if (subsize == 1) { // last-choice shortcut
            while (nextbit != 0) {
                encodeBit((values[offset] and nextbit) != 0)
                nextbit = nextbit shr 1
            }
        }
        if (nextbit == 0) {
            return
        }

        val data = mask and values[offset]
        mask = mask or nextbit

        // count 0-bit-fraction
        var i = offset
        val end = subsize + offset
        while (i < end) {
            if ((values[i] and mask) != data) {
                break
            }
            i++
        }
        val size1 = i - offset
        val size2 = subsize - size1

        encodeBounded(subsize, size1)
        if (size1 > 0) {
            encodeSortedArray(values, offset, size1, nextbit shr 1, mask)
        }
        if (size2 > 0) {
            encodeSortedArray(values, i, size2, nextbit shr 1, mask)
        }
    }

    /**
     * @param values  the array to encode
     * @param offset  position in this array where to start
     * @param subsize number of values to encode
     * @param nextbit bitmask with the most significant bit set to 1
     * @param value   should be 0
     */
    fun decodeSortedArray(values: IntArray, offset0: Int, subsize0: Int, nextbitpos: Int, value0: Int) {
        var offset = offset0
        var subsize = subsize0
        var value = value0
        if (subsize == 1) { // last-choice shortcut
            if (nextbitpos >= 0) {
                value = value or decodeBitsReverse(nextbitpos + 1)
            }
            values[offset] = value
            return
        }
        if (nextbitpos < 0) {
            while (subsize-- > 0) {
                values[offset++] = value
            }
            return
        }

        val size1 = decodeBounded(subsize)
        val size2 = subsize - size1

        if (size1 > 0) {
            decodeSortedArray(values, offset, size1, nextbitpos - 1, value)
        }
        if (size2 > 0) {
            decodeSortedArray(values, offset + size1, size2, nextbitpos - 1, value or (1 shl nextbitpos))
        }
    }
}
