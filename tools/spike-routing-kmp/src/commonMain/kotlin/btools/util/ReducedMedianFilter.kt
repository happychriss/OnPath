package btools.util

/**
 * a median filter with additional edge reduction
 */
class ReducedMedianFilter(size: Int) {
    private var nsamples: Int = 0
    private var weights: DoubleArray = DoubleArray(size)
    private var values: IntArray = IntArray(size)

    fun reset() {
        nsamples = 0
    }

    fun addSample(weight: Double, value: Int) {
        if (weight > 0.0) {
            for (i in 0 until nsamples) {
                if (values[i] == value) {
                    weights[i] += weight
                    return
                }
            }
            weights[nsamples] = weight
            values[nsamples] = value
            nsamples++
        }
    }

    fun calcEdgeReducedMedian(fraction: Double): Double {
        removeEdgeWeight((1.0 - fraction) / 2.0, true)
        removeEdgeWeight((1.0 - fraction) / 2.0, false)

        var totalWeight = 0.0
        var totalValue = 0.0
        for (i in 0 until nsamples) {
            val w = weights[i]
            totalWeight += w
            totalValue += w * values[i]
        }
        return totalValue / totalWeight
    }


    private fun removeEdgeWeight(excessWeight0: Double, high: Boolean) {
        var excessWeight = excessWeight0
        while (excessWeight > 0.0) {
            // first pass to find minmax value
            var totalWeight = 0.0
            var minmax = 0
            for (i in 0 until nsamples) {
                val w = weights[i]
                if (w > 0.0) {
                    val v = values[i]
                    if (totalWeight == 0.0 || (if (high) v > minmax else v < minmax)) {
                        minmax = v
                    }
                    totalWeight += w
                }
            }

            if (totalWeight < excessWeight)
                throw IllegalArgumentException("ups, not enough weight to remove")

            // second pass to remove
            for (i in 0 until nsamples) {
                if (values[i] == minmax && weights[i] > 0.0) {
                    if (excessWeight > weights[i]) {
                        excessWeight -= weights[i]
                        weights[i] = 0.0
                    } else {
                        weights[i] -= excessWeight
                        excessWeight = 0.0
                    }
                }
            }
        }
    }
}
