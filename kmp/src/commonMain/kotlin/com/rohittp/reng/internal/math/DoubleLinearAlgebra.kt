package com.rohittp.reng.internal.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Below this determinant magnitude a matrix is treated as singular: [DoubleMatrix3.inverse] and
 * [DoubleMatrix4.inverseAffine] return `null` rather than divide by something too close to zero to
 * trust, which would otherwise surface as a matrix of `Infinity`/`NaN` far downstream of the actual
 * fault. */
private const val SINGULAR_DETERMINANT_THRESHOLD: Double = 1e-12

internal data class DoubleVector3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: DoubleVector3): DoubleVector3 =
        DoubleVector3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: DoubleVector3): DoubleVector3 =
        DoubleVector3(x - other.x, y - other.y, z - other.z)

    operator fun unaryMinus(): DoubleVector3 = DoubleVector3(-x, -y, -z)

    operator fun times(scalar: Double): DoubleVector3 = DoubleVector3(x * scalar, y * scalar, z * scalar)

    fun dot(other: DoubleVector3): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: DoubleVector3): DoubleVector3 =
        DoubleVector3(
            x = y * other.z - z * other.y,
            y = z * other.x - x * other.z,
            z = x * other.y - y * other.x,
        )
}

internal operator fun Double.times(vector: DoubleVector3): DoubleVector3 = vector * this

/**
 * A 3 by 3 matrix of `Double`s, stored column-major in a [DoubleArray] so that no element is boxed and no
 * operation allocates a `List`. Every operation performs the same floating-point operations in the same
 * order as the `List<Double>` version it replaced, so every result is bit-identical;
 * `DoubleLinearAlgebraOracleTest` holds it to that version, which is kept in test as the oracle.
 */
internal class DoubleMatrix3 private constructor(private val values: DoubleArray) {
    internal constructor(valuesInColumnMajorOrder: List<Double>) : this(
        valuesInColumnMajorOrder.toDoubleArray().also {
            require(it.size == ELEMENT_COUNT) { "a 3 by 3 matrix requires nine values" }
        },
    )

    operator fun get(row: Int, column: Int): Double = values[index(row, column)]

    fun column(column: Int): DoubleVector3 =
        DoubleVector3(this[0, column], this[1, column], this[2, column])

    fun transpose(): DoubleMatrix3 {
        val v = values
        return DoubleMatrix3(doubleArrayOf(v[0], v[3], v[6], v[1], v[4], v[7], v[2], v[5], v[8]))
    }

    /** The matrix inverse, by adjugate over determinant, or `null` when `|det|` is below
     * [SINGULAR_DETERMINANT_THRESHOLD] -- a caller turns that `null` into a typed failure rather
     * than uploading a matrix built from a near-zero divisor. */
    fun inverse(): DoubleMatrix3? {
        val v = values
        val a = v[0]
        val b = v[3]
        val c = v[6]
        val d = v[1]
        val e = v[4]
        val f = v[7]
        val g = v[2]
        val h = v[5]
        val i = v[8]

        val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(determinant) < SINGULAR_DETERMINANT_THRESHOLD) return null

