package com.rohittp.reng.internal.gl

import com.rohittp.reng.Geometry
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.Placement
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.internal.failure.toException
import com.rohittp.reng.internal.math.DoubleMatrix3
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.DrawRegime
import com.rohittp.reng.internal.planning.ResolvedGeometry
import com.rohittp.reng.internal.planning.ResolvedPlacement
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolveGeometry
import com.rohittp.reng.internal.planning.resolvePlacement
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera

/**
 * One sticker still carrying its raw, unresolved [Placement] plus the GL texture name its image
 * already uploaded to (Task 4's `uploadTexture`, run once by whoever assembles a [SceneContent]).
 * [SceneContent] resolves [placement] against the frame's camera at draw time — see the class KDoc
 * for why that does not repeat the mistake ADR 0024 warns against.
 *
 * [imageWidthPixels] and [imageHeightPixels] are the uploaded image's own pixel dimensions
 * (`internal.image.DecodedImage.width`/`.height`) — `CONTEXT.md`: a Sticker draws "as a centred
 * local XY quad whose width and height are the image's pixel dimensions." Defaulting both to `1`
 * keeps every pre-existing caller (and test) that has no image dimensions to report compiling
 * unchanged, at exactly the unit-quad size this cycle shipped before Task 9b's fix.
 */
internal class SceneSticker(
    val placement: Placement,
    val texture: Int,
    val imageWidthPixels: Int = 1,
    val imageHeightPixels: Int = 1,
)

/**
 * One consumer [Geometry] paired with the [GeometryPipeline] already compiled — and cached by
 * shader pair — for its own [Geometry.shaderPair]. Compiling and caching that program is
 * `GlProgramCache` bookkeeping that belongs to whoever assembles a [SceneContent] once per frame
 * (Cycle F-1 Task 9's renderer), not to [SceneContent] itself.
 *
 * [consumerUniforms] and [consumerTextures] are the [PreparedFrame][com.rohittp.reng.PreparedFrame]-time
 * SNAPSHOTS of [geometry]'s own [Geometry.uniforms] and [Geometry.textures] — never the live `Map`
 * references [geometry] itself carries. Reading `geometry.uniforms`/`geometry.textures` directly at
 * draw time would be a second, later read of the same caller-owned object beyond
 * `FramePlanningCore.plan()`'s single synchronous read, exactly the gap `Geometry`'s own KDoc warns
 * about: a consumer mutating one of those maps between `prepare()` and `draw()` would then render
 * content differing from what the frame's canonical identity already hashed. `com.rohittp.reng.RenGPreparedFrame`
 * takes both snapshots once, at `prepare()` time — [consumerUniforms] defaults to `geometry.uniforms`
 * only so a test (or any other caller with no prepare/draw gap to protect) can omit it; production
 * drawing always passes the prepare-time snapshot explicitly.
 *
 * [consumerTextures] carries each consumer sampler name's ALREADY-UPLOADED GL texture object name —
 * not a [com.rohittp.reng.internal.image.DecodedImage] — because upload-and-cache-by-`ResourceKey`
 * (Task 9b's texture-lifetime fix) happens one layer up, in whoever assembles a [SceneContent], the
 * same layer [SceneSticker.texture] already establishes the pattern for.
 */
internal class SceneGeometry(
    val geometry: Geometry,
    val pipeline: GeometryPipeline,
    val consumerUniforms: Map<String, ShaderValue> = geometry.uniforms,
    val consumerTextures: Map<String, Int> = emptyMap(),
)

/**
 * Everything one frame needs [SceneContent] to draw. [outputPixelSize] and [frameIndex] feed the
 * documented `uResolution` / `uFrameIndex` uniforms directly; they play no part in placement
 * resolution itself, since [camera] already folded the output size into its projection matrix.
 */
internal class Scene(
    val outputPixelSize: OutputPixelSize,
    val frameIndex: Long,
    val stickers: List<SceneSticker> = emptyList(),
    val geometries: List<SceneGeometry> = emptyList(),
)

