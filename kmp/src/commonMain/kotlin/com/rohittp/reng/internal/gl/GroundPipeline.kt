package com.rohittp.reng.internal.gl

import com.rohittp.reng.BlendMask
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile

/** Number of samples in the small tent placed over a prefiltered pole-cap edge strip. */
internal const val CAP_BLUR_TAPS: Int = 5

/** Power-of-two count of LOD-zero rows averaged into each pole band's first one-row mip level. */
internal const val CAP_BAND_ROWS: Int = 8

/** Distance inside the Mercator world over which an edge-row tile becomes its pole-band colour. */
internal const val CAP_EDGE_FADE_LOGICAL_PIXELS: Float = 160.0f

/**
 * The most of the world's height, from either Mercator edge, that the edge fade may cover: 0.03 of the
 * world is 85.05° to about 84.0°, north of every land tip (Kaffeklubben Island, 83.66°N), so a
 * zoomed-out frame never fades Greenland or Ellesmere into the band colour.
 */
internal const val CAP_EDGE_FADE_WORLD_FRACTION: Float = 0.03f

/** How many additional source-tile widths the pole-cap blur gains per cap tile height. */
internal const val CAP_BLUR_GROWTH: Float = 6.0f

/** Exclusive cap values plus independently composable ordinary-tile world-edge bits. */
internal const val GROUND_CAP_MODE_NORTH: Int = 1
internal const val GROUND_CAP_MODE_SOUTH: Int = 2
internal const val GROUND_CAP_MODE_NORTH_EDGE: Int = 4
internal const val GROUND_CAP_MODE_SOUTH_EDGE: Int = 8
internal const val GROUND_CAP_MODE_BOTH_EDGES: Int =
    GROUND_CAP_MODE_NORTH_EDGE or GROUND_CAP_MODE_SOUTH_EDGE

/**
 * RenG's own ground shader: one shared, cached [GroundGrid] drawn once per basemap tile instance,
 * with the tile's own model-view-projection as the only per-tile uniform.
 *
 * **The vertex stage takes a grid coordinate and derives the position**, where before Cycle
 * E-terrain the buffer carried a position and a texture coordinate per vertex. That is not tidying:
 * it makes the buffer bit-identical to the globe ground's, which is what lets one lattice serve both
 * grounds (see [GroundGrid]), and it halves what a 129 x 129 grid uploads. The derivation is
 * `(u - 0.5, 0.5 - v)` and its sign convention is [GroundPipeline]'s KDoc.
 *
 * Written as a GLES 3.00 source and compiled through
 * [com.rohittp.reng.internal.shader.ShaderProfilePlan.sourceFor] exactly as a consumer's
 * [ShaderPair] is — RenG is not exempt from its own dialect-substitution rule (ADR 0008).
 *
 * A separate program from [STICKER_SHADER_PAIR] rather than a reuse of it. The two are structurally
 * identical today, and that is precisely the trap: a sticker is consumer content whose shading is
 * free to grow (a tint, a fade, a premultiplied-alpha correction), while the ground is RenG's own
 * backdrop and must keep sampling its tile unchanged. Sharing one program would make every future
 * sticker change a silent ground change, and the cost of not sharing is one more cached program per
 * renderer.
 */
internal const val GROUND_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengGroundGrid;\n" +
        "uniform mat4 rengGroundModelViewProjection;\n" +
        "uniform vec2 rengGroundUvV;\n" +
        "out vec2 rengGroundUv;\n" +
        "out vec2 rengGroundGridUv;\n" +
        "uniform vec3 rengGroundColourWindow;\n" +
        "void main() {\n" +
        "    vec2 position = vec2(rengGroundGrid.x - 0.5, 0.5 - rengGroundGrid.y);\n" +
        "    vec2 rengGroundTileUv = vec2(rengGroundGrid.x, mix(rengGroundUvV.x, " +
        "rengGroundUvV.y, rengGroundGrid.y));\n" +
        "    rengGroundUv = rengGroundTileUv * rengGroundColourWindow.x + rengGroundColourWindow.yz;\n" +
        "    rengGroundGridUv = rengGroundGrid;\n" +
        "    gl_Position = rengGroundModelViewProjection * vec4(position, 0.0, 1.0);\n" +
        "}\n"

/** Globe-compatible ordinary fragment source; Mercator adds its cap branch in the variant below. */
internal const val GROUND_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengGroundTexture;\n" +
        "in vec2 rengGroundUv;\n" +
        "layout(location = 0) out vec4 rengGroundColour;\n" +
        "void main() {\n" +
        "    rengGroundColour = texture(rengGroundTexture, rengGroundUv);\n" +
        "}\n"

internal val MERCATOR_GROUND_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengGroundTexture;\n" +
        groundCapUniformsGlsl(mixed = false) +
        "in vec2 rengGroundUv;\n" +
        "in vec2 rengGroundGridUv;\n" +
        "layout(location = 0) out vec4 rengGroundColour;\n" +
        groundCapBlurGlsl() +
        "void main() {\n" +
        "    if (rengGroundCapMode == 0) {\n" +
        "        rengGroundColour = texture(rengGroundTexture, rengGroundUv);\n" +
        "        return;\n" +
        "    }\n" +
        groundWorldUGlsl() +
        "    if (rengGroundCapMode == $GROUND_CAP_MODE_NORTH || " +
        "rengGroundCapMode == $GROUND_CAP_MODE_SOUTH) {\n" +
        groundCapCoordinatesGlsl(indent = "        ") +
        "        rengGroundColour = rengGroundWorldBlur(rengGroundWorldStrip, " +
        "rengGroundWorldStripSide, worldU, blurWidth);\n" +
        "        return;\n" +
        "    }\n" +
        "    vec4 sharp = texture(rengGroundTexture, rengGroundUv);\n" +
        "    float blurWidth = min(1.0, 1.0 / rengGroundCapLodScale);\n" +
        groundEdgeFadeGlsl() +
        "    vec4 world = rengGroundWorldBlur(rengGroundWorldStrip, rengGroundWorldStripSide, " +
        "worldU, blurWidth);\n" +
        "    if (rengGroundCapMode == $GROUND_CAP_MODE_BOTH_EDGES && southFade > northFade) {\n" +
        "        world = rengGroundWorldBlur(rengGroundOppositeWorldStrip, " +
        "rengGroundWorldStripSide, worldU, blurWidth);\n" +
        "    }\n" +
        "    rengGroundColour = mix(sharp, world, fade);\n" +
        "}\n"

/** ADR 0086's one-pass vertex stage, with the texture-v collapse applied before both windows. */
internal const val GROUND_MIXED_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengGroundGrid;\n" +
        "uniform mat4 rengGroundModelViewProjection;\n" +
        "uniform vec2 rengGroundUvV;\n" +
        "out vec2 rengGroundUv;\n" +
        "out vec2 rengGroundBlendUv;\n" +
        "out vec2 rengGroundGridUv;\n" +
        "uniform vec3 rengGroundColourWindow;\n" +
        "uniform vec3 rengGroundBlendColourWindow;\n" +
        "void main() {\n" +
        "    vec2 position = vec2(rengGroundGrid.x - 0.5, 0.5 - rengGroundGrid.y);\n" +
        "    vec2 rengGroundTileUv = vec2(rengGroundGrid.x, mix(rengGroundUvV.x, " +
        "rengGroundUvV.y, rengGroundGrid.y));\n" +
        "    rengGroundUv = rengGroundTileUv * rengGroundColourWindow.x + rengGroundColourWindow.yz;\n" +
        "    rengGroundBlendUv = rengGroundTileUv * rengGroundBlendColourWindow.x + " +
        "rengGroundBlendColourWindow.yz;\n" +
        "    rengGroundGridUv = rengGroundGrid;\n" +
        "    gl_Position = rengGroundModelViewProjection * vec4(position, 0.0, 1.0);\n" +
        "}\n"

/**
 * Globe-compatible ADR 0086 mixed fragment source; both colours are premultiplied, and Mercator adds
 * cap smudging in the variant below.
 */
internal val GROUND_MIXED_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengGroundTexture;\n" +
        "uniform sampler2D rengGroundBlendTexture;\n" +
        "uniform int rengGroundBlendKind;\n" +
        "uniform vec4 rengGroundBlendParams;\n" +
        "uniform vec2 rengGroundBlendFrameSize;\n" +
        "in vec2 rengGroundUv;\n" +
        "in vec2 rengGroundBlendUv;\n" +
        "layout(location = 0) out vec4 rengGroundColour;\n" +
        groundBlendMaskGlsl() +
        "void main() {\n" +
        "    vec4 primary = texture(rengGroundTexture, rengGroundUv);\n" +
        "    vec4 target = texture(rengGroundBlendTexture, rengGroundBlendUv);\n" +
        "    rengGroundColour = mix(primary, target, rengGroundBlendAmount());\n" +
        "}\n"

internal val MERCATOR_GROUND_MIXED_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengGroundTexture;\n" +
        "uniform sampler2D rengGroundBlendTexture;\n" +
        "uniform int rengGroundBlendKind;\n" +
        "uniform vec4 rengGroundBlendParams;\n" +
        "uniform vec2 rengGroundBlendFrameSize;\n" +
        groundCapUniformsGlsl(mixed = true) +
        "in vec2 rengGroundUv;\n" +
        "in vec2 rengGroundBlendUv;\n" +
        "in vec2 rengGroundGridUv;\n" +
        "layout(location = 0) out vec4 rengGroundColour;\n" +
        groundBlendMaskGlsl() +
        groundCapBlurGlsl() +
        "void main() {\n" +
        "    float blendAmount = rengGroundBlendAmount();\n" +
        "    if (rengGroundCapMode == 0) {\n" +
        "        vec4 primary = texture(rengGroundTexture, rengGroundUv);\n" +
        "        vec4 target = texture(rengGroundBlendTexture, rengGroundBlendUv);\n" +
        "        rengGroundColour = mix(primary, target, blendAmount);\n" +
        "        return;\n" +
        "    }\n" +
        groundWorldUGlsl() +
        "    if (rengGroundCapMode == $GROUND_CAP_MODE_NORTH || " +
        "rengGroundCapMode == $GROUND_CAP_MODE_SOUTH) {\n" +
        groundCapCoordinatesGlsl(indent = "        ") +
        "        vec4 primaryWorld = rengGroundWorldBlur(rengGroundWorldStrip, " +
        "rengGroundWorldStripSide, worldU, blurWidth);\n" +
        "        vec4 targetWorld;\n" +
        "        if (rengGroundBlendWorldAvailable != 0) {\n" +
        "            targetWorld = rengGroundWorldBlur(rengGroundBlendWorldStrip, " +
        "rengGroundBlendWorldStripSide, worldU, blurWidth);\n" +
        "        } else {\n" +
        "            targetWorld = texture(rengGroundBlendTexture, rengGroundBlendUv);\n" +
        "        }\n" +
        "        rengGroundColour = mix(primaryWorld, targetWorld, blendAmount);\n" +
        "        return;\n" +
        "    }\n" +
        "    vec4 primary = texture(rengGroundTexture, rengGroundUv);\n" +
        "    vec4 target = texture(rengGroundBlendTexture, rengGroundBlendUv);\n" +
        "    vec4 sharp = mix(primary, target, blendAmount);\n" +
        "    float blurWidth = min(1.0, 1.0 / rengGroundCapLodScale);\n" +
        groundEdgeFadeGlsl() +
        "    vec4 primaryWorld = rengGroundWorldBlur(rengGroundWorldStrip, " +
        "rengGroundWorldStripSide, worldU, blurWidth);\n" +
        "    vec4 targetWorld = target;\n" +
        "    if (rengGroundBlendWorldAvailable != 0) {\n" +
        "        targetWorld = rengGroundWorldBlur(rengGroundBlendWorldStrip, " +
        "rengGroundBlendWorldStripSide, worldU, blurWidth);\n" +
        "    }\n" +
        "    if (rengGroundCapMode == $GROUND_CAP_MODE_BOTH_EDGES && southFade > northFade) {\n" +
        "        primaryWorld = rengGroundWorldBlur(rengGroundOppositeWorldStrip, " +
        "rengGroundWorldStripSide, worldU, blurWidth);\n" +
        "        if (rengGroundBlendWorldAvailable != 0) {\n" +
        "            targetWorld = rengGroundWorldBlur(rengGroundBlendOppositeWorldStrip, " +
        "rengGroundBlendWorldStripSide, worldU, blurWidth);\n" +
        "        }\n" +
        "    }\n" +
        "    vec4 world = mix(primaryWorld, targetWorld, blendAmount);\n" +
        "    rengGroundColour = mix(sharp, world, fade);\n" +
        "}\n"

