package dev.mobilelps.gnss

/** Small dense least-squares helpers; systems here have at most 5 unknowns. */
internal object LinearAlgebra {
    /**
     * Solves the weighted normal equations for [h] (rows = observations) and returns the correction and
     * the covariance (HᵀWH)⁻¹, or null when the geometry is singular.
     */
    fun weightedLeastSquares(
        h: List<DoubleArray>,
        residuals: DoubleArray,
        weights: DoubleArray,
    ): Pair<DoubleArray, Array<DoubleArray>>? {
        val n = h.first().size
        val normal = Array(n) { DoubleArray(n) }
        val rhs = DoubleArray(n)
        for (row in h.indices) {
            for (i in 0 until n) {
                rhs[i] += h[row][i] * weights[row] * residuals[row]
                for (j in 0 until n) normal[i][j] += h[row][i] * weights[row] * h[row][j]
            }
        }
        val inverse = invert(normal) ?: return null
        val correction = DoubleArray(n) { i -> (0 until n).sumOf { j -> inverse[i][j] * rhs[j] } }
        return correction to inverse
    }

    /** Gauss-Jordan inversion with partial pivoting. */
    fun invert(matrix: Array<DoubleArray>): Array<DoubleArray>? {
        val n = matrix.size
        val a = Array(n) { i -> matrix[i].copyOf() }
        val inv = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { kotlin.math.abs(a[it][col]) }
            if (kotlin.math.abs(a[pivot][col]) < SINGULAR_THRESHOLD) return null
            a[col] = a[pivot].also { a[pivot] = a[col] }
            inv[col] = inv[pivot].also { inv[pivot] = inv[col] }
            val scale = a[col][col]
            for (j in 0 until n) {
                a[col][j] /= scale
                inv[col][j] /= scale
            }
            for (row in 0 until n) {
                if (row == col) continue
                val factor = a[row][col]
                for (j in 0 until n) {
                    a[row][j] -= factor * a[col][j]
                    inv[row][j] -= factor * inv[col][j]
                }
            }
        }
        return inv
    }

    private const val SINGULAR_THRESHOLD = 1e-12
}