/**
 * The [GlFrameContent] Cycle D always left a seam for: its own KDoc says "Cycle D draws no frame
 * content of its own; Cycle E replaces this with the real scene draw." This is that replacement.
 *
 * **Ordering (ADR 0024).** The map regime draws first, depth-tested: every [Geometry] (map-anchored
 * by definition — `CONTEXT.md` says "A Geometry carries no Placement") and every map-anchored
 * sticker. The screen regime then composites on top with depth testing off. [drawStickers] already
 * runs both sticker halves of that order in one call, so [draw] only has to draw every geometry
 * *before* calling it — the ambient depth-test-enabled state that leaves behind for the map-anchored
 * stickers is exactly the state geometries drew under too.
 *
 * **Why resolving [Placement]/[Geometry] here does not put spatial-failure handling inside a GL
 * draw call.** Cycle F-1 Tasks 5 and 6 pushed placement and geometry resolution out of
 * [drawStickers]/[drawGeometry] because [resolvePlacement]/[resolveGeometry] can fail and a GL draw
 * call has no way to surface that failure. That resolution still happens exactly once, at
 * `FRAME_PLANNING`, inside `internal.planning.planMercatorSpatial` — whoever assembles a
 * [SceneContent] (Task 9's renderer) only does so with a [Geometry]/[Placement] plus [camera] pair
 * that has *already* resolved successfully during `prepare()`. Both resolver functions are pure and
 * deterministic in [camera] and their one domain argument, so calling them again here on the
 * identical inputs cannot newly fail — it is exactly as safe as threading `FRAME_PLANNING`'s
 * resolved objects all the way into this GL layer would have been, without growing that seam before
 * Task 9 fixes what a prepared frame actually retains. A resolution failure reaching [draw] is
 * therefore a caller contract violation, not a legitimate runtime outcome, and is reported as a
 * typed [com.rohittp.reng.RenGException] (`GPU_OPERATION_FAILED` at `DRAW`) via
 * [requireResolvedAtDrawTime] rather than silently swallowed, drawn wrong, or thrown as a bare
 * untyped exception.
 *
 * **Precision.** [composeMapModelViewProjection], [composeScreenModelViewProjection], and
 * [composeGeometryViewProjection] multiply [ResolvedMercatorCamera]'s and [ResolvedPlacement]'s
 * `Double` matrices and vectors throughout, narrowing to `Float` only in the column-major array
 * each hands to [GlBinding.uniformMatrix4fv]. Geometry vertex positions come from
 * [ResolvedGeometry.cornersClockwiseFromTopLeft] untouched but for that same last-step narrowing:
 * this class never derives a vertex position from [Geometry.topLeft] / [Geometry.bottomRight]
 * degrees directly, which is what would discard Cycle B's sub-0.001px camera-relative precision.
 */
internal class SceneContent(
    private val camera: ResolvedMercatorCamera,
    private val scene: Scene,
    private val stickerPipeline: StickerPipeline,
) : GlFrameContent {

    override fun draw(binding: GlBinding) {
        if (scene.geometries.isEmpty() && scene.stickers.isEmpty()) return

        if (scene.geometries.isNotEmpty()) {
            binding.enable(GL_DEPTH_TEST)
            val geometryViewProjection = composeGeometryViewProjection(camera)
            for (sceneGeometry in scene.geometries) {
                val resolved = resolveGeometry(sceneGeometry.geometry, camera).requireResolvedAtDrawTime()
                drawGeometry(
                    binding = binding,
                    pipeline = sceneGeometry.pipeline,
                    cameraRelativeCornersXyz = resolved.cornersToFloatArray(),
                    modelViewProjection = geometryViewProjection,
                    resolutionWidthPixels = scene.outputPixelSize.width.toFloat(),
                    resolutionHeightPixels = scene.outputPixelSize.height.toFloat(),
                    boundsWestSouthEastNorthDegrees = sceneGeometry.geometry.boundsWestSouthEastNorth(),
                    frameIndex = scene.frameIndex,
                    consumerUniforms = sceneGeometry.consumerUniforms,
                    consumerTextures = sceneGeometry.consumerTextures,
                )
            }
        }

        val mapAnchored = ArrayList<ResolvedSticker>()
        val screenAnchored = ArrayList<ResolvedSticker>()
        for (sticker in scene.stickers) {
            val resolved = resolvePlacement(sticker.placement, camera).requireResolvedAtDrawTime()
            // CONTEXT.md: a Sticker draws "as a centred local XY quad whose width and height are
            // the image's pixel dimensions" -- so the image's own dimensions scale the unit quad
            // BEFORE the placement's own (map-metres-per-unit or screen-pixels-per-unit) scale is
            // applied, exactly the way `affineModelMatrix`'s per-axis scale composes below.
            val localDimensions = DoubleVector3(
                sticker.imageWidthPixels.toDouble(),
                sticker.imageHeightPixels.toDouble(),
                1.0,
            )
            val modelViewProjection = when (resolved.drawRegime) {
                DrawRegime.MAP_OCCLUDED -> composeMapModelViewProjection(camera, resolved, localDimensions)
                DrawRegime.SCREEN_COMPOSITED ->
                    composeScreenModelViewProjection(scene.outputPixelSize, resolved, localDimensions)
            }
            val entry = ResolvedSticker(
                modelViewProjection = modelViewProjection,
                texture = sticker.texture,
                screenCompositeZ = resolved.screenCompositeZ ?: 0.0,
            )
            when (resolved.drawRegime) {
                DrawRegime.MAP_OCCLUDED -> mapAnchored += entry
                DrawRegime.SCREEN_COMPOSITED -> screenAnchored += entry
            }
        }

        drawStickers(binding, stickerPipeline, StickerWorld(mapAnchored, screenAnchored))
    }
}

