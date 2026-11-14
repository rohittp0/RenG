package com.rohittp.reng

import com.rohittp.reng.internal.firewall.BasemapTileRenderer
import com.rohittp.reng.internal.firewall.installedBasemapTileRenderer
import kotlin.js.JsAny
import kotlin.js.unsafeCast

internal var currentWebGl2Context: WebGl2Context? = null

/**
 * A kind of WebGL object whose integer names are shared by RenG and its consumer on a context.
 * RenG uses consumer-owned contexts and never creates a WebGL context itself (ADR 0001).
 */
public enum class WebGlObjectKind {
    FRAMEBUFFER,
    RENDERBUFFER,
    TEXTURE,
    SAMPLER,
    BUFFER,
    VERTEX_ARRAY,
    SHADER,
    PROGRAM,
}

/** Selects the consumer-owned WebGL2 context used by subsequent RenG calls on the browser main thread. */
public fun makeWebGl2ContextCurrent(context: JsAny) {
    currentWebGl2Context = context.unsafeCast<WebGl2Context>()
}

/**
 * Returns the integer name shared by RenG and its consumer for [obj] on [context], registering it
 * if necessary. Names belong to the consumer-owned context; RenG never creates a context (ADR 0001).
 */
public fun webGlObjectName(context: JsAny, kind: WebGlObjectKind, obj: JsAny): Int =
    webGlObjectNames(context, kind).nameFor(obj)

/**
 * Returns the WebGL object behind [name] in the names shared by RenG and its consumer on [context],
 * or null for zero or an unknown/deleted name. RenG never creates a context (ADR 0001).
 */
public fun webGlObjectForName(context: JsAny, kind: WebGlObjectKind, name: Int): JsAny? =
    webGlObjectNames(context, kind)[name]

/**
 * Forgets [name] from the names shared by RenG and its consumer on [context]. Call this after the
 * consumer deletes the object itself. RenG never creates a context (ADR 0001).
 */
public fun forgetWebGlObjectName(context: JsAny, kind: WebGlObjectKind, name: Int) {
    webGlObjectNames(context, kind).forget(name)
}

/** Clears the consumer-owned WebGL2 context currently selected for RenG. */
public fun clearCurrentWebGl2Context() {
    currentWebGl2Context = null
}

internal fun webGlObjectNames(context: JsAny, kind: WebGlObjectKind): WebGlObjectNames =
    WebGlObjectNames(objectNamesState(contextObjectNameRegistries, context, kind.ordinal))

internal fun webGlUniformLocationNames(context: JsAny, program: Int): WebGlObjectNames =
    WebGlObjectNames(objectNamesState(contextObjectNameRegistries, context, uniformLocationKey(program)))

internal fun forgetWebGlUniformLocationNames(context: JsAny, program: Int) {
    forgetObjectNamesState(contextObjectNameRegistries, context, uniformLocationKey(program))
}

internal class WebGlObjectNames(private val state: JsObjectNamesState) {
    operator fun get(name: Int): JsAny? = if (name == 0) null else objectNameValue(state, name)

    fun require(name: Int): JsAny = get(name) ?: error("unknown WebGL object name $name")

    fun add(value: JsAny?): Int {
        val actual = value ?: error("WebGL object allocation failed")
        return addObjectName(state, actual)
    }

    fun nameFor(value: JsAny?): Int {
        if (value == null) return 0
        return objectNameFor(state, value).takeIf { it != 0 } ?: add(value)
    }

    fun delete(name: Int, action: (JsAny?) -> Unit) {
        if (name == 0) return
        val value = get(name) ?: return
        action(value)
        forget(name)
    }

    fun forget(name: Int) {
        if (name != 0) forgetObjectName(state, name)
    }
}

private fun uniformLocationKey(program: Int): Int = -program - 1

private external interface JsContextObjectNameRegistries : JsAny

internal external interface JsObjectNamesState : JsAny

