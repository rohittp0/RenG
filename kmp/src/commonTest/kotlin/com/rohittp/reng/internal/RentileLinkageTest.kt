package com.rohittp.reng.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class RentileLinkageTest {
    @Test
    fun rentileImplementationIsLinked() {
        assertEquals(512, rentileLinkageAnchor())
    }
}
