package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.failure.FailureDescriptor

internal enum class ShaderDialect {
    GLES,
    DESKTOP,
}

internal data class GlVersion(val major: Int, val minor: Int) : Comparable<GlVersion> {
    override fun compareTo(other: GlVersion): Int =
        if (major != other.major) major.compareTo(other.major) else minor.compareTo(other.minor)
}

internal const val ES_SHADING_LANGUAGE_PREFIX: String = "OpenGL ES GLSL ES"
internal const val ES_VERSION_PREFIX: String = "OpenGL ES "
internal val MINIMUM_GLES_VERSION: GlVersion = GlVersion(3, 0)
internal val MINIMUM_DESKTOP_VERSION: GlVersion = GlVersion(3, 3)
internal const val ES3_COMPATIBILITY_EXTENSION: String = "GL_ARB_ES3_compatibility"
internal const val SRGB_WRITE_CONTROL_EXTENSION: String = "GL_EXT_sRGB_write_control"

/**
 * The shading dialect is a runtime property of the adopted context, never a property of the
 * build target. `GL_SHADING_LANGUAGE_VERSION` is the only signal consulted: its string begins
 * with [ES_SHADING_LANGUAGE_PREFIX] exactly when the context is ES. No `expect`/`actual`,
 * `Platform.osFamily`, or target-conditional compilation may substitute for this read.
 */
internal fun detectShaderDialect(shadingLanguageVersion: String): ShaderDialect =
    if (shadingLanguageVersion.startsWith(ES_SHADING_LANGUAGE_PREFIX)) {
        ShaderDialect.GLES
    } else {
        ShaderDialect.DESKTOP
    }

/**
 * Parses `GL_VERSION` rather than reading `GL_MAJOR_VERSION`/`GL_MINOR_VERSION`, because those
 * integer queries raise `GL_INVALID_ENUM` on exactly the pre-3.0 contexts that must be rejected,
 * and a rejection path that itself provokes an error flag is worse than a string parse.
 */
internal fun parseGlVersion(versionText: String, dialect: ShaderDialect): GlVersion? {
    val body = when (dialect) {
        ShaderDialect.GLES ->
            if (versionText.startsWith(ES_VERSION_PREFIX)) {
                versionText.substring(ES_VERSION_PREFIX.length)
            } else {
                return null
            }

        ShaderDialect.DESKTOP ->
            if (versionText.startsWith(ES_VERSION_PREFIX)) return null else versionText
    }

    var index = 0
    var major = 0
    var majorDigits = 0
    while (index < body.length && body[index] in '0'..'9') {
        if (majorDigits == MAXIMUM_VERSION_DIGITS) return null
        major = major * 10 + (body[index] - '0')
        majorDigits += 1
        index += 1
    }
    if (majorDigits == 0 || index >= body.length || body[index] != '.') return null
    index += 1

    var minor = 0
    var minorDigits = 0
    while (index < body.length && body[index] in '0'..'9') {
        if (minorDigits == MAXIMUM_VERSION_DIGITS) return null
        minor = minor * 10 + (body[index] - '0')
        minorDigits += 1
        index += 1
    }
    if (minorDigits == 0) return null
    return GlVersion(major, minor)
}

private const val MAXIMUM_VERSION_DIGITS: Int = 4

internal data class RenderContextProfile(
    val dialect: ShaderDialect,
    val version: GlVersion,
    val vendorName: String,
    val rendererName: String,
    val shadingLanguageVersionText: String,
    val supportsEs3Compatibility: Boolean,
    val supportsSrgbWriteControl: Boolean,
    val maxTextureSize: Int,
    val maxColorAttachments: Int,
    val maxCombinedTextureImageUnits: Int,
)

internal sealed interface RenderContextAdoption {
    data class Adopted(val profile: RenderContextProfile) : RenderContextAdoption

    data class Rejected(val failure: FailureDescriptor) : RenderContextAdoption
}

