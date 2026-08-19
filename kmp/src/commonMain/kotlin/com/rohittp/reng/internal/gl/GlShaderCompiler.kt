package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.shader.ShaderProfilePlan

internal enum class ShaderCompileStep {
    VERTEX_COMPILE,
    FRAGMENT_COMPILE,
    LINK,
}

internal fun interface ShaderInfoLogObserver {
    fun observe(step: ShaderCompileStep, log: String)
}

internal sealed interface GlProgramResult {
    data class Linked(val program: Int) : GlProgramResult

    data class Failed(val failure: FailureDescriptor) : GlProgramResult
}

internal fun ShaderProfilePlan.sourceFor(dialect: ShaderDialect): String = when (dialect) {
    ShaderDialect.GLES -> gles300Source()
    ShaderDialect.DESKTOP -> desktop330Source()
}

internal fun shaderProgramFailure(code: RenGErrorCode, key: ResourceKey): FailureDescriptor =
    if (key.kind == ResourceKind.GEOMETRY_PROGRAM) {
        FailureDescriptor(
            code = code,
            stage = PipelineStage.SHADER_COMPILATION,
            diagnostic = failureContextDiagnostic(
                stage = PipelineStage.SHADER_COMPILATION,
                fieldName = DiagnosticField.SHADER_PAIR,
                resourceKey = key,
            ),
        )
    } else {
        glOperationFailure(PipelineStage.GPU_RESOURCE, key)
    }

internal fun compileShaderProgram(
    binding: GlBinding,
    dialect: ShaderDialect,
    key: ResourceKey,
    vertexPlan: ShaderProfilePlan,
    fragmentPlan: ShaderProfilePlan,
    infoLogObserver: ShaderInfoLogObserver = ShaderInfoLogObserver { _, _ -> },
): GlProgramResult {
    val vertexShader = compileStage(
        binding, GL_VERTEX_SHADER, vertexPlan.sourceFor(dialect),
        ShaderCompileStep.VERTEX_COMPILE, infoLogObserver,
    ) ?: return GlProgramResult.Failed(
        shaderProgramFailure(RenGErrorCode.SHADER_COMPILE_FAILED, key),
    )

    val fragmentShader = compileStage(
        binding, GL_FRAGMENT_SHADER, fragmentPlan.sourceFor(dialect),
        ShaderCompileStep.FRAGMENT_COMPILE, infoLogObserver,
    )
    if (fragmentShader == null) {
        binding.deleteShader(vertexShader)
        return GlProgramResult.Failed(
            shaderProgramFailure(RenGErrorCode.SHADER_COMPILE_FAILED, key),
        )
    }

    val program = binding.createProgram()
    binding.attachShader(program, vertexShader)
    binding.attachShader(program, fragmentShader)
    binding.linkProgram(program)

    val status = IntArray(1)
    binding.getProgramiv(program, GL_LINK_STATUS, status)
    if (status[0] == 0) {
        infoLogObserver.observe(ShaderCompileStep.LINK, binding.getProgramInfoLog(program))
        binding.deleteShader(vertexShader)
        binding.deleteShader(fragmentShader)
        binding.deleteProgram(program)
        return GlProgramResult.Failed(
            shaderProgramFailure(RenGErrorCode.SHADER_LINK_FAILED, key),
        )
    }

    binding.deleteShader(vertexShader)
    binding.deleteShader(fragmentShader)
    return GlProgramResult.Linked(program)
}

private fun compileStage(
    binding: GlBinding,
    type: Int,
    source: String,
    step: ShaderCompileStep,
    infoLogObserver: ShaderInfoLogObserver,
): Int? {
    val shader = binding.createShader(type)
    binding.shaderSource(shader, source)
    binding.compileShader(shader)
    val status = IntArray(1)
    binding.getShaderiv(shader, GL_COMPILE_STATUS, status)
    if (status[0] != 0) return shader
    infoLogObserver.observe(step, binding.getShaderInfoLog(shader))
    binding.deleteShader(shader)
    return null
}
