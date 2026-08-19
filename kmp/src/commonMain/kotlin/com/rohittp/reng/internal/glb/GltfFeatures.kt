package com.rohittp.reng.internal.glb

/** The four accessor `type`/`componentType` combinations RenG draws attributes with, plus the two
 * component types the specification forbids [GltfAccessor.normalized] on -- see
 * [GltfUnsupported.NORMALIZED_NOT_PERMITTED]. */
private const val COMPONENT_TYPE_FLOAT = 5126
private const val COMPONENT_TYPE_UNSIGNED_INT = 5125

/** glTF's default primitive topology and the only one ADR 0021 admits. */
private const val SUPPORTED_PRIMITIVE_MODE = 4

/** The only embedded image media type RenG decodes. */
private const val SUPPORTED_IMAGE_MEDIA_TYPE = "image/png"

/** Attribute semantics ADR 0021 admits. Anything else -- `TEXCOORD_n`/`COLOR_n` above zero,
 * `JOINTS_n`, `WEIGHTS_n`, or an application-specific `_CUSTOM` name -- is
 * [GltfUnsupported.ATTRIBUTE_SEMANTIC]. */
private val SUPPORTED_ATTRIBUTE_SEMANTICS = setOf("POSITION", "NORMAL", "TEXCOORD_0", "TANGENT", "COLOR_0")

/** Animation channel target paths RenG plays. `weights` -- morph-target weight animation -- is
 * outside the subset because morph targets themselves are ([GltfUnsupported.MORPH_TARGET]). */
private val SUPPORTED_ANIMATION_TARGET_PATHS = setOf("translation", "rotation", "scale")

/** Animation sampler interpolations RenG plays. `CUBICSPLINE` is rejected rather than
 * approximated: substituting `LINEAR` would silently change motion (ADR 0021). */
private val SUPPORTED_INTERPOLATIONS = setOf("LINEAR", "STEP")

/**
 * The outcome of [validateGltfFeatures]: either [document] draws entirely within the subset ADR
 * 0021 admits, or the first feature outside it, reported as [Unsupported.reason].
 */
internal sealed interface GltfFeatureResult {
    data object Supported : GltfFeatureResult

    data class Unsupported(val reason: GltfUnsupported) : GltfFeatureResult
}

/**
 * Every reason [validateGltfFeatures] can refuse a document. Unlike [GltfReject], every one of
 * these documents is a perfectly valid glTF 2.0 asset -- ADR 0021 draws the line here, not at
 * malformation, so `PARSE_GLB` must never report any of these.
 *
 * - [EXTENSION_REQUIRED] covers any non-empty `extensionsRequired`, one blanket rule that covers
 *   Draco, meshopt, Basis and every future compression extension without going stale. Checked
 *   first and unconditionally: a Draco-shaped accessor with no `bufferView` is reported here, not
 *   as [ACCESSOR_WITHOUT_BUFFER_VIEW], because the true fault is the required extension, not the
 *   accessor shape that extension happens to produce.
 * - [ACCESSOR_WITHOUT_BUFFER_VIEW] covers an accessor with no `bufferView` when
 *   `extensionsRequired` is empty -- the specification's "all zeros" form, legal but drawing
 *   nothing RenG can distinguish from a genuine compression extension's absence.
 * - [SPARSE_ACCESSOR] covers any accessor carrying a `sparse` member. Checked before
 *   [ACCESSOR_WITHOUT_BUFFER_VIEW] on the same accessor, since a sparse accessor's `bufferView`
 *   being absent is the ordinary base-value form the specification itself permits for sparse
 *   accessors, not the Draco signature that code names.
 * - [PRIMITIVE_MODE] covers any primitive `mode` other than `4` (`TRIANGLES`) -- strips, fans,
 *   points and lines.
 * - [ATTRIBUTE_SEMANTIC] covers any primitive attribute semantic outside
 *   [SUPPORTED_ATTRIBUTE_SEMANTICS]: `TEXCOORD_n`/`COLOR_n` above zero, `JOINTS_n`, `WEIGHTS_n`,
 *   and any application-specific `_`-prefixed name. Checked after [SKIN]: a skinned mesh carries
 *   both the flagged `JOINTS_0`/`WEIGHTS_0` attributes and a `node.skin` reference, and stripping
 *   just the attributes would not fix the file, since the skin reference remains and export fails
 *   again next round. [SKIN] names the feature the consumer must actually remove.
 * - [SKIN] covers any node carrying a `skin` index. Checked before [ATTRIBUTE_SEMANTIC] for exactly
 *   the reason given there.
 * - [MORPH_TARGET] covers any primitive whose `targets` array is non-empty.
 * - [ANIMATION_TARGET_PATH] covers any animation channel whose `target.path` is not one of
 *   `translation`, `rotation`, `scale` -- in practice, `weights`.
 * - [INTERPOLATION] covers any animation sampler whose `interpolation` is not `LINEAR` or `STEP`
 *   -- in practice, `CUBICSPLINE`.
 * - [IMAGE_MEDIA_TYPE] covers any image whose `mimeType` is not `image/png`, including an image
 *   with no declared `mimeType` at all. Checked only after [EXTERNAL_URI] on the same image, since
 *   a `uri`-sourced image legitimately omits `mimeType`.
 * - [EXTERNAL_URI] covers any buffer or image carrying a `uri`, `data:` URIs included: a resource
 *   named inside a GLB has no Resource Locator, no Resource Class, and no place in the operation's
 *   route set, so RenG has no correct way to resolve it (ADR 0021).
 * - [MULTIPLE_BUFFERS] covers a document declaring more than one buffer. RenG's only supported
 *   buffer form is `buffers[0]` embedded in the GLB's BIN chunk; `PARSE_GLB` tolerates a second
 *   declared buffer structurally (nothing about it is malformed on its own), but RenG has no route
 *   to any buffer but the first.
 * - [SCENE_AMBIGUOUS] covers `scene` absent when `scenes` has zero, or two or more, entries --
 *   there is no single default scene to draw.
 * - [NORMALIZED_NOT_PERMITTED] covers an accessor with `normalized: true` whose `componentType` is
 *   `FLOAT` or `UNSIGNED_INT`, a combination the specification itself forbids.
 */
