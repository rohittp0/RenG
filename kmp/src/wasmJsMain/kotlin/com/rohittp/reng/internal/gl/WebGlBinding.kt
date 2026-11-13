@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package com.rohittp.reng.internal.gl

import com.rohittp.reng.WebGl2Context
import com.rohittp.reng.currentWebGl2Context
import kotlin.js.JsAny
import kotlin.js.toJsString
import kotlin.wasm.unsafe.withScopedMemoryAllocator

internal class WebGlBinding(private val gl: WebGl2Context) : GlBinding {
    private val framebuffers = ObjectNames()
    private val renderbuffers = ObjectNames()
    private val textures = ObjectNames()
    private val samplers = ObjectNames()
    private val buffers = ObjectNames()
    private val vertexArrays = ObjectNames()
    private val shaders = ObjectNames()
    private val programs = ObjectNames()
    private val uniformLocations = mutableMapOf<Int, ObjectNames>()
    private var currentProgram: Int = 0

    private val extensionNames: List<String>
    private val debugRendererInfo: JsAny?

    init {
        val supported = gl.getSupportedExtensions()
        extensionNames = if (supported == null) {
            emptyList()
        } else {
            List(supported.length) { index -> supported[index].toString() }
        }
        var debug: JsAny? = null
        extensionNames.forEach { name ->
            val extension = gl.getExtension(name.toJsString())
            if (name == "WEBGL_debug_renderer_info") debug = extension
        }
        debugRendererInfo = debug
    }

    override fun getError(): Int = gl.getError()

    override fun getString(name: Int): String? {
        val original = when (name) {
            GL_RENDERER -> if (debugRendererInfo != null) gl.getParameter(UNMASKED_RENDERER_WEBGL) else gl.getParameter(name)
            GL_VENDOR -> if (debugRendererInfo != null) gl.getParameter(UNMASKED_VENDOR_WEBGL) else gl.getParameter(name)
            else -> gl.getParameter(name)
        }
        val text = jsStringValue(original)?.toString() ?: return null
        return when (name) {
            GL_SHADING_LANGUAGE_VERSION -> "OpenGL ES GLSL ES 3.00 (WebGL: $text)"
            GL_VERSION -> "OpenGL ES 3.0 (WebGL: $text)"
            else -> text
        }
    }

    override fun getStringi(name: Int, index: Int): String? =
        if (name == GL_EXTENSIONS && index in extensionNames.indices) "GL_${extensionNames[index]}" else null

    override fun getIntegerv(pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an integer query needs a destination" }
        if (pname == GL_NUM_EXTENSIONS) {
            out[0] = extensionNames.size
            return
        }
        objectNamesForQuery(pname)?.let { names ->
            out[0] = names.nameFor(gl.getParameter(pname))
            return
        }
        copyNumbers(gl.getParameter(pname), out.size) { index, value -> out[index] = value.toInt() }
    }

    override fun getFloatv(pname: Int, out: FloatArray) {
        require(out.isNotEmpty()) { "a float query needs a destination" }
        copyNumbers(gl.getParameter(pname), out.size) { index, value -> out[index] = value.toFloat() }
    }

    override fun getBooleanv(pname: Int, out: BooleanArray) {
        require(out.isNotEmpty()) { "a boolean query needs a destination" }
        val value = gl.getParameter(pname)
        val count = minOf(out.size, jsArrayLikeLength(value))
        for (index in 0 until count) out[index] = jsBooleanAt(value, index)
    }

    override fun isEnabled(cap: Int): Boolean = gl.isEnabled(cap)

    override fun getIntegeri_v(pname: Int, index: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an indexed integer query needs a destination" }
        val value = gl.getIndexedParameter(pname, index)
        out[0] = if (pname == GL_UNIFORM_BUFFER_BINDING) buffers.nameFor(value) else jsNumberAt(value, 0).toInt()
    }

