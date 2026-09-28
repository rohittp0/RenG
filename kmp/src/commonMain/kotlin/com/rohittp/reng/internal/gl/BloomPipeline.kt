package com.rohittp.reng.internal.gl

import com.rohittp.reng.Bloom
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile

private const val BLOOM_SOURCE_UNIFORM: String = "rengBloomSource"
private const val BLOOM_LEVEL_UNIFORM: String = "rengBloomLevel"
private const val BLOOM_EMISSION_UNIFORM: String = "rengBloomEmission"
private const val BLOOM_SOURCE_TEXEL_UNIFORM: String = "rengBloomSourceTexel"
private const val BLOOM_TARGET_TEXEL_UNIFORM: String = "rengBloomTargetTexel"
private const val BLOOM_ACCUMULATION_WEIGHT_UNIFORM: String = "rengBloomAccumulationWeight"
private const val BLOOM_LEVEL_WEIGHT_UNIFORM: String = "rengBloomLevelWeight"
private const val BLOOM_INTENSITY_UNIFORM: String = "rengBloomIntensity"
private const val BLOOM_INSIDE_SUPPRESSION_UNIFORM: String = "rengBloomInsideSuppression"
private const val BLOOM_WHITENESS_UNIFORM: String = "rengBloomWhiteness"

internal const val BLOOM_TARGET_LEVEL_COUNT: Int = 7

private const val BLOOM_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengBloomPosition;\n" +
        "layout(location = 1) in vec2 rengBloomTexCoord;\n" +
        "out vec2 rengBloomUv;\n" +
        "void main() {\n" +
        "    rengBloomUv = rengBloomTexCoord;\n" +
        "    gl_Position = vec4(rengBloomPosition, 0.0, 1.0);\n" +
        "}\n"

private const val BLOOM_DOWNSAMPLE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengBloomSource;\n" +
        "uniform vec2 rengBloomSourceTexel;\n" +
        "in vec2 rengBloomUv;\n" +
        "layout(location = 0) out vec4 rengBloomColour;\n" +
        "void main() {\n" +
        "    vec4 rengBloomCentre = texture(rengBloomSource, rengBloomUv);\n" +
        "    vec4 rengBloomCorners =\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomSourceTexel * vec2(-1.0, -1.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomSourceTexel * vec2( 1.0, -1.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomSourceTexel * vec2(-1.0,  1.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomSourceTexel * vec2( 1.0,  1.0));\n" +
        "    rengBloomColour = (4.0 * rengBloomCentre + rengBloomCorners) / 8.0;\n" +
        "}\n"

private const val BLOOM_UPSAMPLE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengBloomSource;\n" +
        "uniform sampler2D rengBloomLevel;\n" +
        "uniform vec2 rengBloomTargetTexel;\n" +
        "uniform float rengBloomAccumulationWeight;\n" +
        "uniform float rengBloomLevelWeight;\n" +
        "in vec2 rengBloomUv;\n" +
        "layout(location = 0) out vec4 rengBloomColour;\n" +
        "void main() {\n" +
        "    vec4 rengBloomEdges =\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2(-1.0,  0.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2( 1.0,  0.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2( 0.0, -1.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2( 0.0,  1.0));\n" +
        "    vec4 rengBloomDiagonals =\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2(-1.0, -1.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2( 1.0, -1.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2(-1.0,  1.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2( 1.0,  1.0));\n" +
        "    vec4 rengBloomTent = (rengBloomEdges + 2.0 * rengBloomDiagonals) / 12.0;\n" +
        "    rengBloomColour = rengBloomAccumulationWeight * rengBloomTent +\n" +
        "        rengBloomLevelWeight * texture(rengBloomLevel, rengBloomUv);\n" +
        "}\n"

