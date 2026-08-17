package com.rohittp.reng.internal.shader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShaderProfilePlannerTest {
    @Test
    fun acceptsDirectiveAtSourceStartAndEndOfSource() {
        val source = "#version 300 es"

        val plan = assertNotNull(scanShaderProfile(source))

        assertEquals(0, plan.directiveStartUtf16)
        assertEquals(source.length, plan.directiveEndExclusiveUtf16)
        assertTrue(plan.gles300Source() === source)
        assertEquals("#version 330 core", plan.desktop330Source())
    }

    @Test
    fun acceptsAsciiTrimAndReplacesTheCompleteDirectivePhysicalLineSpan() {
        val source = " \t#version 300 es\t \r\nvoid main() {}"

        val plan = assertNotNull(scanShaderProfile(source))

        assertEquals(0, plan.directiveStartUtf16)
        assertEquals(" \t#version 300 es\t ".length, plan.directiveEndExclusiveUtf16)
        assertEquals(source, plan.gles300Source())
        assertEquals("#version 330 core\r\nvoid main() {}", plan.desktop330Source())
    }

    @Test
    fun acceptsLfCrLfAndBareCrPhysicalLineTerminators() {
        val cases = listOf(
            "\n" to "\n#version 330 core\nbody",
            "\r\n" to "\r\n#version 330 core\r\nbody",
            "\r" to "\r#version 330 core\rbody",
        )

        for ((terminator, expectedDesktop) in cases) {
            val source = "${terminator}#version 300 es${terminator}body"
            val plan = assertNotNull(scanShaderProfile(source), "terminator code units: ${terminator.length}")

            assertEquals(terminator.length, plan.directiveStartUtf16)
            assertEquals(terminator.length + "#version 300 es".length, plan.directiveEndExclusiveUtf16)
            assertTrue(plan.gles300Source() === source)
            assertEquals(expectedDesktop, plan.desktop330Source())
        }
    }

    @Test
    fun acceptsBlankAndCommentPrefixLinesUnderExactCommentRules() {
        val prefix = buildString {
            append(" \t// emoji 😀, Unicode separator  , and \\\r\n")
            append("/* outer marker /* is comment text */ /* second */ // tail\r")
            append("/* multiline\r\ncomment */\t\n")
        }
        val directiveLine = "\t#version 300 es \t"
        val body = "\r\nprecision mediump float;\n// keep π 😀\rvoid main() {}"
        val source = prefix + directiveLine + body

        val plan = assertNotNull(scanShaderProfile(source))

        assertEquals(prefix.length, plan.directiveStartUtf16)
        assertEquals(prefix.length + directiveLine.length, plan.directiveEndExclusiveUtf16)
        assertTrue(plan.gles300Source() === source)
        assertEquals(prefix + "#version 330 core" + body, plan.desktop330Source())
    }

    @Test
    fun versionTextInsideCommentsAfterTheDirectiveIsNotADuplicate() {
        val source = """#version 300 es
            |// #version 330 core
            |/* # version 310 es */
            |void main() {}
        """.trimMargin()

        val expectedDesktop = """#version 330 core
            |// #version 330 core
            |/* # version 310 es */
            |void main() {}
        """.trimMargin()
        val plan = assertNotNull(scanShaderProfile(source))

        assertEquals(expectedDesktop, plan.desktop330Source())
    }

    @Test
    fun versionTokenAfterBodyCodeIsNotASecondDirective() {
        val source = """#version 300 es
            |#define stringifyVersion(version) # version
            |void main() {}
        """.trimMargin()

        assertNotNull(scanShaderProfile(source))
    }

    @Test
    fun rejectsMissingAlternateMisplacedMalformedAndDuplicateDirectives() {
        val invalidSources = listOf(
            "",
            " \t",
            "void main() {}",
            "void main() {}\n#version 300 es",
            "#version 330 core",
            "#version 300",
            "#version 300 ES",
            "#VERSION 300 es",
            "# version 300 es",
            "#version\t300 es",
            "#version  300 es",
            "#version 300 es token",
            "#version 300 es // comment",
            "#version 300 es/* comment */",
            "#version 300 \\\nes",
            "#version 300 es\\\nes",
            "#version 300 es\n#version 300 es",
            "#version 300 es\n \t#version 310 es",
            "#version 300 es\n# version 300 es",
        )

        for (source in invalidSources) {
            assertNull(scanShaderProfile(source), "unexpectedly accepted: ${source.escapeForAssertion()}")
        }
    }

    @Test
    fun rejectsBomNonAsciiWhitespaceAndOtherPrefixTokens() {
        val invalidPrefixes = listOf(
            "﻿",
            " ",
            " ",
            "​",
            " ",
            "",
            "",
            "/",
            "*/",
            "token",
            "\\",
        )

        for (prefix in invalidPrefixes) {
            val source = "$prefix\n#version 300 es"
            assertNull(scanShaderProfile(source), "unexpectedly accepted prefix: ${prefix.escapeForAssertion()}")
        }
    }

    @Test
    fun rejectsCommentSharingTheDirectiveLineAndUnterminatedPrefixComments() {
        val invalidSources = listOf(
            "/* comment */ #version 300 es",
            "// comment #version 300 es",
            "/* first line\n*/ #version 300 es",
            "/* unterminated\n#version 300 es",
            "// comment-only EOF",
            "/* closed comment-only EOF */",
            " \t/* closed */ \t",
        )

        for (source in invalidSources) {
            assertNull(scanShaderProfile(source), "unexpectedly accepted: ${source.escapeForAssertion()}")
        }
    }

    @Test
    fun invalidSourcesReturnOnlyNullAndPlansRedactShaderText() {
        val secret = "#version 300 es\n// signed-resource-secret"
        val plan = assertNotNull(scanShaderProfile(secret))

        assertEquals("ShaderProfilePlan(<redacted>)", plan.toString())
        assertFalse(plan.toString().contains("signed-resource-secret"))
        assertNull(scanShaderProfile("signed-resource-secret\n#version 300 es"))
    }

    private fun String.escapeForAssertion(): String =
        replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
}
