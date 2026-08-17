package com.rohittp.reng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SpatialValuesTest {
    @Test
    fun canonicalizesNegativeZeroBeforeAssignment() {
        val vector = Vector3(-0.0, -0.0, -0.0)
        val camera = Camera(-0.0, -0.0, -0.0, -0.0, -0.0)
        val placement = Placement(
            AnchoringMode.MAP,
            vector,
            AnchoringMode.SCREEN,
            vector,
            AnchoringMode.MAP,
            -0.0,
        )

        assertEquals(0L, vector.x.toBits())
        assertEquals(0L, vector.y.toBits())
        assertEquals(0L, vector.z.toBits())
        assertEquals(0L, camera.latitude.toBits())
        assertEquals(0L, camera.unwrappedLongitude.toBits())
        assertEquals(0L, camera.zoom.toBits())
        assertEquals(0L, camera.bearing.toBits())
        assertEquals(0L, camera.pitch.toBits())
        assertEquals(0L, placement.scale.toBits())
    }

    @Test
    fun cameraBoundariesAreClosedOrOpenExactlyAsSpecified() {
        Camera(-90.0, -0.0, 0.0, 0.0, 0.0)
        Camera(90.0, 1.0, 22.0, 359.999, 89.999)

        assertFailsWith<IllegalArgumentException> { Camera(-90.0001, 0.0, 0.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Camera(90.0001, 0.0, 0.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Camera(0.0, 0.0, -0.0001, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Camera(0.0, 0.0, 22.0001, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Camera(0.0, 0.0, 0.0, -0.0001, 0.0) }
        assertFailsWith<IllegalArgumentException> { Camera(0.0, 0.0, 0.0, 360.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Camera(0.0, 0.0, 0.0, 0.0, -0.0001) }
        assertFailsWith<IllegalArgumentException> { Camera(0.0, 0.0, 0.0, 0.0, 90.0) }
    }

    @Test
    fun outputProductUsesExactLongArithmetic() {
        OutputPixelSize(1, Int.MAX_VALUE)
        assertFailsWith<IllegalArgumentException> { OutputPixelSize(46_341, 46_341) }
    }

    @Test
    fun outputPixelSizeRequiresPositiveBoundedDimensions() {
        assertFailsWith<IllegalArgumentException> { OutputPixelSize(0, 1) }
        assertFailsWith<IllegalArgumentException> { OutputPixelSize(1, 0) }
        assertFailsWith<IllegalArgumentException> { OutputPixelSize(-1, 1) }
        assertFailsWith<IllegalArgumentException> { OutputPixelSize(1, -1) }
    }

    @Test
    fun outputPixelSizeUsesExactStructuralEqualityAndHashing() {
        val first = OutputPixelSize(640, 480)
        val equal = OutputPixelSize(640, 480)
        val different = OutputPixelSize(641, 480)

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertFalse(first == different)
    }

    @Test
    fun rejectsEveryNonFiniteFloatingArgument() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { Vector3(invalid, 0.0, 0.0) }
            assertFailsWith<IllegalArgumentException> { Vector3(0.0, invalid, 0.0) }
            assertFailsWith<IllegalArgumentException> { Vector3(0.0, 0.0, invalid) }
            assertFailsWith<IllegalArgumentException> { Camera(invalid, 0.0, 0.0, 0.0, 0.0) }
            assertFailsWith<IllegalArgumentException> { Camera(0.0, invalid, 0.0, 0.0, 0.0) }
            assertFailsWith<IllegalArgumentException> { Camera(0.0, 0.0, invalid, 0.0, 0.0) }
            assertFailsWith<IllegalArgumentException> { Camera(0.0, 0.0, 0.0, invalid, 0.0) }
            assertFailsWith<IllegalArgumentException> { Camera(0.0, 0.0, 0.0, 0.0, invalid) }
            assertFailsWith<IllegalArgumentException> { placement(scale = invalid) }
        }
    }

    @Test
    fun rotationAndScaleRangesAreExact() {
        Placement(
            AnchoringMode.MAP,
            Vector3(0.0, 0.0, 0.0),
            AnchoringMode.SCREEN,
            Vector3(-180.0, 0.0, 179.999),
            AnchoringMode.MAP,
            0.0,
        )
        placement(scale = Double.MAX_VALUE)

        assertFailsWith<IllegalArgumentException> { placement(rotation = Vector3(-180.0001, 0.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { placement(rotation = Vector3(180.0, 0.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { placement(rotation = Vector3(0.0, -180.0001, 0.0)) }
        assertFailsWith<IllegalArgumentException> { placement(rotation = Vector3(0.0, 180.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { placement(rotation = Vector3(0.0, 0.0, -180.0001)) }
        assertFailsWith<IllegalArgumentException> { placement(rotation = Vector3(0.0, 0.0, 180.0)) }
        assertFailsWith<IllegalArgumentException> { placement(scale = -0.0001) }
    }

    @Test
    fun permitsEveryAnchoringCombination() {
        AnchoringMode.entries.forEach { positionMode ->
            AnchoringMode.entries.forEach { rotationMode ->
                AnchoringMode.entries.forEach { scaleMode ->
                    val placement = Placement(
                        positionMode,
                        Vector3(1.0, 2.0, 3.0),
                        rotationMode,
                        Vector3(4.0, 5.0, 6.0),
                        scaleMode,
                        7.0,
                    )

                    assertEquals(positionMode, placement.positionMode)
                    assertEquals(rotationMode, placement.rotationMode)
                    assertEquals(scaleMode, placement.scaleMode)
                }
            }
        }
    }

    @Test
    fun valuesUseExactStructuralEqualityAndHashing() {
        assertEquals(Vector3(-0.0, 2.0, 3.0), Vector3(0.0, 2.0, 3.0))
        assertEquals(Vector3(-0.0, 2.0, 3.0).hashCode(), Vector3(0.0, 2.0, 3.0).hashCode())

        val firstCamera = Camera(-0.0, 1.0, 2.0, 3.0, 4.0)
        val secondCamera = Camera(0.0, 1.0, 2.0, 3.0, 4.0)
        assertEquals(firstCamera, secondCamera)
        assertEquals(firstCamera.hashCode(), secondCamera.hashCode())

        val firstPlacement = placement(scale = -0.0)
        val secondPlacement = placement(scale = 0.0)
        assertEquals(firstPlacement, secondPlacement)
        assertEquals(firstPlacement.hashCode(), secondPlacement.hashCode())
    }

    @Test
    fun constructorFailureMessagesDoNotExposeArgumentValues() {
        val exception = assertFailsWith<IllegalArgumentException> { placement(scale = -12345.678) }

        assertFalse(exception.message.orEmpty().contains("-12345.678"))
    }

    private fun placement(
        rotation: Vector3 = Vector3(0.0, 0.0, 0.0),
        scale: Double = 1.0,
    ): Placement =
        Placement(
            AnchoringMode.MAP,
            Vector3(0.0, 0.0, 0.0),
            AnchoringMode.SCREEN,
            rotation,
            AnchoringMode.MAP,
            scale,
        )
}