private const val BLOOM_COMPOSITE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengBloomSource;\n" +
        "uniform sampler2D rengBloomEmission;\n" +
        "uniform float rengBloomIntensity;\n" +
        "uniform float rengBloomInsideSuppression;\n" +
        "uniform float rengBloomWhiteness;\n" +
        "in vec2 rengBloomUv;\n" +
        "layout(location = 0) out vec4 rengBloomColour;\n" +
        "void main() {\n" +
        "    vec3 rengBloomC = texture(rengBloomSource, rengBloomUv).rgb;\n" +
        "    float rengBloomMaximum = max(rengBloomC.r, max(rengBloomC.g, rengBloomC.b));\n" +
        "    rengBloomC = mix(rengBloomC, vec3(rengBloomMaximum), rengBloomWhiteness);\n" +
        "    float rengBloomInside = smoothstep(0.02, 0.30, texture(rengBloomEmission, rengBloomUv).a);\n" +
        "    rengBloomC *= rengBloomIntensity *\n" +
        "        (1.0 - rengBloomInsideSuppression * rengBloomInside);\n" +
        "    rengBloomColour = vec4(rengBloomC, 0.0);\n" +
        "}\n"

private val BLOOM_DOWNSAMPLE_SHADER_PAIR =
    ShaderPair(BLOOM_VERTEX_SOURCE, BLOOM_DOWNSAMPLE_FRAGMENT_SOURCE)
private val BLOOM_UPSAMPLE_SHADER_PAIR =
    ShaderPair(BLOOM_VERTEX_SOURCE, BLOOM_UPSAMPLE_FRAGMENT_SOURCE)
private val BLOOM_COMPOSITE_SHADER_PAIR =
    ShaderPair(BLOOM_VERTEX_SOURCE, BLOOM_COMPOSITE_FRAGMENT_SOURCE)

internal data class BloomTarget(
    val framebuffer: Int,
    val texture: Int,
    val width: Int,
    val height: Int,
)

internal class BloomDownsampleProgram(
    val key: ResourceKey,
    val program: Int,
    val sourceLocation: Int,
    val sourceTexelLocation: Int,
)

internal class BloomUpsampleProgram(
    val key: ResourceKey,
    val program: Int,
    val sourceLocation: Int,
    val levelLocation: Int,
    val targetTexelLocation: Int,
    val accumulationWeightLocation: Int,
    val levelWeightLocation: Int,
)

internal class BloomCompositeProgram(
    val key: ResourceKey,
    val program: Int,
    val sourceLocation: Int,
    val emissionLocation: Int,
    val intensityLocation: Int,
    val insideSuppressionLocation: Int,
    val whitenessLocation: Int,
)

internal class BloomResources internal constructor(
    internal val emission: BloomTarget,
    internal val downsampleLevels: List<BloomTarget>,
    internal val upsampleLevels: List<BloomTarget>,
    internal val sharedDepthRenderbuffer: Int,
    internal val vertexArray: Int,
    internal val vertexBuffer: Int,
    internal val downsample: BloomDownsampleProgram,
    internal val upsample: BloomUpsampleProgram,
    internal val composite: BloomCompositeProgram,
)

internal sealed interface BloomResourcesResult {
    data class Created(val resources: BloomResources) : BloomResourcesResult
    data class Failed(val failure: FailureDescriptor) : BloomResourcesResult
}

