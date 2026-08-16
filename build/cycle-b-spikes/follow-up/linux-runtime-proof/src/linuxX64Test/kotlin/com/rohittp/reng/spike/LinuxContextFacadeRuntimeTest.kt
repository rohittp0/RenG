@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rohittp.reng.spike

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import platform.posix.getenv

typealias EglGetDisplay = CFunction<(COpaquePointer?) -> COpaquePointer?>
typealias EglInitialize = CFunction<(COpaquePointer?, CPointer<IntVar>?, CPointer<IntVar>?) -> UInt>
typealias EglBindApi = CFunction<(UInt) -> UInt>
typealias EglChooseConfig = CFunction<(
    COpaquePointer?,
    CPointer<IntVar>?,
    CPointer<COpaquePointerVar>?,
    Int,
    CPointer<IntVar>?,
) -> UInt>
typealias EglCreatePbufferSurface = CFunction<(
    COpaquePointer?,
    COpaquePointer?,
    CPointer<IntVar>?,
) -> COpaquePointer?>
typealias EglCreateContext = CFunction<(
    COpaquePointer?,
    COpaquePointer?,
    COpaquePointer?,
    CPointer<IntVar>?,
) -> COpaquePointer?>
typealias EglMakeCurrent = CFunction<(
    COpaquePointer?,
    COpaquePointer?,
    COpaquePointer?,
    COpaquePointer?,
) -> UInt>
typealias EglDestroySurface = CFunction<(COpaquePointer?, COpaquePointer?) -> UInt>
typealias EglDestroyContext = CFunction<(COpaquePointer?, COpaquePointer?) -> UInt>
typealias EglTerminate = CFunction<(COpaquePointer?) -> UInt>
typealias EglGetError = CFunction<() -> UInt>
typealias EglQueryString = CFunction<(COpaquePointer?, Int) -> CPointer<ByteVar>?>

class LinuxContextFacadeRuntimeTest {
    @Test
    fun mesaSurfacelessContextsAndFramebufferIdentitiesAreReal() = withEgl { egl ->
        assertEquals("1", getenv("LIBGL_ALWAYS_SOFTWARE")?.toKString())
        assertEquals("surfaceless", getenv("EGL_PLATFORM")?.toKString())

        egl.makeCurrentA()
        val contextA = RenderContext.current()
        val facade = ContextFacade(contextA)
        val gl = facade.glInfo()

        println("OBSERVED EGL_VENDOR=${egl.vendor}")
        println("OBSERVED EGL_VERSION=${egl.version}")
        println("OBSERVED EGL_CLIENT_APIS=${egl.clientApis}")
        println("OBSERVED GL_VENDOR=${gl.vendor}")
        println("OBSERVED GL_RENDERER=${gl.renderer}")
        println("OBSERVED GL_VERSION=${gl.version}")
        println("OBSERVED CONTEXT_A=${contextA.description}")
        println("OBSERVED CONTEXT_B=${egl.contextBDescription}")

        assertTrue(gl.renderer.contains("llvmpipe", ignoreCase = true), "Expected Mesa llvmpipe, got ${gl.renderer}")
        assertTrue(gl.vendor.contains("Mesa", ignoreCase = true), "Expected Mesa vendor, got ${gl.vendor}")
        assertNotEquals(contextA.description, egl.contextBDescription)

        val framebufferZero = facade.mintRenderTarget(FramebufferName(0u))
        facade.bind(framebufferZero)
        assertEquals(0u, framebufferZero.framebufferName.value)

        val generatedName = facade.createOwnedFramebuffer()
        assertNotEquals(0u, generatedName.value)
        val generatedTarget = facade.mintRenderTarget(generatedName)
        facade.bind(generatedTarget)
        println("OBSERVED FRAMEBUFFER_DEFAULT=${framebufferZero.framebufferName.value}")
        println("OBSERVED FRAMEBUFFER_GENERATED=${generatedName.value}")

        egl.makeCurrentB()
        val different = assertFailsWith<ContextAffinityException> {
            facade.mintRenderTarget(FramebufferName(0u))
        }
        assertEquals(ContextAffinityFailure.DIFFERENT_CURRENT_CONTEXT, different.failure)
        println("OBSERVED DIFFERENT_CURRENT_FAILURE=${different.failure}")

        egl.makeNoContextCurrent()
        val missing = assertFailsWith<ContextAffinityException> {
            facade.mintRenderTarget(FramebufferName(0u))
        }
        assertEquals(ContextAffinityFailure.NO_CURRENT_CONTEXT, missing.failure)
        println("OBSERVED NO_CURRENT_FAILURE=${missing.failure}")

        egl.makeCurrentA()
        facade.close()
    }