        val inverseDeterminant = 1.0 / determinant
        return rowMajor(
            (e * i - f * h) * inverseDeterminant,
            (c * h - b * i) * inverseDeterminant,
            (b * f - c * e) * inverseDeterminant,
            (f * g - d * i) * inverseDeterminant,
            (a * i - c * g) * inverseDeterminant,
            (c * d - a * f) * inverseDeterminant,
            (d * h - e * g) * inverseDeterminant,
            (b * g - a * h) * inverseDeterminant,
            (a * e - b * d) * inverseDeterminant,
        )
    }

    operator fun times(other: DoubleMatrix3): DoubleMatrix3 {
        val a = values
        val b = other.values
        val product = DoubleArray(ELEMENT_COUNT)
        for (index in 0 until ELEMENT_COUNT) {
            val row = index % DIMENSION
            val firstOfColumn = (index / DIMENSION) * DIMENSION
            product[index] = a[row] * b[firstOfColumn] +
                a[DIMENSION + row] * b[firstOfColumn + 1] +
                a[2 * DIMENSION + row] * b[firstOfColumn + 2]
        }
        return DoubleMatrix3(product)
    }

    operator fun times(vector: DoubleVector3): DoubleVector3 {
        val v = values
        return DoubleVector3(
            x = v[0] * vector.x + v[3] * vector.y + v[6] * vector.z,
            y = v[1] * vector.x + v[4] * vector.y + v[7] * vector.z,
            z = v[2] * vector.x + v[5] * vector.y + v[8] * vector.z,
        )
    }

    /** Element-wise `Double.equals`, exactly the `List<Double>` equality this replaced: `NaN` equals
     * `NaN`, and `0.0` does not equal `-0.0`. */
    override fun equals(other: Any?): Boolean = other is DoubleMatrix3 && sameElements(values, other.values)

    override fun hashCode(): Int = listHashCode(values)

    override fun toString(): String = "DoubleMatrix3(valuesInColumnMajorOrder=${listString(values)})"

    private fun index(row: Int, column: Int): Int {
        require(row in 0 until DIMENSION) { "row must be in 0..2" }
        require(column in 0 until DIMENSION) { "column must be in 0..2" }
        return column * DIMENSION + row
    }

    internal companion object {
        val identity: DoubleMatrix3 = rowMajor(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0,
        )

        fun fromRows(rows: List<List<Double>>): DoubleMatrix3 {
            require(rows.size == DIMENSION && rows.all { it.size == DIMENSION }) {
                "a 3 by 3 matrix requires three rows of three values"
            }
            return DoubleMatrix3(DoubleArray(ELEMENT_COUNT) { index ->
                val row = index % DIMENSION
                val column = index / DIMENSION
                rows[row][column]
            })
        }

        /** The matrix whose rows are the three triples given, in reading order -- [fromRows] without
         * the nested lists. */
        fun rowMajor(
            m00: Double, m01: Double, m02: Double,
            m10: Double, m11: Double, m12: Double,
            m20: Double, m21: Double, m22: Double,
        ): DoubleMatrix3 = DoubleMatrix3(doubleArrayOf(m00, m10, m20, m01, m11, m21, m02, m12, m22))

        fun fromColumns(
            first: DoubleVector3,
            second: DoubleVector3,
            third: DoubleVector3,
        ): DoubleMatrix3 = DoubleMatrix3(
            doubleArrayOf(
                first.x, first.y, first.z,
                second.x, second.y, second.z,
                third.x, third.y, third.z,
            ),
        )

        fun rotationXDegrees(degrees: Double): DoubleMatrix3 {
            val radians = degrees * PI / 180.0
            val cosine = cos(radians)
            val sine = sin(radians)
            return rowMajor(
                1.0, 0.0, 0.0,
                0.0, cosine, -sine,
                0.0, sine, cosine,
            )
        }

        fun rotationYDegrees(degrees: Double): DoubleMatrix3 {
            val radians = degrees * PI / 180.0
            val cosine = cos(radians)
            val sine = sin(radians)
            return rowMajor(
                cosine, 0.0, sine,
                0.0, 1.0, 0.0,
                -sine, 0.0, cosine,
            )
        }

        fun rotationZDegrees(degrees: Double): DoubleMatrix3 {
            val radians = degrees * PI / 180.0
            val cosine = cos(radians)
            val sine = sin(radians)
            return rowMajor(
                cosine, -sine, 0.0,
                sine, cosine, 0.0,
                0.0, 0.0, 1.0,
            )
        }

        fun rotationXyzDegrees(x: Double, y: Double, z: Double): DoubleMatrix3 =
            rotationZDegrees(z) * rotationYDegrees(y) * rotationXDegrees(x)

        private const val DIMENSION: Int = 3
        private const val ELEMENT_COUNT: Int = DIMENSION * DIMENSION
    }
}

/**
 * A 4 by 4 matrix of `Double`s, stored column-major in a [DoubleArray]; see [DoubleMatrix3] for why, and
 * for the bit-identity it keeps with the `List<Double>` version it replaced.
 */
internal class DoubleMatrix4 private constructor(private val values: DoubleArray) {
    internal constructor(valuesInColumnMajorOrder: List<Double>) : this(
        valuesInColumnMajorOrder.toDoubleArray().also {
            require(it.size == ELEMENT_COUNT) { "a 4 by 4 matrix requires sixteen values" }
        },
    )

    operator fun get(row: Int, column: Int): Double = values[index(row, column)]

    /** The element at [index] in column-major order -- `this[index % 4, index / 4]` -- for a caller
     * that walks all sixteen, such as an upload. */
    fun columnMajorElement(index: Int): Double = values[index]

