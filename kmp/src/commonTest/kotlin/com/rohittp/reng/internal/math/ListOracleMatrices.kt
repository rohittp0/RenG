package com.rohittp.reng.internal.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * The List<Double>-backed DoubleMatrix3 and DoubleMatrix4 exactly as they stood at 0.6.0-local36
 * (27f8a60), renamed and kept as the oracle DoubleLinearAlgebraOracleTest holds the DoubleArray
 * versions to bit for bit. Not to be edited except to follow a deliberate change of arithmetic.
 */

private const val ORACLE_SINGULAR_DETERMINANT_THRESHOLD: Double = 1e-12

internal class ListOracleMatrix3 internal constructor(valuesInColumnMajorOrder: List<Double>) {
    val values: List<Double> = valuesInColumnMajorOrder.toList()

    init {
        require(values.size == ELEMENT_COUNT) { "a 3 by 3 matrix requires nine values" }
    }

    operator fun get(row: Int, column: Int): Double = values[index(row, column)]

    fun column(column: Int): DoubleVector3 =
        DoubleVector3(this[0, column], this[1, column], this[2, column])

    fun transpose(): ListOracleMatrix3 = fromRows(
        listOf(
            listOf(this[0, 0], this[1, 0], this[2, 0]),
            listOf(this[0, 1], this[1, 1], this[2, 1]),
            listOf(this[0, 2], this[1, 2], this[2, 2]),
        ),
    )

    /** The matrix inverse, by adjugate over determinant, or `null` when `|det|` is below
     * [ORACLE_SINGULAR_DETERMINANT_THRESHOLD] -- a caller turns that `null` into a typed failure rather
     * than uploading a matrix built from a near-zero divisor. */
    fun inverse(): ListOracleMatrix3? {
        val a = this[0, 0]
        val b = this[0, 1]
        val c = this[0, 2]
        val d = this[1, 0]
        val e = this[1, 1]
        val f = this[1, 2]
        val g = this[2, 0]
        val h = this[2, 1]
        val i = this[2, 2]

        val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(determinant) < ORACLE_SINGULAR_DETERMINANT_THRESHOLD) return null

        val inverseDeterminant = 1.0 / determinant
        return fromRows(
            listOf(
                listOf(
                    (e * i - f * h) * inverseDeterminant,
                    (c * h - b * i) * inverseDeterminant,
                    (b * f - c * e) * inverseDeterminant,
                ),
                listOf(
                    (f * g - d * i) * inverseDeterminant,
                    (a * i - c * g) * inverseDeterminant,
                    (c * d - a * f) * inverseDeterminant,
                ),
                listOf(
                    (d * h - e * g) * inverseDeterminant,
                    (b * g - a * h) * inverseDeterminant,
                    (a * e - b * d) * inverseDeterminant,
                ),
            ),
        )
    }

    operator fun times(other: ListOracleMatrix3): ListOracleMatrix3 =
        ListOracleMatrix3(List(ELEMENT_COUNT) { index ->
            val row = index % DIMENSION
            val column = index / DIMENSION
            this[row, 0] * other[0, column] +
                this[row, 1] * other[1, column] +
                this[row, 2] * other[2, column]
        })

    operator fun times(vector: DoubleVector3): DoubleVector3 =
        DoubleVector3(
            x = this[0, 0] * vector.x + this[0, 1] * vector.y + this[0, 2] * vector.z,
            y = this[1, 0] * vector.x + this[1, 1] * vector.y + this[1, 2] * vector.z,
            z = this[2, 0] * vector.x + this[2, 1] * vector.y + this[2, 2] * vector.z,
        )

    override fun equals(other: Any?): Boolean = other is ListOracleMatrix3 && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "ListOracleMatrix3(valuesInColumnMajorOrder=$values)"

    private fun index(row: Int, column: Int): Int {
        require(row in 0 until DIMENSION) { "row must be in 0..2" }
        require(column in 0 until DIMENSION) { "column must be in 0..2" }
        return column * DIMENSION + row
    }

    internal companion object {
        val identity: ListOracleMatrix3 = ListOracleMatrix3(
            listOf(
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0,
            ),
        )

        fun fromRows(rows: List<List<Double>>): ListOracleMatrix3 {
            require(rows.size == DIMENSION && rows.all { it.size == DIMENSION }) {
                "a 3 by 3 matrix requires three rows of three values"
            }
            return ListOracleMatrix3(List(ELEMENT_COUNT) { index ->
                val row = index % DIMENSION
                val column = index / DIMENSION
                rows[row][column]
            })
        }

        fun fromColumns(
            first: DoubleVector3,
            second: DoubleVector3,
            third: DoubleVector3,
        ): ListOracleMatrix3 = ListOracleMatrix3(
            listOf(
                first.x, first.y, first.z,
                second.x, second.y, second.z,
                third.x, third.y, third.z,
            ),
        )

        fun rotationXDegrees(degrees: Double): ListOracleMatrix3 {
            val radians = degrees * PI / 180.0
            val cosine = cos(radians)
            val sine = sin(radians)
            return fromRows(
                listOf(
                    listOf(1.0, 0.0, 0.0),
                    listOf(0.0, cosine, -sine),
                    listOf(0.0, sine, cosine),
                ),
            )
        }

        fun rotationYDegrees(degrees: Double): ListOracleMatrix3 {
            val radians = degrees * PI / 180.0
            val cosine = cos(radians)
            val sine = sin(radians)
            return fromRows(
                listOf(
                    listOf(cosine, 0.0, sine),
                    listOf(0.0, 1.0, 0.0),
                    listOf(-sine, 0.0, cosine),
                ),
            )
        }

        fun rotationZDegrees(degrees: Double): ListOracleMatrix3 {
            val radians = degrees * PI / 180.0
            val cosine = cos(radians)
            val sine = sin(radians)
            return fromRows(
                listOf(
                    listOf(cosine, -sine, 0.0),
                    listOf(sine, cosine, 0.0),
                    listOf(0.0, 0.0, 1.0),
                ),
            )
        }

        fun rotationXyzDegrees(x: Double, y: Double, z: Double): ListOracleMatrix3 =
            rotationZDegrees(z) * rotationYDegrees(y) * rotationXDegrees(x)

        private const val DIMENSION: Int = 3
        private const val ELEMENT_COUNT: Int = DIMENSION * DIMENSION
    }
}