@JsFun("() => new WeakMap()")
private external fun newContextObjectNameRegistries(): JsContextObjectNameRegistries

private val contextObjectNameRegistries = newContextObjectNameRegistries()

@JsFun("(registries, context, key) => { let registry = registries.get(context); if (!registry) { registry = Object.create(null); registries.set(context, registry); } let state = registry[key]; if (!state) { state = { values: [null], namesByObject: new Map() }; registry[key] = state; } return state; }")
private external fun objectNamesState(
    registries: JsContextObjectNameRegistries,
    context: JsAny,
    key: Int,
): JsObjectNamesState

@JsFun("(registries, context, key) => { const registry = registries.get(context); if (registry) delete registry[key]; }")
private external fun forgetObjectNamesState(
    registries: JsContextObjectNameRegistries,
    context: JsAny,
    key: Int,
)

@JsFun("(state, name) => state.values[name] ?? null")
private external fun objectNameValue(state: JsObjectNamesState, name: Int): JsAny?

@JsFun("(state, value) => { state.values.push(value); const name = state.values.length - 1; state.namesByObject.set(value, name); return name; }")
private external fun addObjectName(state: JsObjectNamesState, value: JsAny): Int

@JsFun("(state, value) => state.namesByObject.get(value) || 0")
private external fun objectNameFor(state: JsObjectNamesState, value: JsAny): Int

@JsFun("(state, name) => { const value = state.values[name]; if (value == null) return; state.namesByObject.delete(value); state.values[name] = null; }")
private external fun forgetObjectName(state: JsObjectNamesState, name: Int)

/**
 * Installs a process-local raw basemap tile renderer. RenG supplies Rentile's pre-acquisition
 * [outputRequestKey] and does not acquire the tile's colour resources itself. Each browser worker
 * has a separate Wasm instance, so installing this in a render worker cannot affect the page or a
 * tile worker.
 */
public fun installBasemapTileRenderer(
    renderer: suspend (
        styleLocator: String,
        zoom: Int,
        column: Int,
        row: Int,
        outputSizePx: Int,
        outputRequestKey: String,
        accessMode: ResourceAccessMode,
    ) -> BasemapTileRenderResult,
) {
    installedBasemapTileRenderer = BasemapTileRenderer(renderer)
}