    @Test
    fun gpuObjectLossInvalidatesTargetsWithoutDeletesAndRequiresExplicitAdoption() = withEgl { egl ->
        egl.makeCurrentA()
        val facade = ContextFacade(RenderContext.current())
        val generatedName = facade.createOwnedFramebuffer()
        val oldTarget = facade.mintRenderTarget(generatedName)
        val beforeLoss = facade.debugState()

        facade.notifyGpuObjectsGone()
        val afterLoss = facade.debugState()

        assertEquals(beforeLoss.deleteInvocationCount, afterLoss.deleteInvocationCount)
        assertEquals(0, afterLoss.liveHandleCount)
        assertEquals(beforeLoss.contextGeneration + 1u, afterLoss.contextGeneration)
        assertTrue(afterLoss.adoptionRequired)
        println("OBSERVED LOSS_DELETE_DELTA=${afterLoss.deleteInvocationCount - beforeLoss.deleteInvocationCount}")
        println("OBSERVED LOSS_LIVE_HANDLES=${afterLoss.liveHandleCount}")
        println("OBSERVED LOSS_GENERATION=${beforeLoss.contextGeneration}->${afterLoss.contextGeneration}")

        val stale = assertFailsWith<StaleRenderTargetException> {
            facade.validate(oldTarget)
        }
        assertEquals(oldTarget.contextGeneration, stale.targetGeneration)
        assertEquals(afterLoss.contextGeneration, stale.currentGeneration)
        println("OBSERVED STALE_TARGET_FAILURE=${stale::class.simpleName}")

        egl.makeCurrentB()
        val adoptionRequired = assertFailsWith<RenderContextAdoptionRequiredException> {
            facade.mintRenderTarget(FramebufferName(0u))
        }
        assertEquals(afterLoss.contextGeneration, adoptionRequired.contextGeneration)
        println("OBSERVED PRE_ADOPTION_FAILURE=${adoptionRequired::class.simpleName}")

        val replacement = RenderContext.current()
        facade.adoptRenderContext(replacement)
        val afterAdoption = facade.debugState()
        assertFalse(afterAdoption.adoptionRequired)
        assertEquals(replacement.description, afterAdoption.expectedContextDescription)
        println("OBSERVED ADOPTED_CONTEXT=${replacement.description}")

        val replacementTarget = facade.mintRenderTarget(FramebufferName(0u))
        facade.bind(replacementTarget)
        facade.close()
    }

    @Test
    fun freeAndCloseRejectWrongContextWithoutMutationThenSucceedOnAdoptedContext() = withEgl { egl ->
        egl.makeCurrentA()
        val facade = ContextFacade(RenderContext.current())
        facade.createOwnedFramebuffer()
        facade.notifyGpuObjectsGone()

        egl.makeCurrentB()
        val adopted = RenderContext.current()
        facade.adoptRenderContext(adopted)
        val freeName = facade.createOwnedFramebuffer()
        val beforeFreeFailures = facade.debugState()

        egl.makeNoContextCurrent()
        val freeNoContext = assertFailsWith<ContextAffinityException> { facade.free() }
        assertEquals(ContextAffinityFailure.NO_CURRENT_CONTEXT, freeNoContext.failure)
        assertEquals(beforeFreeFailures, facade.debugState())

        egl.makeCurrentA()
        val freeDifferentContext = assertFailsWith<ContextAffinityException> { facade.free() }
        assertEquals(ContextAffinityFailure.DIFFERENT_CURRENT_CONTEXT, freeDifferentContext.failure)
        assertEquals(beforeFreeFailures, facade.debugState())

        egl.makeCurrentB()
        facade.free()
        val afterFree = facade.debugState()
        assertEquals(0, afterFree.liveHandleCount)
        assertEquals(beforeFreeFailures.deleteInvocationCount + 1, afterFree.deleteInvocationCount)
        assertEquals(beforeFreeFailures.deletedHandleCount + 1, afterFree.deletedHandleCount)
        println("OBSERVED FREE_HANDLE=${freeName.value}")
        println("OBSERVED FREE_NO_CURRENT=${freeNoContext.failure},STATE_UNCHANGED=true")
        println("OBSERVED FREE_DIFFERENT_CURRENT=${freeDifferentContext.failure},STATE_UNCHANGED=true")
        println("OBSERVED FREE_EXACT_CURRENT=success,DELETE_INVOCATIONS=${afterFree.deleteInvocationCount}")

        val closeName = facade.createOwnedFramebuffer()
        val beforeCloseFailures = facade.debugState()

        egl.makeNoContextCurrent()
        val closeNoContext = assertFailsWith<ContextAffinityException> { facade.close() }
        assertEquals(ContextAffinityFailure.NO_CURRENT_CONTEXT, closeNoContext.failure)
        assertEquals(beforeCloseFailures, facade.debugState())

        egl.makeCurrentA()
        val closeDifferentContext = assertFailsWith<ContextAffinityException> { facade.close() }
        assertEquals(ContextAffinityFailure.DIFFERENT_CURRENT_CONTEXT, closeDifferentContext.failure)
        assertEquals(beforeCloseFailures, facade.debugState())

        egl.makeCurrentB()
        facade.close()
        val afterClose = facade.debugState()
        assertTrue(afterClose.closed)
        assertEquals(0, afterClose.liveHandleCount)
        assertEquals(beforeCloseFailures.deleteInvocationCount + 1, afterClose.deleteInvocationCount)
        assertEquals(beforeCloseFailures.deletedHandleCount + 1, afterClose.deletedHandleCount)
        println("OBSERVED CLOSE_HANDLE=${closeName.value}")
        println("OBSERVED CLOSE_NO_CURRENT=${closeNoContext.failure},STATE_UNCHANGED=true")
        println("OBSERVED CLOSE_DIFFERENT_CURRENT=${closeDifferentContext.failure},STATE_UNCHANGED=true")
        println("OBSERVED CLOSE_EXACT_CURRENT=success,DELETE_INVOCATIONS=${afterClose.deleteInvocationCount}")

        egl.makeNoContextCurrent()
        facade.close()
        assertEquals(afterClose, facade.debugState())
        println("OBSERVED CLOSE_IDEMPOTENT_WITH_NO_CURRENT=true")
    }
}