internal class ListOracleMatrix4 internal constructor(valuesInColumnMajorOrder: List<Double>) {
    val values: List<Double> = valuesInColumnMajorOrder.toList()

    init {
        require(values.size == ELEMENT_COUNT) { "a 4 by 4 matrix requires sixteen values" }
    }

    operator fun get(row: Int, column: Int): Double = values[index(row, column)]

    fun transpose(): ListOracleMatrix4 = fromRows(
        List(DIMENSION) { row -> List(DIMENSION) { column -> this[column, row] } },
    )

    /** The inverse of an affine transform -- one whose bottom row is `[0, 0, 0, 1]`, true of every
     * matrix this codebase builds from translation, rotation and scale. Inverts the upper-left 3
     * by 3 linear block via [ListOracleMatrix3.inverse] and folds the translation column through it as
     * `-inverseLinear * translation`, rather than running general 4 by 4 Gauss-Jordan elimination
     * a glTF node transform never needs. Returns `null`, exactly when the linear block does,
     * rather than a matrix built from a near-zero divisor. */
    fun inverseAffine(): ListOracleMatrix4? {
        val linear = ListOracleMatrix3.fromRows(
            listOf(
                listOf(this[0, 0], this[0, 1], this[0, 2]),
                listOf(this[1, 0], this[1, 1], this[1, 2]),
                listOf(this[2, 0], this[2, 1], this[2, 2]),
            ),
        )
        val inverseLinear = linear.inverse() ?: return null
        val translation = DoubleVector3(this[0, 3], this[1, 3], this[2, 3])
        val inverseTranslation = -(inverseLinear * translation)

        return fromRows(
            listOf(
                listOf(inverseLinear[0, 0], inverseLinear[0, 1], inverseLinear[0, 2], inverseTranslation.x),
                listOf(inverseLinear[1, 0], inverseLinear[1, 1], inverseLinear[1, 2], inverseTranslation.y),
                listOf(inverseLinear[2, 0], inverseLinear[2, 1], inverseLinear[2, 2], inverseTranslation.z),
                listOf(0.0, 0.0, 0.0, 1.0),
            ),
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
    fun inverse(): ListOracleMatrix4? {
        // Row-major working copy of `this | I`, so the identity emerges on the right.
        val augmented = Array(DIMENSION) { row ->
            DoubleArray(DIMENSION * 2) { column ->
                when {
                    column < DIMENSION -> this[row, column]
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

        val inverted = fromRows(
            List(DIMENSION) { row -> List(DIMENSION) { column -> augmented[row][column + DIMENSION] } },
        )
        return if (inverted.values.all { it.isFinite() }) inverted else null
    }

    operator fun times(other: ListOracleMatrix4): ListOracleMatrix4 =
        ListOracleMatrix4(List(ELEMENT_COUNT) { index ->
            val row = index % DIMENSION
            val column = index / DIMENSION
            (0 until DIMENSION).sumOf { this[row, it] * other[it, column] }
        })

    override fun equals(other: Any?): Boolean = other is ListOracleMatrix4 && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "ListOracleMatrix4(valuesInColumnMajorOrder=$values)"

    private fun index(row: Int, column: Int): Int {
        require(row in 0 until DIMENSION) { "row must be in 0..3" }
        require(column in 0 until DIMENSION) { "column must be in 0..3" }
        return column * DIMENSION + row
    }

    internal companion object {
        val identity: ListOracleMatrix4 = ListOracleMatrix4(
            listOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )

        fun fromRows(rows: List<List<Double>>): ListOracleMatrix4 {
            require(rows.size == DIMENSION && rows.all { it.size == DIMENSION }) {
                "a 4 by 4 matrix requires four rows of four values"
            }
            return ListOracleMatrix4(List(ELEMENT_COUNT) { index ->
                val row = index % DIMENSION
                val column = index / DIMENSION
                rows[row][column]
            })
        }

        /** Embeds a 3 by 3 rotation as a 4 by 4 affine transform with zero translation -- the
         * upper-left block [rotation], the identity elsewhere. */
        fun fromRotation(rotation: ListOracleMatrix3): ListOracleMatrix4 = fromRows(
            listOf(
                listOf(rotation[0, 0], rotation[0, 1], rotation[0, 2], 0.0),
                listOf(rotation[1, 0], rotation[1, 1], rotation[1, 2], 0.0),
                listOf(rotation[2, 0], rotation[2, 1], rotation[2, 2], 0.0),
                listOf(0.0, 0.0, 0.0, 1.0),
            ),
        )

        private const val DIMENSION: Int = 4
        private const val ELEMENT_COUNT: Int = DIMENSION * DIMENSION
    }
}
