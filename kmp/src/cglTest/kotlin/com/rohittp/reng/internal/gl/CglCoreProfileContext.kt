package com.rohittp.reng.internal.gl

/**
 * Which CGL renderer a fixture context asks for.
 *
 * This is not a preference, it is a portability control. A hosted GitHub macOS runner has no GPU:
 * it reports `Apple Software Renderer / 4.1 APPLE-23.1.1`, while a developer's machine reports
 * `Apple M3 Max / 4.1 Metal - 90.5`. The two disagree on things a pixel assertion can feel — most
 * concretely `GL_SUBPIXEL_BITS` is 4 on the hardware path and 10 on the software one, so the two
 * rasterise the *same* quad's boundary differently. [SOFTWARE] is therefore the only way a
 * developer can run a readback assertion against the driver CI will actually run it on.
 */
internal enum class MacosGlRenderer {
    /** Whatever CGL picks: the GPU on a developer's machine, the CPU rasteriser on a hosted runner. */
    DEFAULT,

    /** Apple's CPU rasteriser, explicitly — the driver a hosted GitHub macOS runner always uses. */
    SOFTWARE,
}

/**
 * A CGL core-profile context, created and made current on the calling thread. Kotlin/Native builds
 * it through `platform.OpenGLCommon`, the JVM through LWJGL's `CGL`; both pass the same attributes,
 * so both reach the same driver.
 */
internal interface CglCoreProfileContext {
    val probe: RenderContextProbe

    fun destroy()

    companion object {
        /**
         * Creates and makes current a core-profile context, or returns `null` when [renderer] is
         * unavailable on this machine. Only [MacosGlRenderer.SOFTWARE] can be unavailable, and a
         * caller that asks for it is expected to say out loud that it skipped.
         */
        fun createOrNull(renderer: MacosGlRenderer): CglCoreProfileContext? =
            openCglCoreProfileContext(renderer)

        fun create(): CglCoreProfileContext = requireNotNull(
            createOrNull(MacosGlRenderer.DEFAULT),
        ) { "no core-profile pixel format; acceleration must not be requested" }
    }
}

/** The platform half of [CglCoreProfileContext.createOrNull]. */
internal expect fun openCglCoreProfileContext(renderer: MacosGlRenderer): CglCoreProfileContext?