private inline fun <T> withEgl(block: (EglHarness) -> T): T {
    val harness = EglHarness.create()
    try {
        return block(harness)
    } finally {
        harness.close()
    }
}

private class EglHarness private constructor(
    private val eglLibrary: DynamicLibrary,
    private val display: COpaquePointer,
    private val surfaceA: COpaquePointer,
    private val surfaceB: COpaquePointer,
    private val contextA: COpaquePointer,
    private val contextB: COpaquePointer,
    private val makeCurrent: CPointer<EglMakeCurrent>,
    private val destroySurface: CPointer<EglDestroySurface>,
    private val destroyContext: CPointer<EglDestroyContext>,
    private val terminate: CPointer<EglTerminate>,
    private val getError: CPointer<EglGetError>,
    val vendor: String,
    val version: String,
    val clientApis: String,
) {
    val contextBDescription: String = contextB.toString()

    fun makeCurrentA() {
        eglCheck(makeCurrent(display, surfaceA, surfaceA, contextA), "eglMakeCurrent(A)")
    }

    fun makeCurrentB() {
        eglCheck(makeCurrent(display, surfaceB, surfaceB, contextB), "eglMakeCurrent(B)")
    }

    fun makeNoContextCurrent() {
        eglCheck(makeCurrent(display, null, null, null), "eglMakeCurrent(NO_CONTEXT)")
    }

    fun close() {
        makeCurrent(display, null, null, null)
        destroySurface(display, surfaceA)
        destroySurface(display, surfaceB)
        destroyContext(display, contextA)
        destroyContext(display, contextB)
        terminate(display)
        eglLibrary.close()
    }

    private fun eglCheck(result: UInt, operation: String) {
        check(result != 0u) { "$operation failed with EGL error 0x${getError().toString(16)}" }
    }

    companion object {
        fun create(): EglHarness = memScoped {
            val library = DynamicLibrary.open("libEGL.so.1")
            val getDisplay = library.symbol<EglGetDisplay>("eglGetDisplay")
            val initialize = library.symbol<EglInitialize>("eglInitialize")
            val bindApi = library.symbol<EglBindApi>("eglBindAPI")
            val chooseConfig = library.symbol<EglChooseConfig>("eglChooseConfig")
            val createPbufferSurface = library.symbol<EglCreatePbufferSurface>("eglCreatePbufferSurface")
            val createContext = library.symbol<EglCreateContext>("eglCreateContext")
            val makeCurrent = library.symbol<EglMakeCurrent>("eglMakeCurrent")
            val destroySurface = library.symbol<EglDestroySurface>("eglDestroySurface")
            val destroyContext = library.symbol<EglDestroyContext>("eglDestroyContext")
            val terminate = library.symbol<EglTerminate>("eglTerminate")
            val getError = library.symbol<EglGetError>("eglGetError")
            val queryString = library.symbol<EglQueryString>("eglQueryString")

            val display = checkNotNull(getDisplay(null)) { "eglGetDisplay(EGL_DEFAULT_DISPLAY) returned EGL_NO_DISPLAY" }
            val major = alloc<IntVar>()
            val minor = alloc<IntVar>()
            check(initialize(display, major.ptr, minor.ptr) != 0u) {
                "eglInitialize failed with EGL error 0x${getError().toString(16)}"
            }
            check(bindApi(EGL_OPENGL_API) != 0u) {
                "eglBindAPI(EGL_OPENGL_API) failed with EGL error 0x${getError().toString(16)}"
            }

            val configAttributes = cValuesOf(
                EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
                EGL_RED_SIZE, 8,
                EGL_GREEN_SIZE, 8,
                EGL_BLUE_SIZE, 8,
                EGL_ALPHA_SIZE, 8,
                EGL_NONE,
            )
            val config = alloc<COpaquePointerVar>()
            val configCount = alloc<IntVar>()
            check(chooseConfig(display, configAttributes.ptr, config.ptr, 1, configCount.ptr) != 0u) {
                "eglChooseConfig failed with EGL error 0x${getError().toString(16)}"
            }
            check(configCount.value == 1) { "eglChooseConfig returned ${configCount.value} configs" }
            val selectedConfig = checkNotNull(config.value)

            val surfaceAttributes = cValuesOf(EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE)
            val surfaceA = checkNotNull(createPbufferSurface(display, selectedConfig, surfaceAttributes.ptr)) {
                "eglCreatePbufferSurface(A) failed with EGL error 0x${getError().toString(16)}"
            }
            val surfaceB = checkNotNull(createPbufferSurface(display, selectedConfig, surfaceAttributes.ptr)) {
                "eglCreatePbufferSurface(B) failed with EGL error 0x${getError().toString(16)}"
            }

            val contextAttributes = cValuesOf(
                EGL_CONTEXT_MAJOR_VERSION, 3,
                EGL_CONTEXT_MINOR_VERSION, 3,
                EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                EGL_NONE,
            )
            val contextA = checkNotNull(createContext(display, selectedConfig, null, contextAttributes.ptr)) {
                "eglCreateContext(A) failed with EGL error 0x${getError().toString(16)}"
            }
            val contextB = checkNotNull(createContext(display, selectedConfig, null, contextAttributes.ptr)) {
                "eglCreateContext(B) failed with EGL error 0x${getError().toString(16)}"
            }
            check(contextA != contextB) { "EGL returned the same identity for two contexts" }

            fun eglString(name: Int): String = checkNotNull(queryString(display, name)) {
                "eglQueryString($name) returned null"
            }.toKString()

            println("OBSERVED EGL_INITIALIZED_VERSION=${major.value}.${minor.value}")
            EglHarness(
                eglLibrary = library,
                display = display,
                surfaceA = surfaceA,
                surfaceB = surfaceB,
                contextA = contextA,
                contextB = contextB,
                makeCurrent = makeCurrent,
                destroySurface = destroySurface,
                destroyContext = destroyContext,
                terminate = terminate,
                getError = getError,
                vendor = eglString(EGL_VENDOR),
                version = eglString(EGL_VERSION),
                clientApis = eglString(EGL_CLIENT_APIS),
            )
        }
    }
}

private const val EGL_OPENGL_API: UInt = 0x30A2u
private const val EGL_NONE: Int = 0x3038
private const val EGL_SURFACE_TYPE: Int = 0x3033
private const val EGL_PBUFFER_BIT: Int = 0x0001
private const val EGL_RENDERABLE_TYPE: Int = 0x3040
private const val EGL_OPENGL_BIT: Int = 0x0008
private const val EGL_RED_SIZE: Int = 0x3024
private const val EGL_GREEN_SIZE: Int = 0x3023
private const val EGL_BLUE_SIZE: Int = 0x3022
private const val EGL_ALPHA_SIZE: Int = 0x3021
private const val EGL_WIDTH: Int = 0x3057
private const val EGL_HEIGHT: Int = 0x3056
private const val EGL_CONTEXT_MAJOR_VERSION: Int = 0x3098
private const val EGL_CONTEXT_MINOR_VERSION: Int = 0x30FB
private const val EGL_CONTEXT_OPENGL_PROFILE_MASK: Int = 0x30FD
private const val EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT: Int = 0x0001
private const val EGL_VENDOR: Int = 0x3053
private const val EGL_VERSION: Int = 0x3054
private const val EGL_CLIENT_APIS: Int = 0x308D
