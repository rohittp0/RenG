package com.rohittp.reng.internal.gl

import org.lwjgl.opengl.CGL
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryStack

// The same CGLTypes.h attributes the Kotlin/Native actual passes, so both runs reach the same driver.
// kCGLPFAAccelerated is deliberately absent there too: requesting it makes CGLChoosePixelFormat fail
// on a hosted runner with no GPU.
private const val K_CGL_PFA_RENDERER_ID: Int = 70
private const val K_CGL_PFA_OPENGL_PROFILE: Int = 99
private const val K_CGL_PFA_COLOR_SIZE: Int = 8
private const val K_CGL_PFA_DEPTH_SIZE: Int = 12
private const val K_CGL_OGLP_VERSION_3_2_CORE: Int = 0x3200

/** `kCGLRendererGenericFloatID` — Apple's CPU rasteriser. See [MacosGlRenderer.SOFTWARE]. */
private const val K_CGL_RENDERER_GENERIC_FLOAT_ID: Int = 0x00020400

/**
 * The JVM actual, over LWJGL's `CGL`. On anything but macOS, or without LWJGL's macOS natives on the
 * test runtime classpath, the first call throws: the JVM GL gate fails loudly rather than skipping.
 */
private class JvmCglCoreProfileContext(private val context: Long) : CglCoreProfileContext {
    override val probe: RenderContextProbe = RenderContextProbe {
        CGL.CGLGetCurrentContext().takeIf { it != 0L }?.let(::RenderContextIdentity)
    }

    override fun destroy() {
        CGL.CGLSetCurrentContext(0L)
        GL.setCapabilities(null)
        CGL.CGLDestroyContext(context)
    }
}

internal actual fun openCglCoreProfileContext(renderer: MacosGlRenderer): CglCoreProfileContext? =
    MemoryStack.stackPush().use { stack ->
        val attributes = stack.mallocInt(10)
        attributes.put(K_CGL_PFA_OPENGL_PROFILE).put(K_CGL_OGLP_VERSION_3_2_CORE)
        attributes.put(K_CGL_PFA_COLOR_SIZE).put(24)
        attributes.put(K_CGL_PFA_DEPTH_SIZE).put(24)
        if (renderer == MacosGlRenderer.SOFTWARE) {
            attributes.put(K_CGL_PFA_RENDERER_ID).put(K_CGL_RENDERER_GENERIC_FLOAT_ID)
        }
        attributes.put(0).flip()

        val pixelFormat = stack.mallocPointer(1)
        val formatCount = stack.mallocInt(1)
        CGL.CGLChoosePixelFormat(attributes, pixelFormat, formatCount)
        val chosen = pixelFormat.get(0)
        if (chosen == 0L || formatCount.get(0) <= 0) {
            if (chosen != 0L) CGL.CGLDestroyPixelFormat(chosen)
            return@use null
        }

        val contextSlot = stack.mallocPointer(1)
        CGL.CGLCreateContext(chosen, 0L, contextSlot)
        val created = contextSlot.get(0)
        CGL.CGLDestroyPixelFormat(chosen)
        if (created == 0L) return@use null
        CGL.CGLSetCurrentContext(created)
        require(CGL.CGLGetCurrentContext() != 0L) { "CGLSetCurrentContext failed" }
        // LWJGL resolves GL entry points per context; RenG's binding never does this
        // itself (ADR 0001), so the context's owner does.
        GL.createCapabilities()
        JvmCglCoreProfileContext(created)
    }