internal enum class GltfUnsupported {
    EXTENSION_REQUIRED,
    ACCESSOR_WITHOUT_BUFFER_VIEW,
    SPARSE_ACCESSOR,
    PRIMITIVE_MODE,
    ATTRIBUTE_SEMANTIC,
    SKIN,
    MORPH_TARGET,
    ANIMATION_TARGET_PATH,
    INTERPOLATION,
    IMAGE_MEDIA_TYPE,
    EXTERNAL_URI,
    MULTIPLE_BUFFERS,
    SCENE_AMBIGUOUS,
    NORMALIZED_NOT_PERMITTED,
}

/**
 * `VALIDATE_GLB_FEATURES`: refuses a [document] that `PARSE_GLB` ([parseGltf]) already accepted as
 * a well-formed glTF 2.0 asset, but which draws outside the subset ADR 0021 admits. Every check
 * here answers "is this a feature RenG draws", never "is this bytes malformed" -- that split is
 * ADR 0021's, not this function's to blur.
 */
internal fun validateGltfFeatures(document: GltfDocument): GltfFeatureResult =
    try {
        GltfFeatureValidator(document).validate()
        GltfFeatureResult.Supported
    } catch (signal: GltfUnsupportedSignal) {
        GltfFeatureResult.Unsupported(signal.reason)
    }

/** Internal control-flow signal: unwound by [validateGltfFeatures] into a
 * [GltfFeatureResult.Unsupported], never seen outside this file. */
private class GltfUnsupportedSignal(val reason: GltfUnsupported) : RuntimeException()

private fun reject(reason: GltfUnsupported): Nothing = throw GltfUnsupportedSignal(reason)

private class GltfFeatureValidator(private val document: GltfDocument) {
    fun validate() {
        if (document.extensionsRequired.isNotEmpty()) reject(GltfUnsupported.EXTENSION_REQUIRED)

        validateBuffers()
        validateAccessors()
        validateImages()
        validateNodes()
        validateMeshes()
        validateAnimations()
        validateScene()
    }

    private fun validateBuffers() {
        for (buffer in document.buffers) {
            if (buffer.uri != null) reject(GltfUnsupported.EXTERNAL_URI)
        }
        if (document.buffers.size > 1) reject(GltfUnsupported.MULTIPLE_BUFFERS)
    }

    private fun validateAccessors() {
        for (accessor in document.accessors) {
            if (accessor.sparse) reject(GltfUnsupported.SPARSE_ACCESSOR)
            if (accessor.bufferView == null) reject(GltfUnsupported.ACCESSOR_WITHOUT_BUFFER_VIEW)
            val forbidsNormalized = accessor.componentType == COMPONENT_TYPE_FLOAT ||
                accessor.componentType == COMPONENT_TYPE_UNSIGNED_INT
            if (accessor.normalized && forbidsNormalized) reject(GltfUnsupported.NORMALIZED_NOT_PERMITTED)
        }
    }

    private fun validateImages() {
        for (image in document.images) {
            if (image.uri != null) reject(GltfUnsupported.EXTERNAL_URI)
            if (image.mimeType != SUPPORTED_IMAGE_MEDIA_TYPE) reject(GltfUnsupported.IMAGE_MEDIA_TYPE)
        }
    }

    private fun validateMeshes() {
        for (mesh in document.meshes) {
            for (primitive in mesh.primitives) {
                if (primitive.mode != SUPPORTED_PRIMITIVE_MODE) reject(GltfUnsupported.PRIMITIVE_MODE)
                for (semantic in primitive.attributes.keys) {
                    if (semantic !in SUPPORTED_ATTRIBUTE_SEMANTICS) reject(GltfUnsupported.ATTRIBUTE_SEMANTIC)
                }
                if (primitive.targetCount > 0) reject(GltfUnsupported.MORPH_TARGET)
            }
        }
    }

    private fun validateNodes() {
        for (node in document.nodes) {
            if (node.skin != null) reject(GltfUnsupported.SKIN)
        }
    }

    private fun validateAnimations() {
        for (animation in document.animations) {
            for (channel in animation.channels) {
                if (channel.targetPath !in SUPPORTED_ANIMATION_TARGET_PATHS) {
                    reject(GltfUnsupported.ANIMATION_TARGET_PATH)
                }
            }
            for (sampler in animation.samplers) {
                if (sampler.interpolation !in SUPPORTED_INTERPOLATIONS) reject(GltfUnsupported.INTERPOLATION)
            }
        }
    }

    private fun validateScene() {
        if (document.defaultScene == null && document.scenes.size != 1) {
            reject(GltfUnsupported.SCENE_AMBIGUOUS)
        }
    }
}