/**
 * The ground's fragment stage **with terrain shading on**: the same texel, multiplied by ADR 0026's
 * one light applied to the normal the vertex stage derived from the DEM.
 *
 * Shared by both projections for [GROUND_FRAGMENT_SOURCE]'s own reason — the globe and the Mercator
 * ground must sample the identical texture identically, and the cycle's cross-mode agreement gate
 * asserts it — and a second copy would make a drift a silent edit rather than a visible one.
 *
 * ## Three properties of the expression, each deliberate
 *
 * **The light's numbers are derived, never transcribed.** [SCENE_LIGHT_DIRECTION_ENU_GLSL],
 * [SCENE_LIGHT_FLAT_INCIDENCE_GLSL] and [SCENE_LIGHT_RELIEF_GAIN_GLSL] are built from ADR 0026's
 * Kotlin constants, on `demDecodeCoefficients`' argument exactly: a formula written twice is a
 * formula that can drift, and a test can only catch a drift already written.
 *
 * **`1.0 + (incidence - flat) * gain` rather than `ambient + diffuse * incidence`.** The two are
 * algebraically the same light divided by what it does to flat ground, and this arrangement is what
 * makes ground with no relief come out **exactly** unchanged: a flat normal is `(0, 0, 1)` exactly,
 * so `incidence` is the light's own `z` to the bit, so the subtraction is `0.0` and the factor is
 * `1.0` with no rounding anywhere. Written the other way it would be one ulp from one, and "terrain
 * shading changes only the pixels that have relief" would be a tolerance rather than a fact.
 *
 * **The result is clamped against the texel's own alpha**, because a slope facing the light reaches
 * about 1.24 and the ground is composited premultiplied (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`), where a
 * colour above its own alpha is not a valid premultiplied triple. On an opaque tile — every rendered
 * basemap tile with an opaque style background — the clamp is what the framebuffer would do anyway,
 * so it costs nothing and removes the question. There is no lower clamp because the factor's minimum
 * is about 0.43 and cannot reach zero.
 */
internal val GROUND_SHADED_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengGroundTexture;\n" +
        "in vec2 rengGroundUv;\n" +
        "in vec3 rengGroundNormalEnu;\n" +
        "layout(location = 0) out vec4 rengGroundColour;\n" +
        "void main() {\n" +
        "    vec4 tile = texture(rengGroundTexture, rengGroundUv);\n" +
        "    float incidence = max(dot(normalize(rengGroundNormalEnu), " +
        SCENE_LIGHT_DIRECTION_ENU_GLSL + "), 0.0);\n" +
        "    float shade = 1.0 + (incidence - " + SCENE_LIGHT_FLAT_INCIDENCE_GLSL + ") * " +
        SCENE_LIGHT_RELIEF_GAIN_GLSL + ";\n" +
        "    rengGroundColour = vec4(min(tile.rgb * shade, vec3(tile.a)), tile.a);\n" +
        "}\n"

internal val MERCATOR_GROUND_SHADED_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengGroundTexture;\n" +
        groundCapUniformsGlsl(mixed = false) +
        "in vec2 rengGroundUv;\n" +
        "in vec2 rengGroundGridUv;\n" +
        "in vec3 rengGroundNormalEnu;\n" +
        "layout(location = 0) out vec4 rengGroundColour;\n" +
        groundCapBlurGlsl() +
        "void main() {\n" +
        "    vec4 tile;\n" +
        "    if (rengGroundCapMode == 0) {\n" +
        "        tile = texture(rengGroundTexture, rengGroundUv);\n" +
        "    } else {\n" +
        groundWorldUGlsl(indent = "        ") +
        "        if (rengGroundCapMode == $GROUND_CAP_MODE_NORTH || " +
        "rengGroundCapMode == $GROUND_CAP_MODE_SOUTH) {\n" +
        groundCapCoordinatesGlsl(indent = "            ") +
        "            tile = rengGroundWorldBlur(rengGroundWorldStrip, rengGroundWorldStripSide, " +
        "worldU, blurWidth);\n" +
        "        } else {\n" +
        "            vec4 sharp = texture(rengGroundTexture, rengGroundUv);\n" +
        "            float blurWidth = min(1.0, 1.0 / rengGroundCapLodScale);\n" +
        groundEdgeFadeGlsl(indent = "            ") +
        "            vec4 world = rengGroundWorldBlur(rengGroundWorldStrip, " +
        "rengGroundWorldStripSide, worldU, blurWidth);\n" +
        "            if (rengGroundCapMode == $GROUND_CAP_MODE_BOTH_EDGES && " +
        "southFade > northFade) {\n" +
        "                world = rengGroundWorldBlur(rengGroundOppositeWorldStrip, " +
        "rengGroundWorldStripSide, worldU, blurWidth);\n" +
        "            }\n" +
        "            tile = mix(sharp, world, fade);\n" +
        "        }\n" +
        "    }\n" +
        "    float incidence = max(dot(normalize(rengGroundNormalEnu), " +
        SCENE_LIGHT_DIRECTION_ENU_GLSL + "), 0.0);\n" +
        "    float shade = 1.0 + (incidence - " + SCENE_LIGHT_FLAT_INCIDENCE_GLSL + ") * " +
        SCENE_LIGHT_RELIEF_GAIN_GLSL + ";\n" +
        "    rengGroundColour = vec4(min(tile.rgb * shade, vec3(tile.a)), tile.a);\n" +
        "}\n"

internal val GROUND_SHADED_MIXED_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengGroundTexture;\n" +
        "uniform sampler2D rengGroundBlendTexture;\n" +
        "uniform int rengGroundBlendKind;\n" +
        "uniform vec4 rengGroundBlendParams;\n" +
        "uniform vec2 rengGroundBlendFrameSize;\n" +
        "in vec2 rengGroundUv;\n" +
        "in vec2 rengGroundBlendUv;\n" +
        "in vec3 rengGroundNormalEnu;\n" +
        "layout(location = 0) out vec4 rengGroundColour;\n" +
        groundBlendMaskGlsl() +
        "void main() {\n" +
        "    vec4 primary = texture(rengGroundTexture, rengGroundUv);\n" +
        "    vec4 target = texture(rengGroundBlendTexture, rengGroundBlendUv);\n" +
        "    vec4 tile = mix(primary, target, rengGroundBlendAmount());\n" +
        "    float incidence = max(dot(normalize(rengGroundNormalEnu), " +
        SCENE_LIGHT_DIRECTION_ENU_GLSL + "), 0.0);\n" +
        "    float shade = 1.0 + (incidence - " + SCENE_LIGHT_FLAT_INCIDENCE_GLSL + ") * " +
        SCENE_LIGHT_RELIEF_GAIN_GLSL + ";\n" +
        "    rengGroundColour = vec4(min(tile.rgb * shade, vec3(tile.a)), tile.a);\n" +
        "}\n"

internal val MERCATOR_GROUND_SHADED_MIXED_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengGroundTexture;\n" +
        "uniform sampler2D rengGroundBlendTexture;\n" +
        "uniform int rengGroundBlendKind;\n" +
        "uniform vec4 rengGroundBlendParams;\n" +
        "uniform vec2 rengGroundBlendFrameSize;\n" +
        groundCapUniformsGlsl(mixed = true) +
        "in vec2 rengGroundUv;\n" +
        "in vec2 rengGroundBlendUv;\n" +
        "in vec2 rengGroundGridUv;\n" +
        "in vec3 rengGroundNormalEnu;\n" +
        "layout(location = 0) out vec4 rengGroundColour;\n" +
        groundBlendMaskGlsl() +
        groundCapBlurGlsl() +
        "void main() {\n" +
        "    float blendAmount = rengGroundBlendAmount();\n" +
        "    vec4 tile;\n" +
        "    if (rengGroundCapMode == 0) {\n" +
        "        vec4 primary = texture(rengGroundTexture, rengGroundUv);\n" +
        "        vec4 target = texture(rengGroundBlendTexture, rengGroundBlendUv);\n" +
        "        tile = mix(primary, target, blendAmount);\n" +
        "    } else {\n" +
        groundWorldUGlsl(indent = "        ") +
        "        if (rengGroundCapMode == $GROUND_CAP_MODE_NORTH || " +
        "rengGroundCapMode == $GROUND_CAP_MODE_SOUTH) {\n" +
        groundCapCoordinatesGlsl(indent = "            ") +
        "            vec4 primaryWorld = rengGroundWorldBlur(rengGroundWorldStrip, " +
        "rengGroundWorldStripSide, worldU, blurWidth);\n" +
        "            vec4 targetWorld;\n" +
        "            if (rengGroundBlendWorldAvailable != 0) {\n" +
        "                targetWorld = rengGroundWorldBlur(rengGroundBlendWorldStrip, " +
        "rengGroundBlendWorldStripSide, worldU, blurWidth);\n" +
        "            } else {\n" +
        "                targetWorld = texture(rengGroundBlendTexture, rengGroundBlendUv);\n" +
        "            }\n" +
        "            tile = mix(primaryWorld, targetWorld, blendAmount);\n" +
        "        } else {\n" +
        "            vec4 primary = texture(rengGroundTexture, rengGroundUv);\n" +
        "            vec4 target = texture(rengGroundBlendTexture, rengGroundBlendUv);\n" +
        "            vec4 sharp = mix(primary, target, blendAmount);\n" +
        "            float blurWidth = min(1.0, 1.0 / rengGroundCapLodScale);\n" +
        groundEdgeFadeGlsl(indent = "            ") +
        "            vec4 primaryWorld = rengGroundWorldBlur(rengGroundWorldStrip, " +
        "rengGroundWorldStripSide, worldU, blurWidth);\n" +
        "            vec4 targetWorld = target;\n" +
        "            if (rengGroundBlendWorldAvailable != 0) {\n" +
        "                targetWorld = rengGroundWorldBlur(rengGroundBlendWorldStrip, " +
        "rengGroundBlendWorldStripSide, worldU, blurWidth);\n" +
        "            }\n" +
        "            if (rengGroundCapMode == $GROUND_CAP_MODE_BOTH_EDGES && " +
        "southFade > northFade) {\n" +
        "                primaryWorld = rengGroundWorldBlur(rengGroundOppositeWorldStrip, " +
        "rengGroundWorldStripSide, worldU, blurWidth);\n" +
        "                if (rengGroundBlendWorldAvailable != 0) {\n" +
        "                    targetWorld = rengGroundWorldBlur(rengGroundBlendOppositeWorldStrip, " +
        "rengGroundBlendWorldStripSide, worldU, blurWidth);\n" +
        "                }\n" +
        "            }\n" +
        "            vec4 world = mix(primaryWorld, targetWorld, blendAmount);\n" +
        "            tile = mix(sharp, world, fade);\n" +
        "        }\n" +
        "    }\n" +
        "    float incidence = max(dot(normalize(rengGroundNormalEnu), " +
        SCENE_LIGHT_DIRECTION_ENU_GLSL + "), 0.0);\n" +
        "    float shade = 1.0 + (incidence - " + SCENE_LIGHT_FLAT_INCIDENCE_GLSL + ") * " +
        SCENE_LIGHT_RELIEF_GAIN_GLSL + ";\n" +
        "    rengGroundColour = vec4(min(tile.rgb * shade, vec3(tile.a)), tile.a);\n" +
        "}\n"

