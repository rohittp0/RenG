package com.rohittp.reng.internal.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

internal class DoubleMatrix3 internal constructor(valuesInColumnMajorOrder: List<Double>) {
    private val values: List<Double> = valuesInColumnMajorOrder.toList()

    init {
        require(values.size == ELEMENT_COUNT) { "a 3 by 3 matrix requires nine values" }
    }

    operator fun get(row: Int, column: Int): Double = values[index(row, column)]

    fun column(column: Int): DoubleVector3 =
        DoubleVector3(this[0, column], this[1, column], this[2, column])

    fun transpose(): DoubleMatrix3 = fromRows(
        listOf(
            listOf(this[0, 0], this[1, 0], this[2, 0]),
            listOf(this[0, 1], this[1, 1], this[2, 1]),
            listOf(this[0, 2], this[1, 2], this[2, 2]),
        ),
    )

    operator fun times(other: DoubleMatrix3): DoubleMatrix3 =
        DoubleMatrix3(List(ELEMENT_COUNT) { index ->
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

    override fun equals(other: Any?): Boolean = other is DoubleMatrix3 && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "DoubleMatrix3(valuesInColumnMajorOrder=$values)"

    private fun index(row: Int, column: Int): Int {
        require(row in 0 until DIMENSION) { "row must be in 0..2" }
        require(column in 0 until DIMENSION) { "column must be in 0..2" }
        return column * DIMENSION + row
    }

    internal companion object {
        val identity: DoubleMatrix3 = DoubleMatrix3(
            listOf(
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0,
            ),
        )

        fun fromRows(rows: List<List<Double>>): DoubleMatrix3 {
            require(rows.size == DIMENSION && rows.all { it.size == DIMENSION }) {
                "a 3 by 3 matrix requires three rows of three values"
            }
            return DoubleMatrix3(List(ELEMENT_COUNT) { index ->
                val row = index % DIMENSION
                val column = index / DIMENSION
                rows[row][column]
            })
        }

        fun fromColumns(
            first: DoubleVector3,
            second: DoubleVector3,
            third: DoubleVector3,
        ): DoubleMatrix3 = DoubleMatrix3(
            listOf(
                first.x, first.y, first.z,
                second.x, second.y, second.z,
                third.x, third.y, third.z,
            ),
        )

        fun rotationXDegrees(degrees: Double): DoubleMatrix3 {
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

        fun rotationYDegrees(degrees: Double): DoubleMatrix3 {
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

        fun rotationZDegrees(degrees: Double): DoubleMatrix3 {
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

        fun rotationXyzDegrees(x: Double, y: Double, z: Double): DoubleMatrix3 =
            rotationZDegrees(z) * rotationYDegrees(y) * rotationXDegrees(x)

        private const val DIMENSION: Int = 3
        private const val ELEMENT_COUNT: Int = DIMENSION * DIMENSION
    }
}

internal class DoubleMatrix4 internal constructor(valuesInColumnMajorOrder: List<Double>) {
    private val values: List<Double> = valuesInColumnMajorOrder.toList()

    init {
        require(values.size == ELEMENT_COUNT) { "a 4 by 4 matrix requires sixteen values" }
    }

    operator fun get(row: Int, column: Int): Double = values[index(row, column)]

    fun transpose(): DoubleMatrix4 = fromRows(
        List(DIMENSION) { row -> List(DIMENSION) { column -> this[column, row] } },
    )

    operator fun times(other: DoubleMatrix4): DoubleMatrix4 =
        DoubleMatrix4(List(ELEMENT_COUNT) { index ->
            val row = index % DIMENSION
            val column = index / DIMENSION
            (0 until DIMENSION).sumOf { this[row, it] * other[it, column] }
        })

    override fun equals(other: Any?): Boolean = other is DoubleMatrix4 && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "DoubleMatrix4(valuesInColumnMajorOrder=$values)"

    private fun index(row: Int, column: Int): Int {
        require(row in 0 until DIMENSION) { "row must be in 0..3" }
        require(column in 0 until DIMENSION) { "column must be in 0..3" }
        return column * DIMENSION + row
    }

    internal companion object {
        val identity: DoubleMatrix4 = DoubleMatrix4(
            listOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )

        fun fromRows(rows: List<List<Double>>): DoubleMatrix4 {
            require(rows.size == DIMENSION && rows.all { it.size == DIMENSION }) {
                "a 4 by 4 matrix requires four rows of four values"
            }
            return DoubleMatrix4(List(ELEMENT_COUNT) { index ->
                val row = index % DIMENSION
                val column = index / DIMENSION
                rows[row][column]
            })
        }

        private const val DIMENSION: Int = 4
        private const val ELEMENT_COUNT: Int = DIMENSION * DIMENSION
    }
}