    override fun genFramebuffers(count: Int, out: IntArray) = generate(count, out, framebuffers) { gl.createFramebuffer() }
    override fun deleteFramebuffers(count: Int, names: IntArray) = delete(count, names, framebuffers, gl::deleteFramebuffer)
    override fun bindFramebuffer(target: Int, framebuffer: Int) = gl.bindFramebuffer(target, framebuffers[framebuffer])
    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) =
        gl.framebufferTexture2D(target, attachment, textureTarget, textures[texture], level)
    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: Int) =
        gl.framebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffers[renderbuffer])
    override fun checkFramebufferStatus(target: Int): Int = gl.checkFramebufferStatus(target)
    override fun isFramebuffer(framebuffer: Int): Boolean = gl.isFramebuffer(framebuffers[framebuffer])
    override fun genRenderbuffers(count: Int, out: IntArray) = generate(count, out, renderbuffers) { gl.createRenderbuffer() }
    override fun deleteRenderbuffers(count: Int, names: IntArray) = delete(count, names, renderbuffers, gl::deleteRenderbuffer)
    override fun bindRenderbuffer(target: Int, renderbuffer: Int) = gl.bindRenderbuffer(target, renderbuffers[renderbuffer])
    override fun renderbufferStorage(target: Int, internalFormat: Int, width: Int, height: Int) =
        gl.renderbufferStorage(target, internalFormat, width, height)
    override fun blitFramebuffer(
        sourceX0: Int, sourceY0: Int, sourceX1: Int, sourceY1: Int,
        destinationX0: Int, destinationY0: Int, destinationX1: Int, destinationY1: Int,
        mask: Int, filter: Int,
    ) = gl.blitFramebuffer(
        sourceX0, sourceY0, sourceX1, sourceY1,
        destinationX0, destinationY0, destinationX1, destinationY1,
        mask, filter,
    )
    override fun drawBuffers(count: Int, buffers: IntArray) {
        val view = newInt32Array(count)
        repeat(count) { index -> view[index] = buffers[index] }
        gl.drawBuffers(view)
    }
    override fun readBuffer(mode: Int) = gl.readBuffer(mode)

    override fun genTextures(count: Int, out: IntArray) = generate(count, out, textures) { gl.createTexture() }
    override fun deleteTextures(count: Int, names: IntArray) = delete(count, names, textures, gl::deleteTexture)
    override fun bindTexture(target: Int, texture: Int) = gl.bindTexture(target, textures[texture])
    override fun activeTexture(unit: Int) = gl.activeTexture(unit)
    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: ByteArray?,
    ) {
        if (pixels == null) {
            gl.texImage2D(target, level, internalFormat, width, height, border, format, type, null)
        } else {
            withByteView(pixels) { view ->
                gl.texImage2D(target, level, internalFormat, width, height, border, format, type, view)
            }
        }
    }
    override fun texSubImage2D(
        target: Int, level: Int, xOffset: Int, yOffset: Int, width: Int, height: Int,
        format: Int, type: Int, pixels: ByteArray,
    ) = withByteView(pixels) { view ->
        gl.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, view)
    }
    override fun compressedTexImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, imageSize: Int, data: ByteArray, dataOffset: Int,
    ) = withByteView(data, offset = dataOffset, length = imageSize) { view ->
        gl.compressedTexImage2D(target, level, internalFormat, width, height, border, view)
    }
    override fun texStorage2D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int) =
        gl.texStorage2D(target, levels, internalFormat, width, height)
    override fun texParameteri(target: Int, pname: Int, value: Int) = gl.texParameteri(target, pname, value)
    override fun generateMipmap(target: Int) = gl.generateMipmap(target)
    override fun genSamplers(count: Int, out: IntArray) = generate(count, out, samplers) { gl.createSampler() }
    override fun deleteSamplers(count: Int, names: IntArray) = delete(count, names, samplers, gl::deleteSampler)
    override fun bindSampler(unit: Int, sampler: Int) = gl.bindSampler(unit, samplers[sampler])
    override fun samplerParameteri(sampler: Int, pname: Int, value: Int) =
        gl.samplerParameteri(samplers.require(sampler), pname, value)
    override fun pixelStorei(pname: Int, value: Int) = gl.pixelStorei(pname, value)
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, out: ByteArray) {
        require(out.isNotEmpty()) { "a pixel read needs a destination" }
        withScopedMemoryAllocator { allocator ->
            val pointer = allocator.allocate(out.size)
            gl.readPixels(x, y, width, height, format, type, linearMemoryByteView(pointer.address.toInt(), out.size))
            for (index in out.indices) out[index] = (pointer + index).loadByte()
        }
    }

    override fun genBuffers(count: Int, out: IntArray) = generate(count, out, buffers) { gl.createBuffer() }
    override fun deleteBuffers(count: Int, names: IntArray) = delete(count, names, buffers, gl::deleteBuffer)
    override fun bindBuffer(target: Int, buffer: Int) = gl.bindBuffer(target, buffers[buffer])
    override fun bindBufferBase(target: Int, index: Int, buffer: Int) = gl.bindBufferBase(target, index, buffers[buffer])
    override fun bufferData(target: Int, size: Int, data: ByteArray?, usage: Int) {
        if (data == null || data.isEmpty()) gl.bufferData(target, size, usage)
        else withByteView(data, length = minOf(size, data.size)) { view -> gl.bufferData(target, view, usage) }
    }
    override fun bufferSubData(target: Int, offset: Int, size: Int, data: ByteArray) {
        if (size == 0) return
        withByteView(data, length = minOf(size, data.size)) { view -> gl.bufferSubData(target, offset, view) }
    }
    override fun genVertexArrays(count: Int, out: IntArray) = generate(count, out, vertexArrays) { gl.createVertexArray() }
    override fun deleteVertexArrays(count: Int, names: IntArray) = delete(count, names, vertexArrays, gl::deleteVertexArray)
    override fun bindVertexArray(array: Int) = gl.bindVertexArray(vertexArrays[array])
    override fun enableVertexAttribArray(index: Int) = gl.enableVertexAttribArray(index)
    override fun disableVertexAttribArray(index: Int) = gl.disableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        gl.vertexAttribPointer(index, size, type, normalized, stride, offset)

    override fun createShader(type: Int): Int = shaders.add(gl.createShader(type))
    override fun deleteShader(shader: Int) = shaders.delete(shader, gl::deleteShader)
    override fun shaderSource(shader: Int, source: String) = gl.shaderSource(shaders.require(shader), source.toJsString())
    override fun compileShader(shader: Int) = gl.compileShader(shaders.require(shader))
    override fun getShaderiv(shader: Int, pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "a shader query needs a destination" }
        out[0] = jsNumberAt(gl.getShaderParameter(shaders.require(shader), pname), 0).toInt()
    }
    override fun getShaderInfoLog(shader: Int): String =
        gl.getShaderInfoLog(shaders.require(shader))?.toString().orEmpty()
    override fun createProgram(): Int = programs.add(gl.createProgram())
    override fun deleteProgram(program: Int) {
        uniformLocations.remove(program)
        programs.delete(program, gl::deleteProgram)
    }
    override fun attachShader(program: Int, shader: Int) = gl.attachShader(programs.require(program), shaders.require(shader))
    override fun linkProgram(program: Int) = gl.linkProgram(programs.require(program))
    override fun getProgramiv(program: Int, pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "a program query needs a destination" }
        out[0] = jsNumberAt(gl.getProgramParameter(programs.require(program), pname), 0).toInt()
    }
    override fun getProgramInfoLog(program: Int): String =
        gl.getProgramInfoLog(programs.require(program))?.toString().orEmpty()
    override fun useProgram(program: Int) {
        gl.useProgram(programs[program])
        currentProgram = program
    }
    override fun getAttribLocation(program: Int, name: String): Int =
        gl.getAttribLocation(programs.require(program), name.toJsString())
    override fun getUniformLocation(program: Int, name: String): Int {
        val location = gl.getUniformLocation(programs.require(program), name.toJsString()) ?: return -1
        return uniformLocations.getOrPut(program) { ObjectNames() }.nameFor(location)
    }
    private fun uniform(location: Int): JsAny? =
        if (location < 0) null else uniformLocations[currentProgram]?.get(location)
    override fun uniform1i(location: Int, value: Int) = gl.uniform1i(uniform(location), value)
    override fun uniform1f(location: Int, value: Float) = gl.uniform1f(uniform(location), value)
    override fun uniform2f(location: Int, x: Float, y: Float) = gl.uniform2f(uniform(location), x, y)
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = gl.uniform3f(uniform(location), x, y, z)
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) =
        gl.uniform4f(uniform(location), x, y, z, w)
    override fun uniform1ui(location: Int, value: Int) = gl.uniform1ui(uniform(location), value)
    override fun uniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray) {
        if (value.isEmpty()) return
        val size = count * 16
        val view = newFloat32Array(size)
        repeat(size) { index -> view[index] = value.getOrElse(index) { 0f } }
        gl.uniformMatrix4fv(uniform(location), transpose, view)
    }
    override fun getUniformBlockIndex(program: Int, name: String): Int =
        gl.getUniformBlockIndex(programs.require(program), name.toJsString())
    override fun uniformBlockBinding(program: Int, blockIndex: Int, bindingPoint: Int) =
        gl.uniformBlockBinding(programs.require(program), blockIndex, bindingPoint)

    override fun enable(cap: Int) = gl.enable(cap)
    override fun disable(cap: Int) = gl.disable(cap)
    override fun blendFuncSeparate(sourceRgb: Int, destinationRgb: Int, sourceAlpha: Int, destinationAlpha: Int) =
        gl.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha)
    override fun blendEquationSeparate(modeRgb: Int, modeAlpha: Int) = gl.blendEquationSeparate(modeRgb, modeAlpha)
    override fun blendColor(red: Float, green: Float, blue: Float, alpha: Float) = gl.blendColor(red, green, blue, alpha)
    override fun depthFunc(function: Int) = gl.depthFunc(function)
    override fun depthMask(enabled: Boolean) = gl.depthMask(enabled)
    override fun depthRangef(near: Float, far: Float) = gl.depthRange(near, far)
    override fun cullFace(mode: Int) = gl.cullFace(mode)
    override fun frontFace(mode: Int) = gl.frontFace(mode)
    override fun viewport(x: Int, y: Int, width: Int, height: Int) = gl.viewport(x, y, width, height)
    override fun scissor(x: Int, y: Int, width: Int, height: Int) = gl.scissor(x, y, width, height)
    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) = gl.colorMask(red, green, blue, alpha)
    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) = gl.clearColor(red, green, blue, alpha)
    override fun clearDepthf(depth: Float) = gl.clearDepth(depth)
    override fun clear(mask: Int) = gl.clear(mask)
    override fun drawArrays(mode: Int, first: Int, count: Int) = gl.drawArrays(mode, first, count)
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = gl.drawElements(mode, count, type, offset)
    override fun finish() = gl.finish()

    private fun objectNamesForQuery(pname: Int): ObjectNames? = when (pname) {
        GL_DRAW_FRAMEBUFFER_BINDING, GL_READ_FRAMEBUFFER_BINDING -> framebuffers
        GL_RENDERBUFFER_BINDING -> renderbuffers
        GL_TEXTURE_BINDING_2D -> textures
        GL_SAMPLER_BINDING -> samplers
        GL_ARRAY_BUFFER_BINDING, GL_ELEMENT_ARRAY_BUFFER_BINDING,
        GL_PIXEL_UNPACK_BUFFER_BINDING, GL_UNIFORM_BUFFER_BINDING -> buffers
        GL_VERTEX_ARRAY_BINDING -> vertexArrays
        GL_CURRENT_PROGRAM -> programs
        else -> null
    }

    private inline fun copyNumbers(value: JsAny?, size: Int, write: (Int, Double) -> Unit) {
        val count = minOf(size, jsArrayLikeLength(value))
        for (index in 0 until count) write(index, jsNumberAt(value, index))
    }

    private inline fun <T> withByteView(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        block: (JsAny) -> T,
    ): T {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "byte view is outside the source" }
        if (length == 0) return block(linearMemoryByteView(address = 0, length = 0))
        return withScopedMemoryAllocator { allocator ->
            val pointer = allocator.allocate(length)
            for (index in 0 until length) (pointer + index).storeByte(bytes[offset + index])
            block(linearMemoryByteView(pointer.address.toInt(), length))
        }
    }

    private fun generate(count: Int, out: IntArray, names: ObjectNames, create: () -> JsAny?) {
        require(out.size >= count) { "object generation needs room for $count names" }
        repeat(count) { index -> out[index] = names.add(create()) }
    }

    private fun delete(count: Int, values: IntArray, names: ObjectNames, action: (JsAny?) -> Unit) {
        repeat(count) { index -> names.delete(values[index], action) }
    }

    private class ObjectNames {
        private val values = mutableListOf<JsAny?>(null)

        operator fun get(name: Int): JsAny? = if (name == 0) null else values.getOrNull(name)

        fun require(name: Int): JsAny = get(name) ?: error("unknown WebGL object name $name")

        fun add(value: JsAny?): Int {
            val actual = value ?: error("WebGL object allocation failed")
            values += actual
            return values.lastIndex
        }

        fun nameFor(value: JsAny?): Int {
            if (value == null) return 0
            for (index in 1 until values.size) {
                if (sameJsObject(values[index], value)) return index
            }
            return add(value)
        }

        fun delete(name: Int, action: (JsAny?) -> Unit) {
            if (name == 0) return
            val value = get(name) ?: return
            action(value)
            values[name] = null
        }
    }

    private companion object {
        const val UNMASKED_VENDOR_WEBGL: Int = 0x9245
        const val UNMASKED_RENDERER_WEBGL: Int = 0x9246
    }
}