private fun groundCapUniformsGlsl(mixed: Boolean): String =
    "uniform sampler2D rengGroundWorldStrip;\n" +
        (if (mixed) "uniform sampler2D rengGroundBlendWorldStrip;\n" else "") +
        "uniform sampler2D rengGroundOppositeWorldStrip;\n" +
        (if (mixed) "uniform sampler2D rengGroundBlendOppositeWorldStrip;\n" else "") +
        "uniform int rengGroundCapMode;\n" +
        "uniform float rengGroundCapSpanTiles;\n" +
        "uniform vec2 rengGroundCapWorldU;\n" +
        "uniform float rengGroundCapLodScale;\n" +
        "uniform float rengGroundCapTileSideLogicalPixels;\n" +
        "uniform float rengGroundWorldStripSide;\n" +
        (
            if (mixed) {
                "uniform float rengGroundBlendWorldStripSide;\n" +
                    "uniform int rengGroundBlendWorldAvailable;\n"
            } else {
                ""
            }
            )

private fun groundCapBlurGlsl(): String =
    "vec4 rengGroundWorldBlur(sampler2D worldStrip, float stripSide, float worldU, float width) {\n" +
        "    float level = max(log2(float($CAP_BAND_ROWS)), " +
        "log2(max(1.0, width * stripSide)));\n" +
        "    float spacing = width / float($CAP_BLUR_TAPS);\n" +
        "    vec4 sum = textureLod(worldStrip, vec2(worldU - 2.0 * spacing, 0.5), level);\n" +
        "    sum += 2.0 * textureLod(worldStrip, vec2(worldU - spacing, 0.5), level);\n" +
        "    sum += 3.0 * textureLod(worldStrip, vec2(worldU, 0.5), level);\n" +
        "    sum += 2.0 * textureLod(worldStrip, vec2(worldU + spacing, 0.5), level);\n" +
        "    sum += textureLod(worldStrip, vec2(worldU + 2.0 * spacing, 0.5), level);\n" +
        "    return sum / 9.0;\n" +
        "}\n"

private fun groundWorldUGlsl(indent: String = "    "): String =
    indent + "float worldU = rengGroundCapWorldU.x + " +
        "rengGroundGridUv.x * rengGroundCapWorldU.y;\n"

private fun groundCapCoordinatesGlsl(indent: String = "    "): String =
    indent + "float d = (rengGroundCapMode == $GROUND_CAP_MODE_NORTH ? " +
        "1.0 - rengGroundGridUv.y : rengGroundGridUv.y) * rengGroundCapSpanTiles;\n" +
        indent + "float blurWidth = min(1.0, (1.0 + $CAP_BLUR_GROWTH * d) / " +
        "rengGroundCapLodScale);\n"

private fun groundEdgeFadeGlsl(indent: String = "    "): String =
    indent + "float fadeDistance = min(min($CAP_EDGE_FADE_LOGICAL_PIXELS, " +
        "0.5 * rengGroundCapTileSideLogicalPixels), $CAP_EDGE_FADE_WORLD_FRACTION * " +
        "rengGroundCapTileSideLogicalPixels * rengGroundCapLodScale);\n" +
        indent + "float northFade = 0.0;\n" +
        indent + "if ((rengGroundCapMode & $GROUND_CAP_MODE_NORTH_EDGE) != 0) {\n" +
        indent + "    float s = rengGroundGridUv.y * rengGroundCapTileSideLogicalPixels;\n" +
        indent + "    northFade = 1.0 - smoothstep(0.0, fadeDistance, s);\n" +
        indent + "}\n" +
        indent + "float southFade = 0.0;\n" +
        indent + "if ((rengGroundCapMode & $GROUND_CAP_MODE_SOUTH_EDGE) != 0) {\n" +
        indent + "    float s = (1.0 - rengGroundGridUv.y) * rengGroundCapTileSideLogicalPixels;\n" +
        indent + "    southFade = 1.0 - smoothstep(0.0, fadeDistance, s);\n" +
        indent + "}\n" +
        indent + "float fade = max(northFade, southFade);\n"

private fun groundBlendMaskGlsl(): String =
    "float rengGroundBlendAmount() {\n" +
        "    vec2 p = vec2(gl_FragCoord.x, rengGroundBlendFrameSize.y - gl_FragCoord.y);\n" +
        "    float diagonal = length(rengGroundBlendFrameSize);\n" +
        "    if (rengGroundBlendKind == 0) return rengGroundBlendParams.x;\n" +
        "    if (rengGroundBlendKind == 1) {\n" +
        "        float r = distance(p, rengGroundBlendParams.xy * rengGroundBlendFrameSize) / diagonal;\n" +
        "        if (rengGroundBlendParams.w == 0.0) return r <= rengGroundBlendParams.z ? 1.0 : 0.0;\n" +
        "        return 1.0 - smoothstep(rengGroundBlendParams.z - rengGroundBlendParams.w, " +
        "rengGroundBlendParams.z, r);\n" +
        "    }\n" +
        "    float c = cos(rengGroundBlendParams.x);\n" +
        "    float q = sin(rengGroundBlendParams.x);\n" +
        "    vec2 d = vec2(c, -q);\n" +
        "    float e = 0.5 * (rengGroundBlendFrameSize.x * abs(c) + " +
        "rengGroundBlendFrameSize.y * abs(q)) / diagonal;\n" +
        "    float s = dot(p - rengGroundBlendFrameSize * 0.5, d) / diagonal;\n" +
        "    float feather = rengGroundBlendParams.z;\n" +
        "    float edge = -e - feather + rengGroundBlendParams.y * (2.0 * e + feather);\n" +
        "    if (feather == 0.0) return s < edge ? 1.0 : 0.0;\n" +
        "    return 1.0 - smoothstep(edge, edge + feather, s);\n" +
        "}\n"

internal val GROUND_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = GROUND_VERTEX_SOURCE, fragmentSource = MERCATOR_GROUND_FRAGMENT_SOURCE)

internal const val GROUND_COLOUR_WINDOW_UNIFORM_NAME: String = "rengGroundColourWindow"
internal const val GROUND_BLEND_COLOUR_WINDOW_UNIFORM_NAME: String = "rengGroundBlendColourWindow"
internal const val GROUND_TILE_UV_V_UNIFORM_NAME: String = "rengGroundUvV"

internal const val GROUND_MODEL_VIEW_PROJECTION_UNIFORM_NAME: String = "rengGroundModelViewProjection"
internal const val GROUND_TEXTURE_UNIFORM_NAME: String = "rengGroundTexture"
internal const val GROUND_BLEND_TEXTURE_UNIFORM_NAME: String = "rengGroundBlendTexture"
internal const val GROUND_MERCATOR_Y_UNIFORM_NAME: String = "rengGroundMercatorY"
internal const val GROUND_ELEVATION_SCALE_UNIFORM_NAME: String = "rengGroundElevationScale"
internal const val GROUND_BLEND_KIND_UNIFORM_NAME: String = "rengGroundBlendKind"
internal const val GROUND_BLEND_PARAMS_UNIFORM_NAME: String = "rengGroundBlendParams"
internal const val GROUND_BLEND_FRAME_SIZE_UNIFORM_NAME: String = "rengGroundBlendFrameSize"
internal const val GROUND_WORLD_STRIP_UNIFORM_NAME: String = "rengGroundWorldStrip"
internal const val GROUND_BLEND_WORLD_STRIP_UNIFORM_NAME: String = "rengGroundBlendWorldStrip"
internal const val GROUND_OPPOSITE_WORLD_STRIP_UNIFORM_NAME: String = "rengGroundOppositeWorldStrip"
internal const val GROUND_BLEND_OPPOSITE_WORLD_STRIP_UNIFORM_NAME: String =
    "rengGroundBlendOppositeWorldStrip"
internal const val GROUND_CAP_MODE_UNIFORM_NAME: String = "rengGroundCapMode"
internal const val GROUND_CAP_SPAN_TILES_UNIFORM_NAME: String = "rengGroundCapSpanTiles"
internal const val GROUND_CAP_WORLD_U_UNIFORM_NAME: String = "rengGroundCapWorldU"
internal const val GROUND_CAP_LOD_SCALE_UNIFORM_NAME: String = "rengGroundCapLodScale"
internal const val GROUND_CAP_TILE_SIDE_LOGICAL_PIXELS_UNIFORM_NAME: String =
    "rengGroundCapTileSideLogicalPixels"
internal const val GROUND_WORLD_STRIP_SIDE_UNIFORM_NAME: String = "rengGroundWorldStripSide"
internal const val GROUND_BLEND_WORLD_STRIP_SIDE_UNIFORM_NAME: String = "rengGroundBlendWorldStripSide"
internal const val GROUND_BLEND_WORLD_AVAILABLE_UNIFORM_NAME: String = "rengGroundBlendWorldAvailable"

