package com.rohittp.reng

import com.rohittp.reng.internal.cache.ResidentGenerationId
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.model.DecodedModel

/**
 * One [Model] with every CPU-side derivation this frame needs already done, synchronously, inside
 * `RenGRenderer.prepare()`: the GLB decoded, its animation selectors resolved, its tracks sampled at
 * their own `timeSeconds`, its node hierarchy composed against that sampled pose, and one joint
 * palette per skin any draw item names.
 *
 * **Animation is sampled here rather than at draw time, and that is a correctness claim rather than a
 * scheduling one.** [AnimationTrack.timeSeconds] is part of the [FramePlan], so the sampled pose is a
 * pure function of the plan the frame's canonical identity already hashed. Sampling at draw would make
 * a second `draw()` of the same prepared frame able to produce different pixels from the first, which
 * that identity already promises it cannot — the same guarantee [PreparedGeometry] makes for a
 * consumer's own live `uniforms`/`textures` maps.
 *
 * [glbKey] is the model's `EXTERNAL`/[ResourceClass.MODEL_GLB] key, retained because it is what
 * `ResourceKeyDeriver.modelGeometry` and `.modelImage` namespace their own derivations by — the draw
 * path keys every uploaded primitive and every embedded image off it, so two frames over the same GLB
 * upload each of them exactly once (ADR 0018).
 *
 * [overrideTexture] is [Model.texture], already fetched and decoded, reusing [PreparedGeometryTexture]
 * because a model's base-colour override and a geometry's consumer texture are the same thing: an
 * external PNG paired with the key it was acquired under. `null` when the model declares none.
 *
 * Nothing here is GL-shaped. Uploading the primitives and the images needs a live render context and
 * therefore happens in [RenGRenderer.performDraw], exactly as it does for a sticker's image.
 */
internal class PreparedModel(
    internal val placement: Placement,
    internal val glbKey: ResourceKey,
    internal val glbGenerationId: ResidentGenerationId,
    internal val model: DecodedModel,
    internal val overrideTexture: PreparedGeometryTexture?,
    internal val nodeTransforms: List<DoubleMatrix4?>,
    internal val jointMatricesBySkin: Map<Int, List<DoubleMatrix4>>,
    internal val opacity: Float = 1f,
)
