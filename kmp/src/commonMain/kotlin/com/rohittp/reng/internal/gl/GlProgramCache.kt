package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.internal.shader.ShaderProfilePlan

internal class GlProgramCache {
    private val programs: LinkedHashMap<ResourceKey, Int> = LinkedHashMap()

    /**
     * The consumer-value shadow of each cached program that has asked for one (ADR 0080's 2026-10-22
     * note). Held here, beside the program name, because a shadow describes one linked program: every
     * pipeline drawing with that program must share it, and it must die exactly when the program does
     * -- on [remove], and on [forgetAll] when the context is lost. Made on first request rather than at
     * link, so RenG's own programs, which take no consumer values, never carry one.
     */
    private val shadows: HashMap<ResourceKey, ConsumerValueShadow> = HashMap()

    internal fun getOrCompile(
        binding: GlBinding,
        dialect: ShaderDialect,
        key: ResourceKey,
        vertexPlan: ShaderProfilePlan,
        fragmentPlan: ShaderProfilePlan,
        consumerAuthored: Boolean = false,
        infoLogObserver: ShaderInfoLogObserver = ShaderInfoLogObserver { _, _ -> },
    ): GlProgramResult {
        programs[key]?.let { return GlProgramResult.Linked(it) }
        val result = compileShaderProgram(
            binding, dialect, key, vertexPlan, fragmentPlan, consumerAuthored, infoLogObserver,
        )
        if (result is GlProgramResult.Linked) programs[key] = result.program
        return result
    }

    internal fun program(key: ResourceKey): Int? = programs[key]

    internal fun keys(): List<ResourceKey> = ArrayList(programs.keys)

    /** The shadow of the program cached under [key]; the program must be cached. */
    internal fun consumerShadow(key: ResourceKey): ConsumerValueShadow {
        check(key in programs) { "a consumer-value shadow belongs to a cached program" }
        return shadows.getOrPut(key) { ConsumerValueShadow() }
    }

    internal fun remove(key: ResourceKey): Int? {
        shadows.remove(key)
        return programs.remove(key)
    }

    /** Drops every cached program name without issuing a delete, for declared GPU object loss. */
    internal fun forgetAll() {
        programs.clear()
        shadows.clear()
    }
}
