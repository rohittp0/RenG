@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.smoke.harness

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.OpenGLCommon.CGLChoosePixelFormat
import platform.OpenGLCommon.CGLCreateContext
import platform.OpenGLCommon.CGLDestroyContext
import platform.OpenGLCommon.CGLDestroyPixelFormat
import platform.OpenGLCommon.CGLGetCurrentContext
import platform.OpenGLCommon.CGLSetCurrentContext

private const val K_CGL_PFA_OPENGL_PROFILE: UInt = 99u
private const val K_CGL_PFA_COLOR_SIZE: UInt = 8u
private const val K_CGL_PFA_DEPTH_SIZE: UInt = 12u
private const val K_CGL_OGLP_VERSION_3_2_CORE: UInt = 0x3200u

/**
 * A headless core-profile Render Context, created by the consumer exactly as RenG's contract
 * requires: RenG never creates, makes current, or destroys a context (ADR 0001).
 *
 * This is a deliberate copy of `kmp`'s own `macosTest` fixture of the same name. That fixture is a
 * test source, so it is not published and a consumer cannot reuse it; the twenty lines are the
 * price of RenG never shipping context creation.
 */
internal class CglCoreProfileContext private constructor(private val context: COpaquePointer) {
    internal fun destroy() {
        CGLSetCurrentContext(null)
        CGLDestroyContext(context.reinterpret())
    }

    internal companion object {
        internal fun create(): CglCoreProfileContext = memScoped {
            val attributes = allocArray<UIntVar>(8)
            attributes[0] = K_CGL_PFA_OPENGL_PROFILE
            attributes[1] = K_CGL_OGLP_VERSION_3_2_CORE
            attributes[2] = K_CGL_PFA_COLOR_SIZE
            attributes[3] = 24u
            attributes[4] = K_CGL_PFA_DEPTH_SIZE
            attributes[5] = 24u
            attributes[6] = 0u

            val pixelFormat = alloc<COpaquePointerVar>()
            val formatCount = alloc<IntVar>()
            CGLChoosePixelFormat(attributes.reinterpret(), pixelFormat.ptr.reinterpret(), formatCount.ptr)
            val chosen = requireNotNull(pixelFormat.value) {
                "no core-profile pixel format; acceleration must not be requested"
            }
            require(formatCount.value > 0) { "CGLChoosePixelFormat returned no formats" }

            val contextSlot = alloc<COpaquePointerVar>()
            CGLCreateContext(chosen.reinterpret(), null, contextSlot.ptr.reinterpret())
            val created = requireNotNull(contextSlot.value) { "CGLCreateContext failed" }
            CGLDestroyPixelFormat(chosen.reinterpret())
            CGLSetCurrentContext(created.reinterpret())
            require(CGLGetCurrentContext() != null) { "CGLSetCurrentContext failed" }
            CglCoreProfileContext(created)
        }
    }
}