internal fun createBloomResources(
    binding: GlBinding,
    profile: RenderContextProfile,
    cache: GlProgramCache,
    outputSize: OutputPixelSize,
    sharedDepthRenderbuffer: Int,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): BloomResourcesResult {
    val downKey = deriver
        .internalPipeline(InternalPipelineRole.BLOOM_DOWNSAMPLE, BLOOM_DOWNSAMPLE_SHADER_PAIR).key
    val upKey = deriver
        .internalPipeline(InternalPipelineRole.BLOOM_UPSAMPLE, BLOOM_UPSAMPLE_SHADER_PAIR).key
    val compositeKey = deriver
        .internalPipeline(InternalPipelineRole.BLOOM_COMPOSITE, BLOOM_COMPOSITE_SHADER_PAIR).key

    val downProgram = compileBloomProgram(
        binding, profile.dialect, cache, downKey, BLOOM_DOWNSAMPLE_SHADER_PAIR,
    ) ?: return BloomResourcesResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, downKey))
    val upProgram = compileBloomProgram(
        binding, profile.dialect, cache, upKey, BLOOM_UPSAMPLE_SHADER_PAIR,
    ) ?: run {
        deleteBloomProgram(binding, cache, downKey)
        return BloomResourcesResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, upKey))
    }
    val compositeProgram = compileBloomProgram(
        binding, profile.dialect, cache, compositeKey, BLOOM_COMPOSITE_SHADER_PAIR,
    ) ?: run {
        deleteBloomProgram(binding, cache, downKey)
        deleteBloomProgram(binding, cache, upKey)
        return BloomResourcesResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, compositeKey))
    }

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]
    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    val quad = littleEndianBytes(COMPOSITE_QUAD)
    binding.bufferData(GL_ARRAY_BUFFER, quad.size, quad, GL_STATIC_DRAW)
    binding.enableVertexAttribArray(0)
    binding.vertexAttribPointer(0, 2, GL_FLOAT, false, BLOOM_QUAD_STRIDE_BYTES, 0)
    binding.enableVertexAttribArray(1)
    binding.vertexAttribPointer(1, 2, GL_FLOAT, false, BLOOM_QUAD_STRIDE_BYTES, BLOOM_QUAD_UV_OFFSET_BYTES)

    val allocated = ArrayList<BloomTarget>(1 + BLOOM_TARGET_LEVEL_COUNT * 2)
    fun fail(): BloomResourcesResult.Failed {
        allocated.forEach { deleteBloomTarget(binding, it) }
        binding.deleteVertexArrays(1, intArrayOf(vertexArray))
        binding.deleteBuffers(1, intArrayOf(vertexBuffer))
        deleteBloomProgram(binding, cache, downKey)
        deleteBloomProgram(binding, cache, upKey)
        deleteBloomProgram(binding, cache, compositeKey)
        return BloomResourcesResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, downKey))
    }

    val emission = createBloomTarget(
        binding, outputSize.width, outputSize.height, GL_RGBA8, sharedDepthRenderbuffer,
    ) ?: return fail()
    allocated += emission

    val levelSizes = ArrayList<Pair<Int, Int>>(BLOOM_TARGET_LEVEL_COUNT + 1)
    var width = outputSize.width
    var height = outputSize.height
    levelSizes += width to height
    repeat(BLOOM_TARGET_LEVEL_COUNT) {
        width = maxOf(1, (width + 1) / 2)
        height = maxOf(1, (height + 1) / 2)
        levelSizes += width to height
    }

    val downsampleLevels = ArrayList<BloomTarget>(BLOOM_TARGET_LEVEL_COUNT)
    for (level in 1..BLOOM_TARGET_LEVEL_COUNT) {
        val size = levelSizes[level]
        val target = createBloomTarget(binding, size.first, size.second, GL_RGBA8, null)
            ?: return fail()
        allocated += target
        downsampleLevels += target
    }

    val upsampleFormat = if (profile.supportsHalfFloatColourBuffer) GL_RGBA16F else GL_RGBA8
    val upsampleLevels = ArrayList<BloomTarget>(BLOOM_TARGET_LEVEL_COUNT)
    for (level in 0 until BLOOM_TARGET_LEVEL_COUNT) {
        val size = levelSizes[level]
        val target = createBloomTarget(binding, size.first, size.second, upsampleFormat, null)
            ?: return fail()
        allocated += target
        upsampleLevels += target
    }

    return BloomResourcesResult.Created(
        BloomResources(
            emission = emission,
            downsampleLevels = downsampleLevels,
            upsampleLevels = upsampleLevels,
            sharedDepthRenderbuffer = sharedDepthRenderbuffer,
            vertexArray = vertexArray,
            vertexBuffer = vertexBuffer,
            downsample = BloomDownsampleProgram(
                downKey,
                downProgram,
                binding.getUniformLocation(downProgram, BLOOM_SOURCE_UNIFORM),
                binding.getUniformLocation(downProgram, BLOOM_SOURCE_TEXEL_UNIFORM),
            ),
            upsample = BloomUpsampleProgram(
                upKey,
                upProgram,
                binding.getUniformLocation(upProgram, BLOOM_SOURCE_UNIFORM),
                binding.getUniformLocation(upProgram, BLOOM_LEVEL_UNIFORM),
                binding.getUniformLocation(upProgram, BLOOM_TARGET_TEXEL_UNIFORM),
                binding.getUniformLocation(upProgram, BLOOM_ACCUMULATION_WEIGHT_UNIFORM),
                binding.getUniformLocation(upProgram, BLOOM_LEVEL_WEIGHT_UNIFORM),
            ),
            composite = BloomCompositeProgram(
                compositeKey,
                compositeProgram,
                binding.getUniformLocation(compositeProgram, BLOOM_SOURCE_UNIFORM),
                binding.getUniformLocation(compositeProgram, BLOOM_EMISSION_UNIFORM),
                binding.getUniformLocation(compositeProgram, BLOOM_INTENSITY_UNIFORM),
                binding.getUniformLocation(compositeProgram, BLOOM_INSIDE_SUPPRESSION_UNIFORM),
                binding.getUniformLocation(compositeProgram, BLOOM_WHITENESS_UNIFORM),
            ),
        ),
    )
}

