@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.smoke.harness

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.OpenGL3.glBindFramebuffer
import platform.OpenGL3.glBindTexture
import platform.OpenGL3.glCheckFramebufferStatus
import platform.OpenGL3.glClear
import platform.OpenGL3.glClearColor
import platform.OpenGL3.glColorMask
import platform.OpenGL3.glDeleteFramebuffers
import platform.OpenGL3.glDeleteTextures
import platform.OpenGL3.glDisable
import platform.OpenGL3.glFramebufferTexture2D
import platform.OpenGL3.glGenFramebuffers
import platform.OpenGL3.glGenTextures
import platform.OpenGL3.glGetError
import platform.OpenGL3.glGetString
import platform.OpenGL3.glPixelStorei
import platform.OpenGL3.glReadBuffer
import platform.OpenGL3.glReadPixels
import platform.OpenGL3.glTexStorage2D
import platform.OpenGL3.glViewport

/**
 * The handful of GL calls the harness makes on its own behalf.
 *
 * Everything here is the *consumer's* GL: allocating the framebuffer RenG draws into, clearing it,
 * and reading it back. RenG's own GL work happens entirely inside `draw`, through its internal
 * binding, and the harness never touches it.
 *
 * Constants are written out rather than imported so the numeric values are visible at the call
 * site; `platform.OpenGL3` exposes them too, but with per-symbol integer widths that differ from
 * the argument types they are passed to.
 */
private const val GL_TEXTURE_2D: UInt = 0x0DE1u
private const val GL_RGBA8: UInt = 0x8058u
private const val GL_RGBA: UInt = 0x1908u
private const val GL_UNSIGNED_BYTE: UInt = 0x1401u
private const val GL_DRAW_FRAMEBUFFER: UInt = 0x8CA9u
private const val GL_READ_FRAMEBUFFER: UInt = 0x8CA8u
private const val GL_COLOR_ATTACHMENT0: UInt = 0x8CE0u
private const val GL_FRAMEBUFFER_COMPLETE: UInt = 0x8CD5u
private const val GL_PACK_ALIGNMENT: UInt = 0x0D05u
private const val GL_COLOR_BUFFER_BIT: UInt = 0x00004000u
private const val GL_SCISSOR_TEST: UInt = 0x0C11u
private const val GL_VENDOR: UInt = 0x1F00u
private const val GL_RENDERER: UInt = 0x1F01u
private const val GL_VERSION: UInt = 0x1F02u
private const val GL_SHADING_LANGUAGE_VERSION: UInt = 0x8B8Cu

internal fun glStringOrNull(name: UInt): String? =
    glGetString(name)?.reinterpret<ByteVar>()?.toKString()

internal fun describeCurrentContext(): String =
    "vendor=" + glStringOrNull(GL_VENDOR) +
        " | renderer=" + glStringOrNull(GL_RENDERER) +
        " | version=" + glStringOrNull(GL_VERSION) +
        " | glsl=" + glStringOrNull(GL_SHADING_LANGUAGE_VERSION)

/** The consumer-owned colour target RenG composites its finished frame into. */
internal class CaptureTarget private constructor(
    val framebuffer: UInt,
    private val texture: UInt,
    val width: Int,
    val height: Int,
) {
    /**
     * Clears to [clear] so an undrawn frame is unmistakable rather than plausible black. RenG's own
     * offscreen surface clears to transparent black and composites with source alpha, so whatever
     * RenG does not draw survives as exactly this colour.
     */
    fun clearTo(clear: IntArray) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
        glDisable(GL_SCISSOR_TEST)
        glColorMask(1u, 1u, 1u, 1u)
        glViewport(0, 0, width, height)
        glClearColor(clear[0] / 255f, clear[1] / 255f, clear[2] / 255f, clear[3] / 255f)
        glClear(GL_COLOR_BUFFER_BIT)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0u)
    }

    /** RGBA8, bottom-up, exactly as `glReadPixels` produces it. */
    fun readPixels(): ByteArray {
        val pixels = ByteArray(width * height * 4)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer)
        glReadBuffer(GL_COLOR_ATTACHMENT0)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        pixels.usePinned { pinned ->
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pinned.addressOf(0))
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0u)
        return pixels
    }

    fun destroy() {
        deleteName(::glDeleteFramebuffers, framebuffer)
        deleteName(::glDeleteTextures, texture)
    }

    companion object {
        fun create(width: Int, height: Int): CaptureTarget {
            val texture = generateName(::glGenTextures)
            glBindTexture(GL_TEXTURE_2D, texture)
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height)
            val framebuffer = generateName(::glGenFramebuffers)
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
            val status = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)
            check(status == GL_FRAMEBUFFER_COMPLETE) {
                "the capture target is an incomplete framebuffer (status 0x${status.toString(16)})"
            }
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0u)
            return CaptureTarget(framebuffer, texture, width, height)
        }
    }
}

/** Drains and reports the GL error queue, so a silent driver rejection is not mistaken for a picture. */
internal fun drainGlErrors(where: String) {
    var error = glGetError()
    while (error != 0u) {
        println("  GL error after $where: 0x${error.toString(16)}")
        error = glGetError()
    }
}

private inline fun generateName(generator: (Int, kotlinx.cinterop.CValuesRef<UIntVar>?) -> Unit): UInt {
    val names = UIntArray(1)
    names.usePinned { pinned -> generator(1, pinned.addressOf(0)) }
    return names[0]
}

private inline fun deleteName(deleter: (Int, kotlinx.cinterop.CValuesRef<UIntVar>?) -> Unit, name: UInt) {
    val names = uintArrayOf(name)
    names.usePinned { pinned -> deleter(1, pinned.addressOf(0)) }
}