/**
 * Unwraps a draw-time re-resolution (of a [Placement], [Geometry], or [com.rohittp.reng.Camera])
 * that a reviewer established CANNOT legitimately fail: only per-object resolution is re-derived at
 * draw time (the camera itself arrives already resolved), and every resolver this calls
 * ([resolvePlacement], [resolveGeometry], [com.rohittp.reng.internal.projection.resolveMercatorCamera])
 * is pure and deterministic in its inputs, which are themselves immutable
 * (`com.rohittp.reng.RenGPreparedFrame` snapshots every mutable input before this ever runs — see
 * `Geometry.uniforms`/`.textures`'s KDoc). A [SpatialOutcome.Failure] reaching here is therefore a
 * caller contract violation, not a legitimate runtime outcome.
 *
 * **Typed, not [error].** This used to throw a bare, untyped `IllegalStateException` via [error] —
 * which contradicted RenG's typed-failure contract at exactly the boundary `drawFrame`'s
 * `try`/`finally` cannot shield a consumer from (state restoration still runs, but the exception
 * still escapes to the caller of `Renderer.draw`). [RenGErrorCode.GPU_OPERATION_FAILED] at
 * [PipelineStage.DRAW] with no resource key is the same generic "something is wrong at draw time
 * with nothing more specific to report" shape [GlFrameDrawer.kt]'s own driver-error path already
 * uses ([glOperationFailure]) — reused here rather than a new code, since 9b's scope adds no public
 * ABI and this is exactly the same class of "impossible, but typed if it ever happens" failure.
 *
 * `internal`, not `private`, specifically so a test can drive a synthetic
 * [SpatialOutcome.Failure] directly and assert the resulting shape without needing to construct an
 * actually-unreachable end-to-end scenario. Shared by [com.rohittp.reng.RenGRenderer]'s own
 * draw-time camera re-resolution, which used to duplicate this exact `error(...)` pattern.
 */
internal fun <T> SpatialOutcome<T>.requireResolvedAtDrawTime(): T = when (this) {
    is SpatialOutcome.Success -> value
    is SpatialOutcome.Failure -> throw glOperationFailure(PipelineStage.DRAW, resourceKey = null).toException()
}

/**
 * [ResolvedGeometry.cornersClockwiseFromTopLeft] narrowed to a flat `x,y,z,x,y,z,...` `FloatArray`
 * in the same clockwise-from-top-left order [drawGeometry] documents. This is the only place a
 * resolved corner's `Double` components lose precision, and it is a direct per-component
 * `toFloat()` narrowing — never a recomputation from degrees.
 */
private fun ResolvedGeometry.cornersToFloatArray(): FloatArray {
    val corners = cornersClockwiseFromTopLeft
    val result = FloatArray(corners.size * 3)
    corners.forEachIndexed { index, corner ->
        result[index * 3] = corner.x.toFloat()
        result[index * 3 + 1] = corner.y.toFloat()
        result[index * 3 + 2] = corner.z.toFloat()
    }
    return result
}

/**
 * The informational `uGeometryBounds` payload: west, south, east, north in degrees, straight from
 * [Geometry]'s own construction-time-validated corners (`topLeft.x > bottomRight.x` and
 * `topLeft.y < bottomRight.y`). Never fed into a vertex position — see [UNIFORM_GEOMETRY_BOUNDS].
 */
private fun Geometry.boundsWestSouthEastNorth(): FloatArray = floatArrayOf(
    topLeft.y.toFloat(),
    bottomRight.x.toFloat(),
    bottomRight.y.toFloat(),
    topLeft.x.toFloat(),
)