private fun compileBloomProgram(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    key: ResourceKey,
    shaderPair: ShaderPair,
): Int? {
    val vertexPlan = scanShaderProfile(shaderPair.vertexSource) ?: return null
    val fragmentPlan = scanShaderProfile(shaderPair.fragmentSource) ?: return null
    return when (val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> null
    }
}

private fun createBloomTarget(
    binding: GlBinding,
    width: Int,
    height: Int,
    internalFormat: Int,
    depthRenderbuffer: Int?,
): BloomTarget? {
    GlErrorQueue.drainOnEntry(binding)
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, internalFormat, width, height)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(
        GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0,
    )
    depthRenderbuffer?.let {
        binding.framebufferRenderbuffer(
            GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, it,
        )
    }
    val status = binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER)
    val error = GlErrorQueue.firstOwnError(binding)
    if (status != GL_FRAMEBUFFER_COMPLETE || error != GL_NO_ERROR) {
        binding.deleteFramebuffers(1, intArrayOf(framebuffer))
        binding.deleteTextures(1, intArrayOf(texture))
        return null
    }
    return BloomTarget(framebuffer, texture, width, height)
}

internal fun deleteBloomResources(
    binding: GlBinding,
    cache: GlProgramCache,
    resources: BloomResources,
) {
    (listOf(resources.emission) + resources.downsampleLevels + resources.upsampleLevels)
        .forEach { deleteBloomTarget(binding, it) }
    binding.deleteVertexArrays(1, intArrayOf(resources.vertexArray))
    binding.deleteBuffers(1, intArrayOf(resources.vertexBuffer))
    deleteBloomProgram(binding, cache, resources.downsample.key)
    deleteBloomProgram(binding, cache, resources.upsample.key)
    deleteBloomProgram(binding, cache, resources.composite.key)
}

private fun deleteBloomTarget(binding: GlBinding, target: BloomTarget) {
    binding.deleteFramebuffers(1, intArrayOf(target.framebuffer))
    binding.deleteTextures(1, intArrayOf(target.texture))
}

private fun deleteBloomProgram(binding: GlBinding, cache: GlProgramCache, key: ResourceKey) {
    cache.remove(key)?.let(binding::deleteProgram)
}