/**
 * The Mercator ground's displacing vertex stage: [GROUND_VERTEX_SOURCE] with
 * [GROUND_ELEVATION_SOURCE] composed in and one term added to the position.
 *
 * **Mercator displaces along the surface normal, which in the tile's own map axes is `+z`.** The
 * tile's model matrix is `Translate(centre) * Scale(side, side, 1)` ([composeGroundModelViewProjection]),
 * so `x` and `y` are scaled from the unit square into logical pixels and **`z` passes through with a
 * scale of exactly one**. The displacement therefore has to arrive already in logical pixels, and
 * everything below exists to convert metres into them.
 *
 * ## Metres to logical pixels, and why the latitude term is per vertex
 *
 * RenG stays in logical pixels, never metres, and Mercator's own conversion is already written down
 * twice: `projectMercator` computes `z = altitudeMetres / (C * cos(latitude))` in world units and
 * `PlacementResolver` multiplies by `worldSizeLogicalPixels`. This is that same expression, split so
 * that the part which varies across a tile is evaluated per vertex:
 *
 * ```
 * logicalPixels = metres * (worldSize / C) * (1 / cos(latitude))
 *               = metres * rengGroundElevationScale * cosh(PI * (1 - 2 * mercatorY))
 * ```
 *
 * The identity is exact rather than an approximation. Web Mercator defines
 * `asinh(tan(latitude)) = PI * (1 - 2y)`, so `tan(latitude) = sinh(PI * (1 - 2y))` and
 * `1 / cos(latitude) = sqrt(1 + tan^2) = cosh(PI * (1 - 2y))`. At the Mercator clip that is
 * `cosh(PI) = 11.592`, which is the distortion at 85.0511 degrees to six figures.
 *
 * **Per vertex rather than per tile, and that is a seam decision rather than an accuracy one.** A
 * single `1 / cos(latitude)` per tile — the tile's centre, say — would be cheaper and would scale
 * one DEM height by two different constants on the two sides of every east-west tile boundary. The
 * heights already agree there, because the padded ring makes both sides read the same texel; the
 * *drawn* ground would still step, in exactly the places the ring exists to make continuous. Two
 * tiles sharing a line of latitude are handed the identical `mercatorY` endpoint and `mix` returns
 * its endpoints exactly, so the two evaluate `cosh` on the identical argument instead.
 *
 * ## What [shading] adds, and what stays out either way
 *
 * With [shading] false this emits the ordinary displacing source; with it true it emits that source
 * plus [GROUND_NORMAL_SOURCE], one `out vec3`, and one statement. Terrain shading is a **renderer**
 * option
 * (`RendererConfiguration.terrainShading`), fixed for a renderer's whole life, so which of the two
 * is compiled is decided once at setup and no frame can vary it. ADR 0026 leaves the ground unlit
 * and that stays the default.
 *
 * `1 / cos(latitude)` reaches the normal as `1 / cosh(PI * (1 - 2y))`, the reciprocal of the same
 * per-vertex term the displacement uses, because the run of a tap is real ground metres and Mercator
 * measures its tile in equatorial ones. It is the same latitude factor, applied to the horizontal
 * where the displacement applies it to the vertical.
 *
 * No depth write here either way: ADR 0039's write is conditional on the *frame* being displaced, so
 * it belongs to [drawGround] -- which is the only place that knows which of a frame's tiles carry a
 * DEM -- and not to a shader compiled once per renderer. The UV, the winding and the position of an
 * undisplaced vertex are [GROUND_VERTEX_SOURCE]'s, character for character.
 */
private fun terrainGroundVertexSource(shading: Boolean): String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "layout(location = 0) in vec2 rengGroundGrid;\n" +
        "uniform mat4 rengGroundModelViewProjection;\n" +
        "uniform vec2 rengGroundUvV;\n" +
        "uniform vec2 rengGroundMercatorY;\n" +
        "uniform float rengGroundElevationScale;\n" +
        GROUND_ELEVATION_SOURCE +
        (if (shading) GROUND_NORMAL_SOURCE else "") +
        "out vec2 rengGroundUv;\n" +
        "out vec2 rengGroundGridUv;\n" +
        "uniform vec3 rengGroundColourWindow;\n" +
        (if (shading) "out vec3 rengGroundNormalEnu;\n" else "") +
        "void main() {\n" +
        "    float mercatorY = mix(rengGroundMercatorY.x, rengGroundMercatorY.y, rengGroundGrid.y);\n" +
        "    float up = rengGroundElevationMetres(rengGroundGrid) * rengGroundElevationScale *\n" +
        "        cosh(3.141592653589793 * (1.0 - 2.0 * mercatorY));\n" +
        "    vec2 position = vec2(rengGroundGrid.x - 0.5, 0.5 - rengGroundGrid.y);\n" +
        "    vec2 rengGroundTileUv = vec2(rengGroundGrid.x, mix(rengGroundUvV.x, " +
        "rengGroundUvV.y, rengGroundGrid.y));\n" +
        "    rengGroundUv = rengGroundTileUv * rengGroundColourWindow.x + rengGroundColourWindow.yz;\n" +
        "    rengGroundGridUv = rengGroundGrid;\n" +
        (
            if (shading) {
                "    rengGroundNormalEnu = rengGroundEnuNormal(rengGroundGrid, 1.0 /\n" +
                    "        cosh(3.141592653589793 * (1.0 - 2.0 * mercatorY)));\n"
            } else {
                ""
            }
            ) +
        "    gl_Position = rengGroundModelViewProjection * vec4(position, up, 1.0);\n" +
        "}\n"

internal val TERRAIN_GROUND_VERTEX_SOURCE: String = terrainGroundVertexSource(shading = false)

/**
 * [TERRAIN_GROUND_VERTEX_SOURCE] with [GROUND_NORMAL_SOURCE] composed in and one varying added, for
 * a renderer whose configuration asked for terrain shading.
 *
 * Both variants come out of one builder that adds shading to the unshaded body rather than
 * restructuring it, so there is no arrangement of this file in which the two drift.
 *
 * `cosh` is evaluated twice, once for the displacement and once for `1 / cos(latitude)`, rather than
 * hoisted into a local. Hoisting would have changed the unshaded text, and every GLSL compiler in
 * the world eliminates the common subexpression.
 */
internal val TERRAIN_SHADED_GROUND_VERTEX_SOURCE: String = terrainGroundVertexSource(shading = true)

/** ADR 0086's displacing mixed vertex stage; the pinned ordinary variants above are not edited. */
private fun terrainMixedGroundVertexSource(shading: Boolean): String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "layout(location = 0) in vec2 rengGroundGrid;\n" +
        "uniform mat4 rengGroundModelViewProjection;\n" +
        "uniform vec2 rengGroundUvV;\n" +
        "uniform vec2 rengGroundMercatorY;\n" +
        "uniform float rengGroundElevationScale;\n" +
        GROUND_ELEVATION_SOURCE +
        (if (shading) GROUND_NORMAL_SOURCE else "") +
        "out vec2 rengGroundUv;\n" +
        "out vec2 rengGroundBlendUv;\n" +
        "out vec2 rengGroundGridUv;\n" +
        "uniform vec3 rengGroundColourWindow;\n" +
        "uniform vec3 rengGroundBlendColourWindow;\n" +
        (if (shading) "out vec3 rengGroundNormalEnu;\n" else "") +
        "void main() {\n" +
        "    float mercatorY = mix(rengGroundMercatorY.x, rengGroundMercatorY.y, rengGroundGrid.y);\n" +
        "    float up = rengGroundElevationMetres(rengGroundGrid) * rengGroundElevationScale *\n" +
        "        cosh(3.141592653589793 * (1.0 - 2.0 * mercatorY));\n" +
        "    vec2 position = vec2(rengGroundGrid.x - 0.5, 0.5 - rengGroundGrid.y);\n" +
        "    vec2 rengGroundTileUv = vec2(rengGroundGrid.x, mix(rengGroundUvV.x, " +
        "rengGroundUvV.y, rengGroundGrid.y));\n" +
        "    rengGroundUv = rengGroundTileUv * rengGroundColourWindow.x + rengGroundColourWindow.yz;\n" +
        "    rengGroundBlendUv = rengGroundTileUv * rengGroundBlendColourWindow.x + " +
        "rengGroundBlendColourWindow.yz;\n" +
        "    rengGroundGridUv = rengGroundGrid;\n" +
        (
            if (shading) {
                "    rengGroundNormalEnu = rengGroundEnuNormal(rengGroundGrid, 1.0 /\n" +
                    "        cosh(3.141592653589793 * (1.0 - 2.0 * mercatorY)));\n"
            } else {
                ""
            }
            ) +
        "    gl_Position = rengGroundModelViewProjection * vec4(position, up, 1.0);\n" +
        "}\n"

internal val TERRAIN_MIXED_GROUND_VERTEX_SOURCE: String =
    terrainMixedGroundVertexSource(shading = false)

internal val TERRAIN_SHADED_MIXED_GROUND_VERTEX_SOURCE: String =
    terrainMixedGroundVertexSource(shading = true)

internal val TERRAIN_GROUND_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = TERRAIN_GROUND_VERTEX_SOURCE, fragmentSource = MERCATOR_GROUND_FRAGMENT_SOURCE)

internal val TERRAIN_SHADED_GROUND_SHADER_PAIR: ShaderPair = ShaderPair(
    vertexSource = TERRAIN_SHADED_GROUND_VERTEX_SOURCE,
    fragmentSource = MERCATOR_GROUND_SHADED_FRAGMENT_SOURCE,
)

/**
 * The Mercator ground's displacing program and every uniform location it needs.
 *
 * Compiled beside the flat program rather than on the first terrain frame, unlike
 * [GlobeGroundPipeline], and the asymmetry is deliberate: a globe pipeline is a whole second
 * pipeline object a Mercator renderer must never pay for, while this is one more program inside a
 * pipeline that already exists. Lazily compiling it would put a `var` on the draw path and a
 * `Failed` arm in a function that currently cannot fail, to save one link on the 28 of 34 corpus
 * styles that declare no terrain.
 */
internal class TerrainGroundProgram(
    val key: ResourceKey,
    val program: Int,
    val modelViewProjectionUniformLocation: Int,
    val tileUvVUniformLocation: Int,
    /**
     * Which part of the bound texture a tile samples (ADR 0057): scale, then u and v offsets.
     *
     * The identity window is what an exact tile gets, so this is written for every tile and the
     * shader carries no branch and no second variant.
     */
    val colourWindowUniformLocation: Int,
    val textureUniformLocation: Int,
    val mercatorYUniformLocation: Int,
    val elevationScaleUniformLocation: Int,
    val elevation: GroundElevationUniformLocations,
    val cap: GroundCapUniformLocations,
)

internal class GroundMixedProgram(
    val key: ResourceKey,
    val program: Int,
    val modelViewProjectionUniformLocation: Int,
    val tileUvVUniformLocation: Int,
    val colourWindowUniformLocation: Int,
    val textureUniformLocation: Int,
    val blendColourWindowUniformLocation: Int,
    val blendTextureUniformLocation: Int,
    val blendKindUniformLocation: Int,
    val blendParamsUniformLocation: Int,
    val blendFrameSizeUniformLocation: Int,
    val mercatorYUniformLocation: Int = -1,
    val elevationScaleUniformLocation: Int = -1,
    val elevation: GroundElevationUniformLocations? = null,
    val cap: GroundCapUniformLocations,
)