    /** One row of `this * (point, 1)`: `m[row,0] * x + m[row,1] * y + m[row,2] * z + m[row,3]`. */
    fun homogeneousRow(row: Int, point: DoubleVector3): Double {
        require(row in 0 until DIMENSION) { "row must be in 0..3" }
        val v = values
        return v[row] * point.x + v[DIMENSION + row] * point.y + v[2 * DIMENSION + row] * point.z +
            v[3 * DIMENSION + row]
    }

    fun transpose(): DoubleMatrix4 {
        val v = values
        return DoubleMatrix4(DoubleArray(ELEMENT_COUNT) { index -> v[(index % DIMENSION) * DIMENSION + index / DIMENSION] })
    }

    /** The inverse of an affine transform -- one whose bottom row is `[0, 0, 0, 1]`, true of every
     * matrix this codebase builds from translation, rotation and scale. Inverts the upper-left 3
     * by 3 linear block via [DoubleMatrix3.inverse] and folds the translation column through it as
     * `-inverseLinear * translation`, rather than running general 4 by 4 Gauss-Jordan elimination
     * a glTF node transform never needs. Returns `null`, exactly when the linear block does,
     * rather than a matrix built from a near-zero divisor. */
    fun inverseAffine(): DoubleMatrix4? {
        val v = values
        val linear = DoubleMatrix3.rowMajor(
            v[0], v[4], v[8],
            v[1], v[5], v[9],
            v[2], v[6], v[10],
        )
        val inverseLinear = linear.inverse() ?: return null
        val translation = DoubleVector3(v[12], v[13], v[14])
        val inverseTranslation = -(inverseLinear * translation)

        return rowMajor(
            inverseLinear[0, 0], inverseLinear[0, 1], inverseLinear[0, 2], inverseTranslation.x,
            inverseLinear[1, 0], inverseLinear[1, 1], inverseLinear[1, 2], inverseTranslation.y,
            inverseLinear[2, 0], inverseLinear[2, 1], inverseLinear[2, 2], inverseTranslation.z,
            0.0, 0.0, 0.0, 1.0,
        )
    }

    /**
     * The inverse of any invertible 4 by 4, by Gauss-Jordan elimination with partial pivoting, or
     * `null` when it is singular (ADR 0072).
     *
     * [inverseAffine] is the one to reach for whenever the bottom row really is `[0, 0, 0, 1]`: it
     * is cheaper and it says so in the type of problem it solves. This exists for the one matrix in
     * this codebase that is **not** affine -- a projection, whose bottom row is `[0, 0, -1, 0]` --
     * and for the product of a projection with a view, which a backdrop shader is handed inverted.
     *
     * Deliberately general rather than the four-line closed form the current projection admits.
     * The point of inverting it for a consumer is that RenG absorbs projection changes on their
     * behalf, and a hand-derived inverse would absorb the next one by quietly returning the wrong
     * answer.
     */
    fun inverse(): DoubleMatrix4? {
        // Row-major working copy of `this | I`, so the identity emerges on the right.
        val augmented = Array(DIMENSION) { row ->
            DoubleArray(DIMENSION * 2) { column ->
                when {
                    column < DIMENSION -> values[column * DIMENSION + row]
                    column - DIMENSION == row -> 1.0
                    else -> 0.0
                }
            }
        }

        for (pivotColumn in 0 until DIMENSION) {
            // Partial pivoting: the largest magnitude available, which is what keeps a
            // near-singular matrix from being inverted through a near-zero divisor.
            var pivotRow = pivotColumn
            for (row in pivotColumn + 1 until DIMENSION) {
                if (abs(augmented[row][pivotColumn]) > abs(augmented[pivotRow][pivotColumn])) {
                    pivotRow = row
                }
            }
            val pivot = augmented[pivotRow][pivotColumn]
            if (pivot == 0.0 || !pivot.isFinite()) return null

            val swap = augmented[pivotColumn]
            augmented[pivotColumn] = augmented[pivotRow]
            augmented[pivotRow] = swap

            val pivotValues = augmented[pivotColumn]
            for (column in 0 until DIMENSION * 2) pivotValues[column] /= pivot

            for (row in 0 until DIMENSION) {
                if (row == pivotColumn) continue
                val factor = augmented[row][pivotColumn]
                if (factor == 0.0) continue
                val target = augmented[row]
                for (column in 0 until DIMENSION * 2) {
                    target[column] -= factor * pivotValues[column]
                }
            }
        }

        val inverted = DoubleArray(ELEMENT_COUNT) { index ->
            augmented[index % DIMENSION][index / DIMENSION + DIMENSION]
        }
        return if (inverted.all { it.isFinite() }) DoubleMatrix4(inverted) else null
    }