/**
 * Reads the active extension set through `glGetIntegerv(GL_NUM_EXTENSIONS)` plus indexed
 * `glGetStringi` calls. `glGetString(GL_EXTENSIONS)` returns `NULL` with `GL_INVALID_ENUM` on a
 * desktop core profile, so that path is never used.
 */
internal fun readExtensionNames(binding: GlBinding): Set<String> {
    val count = IntArray(1)
    binding.getIntegerv(GL_NUM_EXTENSIONS, count)
    if (GlErrorQueue.firstOwnError(binding) != GL_NO_ERROR || count[0] <= 0) return emptySet()
    val names = LinkedHashSet<String>()
    for (index in 0 until count[0]) {
        val name = binding.getStringi(GL_EXTENSIONS, index) ?: continue
        names += name
    }
    return names
}

/**
 * Adopts the caller's already-current GL context by querying it, never by mutating it. Three
 * properties are load-bearing here: only `glGetString`, `glGetStringi`, and `glGetIntegerv` are
 * issued, so a rejected context is left exactly as it was found — no binding, enable, or
 * parameter is touched on any path; extensions are read through `GL_NUM_EXTENSIONS` plus
 * `glGetStringi`, never `glGetString(GL_EXTENSIONS)`; and `supportsEs3Compatibility` is recorded
 * but never consulted by the substitution decision — ADR 0008 substitutes on every desktop
 * context regardless of what it advertises.
 */
internal fun adoptRenderContext(binding: GlBinding): RenderContextAdoption {
    GlErrorQueue.drainOnEntry(binding)

    val shadingLanguageVersionText = binding.getString(GL_SHADING_LANGUAGE_VERSION)
        ?: return rejectedRenderContext()
    val versionText = binding.getString(GL_VERSION) ?: return rejectedRenderContext()
    val dialect = detectShaderDialect(shadingLanguageVersionText)
    val version = parseGlVersion(versionText, dialect) ?: return rejectedRenderContext()
    val minimum = when (dialect) {
        ShaderDialect.GLES -> MINIMUM_GLES_VERSION
        ShaderDialect.DESKTOP -> MINIMUM_DESKTOP_VERSION
    }
    if (version < minimum) return rejectedRenderContext()

    val extensions = readExtensionNames(binding)
    val limits = IntArray(1)
    binding.getIntegerv(GL_MAX_TEXTURE_SIZE, limits)
    val maxTextureSize = limits[0]
    binding.getIntegerv(GL_MAX_COLOR_ATTACHMENTS, limits)
    val maxColorAttachments = limits[0]
    binding.getIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, limits)
    val maxCombinedTextureImageUnits = limits[0]
    if (GlErrorQueue.firstOwnError(binding) != GL_NO_ERROR) return rejectedRenderContext()

    return RenderContextAdoption.Adopted(
        RenderContextProfile(
            dialect = dialect,
            version = version,
            vendorName = binding.getString(GL_VENDOR).orEmpty(),
            rendererName = binding.getString(GL_RENDERER).orEmpty(),
            shadingLanguageVersionText = shadingLanguageVersionText,
            supportsEs3Compatibility = ES3_COMPATIBILITY_EXTENSION in extensions,
            supportsSrgbWriteControl = when (dialect) {
                ShaderDialect.DESKTOP -> true
                ShaderDialect.GLES -> SRGB_WRITE_CONTROL_EXTENSION in extensions
            },
            maxTextureSize = maxTextureSize,
            maxColorAttachments = maxColorAttachments,
            maxCombinedTextureImageUnits = maxCombinedTextureImageUnits,
        ),
    )
}

private fun rejectedRenderContext(): RenderContextAdoption.Rejected =
    RenderContextAdoption.Rejected(
        FailureDescriptor(
            code = RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT,
            stage = PipelineStage.CONTEXT_ADOPTION,
        ),
    )