/** Uniform locations shared by every flat, terrain, single-style, and mixed Mercator cap program. */
internal class GroundCapUniformLocations(
    val worldStrip: Int,
    val blendWorldStrip: Int,
    val oppositeWorldStrip: Int,
    val blendOppositeWorldStrip: Int,
    val mode: Int,
    val spanTiles: Int,
    val worldU: Int,
    val lodScale: Int,
    val tileSideLogicalPixels: Int,
    val worldStripSide: Int,
    val blendWorldStripSide: Int,
    val blendWorldAvailable: Int,
)

/**
 * The two band-averaged edge strips derived from one LOD-zero world texture. [sourceTexture]
 * makes the pipeline cache reject an entry after that key has been evicted and uploaded under a new
 * GL name; the registry owns all three handles under the same [ResourceKey].
 */
internal class GroundEdgeStrips(
    val sourceTexture: Int,
    val northTexture: Int,
    val southTexture: Int,
    val sidePx: Int,
)

/**
 * RenG's Mercator ground pipeline: one program and one [GroundGrid] per granularity, drawn once per
 * basemap tile with the tile's own model-view-projection.
 *
 * **The reading this pipeline puts on a grid vertex is documented here because the shader states it
 * in GLSL and nothing else can.** A grid vertex is a unit `(u, v)` cell coordinate and nothing more;
 * the vertex stage turns it into a position in the tile's own local map axes — `+x` east and `+y`
 * **north** — as `(u - 0.5, 0.5 - v)`, so the grid spans the same unit square centred on the origin
 * that `GROUND_QUAD`'s four vertices did before Cycle E-terrain subdivided it, and the tile's own
 * model-view-projection places it exactly as before.
 *
 * `v = 0` therefore lands at local `+y` because row zero of a rendered basemap tile is its **north**
 * edge and [uploadTexture] uploads row zero first. Flipping the sign of that subtraction mirrors
 * every tile about its own centre line — invisible on a solid-coloured tile and catastrophic on a
 * real map — so it is pinned by `GroundPipelineTest.theVertexStagePutsTextureRowZeroAtTheNorthEdge`
 * on the source text and, in pixels, by `runBasemapReadbackSuite`'s asymmetric fixture.
 *
 * The grid's triangles wind counter-clockwise in that frame, matching `drawFrame`'s `GL_CCW` front
 * face — see [groundGridIndices]. [drawGround] disables culling, so nothing on a real driver would
 * catch a flip; `GroundGridTest` asserts it on the signed areas instead.
 */
internal class GroundPipeline(
    val key: ResourceKey,
    val program: Int,
    val modelViewProjectionUniformLocation: Int,
    val tileUvVUniformLocation: Int,
    /**
     * Which part of the bound texture a tile samples (ADR 0057): scale, then u and v offsets.
     *
     * The identity window is what an exact tile gets, so this is written for every tile and the
     * shader carries no branch and no second variant.
     */
    val colourWindowUniformLocation: Int,
    val textureUniformLocation: Int,
    val cap: GroundCapUniformLocations,
    /**
     * The displacing program this pipeline draws a tile with a DEM through — see
     * [TerrainGroundProgram] for why it is compiled here rather than on first use.
     */
    val terrain: TerrainGroundProgram,
    val terrainShading: Boolean,
    var mixed: GroundMixedProgram? = null,
    var terrainMixed: GroundMixedProgram? = null,
    /** LOD-zero edge strips, owned by the registry and only indexed here for reuse. */
    val worldStrips: MutableMap<ResourceKey, GroundEdgeStrips> = mutableMapOf(),
    /**
     * Every grid this pipeline has been asked for, keyed by [GroundGrid.cellsPerSide].
     *
     * Mutable, lazily filled and owned here for [GlobeGroundPipeline.grids]' reason exactly: the
     * granularity a frame needs is a function of the camera and of the elevation it has to follow,
     * so it is unknown at creation time. One grid per granularity is built on first use and every
     * later tile of every later frame at that granularity reuses it, so a frame allocates nothing.
     */
    val grids: MutableMap<Int, GroundGrid> = mutableMapOf(),
)

internal sealed interface GroundPipelineResult {
    data class Created(val pipeline: GroundPipeline) : GroundPipelineResult

    data class Failed(val failure: FailureDescriptor) : GroundPipelineResult
}

/**
 * [terrainShading] picks which of the two displacing sources is compiled, and picking it **here** is
 * the whole of how the option is spent.
 *
 * `RendererConfiguration.terrainShading` is a property of the renderer rather than of a `FramePlan`,
 * so the choice is made once, at setup, and `drawGround` needs no branch, no uniform and no way to
 * get it wrong per tile. Off — the default — the flat program and the displacing program are the two
 * this file has compiled since task 6, byte for byte, so a frame drawn with shading off is not
 * merely expected to match the unshaded picture, it runs the identical program.
 */
internal fun createGroundPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
    terrainShading: Boolean = false,
): GroundPipelineResult {
    val key = deriver.internalPipeline(InternalPipelineRole.GROUND, GROUND_SHADER_PAIR).key
    val vertexPlan = scanShaderProfile(GROUND_VERTEX_SOURCE)
        ?: return GroundPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    val fragmentPlan = scanShaderProfile(MERCATOR_GROUND_FRAGMENT_SOURCE)
        ?: return GroundPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))

    val program = when (
        val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return GroundPipelineResult.Failed(result.failure)
    }

    val terrainPair = if (terrainShading) TERRAIN_SHADED_GROUND_SHADER_PAIR else TERRAIN_GROUND_SHADER_PAIR
    val terrainKey = deriver.internalPipeline(InternalPipelineRole.TERRAIN_GROUND, terrainPair).key
    val terrainVertexPlan = scanShaderProfile(terrainPair.vertexSource)
        ?: return GroundPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, terrainKey))
    val terrainFragmentPlan = if (terrainShading) {
        scanShaderProfile(terrainPair.fragmentSource)
            ?: return GroundPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, terrainKey))
    } else {
        fragmentPlan
    }
    val terrainProgram = when (
        val result = cache.getOrCompile(binding, dialect, terrainKey, terrainVertexPlan, terrainFragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return GroundPipelineResult.Failed(result.failure)
    }

    // No vertex array and no buffer here, which is the one shape change Cycle E-terrain makes to
    // setup: the pipeline's geometry is now a [GroundGrid] per granularity, and which granularities
    // a session reaches is a camera question that setup cannot answer. [groundGrid] builds them on
    // the draw path, as the globe's has since Cycle G.
    return GroundPipelineResult.Created(
        GroundPipeline(
            key = key,
            program = program,
            modelViewProjectionUniformLocation = binding.getUniformLocation(
                program,
                GROUND_MODEL_VIEW_PROJECTION_UNIFORM_NAME,
            ),
            tileUvVUniformLocation = binding.getUniformLocation(
                program,
                GROUND_TILE_UV_V_UNIFORM_NAME,
            ),
            colourWindowUniformLocation = binding.getUniformLocation(
                program,
                GROUND_COLOUR_WINDOW_UNIFORM_NAME,
            ),
            textureUniformLocation = binding.getUniformLocation(program, GROUND_TEXTURE_UNIFORM_NAME),
            cap = resolveGroundCapUniforms(binding = binding, program = program, mixed = false),
            terrain = TerrainGroundProgram(
                key = terrainKey,
                program = terrainProgram,
                modelViewProjectionUniformLocation = binding.getUniformLocation(
                    terrainProgram,
                    GROUND_MODEL_VIEW_PROJECTION_UNIFORM_NAME,
                ),
                tileUvVUniformLocation = binding.getUniformLocation(
                    terrainProgram,
                    GROUND_TILE_UV_V_UNIFORM_NAME,
                ),
                colourWindowUniformLocation = binding.getUniformLocation(
                    terrainProgram,
                    GROUND_COLOUR_WINDOW_UNIFORM_NAME,
                ),
                textureUniformLocation = binding.getUniformLocation(
                    terrainProgram,
                    GROUND_TEXTURE_UNIFORM_NAME,
                ),
                mercatorYUniformLocation = binding.getUniformLocation(
                    terrainProgram,
                    GROUND_MERCATOR_Y_UNIFORM_NAME,
                ),
                elevationScaleUniformLocation = binding.getUniformLocation(
                    terrainProgram,
                    GROUND_ELEVATION_SCALE_UNIFORM_NAME,
                ),
                elevation = resolveGroundElevationUniforms(binding, terrainProgram),
                cap = resolveGroundCapUniforms(binding = binding, program = terrainProgram, mixed = false),
            ),
            terrainShading = terrainShading,
        ),
    )
}

/** Deletes **both** programs and **every** cached grid, on [deleteGroundGrids]' terms. */
internal fun deleteGroundPipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: GroundPipeline,
) {
    deleteGroundGrids(binding, pipeline.grids)
    pipeline.worldStrips.clear()
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
    cache.remove(pipeline.terrain.key)?.let { binding.deleteProgram(it) }
    pipeline.mixed?.let { mixed -> cache.remove(mixed.key)?.let { binding.deleteProgram(it) } }
    pipeline.terrainMixed?.let { mixed -> cache.remove(mixed.key)?.let { binding.deleteProgram(it) } }
}

/**
 * Copies the north and south [CAP_BAND_ROWS] rows of [sourceTexture] into independent RGBA8 textures
 * and builds their mip pyramids. At level `log2(CAP_BAND_ROWS)` each is one row tall and has averaged
 * the whole band vertically; every coarser level therefore continues averaging horizontally only.
 * The temporary read/draw framebuffers are private to this call; both bindings are restored even
 * when either attachment is incomplete.
 */
