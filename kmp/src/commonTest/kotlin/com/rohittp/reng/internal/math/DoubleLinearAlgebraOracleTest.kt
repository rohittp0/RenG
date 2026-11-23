package com.rohittp.reng.internal.math

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.globeFixedViewProjection
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `DoubleArray`-backed [DoubleMatrix3] and [DoubleMatrix4] against the `List<Double>` versions they
 * replaced ([ListOracleMatrix3], [ListOracleMatrix4]), bit for bit, on randomised matrices. Bits rather
 * than values, so a `-0.0` where the oracle has `0.0`, or a different `NaN`, fails.
 *
 * The inputs mix ordinary magnitudes with exact zeros of both signs, small integers and values spread
 * over many binades, because those are where a reordered sum or a dropped `0.0 +` changes a result.
 */
class DoubleLinearAlgebraOracleTest {
    private val random = Random(0x6c6f63616c3337)

    @Test
    fun fourByFourOperationsMatchTheListOracleBitForBit() {
        repeat(ROUNDS) { round ->
            val left = randomValues(16)
            val right = randomValues(16)
            val subject = DoubleMatrix4(left)
            val oracle = ListOracleMatrix4(left)
            val label = "round $round"

            assertBits(oracle.values, subject.elements(), "$label construction")
            assertBits((oracle * ListOracleMatrix4(right)).values, (subject * DoubleMatrix4(right)).elements(), "$label times")
            assertBits(oracle.transpose().values, subject.transpose().elements(), "$label transpose")
            assertBits(oracle.inverse()?.values, subject.inverse()?.elements(), "$label inverse")
            assertBits(oracle.inverseAffine()?.values, subject.inverseAffine()?.elements(), "$label inverseAffine")

            val affineValues = affineValues()
            assertBits(
                ListOracleMatrix4(affineValues).inverseAffine()?.values,
                DoubleMatrix4(affineValues).inverseAffine()?.elements(),
                "$label inverseAffine of an affine matrix",
            )

            val rows = List(4) { row -> List(4) { column -> left[column * 4 + row] } }
            assertBits(ListOracleMatrix4.fromRows(rows).values, DoubleMatrix4.fromRows(rows).elements(), "$label fromRows")
            assertBits(
                ListOracleMatrix4.fromRows(rows).values,
                DoubleMatrix4.rowMajor(
                    rows[0][0], rows[0][1], rows[0][2], rows[0][3],
                    rows[1][0], rows[1][1], rows[1][2], rows[1][3],
                    rows[2][0], rows[2][1], rows[2][2], rows[2][3],
                    rows[3][0], rows[3][1], rows[3][2], rows[3][3],
                ).elements(),
                "$label rowMajor",
            )

            val rotation = randomValues(9)
            assertBits(
                ListOracleMatrix4.fromRotation(ListOracleMatrix3(rotation)).values,
                DoubleMatrix4.fromRotation(DoubleMatrix3(rotation)).elements(),
                "$label fromRotation",
            )

            val point = DoubleVector3(randomValue(), randomValue(), randomValue())
            for (row in 0 until 4) {
                val expected = oracle[row, 0] * point.x + oracle[row, 1] * point.y + oracle[row, 2] * point.z + oracle[row, 3]
                assertEquals(expected.toBits(), subject.homogeneousRow(row, point).toBits(), "$label homogeneousRow $row")
            }

            assertEquals(oracle.hashCode(), subject.hashCode(), "$label hashCode")
            assertEquals(
                oracle.toString().removePrefix("ListOracleMatrix4"),
                subject.toString().removePrefix("DoubleMatrix4"),
                "$label toString",
            )
            assertEquals(subject, DoubleMatrix4(left), "$label equals")
            assertEquals(
                ListOracleMatrix4(left) == ListOracleMatrix4(right),
                subject == DoubleMatrix4(right),
                "$label inequality",
            )
        }
    }

    @Test
    fun threeByThreeOperationsMatchTheListOracleBitForBit() {
        repeat(ROUNDS) { round ->
            val left = randomValues(9)
            val right = randomValues(9)
            val subject = DoubleMatrix3(left)
            val oracle = ListOracleMatrix3(left)
            val label = "round $round"

            assertBits(oracle.values, subject.elements(), "$label construction")
            assertBits((oracle * ListOracleMatrix3(right)).values, (subject * DoubleMatrix3(right)).elements(), "$label times")
            assertBits(oracle.transpose().values, subject.transpose().elements(), "$label transpose")
            assertBits(oracle.inverse()?.values, subject.inverse()?.elements(), "$label inverse")

            val vector = DoubleVector3(randomValue(), randomValue(), randomValue())
            val expected = oracle * vector
            val actual = subject * vector
            assertBits(listOf(expected.x, expected.y, expected.z), listOf(actual.x, actual.y, actual.z), "$label times vector")
            for (column in 0 until 3) {
                val expectedColumn = oracle.column(column)
                val actualColumn = subject.column(column)
                assertBits(
                    listOf(expectedColumn.x, expectedColumn.y, expectedColumn.z),
                    listOf(actualColumn.x, actualColumn.y, actualColumn.z),
                    "$label column $column",
                )
            }

            val rows = List(3) { row -> List(3) { column -> left[column * 3 + row] } }
            assertBits(ListOracleMatrix3.fromRows(rows).values, DoubleMatrix3.fromRows(rows).elements(), "$label fromRows")
            assertBits(
                ListOracleMatrix3.fromRows(rows).values,
                DoubleMatrix3.rowMajor(
                    rows[0][0], rows[0][1], rows[0][2],
                    rows[1][0], rows[1][1], rows[1][2],
                    rows[2][0], rows[2][1], rows[2][2],
                ).elements(),
                "$label rowMajor",
            )
            val first = DoubleVector3(left[0], left[1], left[2])
            val second = DoubleVector3(left[3], left[4], left[5])
            val third = DoubleVector3(left[6], left[7], left[8])
            assertBits(
                ListOracleMatrix3.fromColumns(first, second, third).values,
                DoubleMatrix3.fromColumns(first, second, third).elements(),
                "$label fromColumns",
            )

            val (x, y, z) = Triple(random.nextDouble(-720.0, 720.0), random.nextDouble(-720.0, 720.0), random.nextDouble(-720.0, 720.0))
            assertBits(
                ListOracleMatrix3.rotationXyzDegrees(x, y, z).values,
                DoubleMatrix3.rotationXyzDegrees(x, y, z).elements(),
                "$label rotationXyzDegrees",
            )

            assertEquals(oracle.hashCode(), subject.hashCode(), "$label hashCode")
            assertEquals(
                oracle.toString().removePrefix("ListOracleMatrix3"),
                subject.toString().removePrefix("DoubleMatrix3"),
                "$label toString",
            )
        }
    }