    /**
     * The product, each element summed as `((((0.0 + p0) + p1) + p2) + p3)`: the order, and the leading
     * `0.0`, of the `sumOf` this replaced. The `0.0` is not decoration -- it turns a `-0.0` first product
     * into `+0.0`, so dropping it would change the sign bit of some zero elements.
     */
    operator fun times(other: DoubleMatrix4): DoubleMatrix4 {
        val a = values
        val b = other.values
        val product = DoubleArray(ELEMENT_COUNT)
        for (index in 0 until ELEMENT_COUNT) {
            val row = index % DIMENSION
            val firstOfColumn = (index / DIMENSION) * DIMENSION
            var sum = 0.0
            sum += a[row] * b[firstOfColumn]
            sum += a[DIMENSION + row] * b[firstOfColumn + 1]
            sum += a[2 * DIMENSION + row] * b[firstOfColumn + 2]
            sum += a[3 * DIMENSION + row] * b[firstOfColumn + 3]
            product[index] = sum
        }
        return DoubleMatrix4(product)
    }

    /** Element-wise `Double.equals`, exactly the `List<Double>` equality this replaced. */
    override fun equals(other: Any?): Boolean = other is DoubleMatrix4 && sameElements(values, other.values)

    override fun hashCode(): Int = listHashCode(values)

    override fun toString(): String = "DoubleMatrix4(valuesInColumnMajorOrder=${listString(values)})"

    private fun index(row: Int, column: Int): Int {
        require(row in 0 until DIMENSION) { "row must be in 0..3" }
        require(column in 0 until DIMENSION) { "column must be in 0..3" }
        return column * DIMENSION + row
    }

    internal companion object {
        val identity: DoubleMatrix4 = rowMajor(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )

        fun fromRows(rows: List<List<Double>>): DoubleMatrix4 {
            require(rows.size == DIMENSION && rows.all { it.size == DIMENSION }) {
                "a 4 by 4 matrix requires four rows of four values"
            }
            return DoubleMatrix4(DoubleArray(ELEMENT_COUNT) { index ->
                val row = index % DIMENSION
                val column = index / DIMENSION
                rows[row][column]
            })
        }

        /** The matrix whose rows are the four quadruples given, in reading order -- [fromRows] without
         * the nested lists, which is what every per-frame builder uses. */
        fun rowMajor(
            m00: Double, m01: Double, m02: Double, m03: Double,
            m10: Double, m11: Double, m12: Double, m13: Double,
            m20: Double, m21: Double, m22: Double, m23: Double,
            m30: Double, m31: Double, m32: Double, m33: Double,
        ): DoubleMatrix4 = DoubleMatrix4(
            doubleArrayOf(
                m00, m10, m20, m30,
                m01, m11, m21, m31,
                m02, m12, m22, m32,
                m03, m13, m23, m33,
            ),
        )

        /** Embeds a 3 by 3 rotation as a 4 by 4 affine transform with zero translation -- the
         * upper-left block [rotation], the identity elsewhere. */
        fun fromRotation(rotation: DoubleMatrix3): DoubleMatrix4 = rowMajor(
            rotation[0, 0], rotation[0, 1], rotation[0, 2], 0.0,
            rotation[1, 0], rotation[1, 1], rotation[1, 2], 0.0,
            rotation[2, 0], rotation[2, 1], rotation[2, 2], 0.0,
            0.0, 0.0, 0.0, 1.0,
        )

        private const val DIMENSION: Int = 4
        private const val ELEMENT_COUNT: Int = DIMENSION * DIMENSION
    }
}

/** `List<Double>` equality over two arrays: the same size, and every pair equal by `Double.equals`. */
private fun sameElements(first: DoubleArray, second: DoubleArray): Boolean {
    if (first.size != second.size) return false
    for (index in first.indices) {
        if (!first[index].equals(second[index])) return false
    }
    return true
}

/** `List<Double>.hashCode()` over an array -- `31 * h + element.hashCode()`, from `1` -- so a matrix
 * hashes to exactly what it did when it held a list. */
private fun listHashCode(values: DoubleArray): Int {
    var hash = 1
    for (value in values) hash = 31 * hash + value.hashCode()
    return hash
}

/** `List<Double>.toString()` over an array: `[a, b, ...]`. */
private fun listString(values: DoubleArray): String = values.joinToString(prefix = "[", postfix = "]")