internal fun buildGroundEdgeStrips(
    binding: GlBinding,
    sourceTexture: Int,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
): GroundEdgeStrips? {
    require(sourceTexture > 0) { "an edge-strip source is a GL texture" }
    require(sourceWidthPx > 0 && sourceHeightPx > 0) { "an edge-strip source has positive dimensions" }
    if (sourceHeightPx < CAP_BAND_ROWS) return null

    val previousDrawFramebuffer = IntArray(1)
    val previousReadFramebuffer = IntArray(1)
    binding.getIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, previousDrawFramebuffer)
    binding.getIntegerv(GL_READ_FRAMEBUFFER_BINDING, previousReadFramebuffer)

    val textures = IntArray(2)
    binding.genTextures(textures.size, textures)
    textures.forEach { texture ->
        binding.bindTexture(GL_TEXTURE_2D, texture)
        binding.texImage2D(
            target = GL_TEXTURE_2D,
            level = 0,
            internalFormat = GL_RGBA8,
            width = sourceWidthPx,
            height = CAP_BAND_ROWS,
            border = 0,
            format = GL_RGBA,
            type = GL_UNSIGNED_BYTE,
            pixels = null,
        )
        binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    }

    val framebuffers = IntArray(2)
    binding.genFramebuffers(framebuffers.size, framebuffers)
    var complete = false
    try {
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, framebuffers[0])
        binding.framebufferTexture2D(
            GL_READ_FRAMEBUFFER,
            GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D,
            sourceTexture,
            0,
        )
        binding.readBuffer(GL_COLOR_ATTACHMENT0)
        if (binding.checkFramebufferStatus(GL_READ_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffers[1])
            complete = textures.indices.all { index ->
                binding.framebufferTexture2D(
                    GL_DRAW_FRAMEBUFFER,
                    GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D,
                    textures[index],
                    0,
                )
                if (binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                    false
                } else {
                    val sourceY = if (index == 0) 0 else sourceHeightPx - CAP_BAND_ROWS
                    binding.blitFramebuffer(
                        sourceX0 = 0,
                        sourceY0 = sourceY,
                        sourceX1 = sourceWidthPx,
                        sourceY1 = sourceY + CAP_BAND_ROWS,
                        destinationX0 = 0,
                        destinationY0 = 0,
                        destinationX1 = sourceWidthPx,
                        destinationY1 = CAP_BAND_ROWS,
                        mask = GL_COLOR_BUFFER_BIT,
                        filter = GL_NEAREST,
                    )
                    binding.bindTexture(GL_TEXTURE_2D, textures[index])
                    binding.generateMipmap(GL_TEXTURE_2D)
                    true
                }
            }
        }
    } finally {
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer[0])
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer[0])
        binding.deleteFramebuffers(framebuffers.size, framebuffers)
    }

    if (!complete) {
        binding.deleteTextures(textures.size, textures)
        return null
    }
    return GroundEdgeStrips(
        sourceTexture = sourceTexture,
        northTexture = textures[0],
        southTexture = textures[1],
        sidePx = sourceWidthPx,
    )
}

/** Compiles ADR 0086's two Mercator mixed variants on the first blended frame. */
internal fun ensureGroundMixedPrograms(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    pipeline: GroundPipeline,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): FailureDescriptor? {
    if (pipeline.mixed != null && pipeline.terrainMixed != null) return null
    val mixedPair = ShaderPair(
        vertexSource = GROUND_MIXED_VERTEX_SOURCE,
        fragmentSource = MERCATOR_GROUND_MIXED_FRAGMENT_SOURCE,
    )
    val mixedKey = deriver.internalPipeline(
        role = InternalPipelineRole.GROUND_MIXED,
        shaderPair = mixedPair,
    ).key
    val vertexPlan = scanShaderProfile(GROUND_MIXED_VERTEX_SOURCE)
        ?: return glOperationFailure(PipelineStage.GPU_RESOURCE, mixedKey)
    val mixedFragmentPlan = scanShaderProfile(MERCATOR_GROUND_MIXED_FRAGMENT_SOURCE)
        ?: return glOperationFailure(PipelineStage.GPU_RESOURCE, mixedKey)
    if (pipeline.mixed == null) {
        val program = when (
            val result = cache.getOrCompile(
                binding = binding,
                dialect = dialect,
                key = mixedKey,
                vertexPlan = vertexPlan,
                fragmentPlan = mixedFragmentPlan,
            )
        ) {
            is GlProgramResult.Linked -> result.program
            is GlProgramResult.Failed -> return result.failure
        }
        pipeline.mixed = groundMixedProgram(binding = binding, key = mixedKey, program = program)
    }

    val terrainMixedPair = ShaderPair(
        vertexSource = if (pipeline.terrainShading) {
            TERRAIN_SHADED_MIXED_GROUND_VERTEX_SOURCE
        } else {
            TERRAIN_MIXED_GROUND_VERTEX_SOURCE
        },
        fragmentSource = if (pipeline.terrainShading) {
            MERCATOR_GROUND_SHADED_MIXED_FRAGMENT_SOURCE
        } else {
            MERCATOR_GROUND_MIXED_FRAGMENT_SOURCE
        },
    )
    val terrainMixedKey = deriver.internalPipeline(
        role = InternalPipelineRole.GROUND_MIXED,
        shaderPair = terrainMixedPair,
    ).key
    val terrainVertexPlan = scanShaderProfile(terrainMixedPair.vertexSource)
        ?: return glOperationFailure(PipelineStage.GPU_RESOURCE, terrainMixedKey)
    val terrainFragmentPlan = scanShaderProfile(terrainMixedPair.fragmentSource)
        ?: return glOperationFailure(PipelineStage.GPU_RESOURCE, terrainMixedKey)
    if (pipeline.terrainMixed == null) {
        val program = when (
            val result = cache.getOrCompile(
                binding = binding,
                dialect = dialect,
                key = terrainMixedKey,
                vertexPlan = terrainVertexPlan,
                fragmentPlan = terrainFragmentPlan,
            )
        ) {
            is GlProgramResult.Linked -> result.program
            is GlProgramResult.Failed -> return result.failure
        }
        pipeline.terrainMixed = groundMixedProgram(
            binding = binding,
            key = terrainMixedKey,
            program = program,
            terrain = true,
        )
    }
    return null
}

private fun groundMixedProgram(
    binding: GlBinding,
    key: ResourceKey,
    program: Int,
    terrain: Boolean = false,
): GroundMixedProgram = GroundMixedProgram(
    key = key,
    program = program,
    modelViewProjectionUniformLocation = binding.getUniformLocation(
        program,
        GROUND_MODEL_VIEW_PROJECTION_UNIFORM_NAME,
    ),
    tileUvVUniformLocation = binding.getUniformLocation(program, GROUND_TILE_UV_V_UNIFORM_NAME),
    colourWindowUniformLocation = binding.getUniformLocation(program, GROUND_COLOUR_WINDOW_UNIFORM_NAME),
    textureUniformLocation = binding.getUniformLocation(program, GROUND_TEXTURE_UNIFORM_NAME),
    blendColourWindowUniformLocation = binding.getUniformLocation(
        program,
        GROUND_BLEND_COLOUR_WINDOW_UNIFORM_NAME,
    ),
    blendTextureUniformLocation = binding.getUniformLocation(program, GROUND_BLEND_TEXTURE_UNIFORM_NAME),
    blendKindUniformLocation = binding.getUniformLocation(program, GROUND_BLEND_KIND_UNIFORM_NAME),
    blendParamsUniformLocation = binding.getUniformLocation(program, GROUND_BLEND_PARAMS_UNIFORM_NAME),
    blendFrameSizeUniformLocation = binding.getUniformLocation(program, GROUND_BLEND_FRAME_SIZE_UNIFORM_NAME),
    mercatorYUniformLocation = if (terrain) {
        binding.getUniformLocation(program, GROUND_MERCATOR_Y_UNIFORM_NAME)
    } else {
        -1
    },
    elevationScaleUniformLocation = if (terrain) {
        binding.getUniformLocation(program, GROUND_ELEVATION_SCALE_UNIFORM_NAME)
    } else {
        -1
    },
    elevation = if (terrain) resolveGroundElevationUniforms(binding, program) else null,
    cap = resolveGroundCapUniforms(binding = binding, program = program, mixed = true),
)

private fun resolveGroundCapUniforms(
    binding: GlBinding,
    program: Int,
    mixed: Boolean,
): GroundCapUniformLocations = GroundCapUniformLocations(
    worldStrip = binding.getUniformLocation(program, GROUND_WORLD_STRIP_UNIFORM_NAME),
    blendWorldStrip = if (mixed) {
        binding.getUniformLocation(program, GROUND_BLEND_WORLD_STRIP_UNIFORM_NAME)
    } else {
        -1
    },
    oppositeWorldStrip = binding.getUniformLocation(program, GROUND_OPPOSITE_WORLD_STRIP_UNIFORM_NAME),
    blendOppositeWorldStrip = if (mixed) {
        binding.getUniformLocation(program, GROUND_BLEND_OPPOSITE_WORLD_STRIP_UNIFORM_NAME)
    } else {
        -1
    },
    mode = binding.getUniformLocation(program, GROUND_CAP_MODE_UNIFORM_NAME),
    spanTiles = binding.getUniformLocation(program, GROUND_CAP_SPAN_TILES_UNIFORM_NAME),
    worldU = binding.getUniformLocation(program, GROUND_CAP_WORLD_U_UNIFORM_NAME),
    lodScale = binding.getUniformLocation(program, GROUND_CAP_LOD_SCALE_UNIFORM_NAME),
    tileSideLogicalPixels = binding.getUniformLocation(
        program,
        GROUND_CAP_TILE_SIDE_LOGICAL_PIXELS_UNIFORM_NAME,
    ),
    worldStripSide = binding.getUniformLocation(program, GROUND_WORLD_STRIP_SIDE_UNIFORM_NAME),
    blendWorldStripSide = if (mixed) {
        binding.getUniformLocation(program, GROUND_BLEND_WORLD_STRIP_SIDE_UNIFORM_NAME)
    } else {
        -1
    },
    blendWorldAvailable = if (mixed) {
        binding.getUniformLocation(program, GROUND_BLEND_WORLD_AVAILABLE_UNIFORM_NAME)
    } else {
        -1
    },
)

/**
 * One ground tile instance ready to draw: its already-composed model-view-projection matrix
 * (column-major, matching [GlBinding.uniformMatrix4fv]), its already-uploaded GL texture name, and
 * — when this frame has terrain and this tile has a DEM — what to displace it by.
 *
 * [elevation] is `null` for every tile of every frame whose style declares no `terrain` block, and
 * also for a tile inside a terrain frame that Rentile returned no DEM for: ADR 0041 makes that tile
 * draw flat rather than failing the frame, so the two cases produce the same drawn tile and are
 * distinguished only by the diagnostic.
 */