internal external interface WebGl2Context : JsAny {
    fun getError(): Int
    fun getParameter(pname: Int): JsAny?
    fun getIndexedParameter(target: Int, index: Int): JsAny?
    fun getSupportedExtensions(): JsArray<JsString>?
    fun getExtension(name: JsString): JsAny?
    fun isEnabled(cap: Int): Boolean

    fun createFramebuffer(): JsAny?
    fun deleteFramebuffer(framebuffer: JsAny?)
    fun bindFramebuffer(target: Int, framebuffer: JsAny?)
    fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: JsAny?, level: Int)
    fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: JsAny?)
    fun checkFramebufferStatus(target: Int): Int
    fun isFramebuffer(framebuffer: JsAny?): Boolean
    fun createRenderbuffer(): JsAny?
    fun deleteRenderbuffer(renderbuffer: JsAny?)
    fun bindRenderbuffer(target: Int, renderbuffer: JsAny?)
    fun renderbufferStorage(target: Int, internalFormat: Int, width: Int, height: Int)
    fun blitFramebuffer(
        sourceX0: Int, sourceY0: Int, sourceX1: Int, sourceY1: Int,
        destinationX0: Int, destinationY0: Int, destinationX1: Int, destinationY1: Int,
        mask: Int, filter: Int,
    )
    fun drawBuffers(buffers: JsAny)
    fun readBuffer(mode: Int)

    fun createTexture(): JsAny?
    fun deleteTexture(texture: JsAny?)
    fun bindTexture(target: Int, texture: JsAny?)
    fun activeTexture(unit: Int)
    fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: JsAny?,
    )
    fun texSubImage2D(
        target: Int, level: Int, xOffset: Int, yOffset: Int, width: Int, height: Int,
        format: Int, type: Int, pixels: JsAny,
    )
    fun compressedTexImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, data: JsAny,
    )
    fun texStorage2D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int)
    fun texParameteri(target: Int, pname: Int, value: Int)
    fun generateMipmap(target: Int)
    fun createSampler(): JsAny?
    fun deleteSampler(sampler: JsAny?)
    fun bindSampler(unit: Int, sampler: JsAny?)
    fun samplerParameteri(sampler: JsAny, pname: Int, value: Int)
    fun pixelStorei(pname: Int, value: Int)
    fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: JsAny)

    fun createBuffer(): JsAny?
    fun deleteBuffer(buffer: JsAny?)
    fun bindBuffer(target: Int, buffer: JsAny?)
    fun bindBufferBase(target: Int, index: Int, buffer: JsAny?)
    fun bufferData(target: Int, size: Int, usage: Int)
    fun bufferData(target: Int, data: JsAny, usage: Int)
    fun bufferSubData(target: Int, offset: Int, data: JsAny)
    fun createVertexArray(): JsAny?
    fun deleteVertexArray(array: JsAny?)
    fun bindVertexArray(array: JsAny?)
    fun enableVertexAttribArray(index: Int)
    fun disableVertexAttribArray(index: Int)
    fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int)

    fun createShader(type: Int): JsAny?
    fun deleteShader(shader: JsAny?)
    fun shaderSource(shader: JsAny, source: JsString)
    fun compileShader(shader: JsAny)
    fun getShaderParameter(shader: JsAny, pname: Int): JsAny?
    fun getShaderInfoLog(shader: JsAny): JsString?
    fun createProgram(): JsAny?
    fun deleteProgram(program: JsAny?)
    fun attachShader(program: JsAny, shader: JsAny)
    fun linkProgram(program: JsAny)
    fun getProgramParameter(program: JsAny, pname: Int): JsAny?
    fun getProgramInfoLog(program: JsAny): JsString?
    fun useProgram(program: JsAny?)
    fun getAttribLocation(program: JsAny, name: JsString): Int
    fun getUniformLocation(program: JsAny, name: JsString): JsAny?
    fun uniform1i(location: JsAny?, value: Int)
    fun uniform1f(location: JsAny?, value: Float)
    fun uniform2f(location: JsAny?, x: Float, y: Float)
    fun uniform3f(location: JsAny?, x: Float, y: Float, z: Float)
    fun uniform4f(location: JsAny?, x: Float, y: Float, z: Float, w: Float)
    fun uniform1ui(location: JsAny?, value: Int)
    fun uniformMatrix4fv(location: JsAny?, transpose: Boolean, value: JsAny)
    fun getUniformBlockIndex(program: JsAny, name: JsString): Int
    fun uniformBlockBinding(program: JsAny, blockIndex: Int, bindingPoint: Int)

    fun enable(cap: Int)
    fun disable(cap: Int)
    fun blendFuncSeparate(sourceRgb: Int, destinationRgb: Int, sourceAlpha: Int, destinationAlpha: Int)
    fun blendEquationSeparate(modeRgb: Int, modeAlpha: Int)
    fun blendColor(red: Float, green: Float, blue: Float, alpha: Float)
    fun depthFunc(function: Int)
    fun depthMask(enabled: Boolean)
    fun depthRange(near: Float, far: Float)
    fun cullFace(mode: Int)
    fun frontFace(mode: Int)
    fun viewport(x: Int, y: Int, width: Int, height: Int)
    fun scissor(x: Int, y: Int, width: Int, height: Int)
    fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean)
    fun clearColor(red: Float, green: Float, blue: Float, alpha: Float)
    fun clearDepth(depth: Float)
    fun clear(mask: Int)
    fun drawArrays(mode: Int, first: Int, count: Int)
    fun drawElements(mode: Int, count: Int, type: Int, offset: Int)
    fun finish()
}
