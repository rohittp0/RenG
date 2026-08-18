package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlEntryPointRosterTest {
    @Test fun rosterHasExactlyEightyFourEntryPoints() {
        assertEquals(84, GlEntryPoint.entries.size)
    }

    @Test fun everyCNameIsDistinctAndWellFormed() {
        val names = GlEntryPoint.entries.map { it.cName }
        assertEquals(names.size, names.toSet().size)
        names.forEach { name ->
            assertTrue(name.startsWith("gl"), "entry point $name must be a GL C name")
            assertTrue(name.length > 2 && name[2].isUpperCase(), "entry point $name is malformed")
            assertTrue(name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' })
        }
    }

    @Test fun rosterOrderIsStableForOrdinalIndexedTables() {
        assertEquals(GlEntryPoint.GET_ERROR, GlEntryPoint.entries.first())
        assertEquals("glGetError", GlEntryPoint.GET_ERROR.cName)
        assertEquals("glGetStringi", GlEntryPoint.GET_STRINGI.cName)
        assertEquals("glDepthRangef", GlEntryPoint.DEPTH_RANGEF.cName)
        assertEquals("glTexStorage2D", GlEntryPoint.TEX_STORAGE_2D.cName)
    }

    @Test fun tokensThatDifferBetweenDialectsAreNotFolded() {
        assertEquals(0x8DB9, GL_FRAMEBUFFER_SRGB)
        assertEquals(0x0C01, GL_DRAW_BUFFER)
        assertEquals(0x0B20, GL_LINE_SMOOTH)
        assertEquals(0x0CF5, GL_UNPACK_ALIGNMENT)
        assertEquals(0x0D05, GL_PACK_ALIGNMENT)
        assertEquals(0x821D, GL_NUM_EXTENSIONS)
        assertEquals(0x1F03, GL_EXTENSIONS)
    }
}
