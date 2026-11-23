package com.rohittp.reng.internal.gl

import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL13C
import org.lwjgl.opengl.GL14C
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL31C
import org.lwjgl.opengl.GL33C
import org.lwjgl.opengl.GL41C
import org.lwjgl.opengl.GL42C
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil

/**
 * The JVM binding of the internal GL seam, over LWJGL 3 desktop GL.
 *
 * Shaped after [AndroidGlBinding]; the only real differences are that LWJGL takes no array offset
 * (names are generated for the whole array, so a `count` shorter than the array goes through a
 * copy) and that every byte payload must be off-heap, so [ByteArray]s are staged through
 * [MemoryUtil.memAlloc]. The consumer creates the context, makes it current and calls
 * `GL.createCapabilities()`; RenG never does (ADR 0001).
 */
internal object JvmGlBinding : GlBinding {

    private inline fun <T> staged(bytes: ByteArray, offset: Int, length: Int, block: (Long) -> T): T {
        val buffer = MemoryUtil.memAlloc(length)
        try {
            buffer.put(bytes, offset, length).flip()
            return block(MemoryUtil.memAddress(buffer))
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    private fun IntArray.first(count: Int): IntArray = if (count == size) this else copyOf(count)

    // ===== Queries =====

    override fun getError(): Int = GL11C.glGetError()

    override fun getString(name: Int): String? = GL11C.glGetString(name)

    override fun getStringi(name: Int, index: Int): String? = GL30C.glGetStringi(name, index)

    override fun getIntegerv(pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an integer query needs a destination" }
        GL11C.glGetIntegerv(pname, out)
    }

    override fun getFloatv(pname: Int, out: FloatArray) {
        require(out.isNotEmpty()) { "a float query needs a destination" }
        GL11C.glGetFloatv(pname, out)
    }

    override fun getBooleanv(pname: Int, out: BooleanArray) {
        require(out.isNotEmpty()) { "a boolean query needs a destination" }
        MemoryStack.stackPush().use { stack ->
            val buffer = stack.calloc(maxOf(out.size, 16))
            GL11C.glGetBooleanv(pname, buffer)
            for (index in out.indices) out[index] = buffer.get(index).toInt() != 0
        }
    }

    override fun isEnabled(cap: Int): Boolean = GL11C.glIsEnabled(cap)

    override fun getIntegeri_v(pname: Int, index: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an indexed integer query needs a destination" }
        GL30C.glGetIntegeri_v(pname, index, out)
    }

    // ===== Framebuffers / renderbuffers =====

    override fun genFramebuffers(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenFramebuffers needs room for $count names" }
        if (count == 0) return
        val names = IntArray(count)
        GL30C.glGenFramebuffers(names)
        names.copyInto(out)
    }

    override fun deleteFramebuffers(count: Int, names: IntArray) {
        if (count == 0) return
        GL30C.glDeleteFramebuffers(names.first(count))
    }

    override fun bindFramebuffer(target: Int, framebuffer: Int) = GL30C.glBindFramebuffer(target, framebuffer)

    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) =
        GL30C.glFramebufferTexture2D(target, attachment, textureTarget, texture, level)

    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: Int) =
        GL30C.glFramebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer)

    override fun checkFramebufferStatus(target: Int): Int = GL30C.glCheckFramebufferStatus(target)

    override fun isFramebuffer(framebuffer: Int): Boolean = GL30C.glIsFramebuffer(framebuffer)

    override fun genRenderbuffers(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenRenderbuffers needs room for $count names" }
        if (count == 0) return
        val names = IntArray(count)
        GL30C.glGenRenderbuffers(names)
        names.copyInto(out)
    }

    override fun deleteRenderbuffers(count: Int, names: IntArray) {
        if (count == 0) return
        GL30C.glDeleteRenderbuffers(names.first(count))
    }

    override fun bindRenderbuffer(target: Int, renderbuffer: Int) = GL30C.glBindRenderbuffer(target, renderbuffer)

    override fun renderbufferStorage(target: Int, internalFormat: Int, width: Int, height: Int) =
        GL30C.glRenderbufferStorage(target, internalFormat, width, height)

    override fun blitFramebuffer(
        sourceX0: Int, sourceY0: Int, sourceX1: Int, sourceY1: Int,
        destinationX0: Int, destinationY0: Int, destinationX1: Int, destinationY1: Int,
        mask: Int, filter: Int,
    ) = GL30C.glBlitFramebuffer(
        sourceX0, sourceY0, sourceX1, sourceY1,
        destinationX0, destinationY0, destinationX1, destinationY1,
        mask, filter,
    )

    override fun drawBuffers(count: Int, buffers: IntArray) {
        if (count == 0) return
        GL20C.glDrawBuffers(buffers.first(count))
    }

    override fun readBuffer(mode: Int) = GL11C.glReadBuffer(mode)

    // ===== Textures / samplers =====

    override fun genTextures(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenTextures needs room for $count names" }
        if (count == 0) return
        val names = IntArray(count)
        GL11C.glGenTextures(names)
        names.copyInto(out)
    }

    override fun deleteTextures(count: Int, names: IntArray) {
        if (count == 0) return
        GL11C.glDeleteTextures(names.first(count))
    }

    override fun bindTexture(target: Int, texture: Int) = GL11C.glBindTexture(target, texture)

    override fun activeTexture(unit: Int) = GL13C.glActiveTexture(unit)

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: ByteArray?,
    ) {
        if (pixels == null || pixels.isEmpty()) {
            GL11C.nglTexImage2D(target, level, internalFormat, width, height, border, format, type, 0L)
        } else {
            staged(pixels, 0, pixels.size) { address ->
                GL11C.nglTexImage2D(target, level, internalFormat, width, height, border, format, type, address)
            }
        }
    }

    override fun texSubImage2D(
        target: Int, level: Int, xOffset: Int, yOffset: Int, width: Int, height: Int,
        format: Int, type: Int, pixels: ByteArray,
    ) {
        staged(pixels, 0, pixels.size) { address ->
            GL11C.nglTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, address)
        }
    }

    override fun compressedTexImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, imageSize: Int, data: ByteArray, dataOffset: Int,
    ) {
        staged(data, dataOffset, imageSize) { address ->
            GL13C.nglCompressedTexImage2D(target, level, internalFormat, width, height, border, imageSize, address)
        }
    }

    // macOS's 4.1 core context exposes glTexStorage2D through ARB_texture_storage; LWJGL resolves
    // the same symbol for GL42C when the extension is present.
    override fun texStorage2D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int) =
        GL42C.glTexStorage2D(target, levels, internalFormat, width, height)

    override fun texParameteri(target: Int, pname: Int, value: Int) = GL11C.glTexParameteri(target, pname, value)

    override fun generateMipmap(target: Int) = GL30C.glGenerateMipmap(target)

    override fun genSamplers(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenSamplers needs room for $count names" }
        if (count == 0) return
        val names = IntArray(count)
        GL33C.glGenSamplers(names)
        names.copyInto(out)
    }

    override fun deleteSamplers(count: Int, names: IntArray) {
        if (count == 0) return
        GL33C.glDeleteSamplers(names.first(count))
    }

    override fun bindSampler(unit: Int, sampler: Int) = GL33C.glBindSampler(unit, sampler)

    override fun samplerParameteri(sampler: Int, pname: Int, value: Int) =
        GL33C.glSamplerParameteri(sampler, pname, value)

    override fun pixelStorei(pname: Int, value: Int) = GL11C.glPixelStorei(pname, value)

    override fun readPixels(
        x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, out: ByteArray,
    ) {
        require(out.isNotEmpty()) { "a pixel read needs a destination" }
        val buffer = MemoryUtil.memAlloc(out.size)
        try {
            GL11C.nglReadPixels(x, y, width, height, format, type, MemoryUtil.memAddress(buffer))
            buffer.get(out)
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    // ===== Buffers / vertex arrays =====

    override fun genBuffers(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenBuffers needs room for $count names" }
        if (count == 0) return
        val names = IntArray(count)
        GL15C.glGenBuffers(names)
        names.copyInto(out)
    }

    override fun deleteBuffers(count: Int, names: IntArray) {
        if (count == 0) return
        GL15C.glDeleteBuffers(names.first(count))
    }

    override fun bindBuffer(target: Int, buffer: Int) = GL15C.glBindBuffer(target, buffer)

    override fun bindBufferBase(target: Int, index: Int, buffer: Int) = GL30C.glBindBufferBase(target, index, buffer)

    override fun bufferData(target: Int, size: Int, data: ByteArray?, usage: Int) {
        if (data == null || data.isEmpty()) {
            GL15C.nglBufferData(target, size.toLong(), 0L, usage)
        } else {
            staged(data, 0, minOf(size, data.size)) { address ->
                GL15C.nglBufferData(target, size.toLong(), address, usage)
            }
        }
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, data: ByteArray) {
        if (data.isEmpty()) return
        staged(data, 0, minOf(size, data.size)) { address ->
            GL15C.nglBufferSubData(target, offset.toLong(), size.toLong(), address)
        }
    }

    override fun genVertexArrays(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenVertexArrays needs room for $count names" }
        if (count == 0) return
        val names = IntArray(count)
        GL30C.glGenVertexArrays(names)
        names.copyInto(out)
    }

    override fun deleteVertexArrays(count: Int, names: IntArray) {
        if (count == 0) return
        GL30C.glDeleteVertexArrays(names.first(count))
    }

    override fun bindVertexArray(array: Int) = GL30C.glBindVertexArray(array)

    override fun enableVertexAttribArray(index: Int) = GL20C.glEnableVertexAttribArray(index)

    override fun disableVertexAttribArray(index: Int) = GL20C.glDisableVertexAttribArray(index)

    override fun vertexAttribPointer(
        index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int,
    ) = GL20C.glVertexAttribPointer(index, size, type, normalized, stride, offset.toLong())

    // ===== Shaders / programs =====

    override fun createShader(type: Int): Int = GL20C.glCreateShader(type)

    override fun deleteShader(shader: Int) = GL20C.glDeleteShader(shader)

    override fun shaderSource(shader: Int, source: String) = GL20C.glShaderSource(shader, source)

    override fun compileShader(shader: Int) = GL20C.glCompileShader(shader)

    override fun getShaderiv(shader: Int, pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "a shader query needs a destination" }
        GL20C.glGetShaderiv(shader, pname, out)
    }

    override fun getShaderInfoLog(shader: Int): String = GL20C.glGetShaderInfoLog(shader)

    override fun createProgram(): Int = GL20C.glCreateProgram()

    override fun deleteProgram(program: Int) = GL20C.glDeleteProgram(program)

    override fun attachShader(program: Int, shader: Int) = GL20C.glAttachShader(program, shader)

    override fun linkProgram(program: Int) = GL20C.glLinkProgram(program)

    override fun getProgramiv(program: Int, pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "a program query needs a destination" }
        GL20C.glGetProgramiv(program, pname, out)
    }

    override fun getProgramInfoLog(program: Int): String = GL20C.glGetProgramInfoLog(program)

    override fun useProgram(program: Int) = GL20C.glUseProgram(program)

    override fun getAttribLocation(program: Int, name: String): Int = GL20C.glGetAttribLocation(program, name)

    override fun getUniformLocation(program: Int, name: String): Int = GL20C.glGetUniformLocation(program, name)

    override fun uniform1i(location: Int, value: Int) = GL20C.glUniform1i(location, value)

    override fun uniform1f(location: Int, value: Float) = GL20C.glUniform1f(location, value)

    override fun uniform2f(location: Int, x: Float, y: Float) = GL20C.glUniform2f(location, x, y)

    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = GL20C.glUniform3f(location, x, y, z)

    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) =
        GL20C.glUniform4f(location, x, y, z, w)

    override fun uniform1ui(location: Int, value: Int) = GL30C.glUniform1ui(location, value)

    override fun uniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray) {
        if (value.isEmpty()) return
        val floats = if (value.size == count * 16) value else value.copyOf(count * 16)
        GL20C.glUniformMatrix4fv(location, transpose, floats)
    }

    override fun getUniformBlockIndex(program: Int, name: String): Int = GL31C.glGetUniformBlockIndex(program, name)

    override fun uniformBlockBinding(program: Int, blockIndex: Int, bindingPoint: Int) =
        GL31C.glUniformBlockBinding(program, blockIndex, bindingPoint)

    // ===== Pipeline state / draw =====

    override fun enable(cap: Int) = GL11C.glEnable(cap)

    override fun disable(cap: Int) = GL11C.glDisable(cap)

    override fun blendFuncSeparate(sourceRgb: Int, destinationRgb: Int, sourceAlpha: Int, destinationAlpha: Int) =
        GL14C.glBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha)

    override fun blendEquationSeparate(modeRgb: Int, modeAlpha: Int) = GL20C.glBlendEquationSeparate(modeRgb, modeAlpha)

    override fun blendColor(red: Float, green: Float, blue: Float, alpha: Float) =
        GL14C.glBlendColor(red, green, blue, alpha)

    override fun depthFunc(function: Int) = GL11C.glDepthFunc(function)

    override fun depthMask(enabled: Boolean) = GL11C.glDepthMask(enabled)

    override fun depthRangef(near: Float, far: Float) = GL41C.glDepthRangef(near, far)

    override fun cullFace(mode: Int) = GL11C.glCullFace(mode)

    override fun frontFace(mode: Int) = GL11C.glFrontFace(mode)

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = GL11C.glViewport(x, y, width, height)

    override fun scissor(x: Int, y: Int, width: Int, height: Int) = GL11C.glScissor(x, y, width, height)

    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) =
        GL11C.glColorMask(red, green, blue, alpha)

    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) =
        GL11C.glClearColor(red, green, blue, alpha)

    override fun clearDepthf(depth: Float) = GL41C.glClearDepthf(depth)

    override fun clear(mask: Int) = GL11C.glClear(mask)

    override fun drawArrays(mode: Int, first: Int, count: Int) = GL11C.glDrawArrays(mode, first, count)

    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) =
        GL11C.glDrawElements(mode, count, type, offset.toLong())

    override fun finish() = GL11C.glFinish()
}

internal actual fun openPlatformGlBinding(): GlBindingResult = GlBindingResult.Bound(JvmGlBinding)