internal class ResolvedGroundTile(
    val modelViewProjection: FloatArray,
    val texture: Int,
    val elevation: MercatorGroundTileDem? = null,
    /** Which part of [texture] to sample; the whole of it unless this tile is provisional (ADR 0057). */
    val colourWindow: GroundTileWindow = GroundTileWindow.WHOLE,
    /** ADR 0086's paired target-style texture, present on every tile of a blended frame. */
    val blendTexture: Int? = null,
    /** Which part of [blendTexture] to sample when its style is provisional (ADR 0057/0086). */
    val blendColourWindow: GroundTileWindow = GroundTileWindow.WHOLE,
    /** Which texture rows the tile spans, before either colour window is applied. */
    val uvV: FloatArray = ORDINARY_TILE_UV_V,
    /** The selected edge strip of the resident LOD-zero texture, continuous across the world. */
    val worldStrip: Int? = null,
    /** ADR 0086's selected target-style edge strip, or `null` when unavailable. */
    val blendWorldStrip: Int? = null,
    /** The other edge strip, present only for a LOD-zero tile that fades both map edges. */
    val oppositeWorldStrip: Int? = null,
    /** ADR 0086's other target-style edge strip for that same LOD-zero tile. */
    val blendOppositeWorldStrip: Int? = null,
    /** `0` ordinary/fallback; cap values are exclusive and edge-row values are a two-bit mask. */
    val capMode: Int = 0,
    val capSpanTiles: Float = 1f,
    val capWorldUStart: Float = 0f,
    val capWorldUWidth: Float = 1f,
    val capLodScale: Float = 1f,
    val capTileSideLogicalPixels: Float = 1f,
    /** Width of [worldStrip] in texels; the shader picks the mip level whose texel is the blur width. */
    val worldStripSide: Float = 1f,
    val blendWorldStripSide: Float = 1f,
) {
    init {
        require(uvV.size == 2) { "a ground tile carries two texture v coordinates" }
        require(
            capMode == 0 || capMode == GROUND_CAP_MODE_NORTH || capMode == GROUND_CAP_MODE_SOUTH ||
                capMode == GROUND_CAP_MODE_NORTH_EDGE || capMode == GROUND_CAP_MODE_SOUTH_EDGE ||
                capMode == GROUND_CAP_MODE_BOTH_EDGES,
        ) { "a ground cap mode is ordinary, one cap, one edge row, or both edge rows" }
        require(capMode != GROUND_CAP_MODE_BOTH_EDGES || oppositeWorldStrip != null) {
            "a tile fading both world edges carries both primary strips"
        }
        require(capSpanTiles.isFinite() && capSpanTiles >= 1f) { "a ground cap span is positive" }
        require(capWorldUStart.isFinite() && capWorldUWidth.isFinite()) { "ground cap world u is finite" }
        require(capWorldUWidth > 0f && capLodScale.isFinite() && capLodScale >= 1f) {
            "ground cap lod scale is positive"
        }
        require(capTileSideLogicalPixels.isFinite() && capTileSideLogicalPixels > 0f) {
            "a ground cap tile has a positive on-screen side"
        }
        require(worldStripSide.isFinite() && worldStripSide > 0f &&
            blendWorldStripSide.isFinite() && blendWorldStripSide > 0f) {
            "a ground world strip has a positive texel side"
        }
    }
}

/** The frame-wide values consumed by ADR 0086's mixed ground programs. */
internal class GroundBlendFrame(
    val mask: BlendMask,
    val frameWidth: Float,
    val frameHeight: Float,
)

/**
 * One Mercator ground tile's DEM: the padded texture and window every projection needs, plus the
 * tile's own Mercator `y` extent, which only Mercator does.
 *
 * The two travel together rather than as two fields with a default, because
 * [TERRAIN_GROUND_VERTEX_SOURCE]'s latitude term is meaningless without the extent and a plausible
 * default for it — the whole world, say — would draw a tile's terrain at up to 11.6x the right
 * height with nothing to notice it.
 */
/**
 * One basemap tile's Mercator `y` extent as `(north, south)`, which is what
 * [TERRAIN_GROUND_VERTEX_SOURCE]'s latitude term interpolates between.
 *
 * **Two vertically adjacent tiles are handed the identical `Float` for the edge they share**, by
 * exactly the mechanism [globeGroundTileEdges] documents: `(tileY + 1) / 2^lod` of one and
 * `tileY / 2^lod` of the next are the same `Double`, so they narrow to the same `Float`, and `mix`
 * returns its endpoints exactly. Both sides therefore evaluate `cosh` on the same argument and scale
 * the same DEM height by the same number, which is what keeps the seam closed in the drawn ground as
 * well as in the sampled one.
 */
internal fun mercatorTileYEdges(lod: Int, tileY: Int): FloatArray {
    require(lod >= 0) { "a basemap tile's lod is never negative" }
    val dimension = (1L shl lod).toDouble()
    return floatArrayOf(
        (tileY.toDouble() / dimension).toFloat(),
        ((tileY.toDouble() + 1.0) / dimension).toFloat(),
    )
}

internal class MercatorGroundTileDem(val dem: GroundTileDem, mercatorY: FloatArray) {
    /** `(mercator y at the tile's north edge, mercator y at its south edge)`, both in `[0, 1]`. */
    val mercatorY: FloatArray = mercatorY.copyOf()

    init {
        require(mercatorY.size == 2) { "a ground tile spans two mercator y bounds" }
    }
}

/**
 * Draws [tiles] as the frame's ground, in the order given.
 *
 * **Depth testing on; depth writes on exactly when this frame's ground is displaced (ADR 0039,
 * superseding ADR 0027 for this pass alone).** ADR 0027 removed the ground's depth writes because
 * flat map-plane content acting as an occluder cost a coplanar altitude-0 `Geometry` up to 100% of
 * its pixels between consecutive frames -- the ground's depth and the quad's are different
 * floating-point products of the same plane, so they differ by an epsilon whose sign changes as the
 * camera moves. Terrain inverts that trade: a mountain that does not hide what is behind it is a
 * different picture of the world, not a flicker on a shared plane.
 *
 * So the write is **conditional, and the condition is the frame's rather than the tile's**. If any
 * tile of this ground is displaced the whole pass writes; if none is -- which is 28 of the corpus's
 * 34 styles and every frame of three published releases -- the pass writes nothing and ADR 0027's
 * rule stands untouched. There is no frame in which that costs something an unconditional write
 * would have bought, because the write buys occlusion only where the ground has relief and the
 * defect it revives exists only where it has none. A per-tile flip was rejected by ADR 0039: it
 * churns state inside the loop, and it would let a coplanar `Geometry` win over a flat coverage gap
 * while losing to the displaced tile beside it.
 *
 * [tiles] order and the ground's position first in [SceneContent.draw] stay contracts. Inside a
 * frame with no terrain they remain the *whole* of the rule rather than only its tie-break; inside a
 * displaced one, depth decides between the ground and what is drawn after it, and order decides the
 * rest.
 *
 * Blend state is established here rather than inherited, exactly as [drawStickers] does and for the
 * same reason: the premultiplied `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` function is what [uploadTexture]'s
 * [TextureContent.IMAGE] path requires, and a basemap style with a transparent background composites
 * correctly over the cleared offscreen surface only under it.
 *
 * **Face culling is established here too, and that is ADR 0038's whole new obligation for this
 * pass.** It is **explicitly disabled** rather than left alone. Until Cycle G this function set no
 * cull state at all and inherited whatever the caller had left, which was harmless only by luck:
 * the ground's triangles happen to wind counter-clockwise, so an inherited enable removed nothing.
 * Disabling makes a mercator frame's pixels a function of this pass rather than of its caller — the
 * same rule ADR 0027 already applies to `depthMask(false)` — and it is what lets a cycle that
 * changes the globe change no mercator pixel.
 *
 * **This function is the Mercator ground and takes no projection mode**, which is Cycle G task 10's
 * correction to task 6. Task 6 gave it a `projectionMode` parameter whose `GLOBE` arm enabled
 * culling and went on drawing the flat quad, because the subdivided sphere grid did not exist yet;
 * task 7 built [drawGlobeGround] and task 10 routed globe frames to it, which left that arm
 * unreachable from production and a trap for whoever wired the next thing — a flat quad drawn with
 * backface culling on is not obviously wrong on screen. ADR 0038's globe half is now
 * [drawGlobeGround]'s unconditional `enable(GL_CULL_FACE)`, on exactly the reasoning that function's
 * own KDoc already gave for taking no mode: a parameter whose only legal value is one constant is a
 * mode that can be got wrong rather than a decision.
 *
 * **[cellsPerTileSide] defaults to 1, and at 1 an undisplaced tile draws exactly what the
 * four-vertex quad drew.** The frame's one granularity is now the caller's answer rather than a
 * placeholder: `SceneContent` reconciles the globe's curvature claim with terrain's own through
 * `groundCellsPerTileSide`, because **two tiles sharing an edge at different granularities is the
 * crack the globe's KDoc already records**. The default survives for the frames that need nothing
 * else — no terrain and no sphere — where the equivalence remains the contract: same outline, same
 * UVs, same pixels.
 *
 * **[elevation] is the frame's, and each tile decides for itself whether it uses it.** A frame with
 * no terrain and no blend passes `null` and every tile draws through [GroundPipeline.program],
 * which is the program three releases shipped; a terrain frame passes the decode and the metre
 * scale, and each tile with a [ResolvedGroundTile.elevation] draws through [GroundPipeline.terrain]
 * instead. ADR 0086 substitutes the corresponding mixed programs without changing this state or
 * order. **The two are interleaved in [tiles] order rather than partitioned**, because ADR 0041 lets
 * a single absent DEM make one tile flat among displaced neighbours, and reordering the ground to
 * group the programs would change which of two overlapping alpha edges is composited last — a
 * pixel difference bought for a `useProgram` this pass issues at most a few dozen times.
 */
