package com.rohittp.reng.internal.json

import com.rohittp.reng.internal.containsOnlyUnicodeScalars
import com.rohittp.reng.internal.requireUnicodeScalars
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsonReaderTest {
    @Test
    fun classifiesNumbersBySpellingRatherThanValue() {
        assertEquals(JsonValue.Integer(1L), memberOf("""{"a":1}"""))
        // 2^53 + 1 is exactly the value a Double loses.
        assertEquals(JsonValue.Integer(9007199254740993L), memberOf("""{"a":9007199254740993}"""))
        assertEquals(JsonValue.Real(100.0), memberOf("""{"a":1e2}"""))
        assertEquals(JsonValue.Integer(0L), memberOf("""{"a":-0}"""))
        assertEquals(JsonReject.NON_FINITE_NUMBER, rejectionOf("""{"a":1E+400}"""))
    }

    @Test
    fun rejectsEveryGrammarViolationTheSubsetNames() {
        assertEquals(JsonReject.DUPLICATE_MEMBER_NAME, rejectionOf("""{"a":1,"a":2}"""))
        assertEquals(JsonReject.LEADING_ZERO, rejectionOf("""{"a":01}"""))
        assertEquals(JsonReject.BAD_FRACTION, rejectionOf("""{"a":5.}"""))
        assertEquals(JsonReject.EXPECTED_MEMBER_NAME, rejectionOf("""{"a":1,}"""))
        assertEquals(JsonReject.TRAILING_CONTENT, rejectionOf("{}{}"))
        assertEquals(JsonReject.UNESCAPED_CONTROL_CHARACTER, rejectionOf("{\"a\":\"tab\there\"}"))
        assertEquals(JsonReject.LONE_HIGH_SURROGATE_ESCAPE, rejectionOf("""{"a":"\uD800"}"""))
        assertEquals(JsonReject.BAD_ESCAPE, rejectionOf("""{"a":"\x"}"""))
    }

    @Test
    fun rejectsAByteOrderMarkAndBoundsDepth() {
        assertEquals(JsonReject.UNEXPECTED_CHARACTER, rejectionOf("﻿{}"))
        assertIs<JsonParse.Parsed>(parseJson(nested(64), 0, nested(64).size, 64))
        assertEquals(JsonReject.DEPTH_EXCEEDED, rejectionOf(nested(65), maximumDepth = 64))
    }

    @Test
    fun rejectsMalformedUtf8WithoutSubstitutingAReplacementCharacter() {
        assertEquals(JsonReject.UTF8_OVERLONG, rejectionOfBytes(byteArrayOf(0x22, 0xE0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x22)))
        assertEquals(JsonReject.UTF8_ENCODED_SURROGATE, rejectionOfBytes(byteArrayOf(0x22, 0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x22)))
        assertEquals(JsonReject.UTF8_TRUNCATED_SEQUENCE, rejectionOfBytes(byteArrayOf(0x22, 0xE2.toByte(), 0x82.toByte(), 0x22)))
    }

    @Test
    fun sharedScalarPredicateAgreesWithTheThrowingValidator() {
        assertTrue(containsOnlyUnicodeScalars("astral 😀"))
        assertFalse(containsOnlyUnicodeScalars("lone \uD800"))
        assertFailsWith<IllegalArgumentException> { requireUnicodeScalars("lone \uD800", "field", nonBlank = true) }
    }

    private fun memberOf(json: String): JsonValue {
        val result = parseJson(json.encodeToByteArray(), 0, json.encodeToByteArray().size, 64)
        val parsed = assertIs<JsonParse.Parsed>(result)
        val obj = assertIs<JsonValue.Obj>(parsed.value)
        return obj.members.getValue("a")
    }

    private fun rejectionOf(json: String, maximumDepth: Int = 64): JsonReject =
        rejectionOfBytes(json.encodeToByteArray(), maximumDepth)

    private fun rejectionOf(bytes: ByteArray, maximumDepth: Int = 64): JsonReject = rejectionOfBytes(bytes, maximumDepth)

    private fun rejectionOfBytes(bytes: ByteArray, maximumDepth: Int = 64): JsonReject {
        val result = parseJson(bytes, 0, bytes.size, maximumDepth)
        val failed = assertIs<JsonParse.Failed>(result)
        return failed.reason
    }

    private fun nested(depth: Int): ByteArray = buildString {
        repeat(depth) { append('[') }
        repeat(depth) { append(']') }
    }.encodeToByteArray()
}
