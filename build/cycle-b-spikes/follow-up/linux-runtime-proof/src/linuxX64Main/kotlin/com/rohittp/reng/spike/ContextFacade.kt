@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rohittp.reng.spike

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.RTLD_LOCAL
import platform.posix.RTLD_NOW
import platform.posix.dlclose
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym

typealias EglGetCurrentContext = CFunction<() -> COpaquePointer?>
typealias GlGetString = CFunction<(UInt) -> CPointer<ByteVar>?>
typealias GlGenFramebuffers = CFunction<(Int, CPointer<UIntVar>?) -> Unit>
typealias GlDeleteFramebuffers = CFunction<(Int, CPointer<UIntVar>?) -> Unit>
typealias GlBindFramebuffer = CFunction<(UInt, UInt) -> Unit>
typealias GlGetError = CFunction<() -> UInt>

internal class DynamicLibrary private constructor(
    private val handle: COpaquePointer,
) {
    fun <T : CFunction<*>> symbol(name: String): CPointer<T> {
        dlerror()
        val address = dlsym(handle, name)
        val error = dlerror()?.toKString()
        check(address != null && error == null) {
            "dlsym($name) failed${if (error == null) "" else ": $error"}"
        }
        return address.reinterpret()
    }

    fun close() {
        check(dlclose(handle) == 0) { "dlclose failed" }
    }

    companion object {
        fun open(path: String): DynamicLibrary {
            dlerror()
            val handle = dlopen(path, RTLD_NOW or RTLD_LOCAL)
            val error = dlerror()?.toKString()
            check(handle != null && error == null) {
                "dlopen($path) failed${if (error == null) "" else ": $error"}"
            }
            return DynamicLibrary(handle)
        }
    }
}

internal enum class ContextAffinityFailure {
    NO_CURRENT_CONTEXT,
    DIFFERENT_CURRENT_CONTEXT,
}

internal class ContextAffinityException(
    val failure: ContextAffinityFailure,
) : IllegalStateException(failure.name)

internal class RenderContextAdoptionRequiredException(
    val contextGeneration: UInt,
) : IllegalStateException("RENDER_CONTEXT_ADOPTION_REQUIRED")

internal class StaleRenderTargetException(
    val targetGeneration: UInt,
    val currentGeneration: UInt,
) : IllegalArgumentException("STALE_RENDER_TARGET")

internal data class FramebufferName(val value: UInt)

internal class RenderContext private constructor(
    internal val handle: COpaquePointer,
) {
    val description: String = handle.toString()

    companion object {
        fun current(): RenderContext {
            val handle = nativeGl.currentContext()
                ?: throw ContextAffinityException(ContextAffinityFailure.NO_CURRENT_CONTEXT)
            return RenderContext(handle)
        }
    }
}

internal class RenderTarget internal constructor(
    val framebufferName: FramebufferName,
    internal val ownerIdentity: UInt,
    val contextGeneration: UInt,
)

internal data class GlInfo(
    val vendor: String,
    val renderer: String,
    val version: String,
)

internal data class FacadeDebugState(
    val expectedContextDescription: String?,
    val contextGeneration: UInt,
    val adoptionRequired: Boolean,
    val liveHandleCount: Int,
    val deleteInvocationCount: Int,
    val deletedHandleCount: Int,
    val closed: Boolean,
)

internal class ContextFacade(initialContext: RenderContext) {
    private val ownerIdentity: UInt = nextOwnerIdentity++
    private var expectedContext: RenderContext? = initialContext
    private var contextGeneration: UInt = 0u
    private val liveFramebufferHandles: MutableSet<UInt> = mutableSetOf()
    private var deleteInvocationCount: Int = 0
    private var deletedHandleCount: Int = 0
    private var closed: Boolean = false

    init {
        requireCurrentMatches(initialContext)
    }

    fun glInfo(): GlInfo {
        requireUsableExactContext()
        return nativeGl.info()
    }

    fun mintRenderTarget(framebufferName: FramebufferName): RenderTarget {
        requireUsableExactContext()
        return RenderTarget(framebufferName, ownerIdentity, contextGeneration)
    }

    fun validate(renderTarget: RenderTarget) {
        check(renderTarget.ownerIdentity == ownerIdentity) { "FOREIGN_RENDER_TARGET" }
        if (renderTarget.contextGeneration != contextGeneration) {
            throw StaleRenderTargetException(renderTarget.contextGeneration, contextGeneration)
        }
    }

    fun bind(renderTarget: RenderTarget) {
        requireUsableExactContext()
        validate(renderTarget)
        nativeGl.bindFramebuffer(renderTarget.framebufferName.value)
    }

    fun createOwnedFramebuffer(): FramebufferName {
        requireUsableExactContext()
        val name = nativeGl.generateFramebuffer()
        check(name != 0u) { "glGenFramebuffers returned framebuffer 0" }
        liveFramebufferHandles += name
        return FramebufferName(name)
    }