internal actual fun openPlatformGlBinding(): GlBindingResult =
    currentWebGl2Context?.let { GlBindingResult.Bound(WebGlBinding(it)) } ?: unsupportedRenderContext()

@JsFun("(a, b) => a === b")
private external fun sameJsObject(a: JsAny?, b: JsAny?): Boolean

@JsFun("value => value == null ? null : String(value)")
private external fun jsStringValue(value: JsAny?): JsString?

@JsFun("value => (Array.isArray(value) || ArrayBuffer.isView(value)) ? value.length : 1")
private external fun jsArrayLikeLength(value: JsAny?): Int

@JsFun("(value, index) => { const v = (Array.isArray(value) || ArrayBuffer.isView(value)) ? value[index] : value; return typeof v === 'boolean' ? (v ? 1 : 0) : Number(v); }")
private external fun jsNumberAt(value: JsAny?, index: Int): Double

@JsFun("(value, index) => { const v = (Array.isArray(value) || ArrayBuffer.isView(value)) ? value[index] : value; return Boolean(v); }")
private external fun jsBooleanAt(value: JsAny?, index: Int): Boolean

private external interface JsIntArray : JsAny {
    operator fun get(index: Int): Int
    operator fun set(index: Int, value: Int)
}

private external interface JsFloatArray : JsAny {
    operator fun set(index: Int, value: Float)
}

// Generated Kotlin glue places @JsFun bodies beside a module-scoped `wasmExports` reference.
@JsFun("(address, length) => new Uint8Array(wasmExports.memory.buffer, address >>> 0, length)")
private external fun linearMemoryByteView(address: Int, length: Int): JsAny

@JsFun("size => new Int32Array(size)")
private external fun newInt32Array(size: Int): JsIntArray

@JsFun("size => new Float32Array(size)")
private external fun newFloat32Array(size: Int): JsFloatArray
