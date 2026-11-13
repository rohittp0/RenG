package com.rohittp.reng

import com.rohittp.reng.internal.firewall.BasemapTileRenderer
import com.rohittp.reng.internal.firewall.installedBasemapTileRenderer
import kotlin.js.JsAny
import kotlin.js.unsafeCast

internal var currentWebGl2Context: WebGl2Context? = null

/** Selects the consumer-owned WebGL2 context used by subsequent RenG calls on the browser main thread. */
public fun makeWebGl2ContextCurrent(context: JsAny) {
    currentWebGl2Context = context.unsafeCast<WebGl2Context>()
}

/** Clears the consumer-owned WebGL2 context currently selected for RenG. */
public fun clearCurrentWebGl2Context() {
    currentWebGl2Context = null
}

/**
 * Installs a process-local raw basemap tile renderer. Each browser worker has a separate Wasm
 * instance, so installing this in a render worker cannot affect the page or a tile worker.
 */
public fun installBasemapTileRenderer(
    renderer: suspend (
        styleLocator: String,
        zoom: Int,
        column: Int,
        row: Int,
        outputSizePx: Int,
    ) -> ByteArray,
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