/**
 * A geometry carries no [Placement] (`CONTEXT.md`), so its vertices need no per-object model
 * matrix — [resolveGeometry] already resolved every corner directly into camera-relative logical
 * pixels. The uniform documented as `uModelViewProjection` is therefore exactly the camera's own
 * view-projection for a geometry.
 */
internal fun composeGeometryViewProjection(camera: ResolvedMercatorCamera): FloatArray =
    (camera.projectionMatrix * camera.viewMatrix).toColumnMajorFloatArray()

/**
 * Composes a map-anchored sticker or model's model-view-projection matrix from
 * [PlacementResolver][com.rohittp.reng.internal.planning.resolvePlacement]'s output and
 * [CameraMatrices][com.rohittp.reng.internal.projection.resolveMercatorCamera]'s output.
 *
 * [ResolvedPlacement.directionTransform] is already expressed as the rotation to apply directly in
 * *camera space* (`PlacementResolver` pre-multiplies out the view matrix's own rotation via the
 * anchor's and the camera's local ENU bases), so the anchor's position is first carried into view
 * space through [camera]'s view matrix, and [ResolvedPlacement.directionTransform] /
 * [ResolvedPlacement.logicalScale] are applied there — never re-multiplied against the view matrix's
 * rotation a second time, which would double-apply it.
 *
 * [localDimensions] scales the local unit quad's x/y/z axes independently BEFORE
 * [ResolvedPlacement.logicalScale] and [ResolvedPlacement.directionTransform] apply — see
 * [affineModelMatrix]'s per-axis (per-column) scale. It defaults to `(1, 1, 1)`, i.e. no local
 * pre-scale at all, which reproduces this function's pre-Task-9b behaviour bit-for-bit. A sticker's
 * caller passes its decoded image's own pixel dimensions here (`CONTEXT.md`: "a centred local XY
 * quad whose width and height are the image's pixel dimensions"); nothing else in this cycle has a
 * local size of its own to report.
 */
internal fun composeMapModelViewProjection(
    camera: ResolvedMercatorCamera,
    placement: ResolvedPlacement,
    localDimensions: DoubleVector3 = DoubleVector3(1.0, 1.0, 1.0),
): FloatArray {
    require(placement.drawRegime == DrawRegime.MAP_OCCLUDED) {
        "composeMapModelViewProjection requires a map-occluded placement"
    }
    val viewSpaceAnchor = camera.viewMatrix.transformAffinePoint(placement.logicalPosition)
    val cameraSpaceModel = affineModelMatrix(
        rotation = placement.directionTransform,
        scale = localDimensions * placement.logicalScale,
        translation = viewSpaceAnchor,
    )
    val modelViewProjection = camera.projectionMatrix * cameraSpaceModel
    return modelViewProjection.toColumnMajorFloatArray()
}

/**
 * Composes a screen-anchored sticker or model's model-view-projection matrix directly in output
 * pixel space — `CONTEXT.md`'s Screen Anchoring never involves [camera]'s view or projection
 * matrix, since it is "resolution against continuous output-pixel screen space," not map space.
 *
 * [ResolvedPlacement.logicalPosition] is already `(position.x, position.y, 0)` in that pixel space
 * (positive x rightward, positive y downward, per `CONTEXT.md`). [ResolvedPlacement.directionTransform]
 * and every drawn thing's local axes (`CONTEXT.md`: "+x right, +y up, +z normal" for a Sticker) use
 * screen-right/screen-up/toward-viewer axes instead, so [SCREEN_ROTATION_ROW_SIGN] flips the y and z
 * rows of the rotation-and-scale block — never the translation — to reconcile the two conventions
 * before the result is combined with [screenOrthographicProjection].
 *
 * [localDimensions] is the same per-axis local pre-scale [composeMapModelViewProjection] documents;
 * it defaults to `(1, 1, 1)`, reproducing this function's pre-Task-9b behaviour bit-for-bit.
 */
internal fun composeScreenModelViewProjection(
    outputPixelSize: OutputPixelSize,
    placement: ResolvedPlacement,
    localDimensions: DoubleVector3 = DoubleVector3(1.0, 1.0, 1.0),
): FloatArray {
    require(placement.drawRegime == DrawRegime.SCREEN_COMPOSITED) {
        "composeScreenModelViewProjection requires a screen-composited placement"
    }
    val pixelSpaceModel = affineModelMatrix(
        rotation = placement.directionTransform,
        scale = localDimensions * placement.logicalScale,
        translation = placement.logicalPosition,
        rotationScaleRowSign = SCREEN_ROTATION_ROW_SIGN,
    )
    val modelViewProjection = screenOrthographicProjection(outputPixelSize) * pixelSpaceModel
    return modelViewProjection.toColumnMajorFloatArray()
}