/** Runs the dual-Kawase chain and adds its result into [surfaceFramebuffer] without changing alpha. */
internal fun drawBloom(
    binding: GlBinding,
    resources: BloomResources,
    bloom: Bloom,
    surfaceFramebuffer: Int,
) {
    val weights = bloom.levelWeights
    val lastLevel = weights.lastIndex
    binding.disable(GL_DEPTH_TEST)
    binding.depthMask(false)
    binding.disable(GL_BLEND)
    binding.bindVertexArray(resources.vertexArray)
    binding.bindSampler(0, 0)
    binding.bindSampler(1, 0)

    var source = resources.emission
    for (level in 1..lastLevel) {
        val target = resources.downsampleLevels[level - 1]
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, target.framebuffer)
        binding.viewport(0, 0, target.width, target.height)
        binding.useProgram(resources.downsample.program)
        binding.activeTexture(GL_TEXTURE0)
        binding.bindTexture(GL_TEXTURE_2D, source.texture)
        bindSamplerUniform(binding, resources.downsample.sourceLocation, 0)
        bindVec2(
            binding,
            resources.downsample.sourceTexelLocation,
            1f / source.width.toFloat(),
            1f / source.height.toFloat(),
        )
        binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
        source = target
    }

    if (lastLevel == 0) {
        val target = resources.upsampleLevels[0]
        drawBloomUpsample(
            binding = binding,
            resources = resources,
            target = target,
            accumulation = resources.emission,
            accumulationWeight = 0f,
            level = resources.emission,
            levelWeight = weights[0],
        )
    } else {
        for (levelIndex in lastLevel - 1 downTo 0) {
            val target = resources.upsampleLevels[levelIndex]
            val first = levelIndex == lastLevel - 1
            val accumulation = if (first) {
                levelTarget(resources, lastLevel)
            } else {
                resources.upsampleLevels[levelIndex + 1]
            }
            drawBloomUpsample(
                binding = binding,
                resources = resources,
                target = target,
                accumulation = accumulation,
                accumulationWeight = if (first) weights[lastLevel] else 1f,
                level = levelTarget(resources, levelIndex),
                levelWeight = weights[levelIndex],
            )
        }
    }

    val composite = resources.composite
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, surfaceFramebuffer)
    binding.viewport(0, 0, resources.emission.width, resources.emission.height)
    binding.useProgram(composite.program)
    binding.bindVertexArray(resources.vertexArray)
    binding.activeTexture(GL_TEXTURE0)
    binding.bindTexture(GL_TEXTURE_2D, resources.upsampleLevels[0].texture)
    bindSamplerUniform(binding, composite.sourceLocation, 0)
    binding.activeTexture(GL_TEXTURE0 + 1)
    binding.bindTexture(GL_TEXTURE_2D, resources.emission.texture)
    bindSamplerUniform(binding, composite.emissionLocation, 1)
    bindFloat(binding, composite.intensityLocation, bloom.intensity)
    bindFloat(binding, composite.insideSuppressionLocation, bloom.insideSuppression)
    bindFloat(binding, composite.whitenessLocation, bloom.whiteness)
    binding.enable(GL_BLEND)
    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
    binding.blendFuncSeparate(GL_ONE, GL_ONE, GL_ZERO, GL_ONE)
    binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
}

private fun drawBloomUpsample(
    binding: GlBinding,
    resources: BloomResources,
    target: BloomTarget,
    accumulation: BloomTarget,
    accumulationWeight: Float,
    level: BloomTarget,
    levelWeight: Float,
) {
    val upsample = resources.upsample
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, target.framebuffer)
    binding.viewport(0, 0, target.width, target.height)
    binding.useProgram(upsample.program)
    binding.activeTexture(GL_TEXTURE0)
    binding.bindTexture(GL_TEXTURE_2D, accumulation.texture)
    bindSamplerUniform(binding, upsample.sourceLocation, 0)
    binding.activeTexture(GL_TEXTURE0 + 1)
    binding.bindTexture(GL_TEXTURE_2D, level.texture)
    bindSamplerUniform(binding, upsample.levelLocation, 1)
    bindVec2(
        binding,
        upsample.targetTexelLocation,
        1f / target.width.toFloat(),
        1f / target.height.toFloat(),
    )
    bindFloat(binding, upsample.accumulationWeightLocation, accumulationWeight)
    bindFloat(binding, upsample.levelWeightLocation, levelWeight)
    binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
}

private fun levelTarget(resources: BloomResources, level: Int): BloomTarget =
    if (level == 0) resources.emission else resources.downsampleLevels[level - 1]

private fun bindSamplerUniform(binding: GlBinding, location: Int, unit: Int) {
    if (location >= 0) binding.uniform1i(location, unit)
}

private fun bindFloat(binding: GlBinding, location: Int, value: Float) {
    if (location >= 0) binding.uniform1f(location, value)
}

private fun bindVec2(binding: GlBinding, location: Int, x: Float, y: Float) {
    if (location >= 0) binding.uniform2f(location, x, y)
}

private const val BLOOM_QUAD_STRIDE_BYTES: Int = 16
private const val BLOOM_QUAD_UV_OFFSET_BYTES: Int = 8
