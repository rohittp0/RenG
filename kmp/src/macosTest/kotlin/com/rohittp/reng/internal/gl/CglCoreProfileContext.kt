@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import kotlinx.cinterop.ExperimentalForeignApi
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

// CGLTypes.h attribute values. kCGLPFAAccelerated (73) is deliberately absent: requesting it makes
// CGLChoosePixelFormat fail with kCGLBadPixelFormat on a hosted runner with no GPU.
private const val K_CGL_PFA_RENDERER_ID: UInt = 70u
private const val K_CGL_PFA_OPENGL_PROFILE: UInt = 99u
private const val K_CGL_PFA_COLOR_SIZE: UInt = 8u
private const val K_CGL_PFA_DEPTH_SIZE: UInt = 12u
private const val K_CGL_OGLP_VERSION_3_2_CORE: UInt = 0x3200u

/** `kCGLRendererGenericFloatID` — Apple's CPU rasteriser. See [MacosGlRenderer.SOFTWARE]. */
private const val K_CGL_RENDERER_GENERIC_FLOAT_ID: UInt = 0x00020400u

private class NativeCglCoreProfileContext(
    private val context: kotlinx.cinterop.COpaquePointer,
) : CglCoreProfileContext {
    override val probe: RenderContextProbe = RenderContextProbe {
        CGLGetCurrentContext()?.let { RenderContextIdentity(it.rawValue.toLong()) }
    }

    override fun destroy() {
        CGLSetCurrentContext(null)
        CGLDestroyContext(context.reinterpret())
    }
}

internal actual fun openCglCoreProfileContext(renderer: MacosGlRenderer): CglCoreProfileContext? = memScoped {
    val attributes = allocArray<UIntVar>(10)
    var next = 0
    attributes[next++] = K_CGL_PFA_OPENGL_PROFILE
    attributes[next++] = K_CGL_OGLP_VERSION_3_2_CORE
    attributes[next++] = K_CGL_PFA_COLOR_SIZE
    attributes[next++] = 24u
    attributes[next++] = K_CGL_PFA_DEPTH_SIZE
    attributes[next++] = 24u
    if (renderer == MacosGlRenderer.SOFTWARE) {
        attributes[next++] = K_CGL_PFA_RENDERER_ID
        attributes[next++] = K_CGL_RENDERER_GENERIC_FLOAT_ID
    }
    attributes[next] = 0u

    val pixelFormat = alloc<kotlinx.cinterop.COpaquePointerVar>()
    val formatCount = alloc<kotlinx.cinterop.IntVar>()
    CGLChoosePixelFormat(attributes.reinterpret(), pixelFormat.ptr.reinterpret(), formatCount.ptr)
    val chosen = pixelFormat.value
    if (chosen == null || formatCount.value <= 0) {
        chosen?.let { CGLDestroyPixelFormat(it.reinterpret()) }
        return@memScoped null
    }

    val contextSlot = alloc<kotlinx.cinterop.COpaquePointerVar>()
    CGLCreateContext(chosen.reinterpret(), null, contextSlot.ptr.reinterpret())
    val created = contextSlot.value
    CGLDestroyPixelFormat(chosen.reinterpret())
    if (created == null) return@memScoped null
    CGLSetCurrentContext(created.reinterpret())
    require(CGLGetCurrentContext() != null) { "CGLSetCurrentContext failed" }
    NativeCglCoreProfileContext(created)
}
