package com.rohittp.reng.internal.math

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DoubleLinearAlgebraTest {
    @Test
    fun matricesSnapshotColumnMajorInputsAndHaveStructuralEquality() {
        val supplied = mutableListOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)
        val matrix = DoubleMatrix3(supplied)
        supplied[0] = 99.0

        assertEquals(1.0, matrix[0, 0])
        assertEquals(4.0, matrix[0, 1])
        assertEquals(
            matrix,
            DoubleMatrix3(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)),
        )
        assertEquals(
            matrix.hashCode(),
            DoubleMatrix3(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)).hashCode(),
        )
        assertNotEquals(matrix, DoubleMatrix3.identity)
        assertFailsWith<IllegalArgumentException> { DoubleMatrix3(List(8) { 0.0 }) }
        assertFailsWith<IllegalArgumentException> { DoubleMatrix4(List(15) { 0.0 }) }

        val suppliedFourByFour = MutableList(16) { it.toDouble() }
        val matrixFourByFour = DoubleMatrix4(suppliedFourByFour)
        suppliedFourByFour[0] = 99.0

        assertEquals(0.0, matrixFourByFour[0, 0])
        assertEquals(
            matrixFourByFour,
            DoubleMatrix4(List(16) { it.toDouble() }),
        )
        assertEquals(
            matrixFourByFour.hashCode(),
            DoubleMatrix4(List(16) { it.toDouble() }).hashCode(),
        )
    }

    @Test
    fun identityTransposeAndCompositionUseColumnVectorOrder() {
        val matrix = DoubleMatrix3(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0))
        val transpose = matrix.transpose()

        assertEquals(1.0, transpose[0, 0])
        assertEquals(2.0, transpose[0, 1])
        assertEquals(4.0, transpose[1, 0])
        assertEquals(matrix, transpose.transpose())
        assertEquals(matrix, DoubleMatrix3.identity * matrix)
        assertEquals(matrix, matrix * DoubleMatrix3.identity)
        assertEquals(DoubleMatrix4.identity, DoubleMatrix4.identity.transpose())
        assertEquals(DoubleMatrix4.identity, DoubleMatrix4.identity * DoubleMatrix4.identity)
    }

    @Test
    fun vectorAlgebraIsRightHanded() {
        val x = DoubleVector3(1.0, 0.0, 0.0)
        val y = DoubleVector3(0.0, 1.0, 0.0)
        val z = DoubleVector3(0.0, 0.0, 1.0)

        assertEquals(z, x.cross(y))
        assertVectorClose(-z, y.cross(x))
        assertEquals(0.0, x.dot(y))
        assertEquals(1.0, x.dot(x))
        assertEquals(DoubleVector3(3.0, 3.0, 3.0), x + DoubleVector3(2.0, 3.0, 3.0))
        assertEquals(DoubleVector3(2.0, 4.0, 6.0), DoubleVector3(1.0, 2.0, 3.0) * 2.0)
    }

    @Test
    fun axisRotationsAndExtrinsicXyzCompositionAreExactInOrder() {
        assertVectorClose(
            DoubleVector3(0.0, 0.0, 1.0),
            DoubleMatrix3.rotationXDegrees(90.0) * DoubleVector3(0.0, 1.0, 0.0),
        )
        assertVectorClose(
            DoubleVector3(1.0, 0.0, 0.0),
            DoubleMatrix3.rotationYDegrees(90.0) * DoubleVector3(0.0, 0.0, 1.0),
        )
        assertVectorClose(
            DoubleVector3(0.0, 1.0, 0.0),
            DoubleMatrix3.rotationZDegrees(90.0) * DoubleVector3(1.0, 0.0, 0.0),
        )

        val composed = DoubleMatrix3.rotationXyzDegrees(90.0, 90.0, 0.0)
        val expected =
            DoubleMatrix3.rotationZDegrees(0.0) *
                DoubleMatrix3.rotationYDegrees(90.0) *
                DoubleMatrix3.rotationXDegrees(90.0)

        assertEquals(expected, composed)
        assertVectorClose(DoubleVector3(1.0, -1.0, 0.0), composed * DoubleVector3(0.0, 1.0, 1.0))
    }

    private fun assertVectorClose(expected: DoubleVector3, actual: DoubleVector3, tolerance: Double = 1e-12) {
        assertClose(expected.x, actual.x, tolerance)
        assertClose(expected.y, actual.y, tolerance)
        assertClose(expected.z, actual.z, tolerance)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double) {
        check(abs(expected - actual) <= tolerance) {
            "Expected $expected but was $actual (tolerance $tolerance)"
        }
    }
}