    fun notifyGpuObjectsGone() {
        if (closed || expectedContext == null) return
        expectedContext = null
        liveFramebufferHandles.clear()
        contextGeneration += 1u
    }

    fun adoptRenderContext(renderContext: RenderContext) {
        check(!closed) { "RENDERER_CLOSED" }
        check(expectedContext == null) { "RENDER_CONTEXT_ADOPTION_NOT_REQUIRED" }
        requireCurrentMatches(renderContext)
        expectedContext = renderContext
    }

    fun free() {
        if (closed || liveFramebufferHandles.isEmpty()) return
        requireUsableExactContext()
        deleteLiveHandles()
    }

    fun close() {
        if (closed) return
        if (liveFramebufferHandles.isNotEmpty()) {
            requireUsableExactContext()
            deleteLiveHandles()
        }
        closed = true
    }

    fun debugState(): FacadeDebugState = FacadeDebugState(
        expectedContextDescription = expectedContext?.description,
        contextGeneration = contextGeneration,
        adoptionRequired = expectedContext == null && !closed,
        liveHandleCount = liveFramebufferHandles.size,
        deleteInvocationCount = deleteInvocationCount,
        deletedHandleCount = deletedHandleCount,
        closed = closed,
    )

    private fun requireUsableExactContext() {
        check(!closed) { "RENDERER_CLOSED" }
        val expected = expectedContext
            ?: throw RenderContextAdoptionRequiredException(contextGeneration)
        requireCurrentMatches(expected)
    }

    private fun requireCurrentMatches(expected: RenderContext) {
        val current = nativeGl.currentContext()
            ?: throw ContextAffinityException(ContextAffinityFailure.NO_CURRENT_CONTEXT)
        if (current != expected.handle) {
            throw ContextAffinityException(ContextAffinityFailure.DIFFERENT_CURRENT_CONTEXT)
        }
    }

    private fun deleteLiveHandles() {
        val names = liveFramebufferHandles.toUIntArray()
        nativeGl.deleteFramebuffers(names)
        liveFramebufferHandles.clear()
        deleteInvocationCount += 1
        deletedHandleCount += names.size
    }

    private companion object {
        var nextOwnerIdentity: UInt = 1u
    }
}

private class NativeGl {
    private val egl = DynamicLibrary.open("libEGL.so.1")
    private val gl = DynamicLibrary.open("libGL.so.1")
    private val eglGetCurrentContext = egl.symbol<EglGetCurrentContext>("eglGetCurrentContext")
    private val glGetString = gl.symbol<GlGetString>("glGetString")
    private val glGenFramebuffers = gl.symbol<GlGenFramebuffers>("glGenFramebuffers")
    private val glDeleteFramebuffers = gl.symbol<GlDeleteFramebuffers>("glDeleteFramebuffers")
    private val glBindFramebuffer = gl.symbol<GlBindFramebuffer>("glBindFramebuffer")
    private val glGetError = gl.symbol<GlGetError>("glGetError")

    fun currentContext(): COpaquePointer? = eglGetCurrentContext()

    fun info(): GlInfo = GlInfo(
        vendor = glString(GL_VENDOR),
        renderer = glString(GL_RENDERER),
        version = glString(GL_VERSION),
    )

    fun generateFramebuffer(): UInt = memScoped {
        clearGlErrors()
        val name = alloc<UIntVar>()
        glGenFramebuffers(1, name.ptr)
        checkGlError("glGenFramebuffers")
        name.value
    }

    fun bindFramebuffer(name: UInt) {
        clearGlErrors()
        glBindFramebuffer(GL_FRAMEBUFFER, name)
        checkGlError("glBindFramebuffer")
    }

    fun deleteFramebuffers(names: UIntArray) {
        check(names.isNotEmpty())
        memScoped {
            clearGlErrors()
            val nativeNames = allocArray<UIntVar>(names.size)
            names.forEachIndexed { index, name -> nativeNames[index] = name }
            glDeleteFramebuffers(names.size, nativeNames)
            checkGlError("glDeleteFramebuffers")
        }
    }

    private fun glString(name: UInt): String {
        clearGlErrors()
        val value = checkNotNull(glGetString(name)) { "glGetString($name) returned null" }.toKString()
        checkGlError("glGetString")
        return value
    }

    private fun clearGlErrors() {
        while (glGetError() != GL_NO_ERROR) {
            // The spike owns only the calls it brackets; discard any earlier consumer error.
        }
    }

    private fun checkGlError(operation: String) {
        val error = glGetError()
        check(error == GL_NO_ERROR) { "$operation failed with GL error 0x${error.toString(16)}" }
    }
}

private val nativeGl: NativeGl by lazy { NativeGl() }

private const val GL_NO_ERROR: UInt = 0u
private const val GL_VENDOR: UInt = 0x1F00u
private const val GL_RENDERER: UInt = 0x1F01u
private const val GL_VERSION: UInt = 0x1F02u
private const val GL_FRAMEBUFFER: UInt = 0x8D40u
