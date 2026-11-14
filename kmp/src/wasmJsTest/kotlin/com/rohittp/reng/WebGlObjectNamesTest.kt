package com.rohittp.reng

import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebGlObjectNamesTest {
    @Test
    fun objectNamesAreSharedPerContext() {
        val firstContext = newJsObject()
        val secondContext = newJsObject()
        val firstObject = newJsObject()
        val secondObject = newJsObject()

        val firstBindingNames = webGlObjectNames(firstContext, WebGlObjectKind.FRAMEBUFFER)
        val secondBindingNames = webGlObjectNames(firstContext, WebGlObjectKind.FRAMEBUFFER)
        val firstName = firstBindingNames.nameFor(firstObject)

        assertEquals(firstName, firstBindingNames.nameFor(firstObject))
        assertEquals(firstName, secondBindingNames.nameFor(firstObject))
        assertEquals(firstName, webGlObjectName(firstContext, WebGlObjectKind.FRAMEBUFFER, firstObject))
        assertTrue(sameJsObject(firstObject, webGlObjectForName(firstContext, WebGlObjectKind.FRAMEBUFFER, firstName)))

        val secondNameOnFirstContext = webGlObjectName(firstContext, WebGlObjectKind.FRAMEBUFFER, secondObject)
        val secondNameOnSecondContext = webGlObjectName(secondContext, WebGlObjectKind.FRAMEBUFFER, secondObject)
        assertNotEquals(secondNameOnFirstContext, secondNameOnSecondContext)
        assertNull(webGlObjectForName(secondContext, WebGlObjectKind.FRAMEBUFFER, secondNameOnFirstContext))

        forgetWebGlObjectName(firstContext, WebGlObjectKind.FRAMEBUFFER, firstName)
        assertNull(secondBindingNames[firstName])
        assertNull(webGlObjectForName(firstContext, WebGlObjectKind.FRAMEBUFFER, firstName))
        assertNull(webGlObjectForName(firstContext, WebGlObjectKind.FRAMEBUFFER, 0))
        assertNull(webGlObjectForName(firstContext, WebGlObjectKind.FRAMEBUFFER, Int.MAX_VALUE))
    }
}

@JsFun("() => ({})")
private external fun newJsObject(): JsAny

@JsFun("(first, second) => first === second")
private external fun sameJsObject(first: JsAny, second: JsAny?): Boolean
