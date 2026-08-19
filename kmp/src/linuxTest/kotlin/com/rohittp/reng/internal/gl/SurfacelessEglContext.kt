@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.invoke
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.posix.RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym

private const val EGL_NONE: Int = 0x3038
private const val EGL_PLATFORM_SURFACELESS_MESA: Int = 0x31DD
private const val EGL_OPENGL_ES_API: Int = 0x30A0
private const val EGL_OPENGL_API: Int = 0x30A2
private const val EGL_RENDERABLE_TYPE: Int = 0x3040
private const val EGL_SURFACE_TYPE: Int = 0x3033
private const val EGL_PBUFFER_BIT: Int = 0x0001
private const val EGL_OPENGL_ES3_BIT: Int = 0x0040
private const val EGL_OPENGL_BIT: Int = 0x0008
private const val EGL_CONTEXT_MAJOR_VERSION: Int = 0x3098
private const val EGL_CONTEXT_MINOR_VERSION: Int = 0x30FB
private const val EGL_CONTEXT_OPENGL_PROFILE_MASK: Int = 0x30FD
private const val EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT: Int = 0x0001

internal class SurfacelessEglContext private constructor(
    private val display: COpaquePointer,
    private val context: COpaquePointer,
    private val destroyContext: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> UInt>>,
    private val makeCurrent: CPointer<CFunction<
        (COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> UInt>>,
    private val terminate: CPointer<CFunction<(COpaquePointer?) -> UInt>>,
    private val getCurrentContext: CPointer<CFunction<() -> COpaquePointer?>>,
) {
    internal val probe: RenderContextProbe = RenderContextProbe {
        getCurrentContext()?.let { RenderContextIdentity(it.rawValue.toLong()) }
    }

    internal fun destroy() {
        makeCurrent(display, null, null, null)
        destroyContext(display, context)
        terminate(display)
    }

    internal companion object {
        internal fun create(dialect: ShaderDialect): SurfacelessEglContext {
            val library = requireNotNull(dlopen("libEGL.so.1", RTLD_NOW)) {
                "libEGL.so.1 is required; install libegl1, libegl-mesa0 and libgles2"
            }

            val getPlatformDisplay = library.function<
                (UInt, COpaquePointer?, CPointer<LongVar>?) -> COpaquePointer?>("eglGetPlatformDisplay")
            val initialize = library.function<
                (COpaquePointer?, CPointer<IntVar>?, CPointer<IntVar>?) -> UInt>("eglInitialize")
            val bindApi = library.function<(UInt) -> UInt>("eglBindAPI")
            val chooseConfig = library.function<
                (COpaquePointer?, CPointer<IntVar>?, CPointer<COpaquePointerVar>?, Int, CPointer<IntVar>?) -> UInt>(
                "eglChooseConfig",
            )
            val createContext = library.function<
                (COpaquePointer?, COpaquePointer?, COpaquePointer?, CPointer<IntVar>?) -> COpaquePointer?>(
                "eglCreateContext",
            )
            val makeCurrent = library.function<
                (COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> UInt>("eglMakeCurrent")
            val getCurrentContext = library.function<() -> COpaquePointer?>("eglGetCurrentContext")
            val destroyContext = library.function<(COpaquePointer?, COpaquePointer?) -> UInt>("eglDestroyContext")
            val terminate = library.function<(COpaquePointer?) -> UInt>("eglTerminate")

            val display = requireNotNull(
                getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA.toUInt(), null, null),
            ) { "EGL_PLATFORM_SURFACELESS_MESA display is required" }
            memScoped {
                val major = alloc<IntVar>()
                val minor = alloc<IntVar>()
                require(initialize(display, major.ptr, minor.ptr) != 0u) { "eglInitialize failed" }
            }

            val renderableBit = when (dialect) {
                ShaderDialect.GLES -> EGL_OPENGL_ES3_BIT
                ShaderDialect.DESKTOP -> EGL_OPENGL_BIT
            }
            val api = when (dialect) {
                ShaderDialect.GLES -> EGL_OPENGL_ES_API
                ShaderDialect.DESKTOP -> EGL_OPENGL_API
            }
            require(bindApi(api.toUInt()) != 0u) { "eglBindAPI failed" }

            val context = memScoped {
                val configAttributes = allocArray<IntVar>(7)
                configAttributes[0] = EGL_SURFACE_TYPE
                configAttributes[1] = EGL_PBUFFER_BIT
                configAttributes[2] = EGL_RENDERABLE_TYPE
                configAttributes[3] = renderableBit
                configAttributes[4] = EGL_NONE
                val configs = allocArray<COpaquePointerVar>(1)
                val configCount = alloc<IntVar>()
                require(
                    chooseConfig(display, configAttributes, configs, 1, configCount.ptr) != 0u &&
                        configCount.value > 0,
                ) { "no surfaceless EGL config for $dialect" }

                val contextAttributes = allocArray<IntVar>(7)
                when (dialect) {
                    ShaderDialect.GLES -> {
                        contextAttributes[0] = EGL_CONTEXT_MAJOR_VERSION
                        contextAttributes[1] = 3
                        contextAttributes[2] = EGL_CONTEXT_MINOR_VERSION
                        contextAttributes[3] = 0
                        contextAttributes[4] = EGL_NONE
                    }

                    ShaderDialect.DESKTOP -> {
                        contextAttributes[0] = EGL_CONTEXT_MAJOR_VERSION
                        contextAttributes[1] = 3
                        contextAttributes[2] = EGL_CONTEXT_MINOR_VERSION
                        contextAttributes[3] = 3
                        contextAttributes[4] = EGL_CONTEXT_OPENGL_PROFILE_MASK
                        contextAttributes[5] = EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT
                        contextAttributes[6] = EGL_NONE
                    }
                }
                requireNotNull(createContext(display, configs[0], null, contextAttributes)) {
                    "eglCreateContext failed for $dialect"
                }
            }

            require(makeCurrent(display, null, null, context) != 0u) { "eglMakeCurrent failed" }
            return SurfacelessEglContext(
                display, context, destroyContext, makeCurrent, terminate, getCurrentContext,
            )
        }
    }
}

private inline fun <reified F : Function<*>> COpaquePointer.function(
    name: String,
): CPointer<CFunction<F>> = requireNotNull(dlsym(this, name)) { "$name is missing from libEGL.so.1" }
    .reinterpret()