/** Flips the y and z rows of a rotation-and-scale block; see [composeScreenModelViewProjection]. */
private val SCREEN_ROTATION_ROW_SIGN: DoubleVector3 = DoubleVector3(1.0, -1.0, -1.0)

/**
 * Builds `Translate(translation) * Rotate(rotation) * Scale(scale)` as a single row-major 4x4
 * matrix, applying [rotationScaleRowSign] to each row of the rotation-and-scale block and [scale]'s
 * three components to each COLUMN of it — never [translation], which is always carried through
 * unchanged. Per-column scaling is what makes [scale] anisotropic: `R * diag(scale.x, scale.y,
 * scale.z)` scales local axis `j` by `scale`'s `j`th component before rotation, exactly the local
 * pre-scale [composeMapModelViewProjection] and [composeScreenModelViewProjection] document. When
 * `scale.x == scale.y == scale.z` (every caller before Task 9b's sticker-sizing fix), this reduces
 * to the previous scalar behaviour bit-for-bit, since scaling every column by the same factor is
 * exactly what multiplying the whole row by that scalar already did.
 */
private fun affineModelMatrix(
    rotation: DoubleMatrix3,
    scale: DoubleVector3,
    translation: DoubleVector3,
    rotationScaleRowSign: DoubleVector3 = DoubleVector3(1.0, 1.0, 1.0),
): DoubleMatrix4 = DoubleMatrix4.fromRows(
    listOf(
        listOf(
            rotationScaleRowSign.x * rotation[0, 0] * scale.x,
            rotationScaleRowSign.x * rotation[0, 1] * scale.y,
            rotationScaleRowSign.x * rotation[0, 2] * scale.z,
            translation.x,
        ),
        listOf(
            rotationScaleRowSign.y * rotation[1, 0] * scale.x,
            rotationScaleRowSign.y * rotation[1, 1] * scale.y,
            rotationScaleRowSign.y * rotation[1, 2] * scale.z,
            translation.y,
        ),
        listOf(
            rotationScaleRowSign.z * rotation[2, 0] * scale.x,
            rotationScaleRowSign.z * rotation[2, 1] * scale.y,
            rotationScaleRowSign.z * rotation[2, 2] * scale.z,
            translation.z,
        ),
        listOf(0.0, 0.0, 0.0, 1.0),
    ),
)

/**
 * Maps continuous output-pixel screen space — `[0, width]` by `[0, height]`, positive x rightward,
 * positive y downward (`CONTEXT.md`'s Screen Anchoring) — to clip space. `z` is fixed at `0`, safely
 * inside `[-1, 1]`, because the screen regime always draws with depth testing disabled
 * ([drawStickers]) and never needs a meaningful depth value.
 */
private fun screenOrthographicProjection(outputPixelSize: OutputPixelSize): DoubleMatrix4 {
    val width = outputPixelSize.width.toDouble()
    val height = outputPixelSize.height.toDouble()
    return DoubleMatrix4.fromRows(
        listOf(
            listOf(2.0 / width, 0.0, 0.0, -1.0),
            listOf(0.0, -2.0 / height, 0.0, 1.0),
            listOf(0.0, 0.0, 0.0, 0.0),
            listOf(0.0, 0.0, 0.0, 1.0),
        ),
    )
}

/**
 * Transforms [point] as a homogeneous `(x, y, z, 1)` column vector through this matrix, assuming
 * (as every affine matrix in this file is) that the bottom row is `(0, 0, 0, 1)` so `w` is always
 * exactly `1` and never needs a perspective divide.
 */
private fun DoubleMatrix4.transformAffinePoint(point: DoubleVector3): DoubleVector3 = DoubleVector3(
    x = this[0, 0] * point.x + this[0, 1] * point.y + this[0, 2] * point.z + this[0, 3],
    y = this[1, 0] * point.x + this[1, 1] * point.y + this[1, 2] * point.z + this[1, 3],
    z = this[2, 0] * point.x + this[2, 1] * point.y + this[2, 2] * point.z + this[2, 3],
)

/** This matrix's elements in the column-major order [GlBinding.uniformMatrix4fv] expects. */
private fun DoubleMatrix4.toColumnMajorFloatArray(): FloatArray = FloatArray(16) { index ->
    val row = index % 4
    val column = index / 4
    this[row, column].toFloat()
}