internal fun drawGround(
    binding: GlBinding,
    pipeline: GroundPipeline,
    tiles: List<ResolvedGroundTile>,
    cellsPerTileSide: Int = 1,
    elevation: MercatorGroundElevationFrame? = null,
    blend: GroundBlendFrame? = null,
) {
    if (tiles.isEmpty()) return
    require(tiles.all { tile -> (tile.blendTexture != null) == (blend != null) }) {
        "every tile in an ADR 0086 blended ground carries exactly one paired target texture"
    }

    val grid = groundGrid(binding, pipeline.grids, cellsPerTileSide)
    binding.bindVertexArray(grid.vertexArray)
    binding.enable(GL_BLEND)
    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
    binding.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
    binding.enable(GL_DEPTH_TEST)
    // Each tile's own answer to "am I displaced", resolved once. The frame's answer is `any` over
    // it rather than a second reading of [elevation], so the mask below and the program switch
    // inside the loop cannot disagree about what this frame's terrain is.
    val tileElevations = tiles.map { if (elevation == null) null else it.elevation }
    binding.depthMask(tileElevations.any { it != null })
    binding.disable(GL_CULL_FACE)

    // Which program was made current last, so a run of tiles on one side of the terrain/flat split
    // costs one `useProgram` rather than one per tile. `null` until the first tile, because either
    // program may be the first: a frame whose only coverage gap is its first tile starts flat.
    var displacing: Boolean? = null
    tiles.forEachIndexed { index, tile ->
        val tileElevation = tileElevations[index]
        val wantsDisplacement = tileElevation != null
        val mixedProgram = if (blend == null) {
            null
        } else {
            requireNotNull(if (wantsDisplacement) pipeline.terrainMixed else pipeline.mixed) {
                "mixed programs must be compiled before an ADR 0086 blended ground draw"
            }
        }
        if (displacing != wantsDisplacement) {
            displacing = wantsDisplacement
            if (mixedProgram != null) {
                val blendFrame = requireNotNull(blend)
                binding.useProgram(mixedProgram.program)
                if (mixedProgram.textureUniformLocation >= 0) {
                    binding.uniform1i(mixedProgram.textureUniformLocation, GROUND_PRIMARY_TEXTURE_UNIT)
                }
                if (mixedProgram.blendTextureUniformLocation >= 0) {
                    binding.uniform1i(
                        mixedProgram.blendTextureUniformLocation,
                        groundBlendTextureUnit(wantsDisplacement = wantsDisplacement),
                    )
                }
                bindGroundCapSamplers(binding = binding, locations = mixedProgram.cap)
                bindGroundBlendMask(
                    binding = binding,
                    program = mixedProgram,
                    mask = blendFrame.mask,
                    frameWidth = blendFrame.frameWidth,
                    frameHeight = blendFrame.frameHeight,
                )
                if (wantsDisplacement) {
                    if (mixedProgram.elevationScaleUniformLocation >= 0) {
                        binding.uniform1f(
                            mixedProgram.elevationScaleUniformLocation,
                            requireNotNull(elevation).equatorialLogicalPixelsPerMetre,
                        )
                    }
                    bindGroundElevationFrame(
                        binding = binding,
                        locations = requireNotNull(mixedProgram.elevation),
                        dem = requireNotNull(elevation).dem,
                    )
                }
            } else if (wantsDisplacement) {
                binding.useProgram(pipeline.terrain.program)
                if (pipeline.terrain.textureUniformLocation >= 0) {
                    binding.uniform1i(pipeline.terrain.textureUniformLocation, GROUND_PRIMARY_TEXTURE_UNIT)
                }
                bindGroundCapSamplers(binding = binding, locations = pipeline.terrain.cap)
                if (pipeline.terrain.elevationScaleUniformLocation >= 0) {
                    binding.uniform1f(
                        pipeline.terrain.elevationScaleUniformLocation,
                        requireNotNull(elevation).equatorialLogicalPixelsPerMetre,
                    )
                }
                bindGroundElevationFrame(
                    binding,
                    pipeline.terrain.elevation,
                    requireNotNull(elevation).dem,
                )
            } else {
                binding.useProgram(pipeline.program)
                if (pipeline.textureUniformLocation >= 0) {
                    binding.uniform1i(pipeline.textureUniformLocation, GROUND_PRIMARY_TEXTURE_UNIT)
                }
                bindGroundCapSamplers(binding = binding, locations = pipeline.cap)
            }
        }

        val matrixLocation = if (mixedProgram != null) {
            mixedProgram.modelViewProjectionUniformLocation
        } else if (wantsDisplacement) {
            pipeline.terrain.modelViewProjectionUniformLocation
        } else {
            pipeline.modelViewProjectionUniformLocation
        }
        if (matrixLocation >= 0) {
            binding.uniformMatrix4fv(matrixLocation, 1, false, tile.modelViewProjection)
        }
        val uvVLocation = if (mixedProgram != null) {
            mixedProgram.tileUvVUniformLocation
        } else if (wantsDisplacement) {
            pipeline.terrain.tileUvVUniformLocation
        } else {
            pipeline.tileUvVUniformLocation
        }
        if (uvVLocation >= 0) {
            binding.uniform2f(uvVLocation, tile.uvV[0], tile.uvV[1])
        }
        val windowLocation = if (mixedProgram != null) {
            mixedProgram.colourWindowUniformLocation
        } else if (wantsDisplacement) {
            pipeline.terrain.colourWindowUniformLocation
        } else {
            pipeline.colourWindowUniformLocation
        }
        if (windowLocation >= 0) {
            // Written for every tile, identity included (ADR 0057): a uniform left over from the
            // previous tile is the one way this can draw the wrong part of the right texture.
            binding.uniform3f(
                windowLocation,
                tile.colourWindow.scale,
                tile.colourWindow.offsetU,
                tile.colourWindow.offsetV,
            )
        }
        if (mixedProgram != null && mixedProgram.blendColourWindowUniformLocation >= 0) {
            binding.uniform3f(
                mixedProgram.blendColourWindowUniformLocation,
                tile.blendColourWindow.scale,
                tile.blendColourWindow.offsetU,
                tile.blendColourWindow.offsetV,
            )
        }
        if (tileElevation != null) {
            val mercatorYLocation = mixedProgram?.mercatorYUniformLocation
                ?: pipeline.terrain.mercatorYUniformLocation
            if (mercatorYLocation >= 0) {
                binding.uniform2f(
                    mercatorYLocation,
                    tileElevation.mercatorY[0],
                    tileElevation.mercatorY[1],
                )
            }
            bindGroundElevationTile(
                binding = binding,
                locations = mixedProgram?.elevation ?: pipeline.terrain.elevation,
                tile = tileElevation.dem,
            )
        }
        val capLocations = mixedProgram?.cap ?: if (wantsDisplacement) pipeline.terrain.cap else pipeline.cap
        bindGroundCapTile(
            binding = binding,
            locations = capLocations,
            tile = tile,
            mixed = mixedProgram != null,
        )
        if (mixedProgram != null) {
            binding.activeTexture(
                GL_TEXTURE0 + groundBlendTextureUnit(wantsDisplacement = wantsDisplacement),
            )
            binding.bindTexture(GL_TEXTURE_2D, requireNotNull(tile.blendTexture))
        }
        // Bind the target first so selecting unit zero also establishes the final active unit.
        binding.activeTexture(GL_TEXTURE0)
        binding.bindTexture(GL_TEXTURE_2D, tile.texture)
        binding.drawElements(GL_TRIANGLES, grid.indexCount, GL_UNSIGNED_SHORT, 0)
    }
}

private const val GROUND_PRIMARY_TEXTURE_UNIT: Int = 0
private const val GROUND_BLEND_TEXTURE_UNIT: Int = 1
private const val GROUND_TERRAIN_BLEND_TEXTURE_UNIT: Int = 2
private const val GROUND_WORLD_TEXTURE_UNIT: Int = 3
private const val GROUND_BLEND_WORLD_TEXTURE_UNIT: Int = 4
private const val GROUND_OPPOSITE_WORLD_TEXTURE_UNIT: Int = 5
private const val GROUND_BLEND_OPPOSITE_WORLD_TEXTURE_UNIT: Int = 6

/** Terrain already owns unit 1 for its DEM, so its ADR 0086 target texture uses unit 2. */
private fun groundBlendTextureUnit(wantsDisplacement: Boolean): Int =
    if (wantsDisplacement) GROUND_TERRAIN_BLEND_TEXTURE_UNIT else GROUND_BLEND_TEXTURE_UNIT

private fun bindGroundCapSamplers(binding: GlBinding, locations: GroundCapUniformLocations) {
    if (locations.worldStrip >= 0) {
        binding.uniform1i(locations.worldStrip, GROUND_WORLD_TEXTURE_UNIT)
    }
    if (locations.blendWorldStrip >= 0) {
        binding.uniform1i(locations.blendWorldStrip, GROUND_BLEND_WORLD_TEXTURE_UNIT)
    }
    if (locations.oppositeWorldStrip >= 0) {
        binding.uniform1i(locations.oppositeWorldStrip, GROUND_OPPOSITE_WORLD_TEXTURE_UNIT)
    }
    if (locations.blendOppositeWorldStrip >= 0) {
        binding.uniform1i(
            locations.blendOppositeWorldStrip,
            GROUND_BLEND_OPPOSITE_WORLD_TEXTURE_UNIT,
        )
    }
}

private fun bindGroundCapTile(
    binding: GlBinding,
    locations: GroundCapUniformLocations,
    tile: ResolvedGroundTile,
    mixed: Boolean,
) {
    if (locations.mode >= 0) binding.uniform1i(locations.mode, tile.capMode)
    if (locations.spanTiles >= 0) binding.uniform1f(locations.spanTiles, tile.capSpanTiles)
    if (locations.worldU >= 0) {
        binding.uniform2f(locations.worldU, tile.capWorldUStart, tile.capWorldUWidth)
    }
    if (locations.lodScale >= 0) binding.uniform1f(locations.lodScale, tile.capLodScale)
    if (locations.tileSideLogicalPixels >= 0) {
        binding.uniform1f(locations.tileSideLogicalPixels, tile.capTileSideLogicalPixels)
    }
    if (locations.worldStripSide >= 0) {
        binding.uniform1f(locations.worldStripSide, tile.worldStripSide)
    }
    if (locations.blendWorldStripSide >= 0) {
        binding.uniform1f(locations.blendWorldStripSide, tile.blendWorldStripSide)
    }
    if (locations.blendWorldAvailable >= 0) {
        binding.uniform1i(locations.blendWorldAvailable, if (tile.blendWorldStrip == null) 0 else 1)
    }

    binding.activeTexture(GL_TEXTURE0 + GROUND_WORLD_TEXTURE_UNIT)
    binding.bindTexture(GL_TEXTURE_2D, tile.worldStrip ?: tile.texture)
    tile.oppositeWorldStrip?.let { opposite ->
        binding.activeTexture(GL_TEXTURE0 + GROUND_OPPOSITE_WORLD_TEXTURE_UNIT)
        binding.bindTexture(GL_TEXTURE_2D, opposite)
    }
    if (mixed) {
        binding.activeTexture(GL_TEXTURE0 + GROUND_BLEND_WORLD_TEXTURE_UNIT)
        binding.bindTexture(GL_TEXTURE_2D, tile.blendWorldStrip ?: requireNotNull(tile.blendTexture))
        tile.blendOppositeWorldStrip?.let { opposite ->
            binding.activeTexture(GL_TEXTURE0 + GROUND_BLEND_OPPOSITE_WORLD_TEXTURE_UNIT)
            binding.bindTexture(GL_TEXTURE_2D, opposite)
        }
    }
}

internal fun bindGroundBlendMask(
    binding: GlBinding,
    program: GroundMixedProgram,
    mask: BlendMask,
    frameWidth: Float,
    frameHeight: Float,
) {
    if (program.blendFrameSizeUniformLocation >= 0) {
        binding.uniform2f(program.blendFrameSizeUniformLocation, frameWidth, frameHeight)
    }
    when (mask) {
        is BlendMask.Uniform -> {
            if (program.blendKindUniformLocation >= 0) binding.uniform1i(program.blendKindUniformLocation, 0)
            if (program.blendParamsUniformLocation >= 0) {
                binding.uniform4f(program.blendParamsUniformLocation, mask.amount, 0f, 0f, 0f)
            }
        }
        is BlendMask.Radial -> {
            if (program.blendKindUniformLocation >= 0) binding.uniform1i(program.blendKindUniformLocation, 1)
            if (program.blendParamsUniformLocation >= 0) {
                binding.uniform4f(
                    program.blendParamsUniformLocation,
                    mask.centreX,
                    mask.centreY,
                    mask.radius,
                    mask.feather,
                )
            }
        }
        is BlendMask.Linear -> {
            if (program.blendKindUniformLocation >= 0) binding.uniform1i(program.blendKindUniformLocation, 2)
            if (program.blendParamsUniformLocation >= 0) {
                binding.uniform4f(
                    program.blendParamsUniformLocation,
                    mask.angleRadians,
                    mask.position,
                    mask.feather,
                    0f,
                )
            }
        }
    }
}
