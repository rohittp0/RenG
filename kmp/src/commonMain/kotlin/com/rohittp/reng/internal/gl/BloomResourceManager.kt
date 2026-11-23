package com.rohittp.reng.internal.gl

import com.rohittp.reng.Bloom
import com.rohittp.reng.Diagnostic
import com.rohittp.reng.Geometry
import com.rohittp.reng.RendererConfiguration
import com.rohittp.reng.internal.bloomEmissionPrecisionReducedDiagnostic
import com.rohittp.reng.internal.bloomUnavailableDiagnostic

/** The renderer's bloom targets and programs (ADRs 0074, 0085), allocated on the first active bloom. */
internal class BloomResourceManager(
    private val configuration: RendererConfiguration,
    private val programs: GlProgramCache,
) {
    /** ADR 0074's fixed-size targets and three programs, allocated on the first active bloom. */
    private var bloomResources: BloomResources? = null

    /**
     * Set when this context reported half-float colour rendering but refused a half-float emission
     * target as a format (ADR 0085: an incomplete framebuffer, not a failed link or memory running
     * out), so later frames asking for one go straight to the RGBA8 chain rather than retrying the
     * allocation every frame. Forgotten with the context.
     */
    private var bloomHalfFloatRefused: Boolean = false

    /**
     * This frame's bloom targets: the ones already allocated when their emission precision is the
     * one this frame gets, otherwise a fresh set (ADR 0085), plus the reach targets when the frame
     * has a far band to draw through them. `null`, with `BLOOM_UNAVAILABLE` staged, when the
     * context refuses them; a chain whose half-float targets the context refuses as a format falls
     * back to RGBA8 for the context's life and stages `BLOOM_EMISSION_PRECISION_REDUCED`, as does a
     * context that cannot render half-float at all. Any other failure is `BLOOM_UNAVAILABLE`.
     */
    internal fun resolveBloomResources(
        bloom: Bloom,
        emitters: List<Geometry>,
        profile: RenderContextProfile,
        depthRenderbuffer: Int,
        stagedDiagnostics: MutableList<Diagnostic>,
        /** The captured binding (ADR 0054): target creation binds textures on the host's active unit. */
        binding: GlBinding,
    ): BloomResources? {
        val wantsHighPrecision = bloomUsesHighPrecisionEmission(bloom, profile) && !bloomHalfFloatRefused
        if (bloom.highPrecisionEmission && !wantsHighPrecision) {
            stagedDiagnostics += bloomEmissionPrecisionReducedDiagnostic()
        }
        bloomResources?.let { existing ->
            if (existing.highPrecisionEmission != wantsHighPrecision) {
                deleteBloomResources(binding, programs, existing)
                bloomResources = null
            }
        }
        fun create(highPrecision: Boolean): BloomResourcesResult = createBloomResources(
            binding = binding,
            profile = profile,
            cache = programs,
            outputSize = configuration.outputPixelSize,
            sharedDepthRenderbuffer = depthRenderbuffer,
            highPrecisionEmission = highPrecision,
            // After a refusal the retry, and every later set, is RGBA8 throughout.
            halfFloatUpsample = !bloomHalfFloatRefused,
        )
        val resources = bloomResources ?: run {
            var result = create(wantsHighPrecision)
            // Only a refused half-float target -- emission, downsample or upsample -- is a precision
            // refusal; a program that fails to link or memory running out is BLOOM_UNAVAILABLE, as it
            // is for an RGBA8 chain, and the next frame asking for half-float tries again.
            if (result is BloomResourcesResult.Failed && result.halfFloatTargetRefused) {
                bloomHalfFloatRefused = true
                result = create(false)
                // Reduced only for a frame that asked for half-float emission; an upsample refusal on
                // an RGBA8 chain changes no precision the consumer asked for.
                if (result is BloomResourcesResult.Created && wantsHighPrecision) {
                    stagedDiagnostics += bloomEmissionPrecisionReducedDiagnostic()
                }
            }
            (result as? BloomResourcesResult.Created)?.resources?.also { bloomResources = it }
        }
        if (resources == null) {
            stagedDiagnostics += bloomUnavailableDiagnostic()
            return null
        }
        if (bloomHasFarBand(bloom, emitters) &&
            ensureBloomReachTargets(binding, resources, checkNotNull(bloomFarBandLevel(bloom))) == null
        ) {
            stagedDiagnostics += bloomUnavailableDiagnostic()
            return null
        }
        return resources
    }

    /** Deletes the allocated bloom resources, if any; [forgetResources] then drops the reference. */
    internal fun delete(binding: GlBinding) {
        bloomResources?.let { deleteBloomResources(binding, programs, it) }
    }

    internal fun forgetResources() {
        bloomResources = null
    }

    /** Forgets everything tied to the current context, without a GL delete. */
    internal fun forgetContext() {
        bloomResources = null
        bloomHalfFloatRefused = false
    }
}