    @Test
    fun theProductsOfAZeroAndANegativeZeroKeepTheOraclesSign() {
        // -0.0 * 1.0 is -0.0, and the oracle's `sumOf` starts from +0.0, so the element is +0.0. A
        // product that summed from the first term would keep the -0.0.
        val negativeZeroColumn = DoubleMatrix4(listOf(-0.0, -0.0, -0.0, -0.0) + List(12) { 0.0 })
        val oracle = ListOracleMatrix4(listOf(-0.0, -0.0, -0.0, -0.0) + List(12) { 0.0 })
        val identityValues = DoubleMatrix4.identity.elements()
        assertBits(
            (oracle * ListOracleMatrix4(identityValues)).values,
            (negativeZeroColumn * DoubleMatrix4.identity).elements(),
            "negative zeros",
        )
        assertEquals(0.0.toBits(), (negativeZeroColumn * DoubleMatrix4.identity)[0, 0].toBits())
    }

    @Test
    fun singularMatricesInvertToNullAsTheOracleDoes() {
        val singular = List(16) { 1.0 }
        assertNull(ListOracleMatrix4(singular).inverse())
        assertNull(DoubleMatrix4(singular).inverse())
        assertNull(ListOracleMatrix4(singular).inverseAffine())
        assertNull(DoubleMatrix4(singular).inverseAffine())
        assertNull(DoubleMatrix3(List(9) { 2.0 }).inverse())
    }

    /** The cached view-projections are the products every caller used to form itself. */
    @Test
    fun cachedCameraViewProjectionsAreTheOracleProducts() {
        repeat(64) { round ->
            val camera = Camera(
                latitude = random.nextDouble(-80.0, 80.0),
                unwrappedLongitude = random.nextDouble(-540.0, 540.0),
                zoom = random.nextDouble(0.0, 22.0),
                bearing = random.nextDouble(0.0, 360.0),
                pitch = random.nextDouble(0.0, 60.0),
            )
            val size = OutputPixelSize(random.nextInt(1, 4096), random.nextInt(1, 4096))
            val mercator = (resolveMercatorCamera(camera, size) as SpatialOutcome.Success<ResolvedMercatorCamera>).value
            assertBits(
                (ListOracleMatrix4(mercator.projectionMatrix.elements()) * ListOracleMatrix4(mercator.viewMatrix.elements())).values,
                mercator.viewProjection.elements(),
                "round $round mercator",
            )
            val globe = (resolveGlobeCamera(camera, size) as SpatialOutcome.Success<ResolvedGlobeCamera>).value
            val globeViewProjection =
                ListOracleMatrix4(globe.projectionMatrix.elements()) * ListOracleMatrix4(globe.viewMatrix.elements())
            assertBits(globeViewProjection.values, globe.viewProjection.elements(), "round $round globe")
            assertBits(
                (globeViewProjection * ListOracleMatrix4(globe.globeFixedToCameraRelative.elements())).values,
                globeFixedViewProjection(globe).elements(),
                "round $round globe-fixed",
            )
        }
    }

    private fun randomValues(count: Int): List<Double> = List(count) { randomValue() }

    private fun affineValues(): List<Double> = List(16) { index ->
        when (index) {
            3, 7, 11 -> 0.0
            15 -> 1.0
            else -> randomValue()
        }
    }

    private fun randomValue(): Double = when (random.nextInt(8)) {
        0 -> 0.0
        1 -> -0.0
        2 -> random.nextInt(-3, 4).toDouble()
        3 -> random.nextDouble(-1.0, 1.0) * 10.0.pow(random.nextInt(-12, 13))
        else -> random.nextDouble(-4.0, 4.0)
    }

    private fun Double.pow(exponent: Int): Double {
        var result = 1.0
        repeat(if (exponent < 0) -exponent else exponent) { result *= this }
        return if (exponent < 0) 1.0 / result else result
    }

    private fun DoubleMatrix4.elements(): List<Double> = List(16) { columnMajorElement(it) }

    private fun DoubleMatrix3.elements(): List<Double> = List(9) { this[it % 3, it / 3] }

    private fun assertBits(expected: List<Double>?, actual: List<Double>?, label: String) {
        assertEquals(expected?.map(Double::toRawBits), actual?.map(Double::toRawBits), label)
    }

    private companion object {
        const val ROUNDS = 4000
    }
}
